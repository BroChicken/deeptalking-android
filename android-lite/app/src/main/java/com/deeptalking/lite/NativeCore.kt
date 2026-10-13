package com.deeptalking.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import com.deeptalking.core.data.CoreDataContainer
import com.deeptalking.core.data.legacy.BackupService
import com.deeptalking.core.data.legacy.FileStickerSink
import com.deeptalking.core.data.legacy.MediaRef
import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.BuiltinPlatforms
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.RequestMetric
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.model.TTS_BACKEND_REMOTE
import com.deeptalking.core.network.HttpWebContentProvider
import com.deeptalking.core.network.DohDns
import com.deeptalking.core.network.ResponsesLlmBackend
import com.deeptalking.core.network.defaultClient
import com.deeptalking.core.network.opencodeSessionHeader
import com.deeptalking.core.security.SecretStore
import com.deeptalking.engine.ondevice.InferenceRegistry
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.TokenUsage
import com.deeptalking.engine.ondevice.TtsSpeaker
import com.deeptalking.engine.cosyvoice.CosyVoiceController
import com.deeptalking.engine.cosyvoice.GenerationSettings
import com.deeptalking.engine.remote.RemoteTtsClient
import com.deeptalking.engine.remote.RemoteTtsConfig
import com.deeptalking.engine.remote.RemoteTtsProbe
import com.deeptalking.engine.remote.RemoteVoiceUpload
import com.deeptalking.engine.remote.RemoteTtsSpeaker
import com.deeptalking.engine.remote.TtsServerFinder
import com.deeptalking.domain.agent.ChatOrchestrator
import com.deeptalking.domain.agent.ToolRegistry
import com.deeptalking.domain.agent.background.BackgroundTaskQueue
import com.deeptalking.domain.agent.background.BackgroundTasks
import com.deeptalking.domain.agent.defaultTools
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.domain.memory.MEMORY_REPAIR_VERSION
import com.deeptalking.domain.memory.needsLorebookRewrite
import com.deeptalking.domain.memory.RelativeTimeMigration
import com.deeptalking.domain.memory.repairMemories
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.time.Instant

/** Manual DI graph for the native app, created once from [DeepTalkingApp]. */
class NativeCore(context: Context) {

    val appContext: Context = context.applicationContext

    /** Client UA sent on API calls (OpenCode Go requires a non-generic identifier). */
    private val userAgent: String = "DeepTalking-Lite/" + BuildConfig.VERSION_NAME

    val secrets: SecretStore = SecretStore(appContext)
    val data: CoreDataContainer = CoreDataContainer(appContext)

    /** Live DoH policy, refreshed from the persisted config (see [applyDohPolicy]). */
    @Volatile
    private var dohPolicy: DohDns.Policy = DohDns.Policy(enabled = true)

    /** Shared resolver: bypasses carrier DNS hijacking via DoH, falls back to system DNS. */
    private val dohDns: DohDns = DohDns { dohPolicy }

    val memory: MemoryServiceImpl = MemoryServiceImpl()
    val web: HttpWebContentProvider = HttpWebContentProvider(defaultClient(dohDns))
    val tools: ToolRegistry = ToolRegistry(defaultTools(memory, web, stickersEnabled = true))

    /**
     * App-process-lifetime scope + serial background queue: legacy background
     * tasks (style critique, memory extraction, quick-reply repair) run for the
     * lifetime of the loaded page, so they must outlive an individual ViewModel
     * / configuration change. [appScope] is also used to persist results.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backgroundQueue: BackgroundTaskQueue = BackgroundTaskQueue(appScope)

    /**
     * Serializes everything that rewrites whole `Character` rows at startup
     * (auto-fill, world-book migration, field migration). Without this, two
     * tasks can upsert copies taken from different snapshots and silently
     * clobber each other's markers (e.g. `lorebookMigratedAt`), which made the
     * migration dialog reappear on every launch.
     */
    private val characterMaintenanceMutex = Mutex()

    /** On-device CosyVoice3 read-aloud engine (model downloaded on demand). */
    val cosyVoice: CosyVoiceController = CosyVoiceController(appContext)

    /** HTTP client for the remote read-aloud server (also used by the settings probe). */
    private val remoteTtsClient: RemoteTtsClient = RemoteTtsClient(userAgent = userAgent, dns = dohDns)

    /** Remote read-aloud engine; inactive unless `AppConfig.ttsBackend` selects it. */
    val remoteTts: RemoteTtsSpeaker = RemoteTtsSpeaker(remoteTtsClient)

    /** Sweeps the phone's own subnets for the server when the address is unknown. */
    private val ttsFinder: TtsServerFinder = TtsServerFinder(remoteTtsClient)

    /** LLM is remote; read-aloud is on-device or remote per configuration. embedding/asr stay null. */
    val inference: InferenceRegistry = InferenceRegistry(
        llm = ResponsesLlmBackend(apiKeyProvider = { secrets.getApiKey() }, userAgent = userAgent, dns = dohDns),
        tts = cosyVoice.backend,
    )

    /**
     * Remote read-aloud settings for [config]; the bearer token comes from [SecretStore].
     *
     * The voice resolves to `ttsRemoteVoice` when set, otherwise to the **on-device
     * selection** (`ttsVoiceFile`'s file name without extension). That keeps one
     * voice list for both engines: whatever the user picked for local read-aloud is
     * requested by name from the server, which is why the server registers its
     * prompt-speech files under the app's own voice names.
     */
    fun remoteTtsConfig(config: AppConfig): RemoteTtsConfig = RemoteTtsConfig(
        baseUrl = config.ttsRemoteUrl,
        token = secrets.getTtsToken(),
        model = config.ttsRemoteModel,
        voice = config.ttsRemoteVoice.trim()
            .ifBlank { localVoiceName(config).orEmpty() }
            .ifBlank { RemoteTtsConfig.DEFAULT_VOICE },
        speed = config.ttsSpeed,
        sampleRate = config.ttsRemoteSampleRate,
        chunkTokens = config.ttsRemoteChunkTokens,
        temperature = config.ttsRemoteTemperature,
        topK = config.ttsRemoteTopK,
        topP = config.ttsRemoteTopP,
        winSize = config.ttsWinSize,
        tauR = config.ttsTauR,
        minTokenTextRatio = config.ttsMinTokenTextRatio,
        maxTokenTextRatio = config.ttsMaxTokenTextRatio,
    )

