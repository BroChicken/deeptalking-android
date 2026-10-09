package com.deeptalking.lite

import android.content.Context
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
import com.deeptalking.core.model.RequestMetric
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.network.HttpWebContentProvider
import com.deeptalking.core.network.ResponsesLlmBackend
import com.deeptalking.core.security.SecretStore
import com.deeptalking.engine.ondevice.InferenceRegistry
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.TokenUsage
import com.deeptalking.engine.cosyvoice.CosyVoiceController
import com.deeptalking.domain.agent.ChatOrchestrator
import com.deeptalking.domain.agent.ToolRegistry
import com.deeptalking.domain.agent.background.BackgroundTaskQueue
import com.deeptalking.domain.agent.background.BackgroundTasks
import com.deeptalking.domain.agent.defaultTools
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.domain.memory.RelativeTimeMigration
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
    val memory: MemoryServiceImpl = MemoryServiceImpl()
    val web: HttpWebContentProvider = HttpWebContentProvider()
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

    /** LLM is remote; the read-aloud TTS backend is on-device. embedding/asr stay null. */
    val inference: InferenceRegistry = InferenceRegistry(
        llm = ResponsesLlmBackend(apiKeyProvider = { secrets.getApiKey() }, userAgent = userAgent),
        tts = cosyVoice.backend,
    )

    val stickerSink: FileStickerSink = FileStickerSink(appContext)
    val backup: BackupService = BackupService(
        characters = data.characters,
        chat = data.chat,
        config = data.config,
        stickerSink = stickerSink,
        mediaSource = MediaRef.source(appContext),
        appVersion = BuildConfig.VERSION_NAME,
    )

    suspend fun currentConfig(): AppConfig = data.config.current()

    /** True when the active platform (or the legacy global slot) has an API key. */
    fun hasApiKey(platform: String): Boolean = !apiKeyFor(platform).isNullOrBlank()

    /** Resolves the active platform's key, falling back to the legacy global key. */
    private fun apiKeyFor(platform: String): String? =
        secrets.getApiKey(platform) ?: secrets.getApiKey()

    private fun backgroundTasks(config: AppConfig): BackgroundTasks =
        BackgroundTasks(
            ResponsesLlmBackend(apiKeyProvider = { apiKeyFor(config.apiPlatform) }, baseUrl = config.apiBaseUrl, userAgent = userAgent),
            memory,
            config.modelName,
            config.apiPlatform,
            backgroundQueue,
            config,
            liveCharacter = { id -> data.characters.get(id) },
        )

    /**
     * Startup maintenance mirroring the legacy on-load tasks: silently fill empty
     * static-profile fields (bounded to 3 jobs). Best-effort; never throws. World-book
     * / field migrations are interactive in the legacy UI and exposed separately via
     * [runWorldLoreMigration] / [remapCharacterFields].
     */
    suspend fun runStartupMaintenance(config: AppConfig) {
        if (apiKeyFor(config.apiPlatform).isNullOrBlank()) return
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

    /** One-time relative-time → absolute migration (legacy `convertLegacyRelativeTimes`). */
    suspend fun runStartupMigrations() {
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
        val base = normalizeApiBaseUrl(config.apiBaseUrl)
        if (base.isEmpty()) return@withContext "未配置 API Base URL，请先填写后再测试。"
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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
            recordAuxMetric(config, taskType, startedAt, null, "failed")
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