    /** The active on-device voice as a bare name (`builtin_1_v2`), or null when unset. */
    private fun localVoiceName(config: AppConfig): String? =
        config.ttsVoiceFile.trim()
            .takeIf { it.isNotEmpty() }
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.removeSuffix(".gguf")
            ?.takeIf { it.isNotEmpty() }

    /**
     * The active read-aloud engine for [config]. Both engines sit behind the same
     * [TtsSpeaker] seam, so callers never branch on the backend; this is the single
     * place `AppConfig.ttsBackend` is interpreted.
     */
    fun ttsSpeaker(config: AppConfig): TtsSpeaker {
        cosyVoice.speed = config.ttsSpeed
        cosyVoice.applyGeneration(generationSettings(config))
        remoteTts.configure(remoteTtsConfig(config))
        return if (config.ttsBackend == TTS_BACKEND_REMOTE) remoteTts else cosyVoice
    }

    /**
     * The shared sampling/chunking knobs. The same values go to the PC in the request
     * body (`remoteTtsConfig`) and to the on-device engine (`cosyVoice.applyGeneration`),
     * so the settings screen tunes both engines at once (see `docs/REMOTE_TTS.md` §9).
     */
    fun generationSettings(config: AppConfig): GenerationSettings = GenerationSettings.fromStored(
        temperature = config.ttsRemoteTemperature,
        topK = config.ttsRemoteTopK,
        topP = config.ttsRemoteTopP,
        winSize = config.ttsWinSize,
        tauR = config.ttsTauR,
        minTokenTextRatio = config.ttsMinTokenTextRatio,
        maxTokenTextRatio = config.ttsMaxTokenTextRatio,
        chunkTokens = config.ttsRemoteChunkTokens,
    )

    /** Settings "test connection": never throws, reports mode/sample rate/voices. */
    suspend fun testTtsConnection(config: AppConfig): RemoteTtsProbe =
        withContext(Dispatchers.IO) { remoteTtsClient.probe(remoteTtsConfig(config)) }

    /** Settings "find server": sweeps the phone's own subnets; null when nothing answers. */
    suspend fun findTtsServer(config: AppConfig): String? =
        withContext(Dispatchers.IO) { ttsFinder.find(config.ttsRemoteUrl, secrets.getTtsToken()) }

    /**
     * Uploads one on-device voice (its prompt-speech gguf) so the server registers it
     * under the same name; the PC then speaks with the phone's own voices.
     */
    suspend fun uploadTtsVoice(config: RemoteTtsConfig, name: String, bytes: ByteArray): RemoteVoiceUpload =
        withContext(Dispatchers.IO) { remoteTtsClient.uploadVoice(config, name, bytes) }

    val stickerSink: FileStickerSink = FileStickerSink(appContext)
    val backup: BackupService = BackupService(
        characters = data.characters,
        chat = data.chat,
        config = data.config,
        stickerSink = stickerSink,
        mediaSource = MediaRef.source(appContext),
        appVersion = BuildConfig.VERSION_NAME,
    )

    suspend fun currentConfig(): AppConfig = data.config.current().also { applyDohPolicy(it) }

    /** Keeps the shared [DohDns] policy in sync with the persisted config. */
    private fun applyDohPolicy(config: AppConfig) {
        dohPolicy = DohDns.Policy(config.dohEnabled, config.dohProvider)
    }

    /** True when the active platform (or the legacy global slot) has an API key. */
    fun hasApiKey(platform: String): Boolean = !apiKeyFor(platform).isNullOrBlank()

    /** Resolves the active platform's key, falling back to the legacy global key. */
    private fun apiKeyFor(platform: String): String? =
        secrets.getApiKey(platform) ?: secrets.getApiKey()

    private fun backgroundTasks(config: AppConfig): BackgroundTasks =
        BackgroundTasks(
            ResponsesLlmBackend(
                apiKeyProvider = { apiKeyFor(config.apiPlatform) },
                baseUrl = config.apiBaseUrl,
                userAgent = userAgent,
                dns = dohDns,
            ),
            memory,
            config.modelName,
            config.apiPlatform,
            backgroundQueue,
            config,
            liveCharacter = { id -> data.characters.get(id) },
            onAuxiliaryFailure = { taskType, reason ->
                lastAuxFailure = AuxFailure(taskType, reason, Instant.now().toEpochMilli())
                recordAuxMetric(config, taskType, System.currentTimeMillis(), null, "failed", reason)
            },
        )

    /** Task type, reason and time of the most recent failed background call. */
    private data class AuxFailure(val taskType: String, val reason: String, val atMillis: Long)

    @Volatile
    private var lastAuxFailure: AuxFailure? = null

    /**
     * The reason of the most recent background failure for [taskType] (when it happened
     * within the last ten minutes), so a dialog can say *why* a pass stopped instead of the
     * blanket "网络或接口异常". Empty when the last failure was another task or too old.
     */
    private fun lastFailureNote(taskType: String): String {
        val failure = lastAuxFailure ?: return ""
        if (failure.taskType != taskType) return ""
        if (System.currentTimeMillis() - failure.atMillis > 10 * 60_000L) return ""
        return failure.reason
    }

    /**
     * Startup maintenance mirroring the legacy on-load tasks: silently fill empty
     * static-profile fields (bounded to 3 jobs). Best-effort; never throws. World-book
     * / field migrations are interactive in the legacy UI and exposed separately via
     * [runWorldLoreMigration] / [remapCharacterFields].
     */
    suspend fun runStartupMaintenance(config: AppConfig) {
        if (apiKeyFor(config.apiPlatform).isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            characterMaintenanceMutex.withLock {
                runCatching {
                    val tasks = backgroundTasks(config)
                    val snapshot = data.characters.all()
                    val updated = tasks.autoFillStaticFields(snapshot)
                    updated.forEachIndexed { index, character ->
                        val original = snapshot.getOrNull(index) ?: return@forEachIndexed
                        if (character == original) return@forEachIndexed
                        // Re-read so a concurrent migration's markers are not reverted;
                        // only apply the fields this task owns.
                        val latest = data.characters.get(character.id) ?: return@forEachIndexed
                        if (latest.staticProfile == character.staticProfile && latest.staticFillMeta == character.staticFillMeta) {
                            return@forEachIndexed
                        }
                        data.characters.upsert(
                            latest.copy(
                                staticProfile = character.staticProfile,
                                staticFillMeta = character.staticFillMeta,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** One-time relative-time → absolute migration (legacy `convertLegacyRelativeTimes`). */
    suspend fun runStartupMigrations() = withContext(Dispatchers.Default) {
        characterMaintenanceMutex.withLock {
            runCatching {
                data.characters.all().forEach { character ->
                    if (character.timeParseVersion >= RelativeTimeMigration.TIME_PARSE_VERSION) return@forEach
                    val latest = data.characters.get(character.id) ?: return@forEach
                    if (latest.timeParseVersion >= RelativeTimeMigration.TIME_PARSE_VERSION) return@forEach
                    val updated = RelativeTimeMigration.convert(latest)
                    if (updated != latest) {
                        val fresh = data.characters.get(character.id) ?: latest
                        data.characters.upsert(
                            fresh.copy(
                                shortTerm = updated.shortTerm,
                                longTerm = updated.longTerm,
                                dynamicState = updated.dynamicState,
                                staticProfile = updated.staticProfile,
                                members = updated.members,
                                timeParseVersion = updated.timeParseVersion,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** One-time deterministic memory repair: reconcile + near-duplicate dedupe (offline, no API key). */
    suspend fun runMemoryRepair() = withContext(Dispatchers.Default) {
        // The near-duplicate pass is O(n²) over the short-term pile-up. Running it on
        // the main thread (which is where `viewModelScope.launch` starts) froze the UI
        // for minutes, so `memoryRepairVersion` was never stamped and the repair re-ran
        // on EVERY launch — an endless hang that the system eventually kills (ANR).
        characterMaintenanceMutex.withLock {
            runCatching {
                data.characters.all().forEach { character ->
                    if (character.memoryRepairVersion >= MEMORY_REPAIR_VERSION) return@forEach
                    val latest = data.characters.get(character.id) ?: return@forEach
                    if (latest.memoryRepairVersion >= MEMORY_REPAIR_VERSION) return@forEach
                    val repaired = repairMemories(latest)
                    val fresh = data.characters.get(character.id) ?: latest
                    data.characters.upsert(
                        fresh.copy(
                            shortTerm = repaired.shortTerm,
                            longTerm = repaired.longTerm,
                            members = repaired.members,
                            memoryRepairVersion = MEMORY_REPAIR_VERSION,
                        ),
                    )
                }
            }
        }
    }

    /**
     * What the memory cleanup sees right now.
     *
     * [shortTermPending] is the work left to do (entries above the low-water mark),
     * while [shortTermTotal] is the raw list length — the "over the cap" prompt is
     * based on the cap, the "how much is left" message on [shortTermPending].
     */
    data class MemoryCleanupTarget(
        val characters: Int,
        val shortTermTotal: Int,
        val shortTermPending: Int,
        val images: Int,
        /** True when some character's short-term is over [AppLimits.Memory.SHORT_TERM]. */
        val overLimit: Boolean,
    ) {
        /** True when there is still something to consume. */
        val hasWork: Boolean get() = shortTermPending > 0 || images > 0
    }

    /** Result of a bounded drain: how many batches landed, plus the last failure reason. */
    data class MemoryDrainResult(val passes: Int, val lastAnalysisError: String)

    /**
     * Detects the two leftovers: short-term memory piled up because the analysis task
     * could never drain it, and image attachments older than
     * [AppLimits.Media.IMAGE_RETENTION_MESSAGES] messages.
     */
    suspend fun memoryCleanupTarget(): MemoryCleanupTarget = withContext(Dispatchers.IO) {
        var characters = 0
        var total = 0
        var pending = 0
        var images = 0
        var overLimit = false
        data.characters.all().forEach { character ->
            val size = character.shortTerm.size
            if (size > AppLimits.Memory.SHORT_TERM) overLimit = true
            // "Backlog" means *above the high watermark*: a window sitting between the low
            // and the high watermark is normal operation (it is about to be consumed).
            val over = (size - AppLimits.Memory.SHORT_TERM).coerceAtLeast(0)
            val staleImages = staleImageCount(data.chat.getMessages(character.id))
            if (over > 0 || staleImages > 0) {
                characters++
                total += size
                // What the analysis still has to consume: everything above the low-water mark.
                pending += (size - AppLimits.Memory.SHORT_TERM_TRIM_FLOOR).coerceAtLeast(0)
                images += staleImages
            }
        }
        MemoryCleanupTarget(characters, total, pending, images, overLimit)
    }

    /**
     * Images outside the retention window whose file is still full-size, i.e. still to
     * be shrunk to a thumbnail. Uses a bounds-only decode (header, not pixels).
     */
    private fun staleImageCount(messages: List<ChatMessage>): Int {
        val cutoff = messages.size - AppLimits.Media.IMAGE_RETENTION_MESSAGES
        if (cutoff <= 0) return 0
        return messages.take(cutoff).sumOf { message ->
            message.attachments.count {
                it.kind == MessageAttachment.Kind.Image && isFullSizeImage(it.uri)
            }
        }
    }

    private fun isFullSizeImage(ref: String): Boolean {
        if (!ref.startsWith("images/")) return false
        val file = File(appContext.filesDir, ref)
        if (!file.exists()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return maxOf(bounds.outWidth, bounds.outHeight) > AppLimits.Media.THUMBNAIL_DIMENSION
    }

    /**
     * Shrinks message images that fell outside the retention window **in place** instead
     * of deleting them: the archive keeps its pictures, just at
     * [AppLimits.Media.THUMBNAIL_DIMENSION] px, so looking back never shows a broken or
     * empty image. The attachment reference is untouched, so no row is rewritten.
     * Returns how many files were shrunk.
     */
    suspend fun pruneOldMessageImages(): Int = withContext(Dispatchers.IO) {
        var shrunk = 0
        val referenced = mutableSetOf<String>()
        data.characters.all().forEach { character ->
            val messages = data.chat.getMessages(character.id)
            val cutoff = (messages.size - AppLimits.Media.IMAGE_RETENTION_MESSAGES).coerceAtLeast(0)
            messages.forEachIndexed { index, message ->
                message.attachments.forEach { attachment ->
                    referenced += attachment.uri
                    if (index < cutoff &&
                        attachment.kind == MessageAttachment.Kind.Image &&
                        shrinkToThumbnail(attachment.uri)
                    ) {
                        shrunk++
                    }
                }
            }
        }
        File(appContext.filesDir, "images").listFiles()?.forEach { file ->
            if ("images/${file.name}" !in referenced) runCatching { file.delete() }
        }
        shrunk
    }

    /** Overwrites an `images/<sha>.jpg` file with a thumbnail; false when already small. */
    private fun shrinkToThumbnail(ref: String): Boolean {
        if (!ref.startsWith("images/")) return false
        val file = File(appContext.filesDir, ref)
        if (!file.exists()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= AppLimits.Media.THUMBNAIL_DIMENSION) return false
        return runCatching {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching false
            val ratio = AppLimits.Media.THUMBNAIL_DIMENSION.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, AppLimits.Media.THUMBNAIL_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            file.writeBytes(out.toByteArray())
            true
        }.getOrDefault(false)
    }

    /** Characters whose window is an oversized backlog, and how many messages that is. */
    data class OversizedWindowTarget(val characters: Int, val messages: Int)

    suspend fun oversizedWindowTarget(): OversizedWindowTarget = withContext(Dispatchers.IO) {
        val sizes = data.characters.all()
            .map { character -> character.instant.count { message -> !message.isLoading } }
            .filter { it > AppLimits.Memory.INSTANT_MIGRATION_THRESHOLD }
        OversizedWindowTarget(sizes.size, sizes.sum())
    }

    /** AI lorebook entries over the per-entry cap that still need the one-time rewrite. */
    suspend fun oversizedLorebookCount(): Int = withContext(Dispatchers.IO) {
        data.characters.all().sumOf { character ->
            character.lorebook.count { needsLorebookRewrite(it) } +
                character.members.sumOf { member -> member.lorebook.count { needsLorebookRewrite(it) } }
        }
    }

    /** Result of one catch-up run: batches folded, and a reason when it stopped early. */
    data class MigrationResult(val batches: Int, val error: String = "")

    /**
     * Failure text for a one-time pass: the reason the background call itself reported
     * when there is one, otherwise a plain "stopped early" message. The pass always keeps
     * what it already did, and a retry resumes instead of repeating it.
     */
    private fun migrationFailure(what: String, reason: String): String =
        if (reason.isBlank()) {
            "${what}未完成，已整理的部分已保存"
        } else {
            "${what}未完成：$reason（已整理的部分已保存）"
        }

    /**
     * One-time catch-up of every oversized window: batches, oldest first, run to
     * completion (the UI blocks on it, as requested). Stops early only when a batch
     * fails — everything already folded is persisted first, so a retry resumes instead
     * of repeating the work.
     */
    suspend fun migrateOversizedWindows(
        config: AppConfig,
        onProgress: (done: Int, remaining: Int) -> Unit = { _, _ -> },
    ): MigrationResult = withContext(Dispatchers.IO) {
        if (apiKeyFor(config.apiPlatform).isNullOrBlank()) {
            return@withContext MigrationResult(0, "没有可用的 API Key，无法整理旧记忆")
        }
        val tasks = backgroundTasks(config)
        var batches = 0
        for (character in data.characters.all()) {
            if (!tasks.hasOversizedWindow(character)) continue
            val (status, updated) = tasks.migrateOversizedWindow(character) { done, remaining ->
                batches = maxOf(batches, done)
                onProgress(done, remaining)
            }
            if (updated != character) persistMemory(character, updated)
            if (status == BackgroundTasks.TaskStatus.Failure) {
                return@withContext MigrationResult(batches, migrationFailure("提取记忆", lastFailureNote("extraction")))
            }
        }
        MigrationResult(batches)
    }

    /**
     * One-time rewrite of the oversized lorebook entries an older version produced: each
     * is handed to the model in full and rewritten into 1..3 concise entries (one concept
     * each) **in place**, replacing the original. Runs to completion (the caller shows
     * progress); a failure keeps everything already rewritten, so a retry resumes.
     */
    suspend fun rebuildOversizedLorebooks(
        config: AppConfig,
        onProgress: (done: Int, remaining: Int) -> Unit = { _, _ -> },
    ): MigrationResult = withContext(Dispatchers.IO) {
        if (apiKeyFor(config.apiPlatform).isNullOrBlank()) {
            return@withContext MigrationResult(0, "没有可用的 API Key，无法整理世界书")
        }
        val tasks = backgroundTasks(config)
        var processed = 0
        for (character in data.characters.all()) {
            if (tasks.oversizedLorebookCount(character) <= 0) continue
            var doneHere = 0
            val (status, updated) = tasks.rebuildOversizedLorebook(character) { done, remaining ->
                doneHere = done
                onProgress(processed + done, remaining)
            }
            if (updated != character) persistMemory(character, updated)
            if (status == BackgroundTasks.TaskStatus.Failure) {
                return@withContext MigrationResult(
                    processed + doneHere,
                    migrationFailure("整理世界书", lastFailureNote("lorebook")),
                )
            }
            processed += doneHere
        }
        MigrationResult(processed)
    }

    /**
     * Drains the short-term backlog towards [AppLimits.Memory.SHORT_TERM_LOW_WATER]:
     * runs up to [maxBatches] whole-window passes. One pass already folds everything
     * pending (the window *is* the unit), so a single invocation converges; extra passes
     * only matter for a backlog larger than the window itself. Bounded so a single
     * invocation never spends a burst of API calls, and it stops as soon as a pass makes
     * no progress. The caller persists the "still pending" flag and continues on the next
     * launch, so one confirmation is enough.
     */
    suspend fun drainShortTermBacklog(
        config: AppConfig,
        maxBatches: Int = AppLimits.Memory.CLEANUP_BATCHES_PER_RUN,
    ): MemoryDrainResult = withContext(Dispatchers.IO) {
        if (apiKeyFor(config.apiPlatform).isNullOrBlank()) return@withContext MemoryDrainResult(0, "")
        val tasks = backgroundTasks(config)
        var passes = 0
        var lastError = ""
        repeat(maxBatches) {
            var progressed = false
            for (character in data.characters.all()) {
                if (character.shortTerm.size <= AppLimits.Memory.SHORT_TERM_LOW_WATER) continue
                val outcome = tasks.drainMemoryBacklog(character)
                if (outcome.character.counters.analysisLastError.isNotBlank()) {
                    lastError = outcome.character.counters.analysisLastError
                }
                // Persist even when the pass made no progress: the counters carry the
                // diagnostic that explains why (and are wiped on the next success).
                if (outcome.character != character) persistMemory(character, outcome.character)
                if (outcome.progressed) progressed = true
            }
            if (!progressed) return@withContext MemoryDrainResult(passes, lastError)
            passes++
        }
        MemoryDrainResult(passes, lastError)
    }

    /**
     * Persists only the fields a background pass actually changed, re-reading the row
     * first. A pass can take seconds (folds, model rewrites); writing its whole starting
     * snapshot back would silently roll back whatever a concurrent writer added in the
     * meantime. `instant` is included because folds consume window messages.
     */
    private suspend fun persistMemory(original: Character, updated: Character) {
        val fresh = data.characters.get(original.id) ?: original
        data.characters.upsert(
            fresh.copy(
                instant = if (updated.instant != original.instant) updated.instant else fresh.instant,
                shortTerm = if (updated.shortTerm != original.shortTerm) updated.shortTerm else fresh.shortTerm,
                longTerm = if (updated.longTerm != original.longTerm) updated.longTerm else fresh.longTerm,
                members = if (updated.members != original.members) updated.members else fresh.members,
                scenes = if (updated.scenes != original.scenes) updated.scenes else fresh.scenes,
                sceneState = if (updated.sceneState != original.sceneState) updated.sceneState else fresh.sceneState,
                lorebook = if (updated.lorebook != original.lorebook) updated.lorebook else fresh.lorebook,
                counters = if (updated.counters != original.counters) updated.counters else fresh.counters,
            ),
        )
    }

    /** One-time world-book migration for characters whose background has not been migrated. */
    suspend fun runWorldLoreMigration(config: AppConfig, trimSource: Boolean): LorebookMigrationOutcome {
        if (apiKeyFor(config.apiPlatform).isNullOrBlank()) return LorebookMigrationOutcome(0, 0)
        return characterMaintenanceMutex.withLock {
            val tasks = backgroundTasks(config)
            var success = 0
            var failed = 0
            data.characters.all().forEach { character ->
                val source = migrationSourceText(character)
                if (character.lorebookMigratedAt != null || source.trim().length < 40) return@forEach
                try {
                    val latest = data.characters.get(character.id) ?: character
                    if (latest.lorebookMigratedAt != null) return@forEach
                    val updated = tasks.migrateWorldLore(latest, trimSource)
                    if (updated != latest) {
                        // Merge onto the freshest row so we don't lose concurrent edits.
                        val fresh = data.characters.get(character.id) ?: latest
                        data.characters.upsert(
                            fresh.copy(
                                lorebook = updated.lorebook,
                                lorebookMigratedAt = updated.lorebookMigratedAt,
                                staticProfile = updated.staticProfile,
                                description = updated.description,
                            ),
                        )
                        success++
                    }
                } catch (t: Throwable) {
                    // Isolate per character: one failure must not abort the batch
                    // (a thrown LLM/serialization error used to zero out all rows).
                    failed++
                    DeepTalkingApp.recordError(t)
                }
            }
            LorebookMigrationOutcome(success, failed)
        }
    }

    /** One-time field-structure remap for characters not yet on the current schema. */
    suspend fun remapCharacterFields(config: AppConfig): Int {
        if (apiKeyFor(config.apiPlatform).isNullOrBlank()) return 0
        return characterMaintenanceMutex.withLock {
            runCatching {
                val tasks = backgroundTasks(config)
                var count = 0
                data.characters.all().forEach { character ->
                    if (character.fieldsMigrationVersion == "1.2.0") return@forEach
                    val latest = data.characters.get(character.id) ?: character
                    if (latest.fieldsMigrationVersion == "1.2.0") return@forEach
                    val updated = tasks.remapFields(latest)
                    if (updated != latest) {
                        val fresh = data.characters.get(character.id) ?: latest
                        data.characters.upsert(
                            fresh.copy(
                                dynamicState = updated.dynamicState,
                                staticProfile = updated.staticProfile,
                                fieldsMigrationVersion = updated.fieldsMigrationVersion,
                            ),
                        )
                        count++
                    }
                }
                count
            }.getOrDefault(0)
        }
    }

    /** Characters not yet on the current field schema (legacy `FIELD_MIGRATION_TARGETS`). */
    suspend fun fieldMigrationTargetCount(): Int =
        data.characters.all().count { it.fieldsMigrationVersion != "1.2.0" }

    /** Legacy `collectLorebookMigrationTargets`: background/description long enough to hold world facts. */
    suspend fun lorebookMigrationTargetCount(): Int =
        data.characters.all().count {
            it.lorebookMigratedAt == null && migrationSourceText(it).trim().length >= 40
        }

    private fun migrationSourceText(character: Character): String =
        if (character.isGroup) character.description else character.staticProfile.background

    /**
     * Two-phase connectivity probe mirroring the legacy `testApiConnection`
     * (`status-settings.js:177-222`): (1) a GET on `baseUrl + '/'` for site
     * reachability, (2) a minimal, non-streaming Responses POST whose real
     * `HTTP <status>` and server error are echoed back. Returns a human-readable
     * multi-line report. Never throws.
     */
    suspend fun testApiConnection(config: AppConfig, apiKey: String): String = withContext(Dispatchers.IO) {
        applyDohPolicy(config)
        val base = normalizeApiBaseUrl(config.apiBaseUrl)
        if (base.isEmpty()) return@withContext "未配置 API Base URL，请先填写后再测试。"
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(dohDns)
            .build()
        val report = StringBuilder()
        report.append("① 站点可达性：")
        report.append(
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url(base.trimEnd('/') + "/")
                        .header("User-Agent", userAgent)
                        .get()
                        .build(),
                ).execute().use { "可达（HTTP ${it.code}）" }
            }.getOrElse { "无法连接（${it.message ?: it}）。可能是网络不通、域名无法解析或被拦截" },
        )
        report.append("\n② 接口调用：")
        val endpoint = responsesEndpointFor(config, base)
        report.append(
            runCatching {
                val payload = buildJsonObject {
                    put("model", config.modelName.ifBlank { "deepseek-flash" })
                    put("input", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", "hi")
                        })
                    })
                    put("max_output_tokens", 50)
                    put("stream", false)
                    put("reasoning", buildJsonObject { put("effort", "none") })
                }.toString()
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", userAgent)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .apply {
                        // OpenCode Go mandates a stable per-session header; the probe
                        // must send it too or the gateway returns 400 MissingSessionID
                        // (legacy `testApiConnection` used `buildApiHeaders`).
                        opencodeSessionHeader(config.apiPlatform, "deeptalking-general")?.let { (name, value) ->
                            addHeader(name, value)
                        }
                    }
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val parsed = runCatching {
                        Json.parseToJsonElement(text) as? JsonObject
                    }.getOrNull()
                    val error = parsed?.get("error") as? JsonObject
                    val output = parsed?.get("output")
                    val outputText = parsed?.get("output_text")
                    buildString {
                        append("HTTP ${response.code}")
                        when {
                            error != null -> append("，服务端报错：")
                                .append(error.str("message").ifBlank { error.str("type") })
                            output is JsonArray || outputText is JsonPrimitive -> append("，正常返回")
                            else -> append("（响应不是标准 JSON，可能端点到错了）")
                        }
                    }
                }
            }.getOrElse { "失败（${it.message ?: it}）。若站点可达但此处失败，多半是网络被拦或 Key 无效" },
        )
        report.append("\n当前端点：").append(endpoint)
        report.toString()
    }

    /** Mirrors the legacy `normalizeApiBaseUrl`: trim + drop trailing slashes. */
    private fun normalizeApiBaseUrl(value: String): String = value.trim().trimEnd('/')

    /** Local mirror of `ResponsesLlmBackend.responsesEndpoint` (`core:network`, internal). */
    private fun responsesEndpointFor(config: AppConfig, base: String): String {
        var url = normalizeApiBaseUrl(base)
            .replace(Regex("/chat/completions$", RegexOption.IGNORE_CASE), "")
            .trimEnd('/')
        val keepV1 = BuiltinPlatforms.firstOrNull { it.id == config.apiPlatform }?.keepV1InResponses == true
        if (!keepV1) url = url.replace(Regex("/v1$", RegexOption.IGNORE_CASE), "")
        return url + "/responses"
    }

    /**
     * Runs a non-streaming auxiliary/background call and records its usage into
     * `requestMetrics`, mirroring the legacy `callAPI` → `recordCacheUsage` path.
     */
    private suspend fun trackedComplete(config: AppConfig, taskType: String, request: LlmRequest): LlmResult {
        val backend = ResponsesLlmBackend(
            apiKeyProvider = { apiKeyFor(config.apiPlatform) },
            baseUrl = config.apiBaseUrl,
            userAgent = userAgent,
        )
        val startedAt = System.currentTimeMillis()
        return try {
            val result = backend.complete(request)
            recordAuxMetric(config, taskType, startedAt, result.usage, "completed")
            result
        } catch (t: Throwable) {
            recordAuxMetric(config, taskType, startedAt, null, "failed", t.message?.take(160).orEmpty())
            throw t
        }
    }

    /** Appends one auxiliary/background request metric (legacy `recordCacheUsage`). */
    private suspend fun recordAuxMetric(
        config: AppConfig,
        taskType: String,
        startedAt: Long,
        usage: TokenUsage?,
        status: String,
        reason: String = "",
    ) {
        val input = usage?.inputTokens?.takeIf { it > 0 }
        val hit = usage?.cachedTokens
        val miss = if (input != null && hit != null) (input - hit).coerceAtLeast(0) else null
        val metric = RequestMetric(
            at = Instant.now().toString(),
            taskType = taskType,
            model = config.modelName,
            platform = config.apiPlatform,
            status = status,
            // The debug block shows `status` and `phase`; a failed background call keeps
            // its reason here, so the log answers "为什么整理没成功" on the device.
            phase = reason,
            durationMs = System.currentTimeMillis() - startedAt,
            inputTokens = input,
            outputTokens = usage?.outputTokens,
            hitTokens = hit,
            missTokens = miss,
            hitRate = if (input != null && hit != null) hit.toDouble() / input else null,
        )
        runCatching {
            val latest = data.config.current()
            data.config.update(
                latest.copy(requestMetrics = (latest.requestMetrics + metric).takeLast(AppLimits.Api.REQUEST_METRICS)),
            )
        }
    }

    /** One-line character/group generation; returns the raw JSON string from the model. */
    suspend fun quickGenerate(config: AppConfig, prompt: String): String? {
        val result = trackedComplete(
            config,
            "auxiliary",
            LlmRequest(
                model = config.modelName,
                instructions = QUICK_GEN_INSTRUCTIONS,
                input = listOf(ChatMessage(role = Role.User, content = prompt)),
                temperature = 1.0,
                maxOutputTokens = 1200,
                reasoningEffort = "none",
                apiPlatform = config.apiPlatform,
                sessionId = "deeptalking-general",
            ),
        )
        return result.text.takeIf { it.isNotBlank() }
    }

    /** Generates a single emoji avatar from a character's description. */
    suspend fun generateEmojiAvatar(config: AppConfig, character: Character): String? =
        requestEmojiAvatar(
            config,
            com.deeptalking.feature.characters.CharacterParity.buildAvatarDescription(character),
        )

    /** Generates a group member's emoji, including `roleInGroup` in the description. */
    suspend fun generateEmojiAvatar(config: AppConfig, member: GroupMember, parent: Character?): String? =
        requestEmojiAvatar(
            config,
            com.deeptalking.feature.characters.CharacterParity.buildAvatarDescription(member, parent),
        )

    private suspend fun requestEmojiAvatar(config: AppConfig, description: String): String? {
        val result = trackedComplete(
            config,
            "auxiliary",
            LlmRequest(
                model = config.modelName,
                instructions = "你是角色头像助手。只输出一个最能代表该角色的 emoji 字符，不要任何文字、标点或解释。",
                input = listOf(ChatMessage(role = Role.User, content = description.ifBlank { "一个神秘角色" })),
                temperature = 1.2,
                maxOutputTokens = 16,
                reasoningEffort = "none",
                apiPlatform = config.apiPlatform,
                sessionId = "deeptalking-general",
            ),
        )
        return extractEmoji(result.text)
    }

    /** Legacy `extractEmoji`: pick the first emoji codepoint from a model reply. */
    private fun extractEmoji(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val isEmoji = (cp in 0x1F300..0x1FAFF) || (cp in 0x2600..0x27BF) ||
                (cp in 0x1F000..0x1F2FF) || (cp in 0x2190..0x21FF) || (cp in 0x2B00..0x2BFF)
            if (isEmoji) {
                val charCount = java.lang.Character.charCount(cp)
                return String(text.toCharArray(), index, charCount)
            }
            index += java.lang.Character.charCount(cp)
        }
        return null
    }

    /** Legacy `tagStickerImage`: vision-classify a sticker into one vocabulary tag. */
    suspend fun tagSticker(config: AppConfig, imageRef: String): String? {
        if (MediaRef.toDataUri(appContext, imageRef) == null) return null
        val message = ChatMessage(
            role = Role.User,
            content = "给这张图打一个短词标签。",
            attachments = listOf(com.deeptalking.core.model.MessageAttachment(com.deeptalking.core.model.MessageAttachment.Kind.Image, imageRef)),
        )
        val result = trackedComplete(
            config,
            "auxiliary",
            LlmRequest(
                model = config.modelName,
                instructions = "你是表情包分类助手。看这张表情包或图片，从下列标签中选一个最贴切的：" + STICKER_TAGS.joinToString("、") +
                    "。若都不贴切，就给出一个 1 到 6 字的中文短词。只输出这一个词，不要标点、解释或其它文字。",
                input = listOf(message),
                temperature = 0.2,
                maxOutputTokens = 8,
                reasoningEffort = "none",
                apiPlatform = config.apiPlatform,
                sessionId = "deeptalking-general",
                imageResolver = { ref -> MediaRef.toDataUri(appContext, ref) },
            ),
        )
        val tag = result.text.trim()
            .replace(Regex("[\\s\r\n\"'“”‘’。，,、！!？?：:；;（）()\\[\\]]"), "")
            .take(6)
        return tag.takeIf { it.isNotEmpty() }
    }

    /** Parses the model's group/member JSON into [GroupMember]s (tolerating prose/fences). */
    fun parseGroupMembers(raw: String): List<GroupMember> =
        com.deeptalking.feature.characters.CharacterParity.parseGroupMembersJson(raw)

    /** Legacy `fillGroupMemberFields`: AI completion constrained to empty fields only. */
    suspend fun fillMemberFields(
        config: AppConfig,
        group: Character,
        member: GroupMember,
        hint: String,
    ): String? {
        val groupInfo = buildJsonObject {
            put("name", group.name)
            put("avatar", group.emoji)
            put("description", group.description)
            put("scene", group.groupSharedDynamic.currentLocation)
            put("interactionRules", group.interactionRules)
        }
        val otherMembers = buildJsonArray {
            group.members.filter { it.id != member.id }.forEach { other ->
                add(
                    buildJsonObject {
                        put("name", other.name)
                        put("personality", other.staticProfile.personality)
                        put("speakingStyle", other.staticProfile.speakingStyle)
                        put("roleInGroup", other.roleInGroup)
                    },
                )
            }
        }
        val memberJson = buildJsonObject {
            put("name", member.name)
            put("avatar", member.emoji)
            put("gender", member.staticProfile.gender)
            put("age", member.staticProfile.age)
            put("race", member.staticProfile.race)
            put("appearance", member.staticProfile.appearance)
            put("personality", member.staticProfile.personality)
            put("values", member.staticProfile.values)
            put("fears", member.staticProfile.fears)
            put("background", member.staticProfile.background)
            put("keyEvents", member.staticProfile.keyEvents)
            put("speakingStyle", member.staticProfile.speakingStyle)
            put("language", member.staticProfile.language)
            put("userAddress", member.staticProfile.userAddress)
            put("roleInGroup", member.roleInGroup)
            put(
                "dynamicState",
                buildJsonObject {
                    put("currentSituation", member.dynamicState.currentSituation)
                    put("currentLocation", member.dynamicState.currentLocation)
                    put("currentMood", member.dynamicState.currentMood)
                    put("currentOccupation", member.dynamicState.currentOccupation)
                    put("currentGoal", member.dynamicState.currentGoal)
                    put("currentTone", member.dynamicState.currentTone)
                },
            )
        }
        val userPayload = buildString {
            append("群组信息:\n").append(groupInfo).append('\n')
            append("群组其他成员:\n").append(otherMembers).append('\n')
            append("待补全成员:\n").append(memberJson).append('\n')
            append("用户补充:\n").append(hint)
            append('\n').append(com.deeptalking.feature.characters.CharacterParity.SPEAKING_STYLE_SAMPLES_RULE)
        }
        val result = trackedComplete(
            config,
            "auxiliary",
            LlmRequest(
                model = config.modelName,
                instructions = FILL_MEMBER_INSTRUCTIONS,
                input = listOf(ChatMessage(role = Role.User, content = userPayload)),
                temperature = 0.8,
                maxOutputTokens = 800,
                reasoningEffort = "none",
                apiPlatform = config.apiPlatform,
                sessionId = "deeptalking-general",
            ),
        )
        return result.text.takeIf { it.isNotBlank() }
    }

    /** Applies a fill payload to a member, only overwriting empty fields. */
    fun applyMemberFill(member: GroupMember, raw: String): GroupMember =
        com.deeptalking.feature.characters.CharacterParity.applyMemberFillPayload(member, raw, java.time.ZonedDateTime.now())

    fun scheduleTestReminder() {
        com.deeptalking.core.notifications.Reminders.schedule(
            context = appContext,
            id = "test-reminder",
            triggerAtMillis = System.currentTimeMillis() + 5_000L,
            title = "DeepTalking",
            text = "提醒功能测试：这是一条本地通知。",
        )
    }

    /** Posts a reminder notification immediately (Android-native equivalent of "到时间提醒"). */
    fun postReminder(id: String, text: String) {
        com.deeptalking.core.notifications.Reminders.post(appContext, id, "DeepTalking · 待办提醒", text)
    }

    /** Schedules a reminder notification at [triggerAtMillis] (idempotent per id). */
    fun scheduleReminder(id: String, triggerAtMillis: Long, text: String) {
        com.deeptalking.core.notifications.Reminders.schedule(
            context = appContext,
            id = id,
            triggerAtMillis = triggerAtMillis,
            title = "DeepTalking · 待办提醒",
            text = text,
        )
    }

    /**
     * Builds a per-turn orchestrator using the current config snapshot so model,
     * base URL, temperature and stream settings always reflect the latest
     * choices from Settings. Background tasks (prose memory extraction, style
     * critique, quick-reply repair) are wired through [BackgroundTasks]' own
     * serial queue.
     */
    fun createOrchestrator(
        config: AppConfig,
        onCharacterUpdated: (Character) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onAuxiliaryUsage: (String, com.deeptalking.engine.ondevice.TokenUsage) -> Unit = { _, _ -> },
    ): ChatOrchestrator {
        val llm = ResponsesLlmBackend(
            apiKeyProvider = { apiKeyFor(config.apiPlatform) },
            baseUrl = config.apiBaseUrl,
            userAgent = userAgent,
            dns = dohDns,
        )
        val background = BackgroundTasks(
            llm,
            memory,
            config.modelName,
            config.apiPlatform,
            backgroundQueue,
            config,
            liveCharacter = { id -> data.characters.get(id) },
            onStatus = onStatus,
            onAuxiliaryUsage = onAuxiliaryUsage,
        )
        return ChatOrchestrator(
            llm = llm,
            tools = tools,
            memory = memory,
            config = config,
            backgroundTasks = background,
            onCharacterUpdated = onCharacterUpdated,
            imageResolver = { ref -> MediaRef.toDataUri(appContext, ref) },
        )
    }

    private fun extractJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        }.getOrNull()
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

    private companion object {
        /** Legacy `STICKER_TAGS` (`src/js/core/config.js:107`). */
        val STICKER_TAGS = listOf(
            "开心", "大笑", "难过", "委屈", "生气", "无语", "惊讶", "疑惑", "害羞",
            "得意", "爱意", "亲亲", "抱抱", "加油", "点赞", "拒绝", "睡觉", "干杯",
        )

        val QUICK_GEN_INSTRUCTIONS = """
            你是角色设计助手。严格按用户在消息里给出的 JSON 结构返回，只输出 JSON，不要任何解释、旁白或代码块标记。
            不得使用陈词滥调的人设模板；世界层设定一律写进 lorebook，不要写进角色个人背景。
        """.trimIndent()

        val FILL_MEMBER_INSTRUCTIONS = """
            你是角色卡补全助手。只返回 JSON。
            只能根据群组设定、现有角色卡和用户的一句话补全空字段，不能覆盖或编造与已有字段冲突的信息。
            已有字段是绝对权威，任何情况下不得改写、润色或替换。
            返回字段：name, avatar, gender, age, race, appearance, personality, values, fears,
            background, keyEvents, speakingStyle, language, userAddress, roleInGroup,
            dynamicState(对象: currentSituation, currentLocation, currentMood, currentOccupation, currentGoal, currentTone)。
            只给出有把握的字段，没有把握就省略。成员的说话方式必须与群内其他成员显著不同。
        """.trimIndent()
    }
}

/** Outcome of a world-book migration batch. */
data class LorebookMigrationOutcome(val success: Int, val failed: Int)
