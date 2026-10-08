package com.deeptalking.lite

import android.content.Context
import com.deeptalking.core.data.CoreDataContainer
import com.deeptalking.core.data.legacy.BackupService
import com.deeptalking.core.data.legacy.FileStickerSink
import com.deeptalking.core.data.legacy.MediaRef
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.network.HttpWebContentProvider
import com.deeptalking.core.network.ResponsesLlmBackend
import com.deeptalking.core.security.SecretStore
import com.deeptalking.engine.ondevice.InferenceRegistry
import com.deeptalking.engine.ondevice.LlmRequest
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

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
     * Two-phase connectivity probe mirroring the legacy `testApiConnection`:
     * (1) site reachability, (2) a minimal Responses call. Returns a
     * human-readable multi-line report. Never throws.
     */
    suspend fun testApiConnection(config: AppConfig, apiKey: String): String = withContext(Dispatchers.IO) {
        val base = config.apiBaseUrl.trimEnd('/')
        val report = StringBuilder()
        report.append("① 站点可达性：")
        report.append(
            runCatching {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
                // Probe the OpenAI-compatible model list: a real HTTP response (200 on
                // OpenCode Go, 401 without a key elsewhere) means the site is reachable.
                val probeUrl = (base.ifBlank { "https://api.deepseek.com/v1" }) + "/models"
                client.newCall(
                    okhttp3.Request.Builder()
                        .url(probeUrl)
                        .header("User-Agent", userAgent)
                        .build(),
                ).execute().use { "可达（HTTP ${it.code}）" }
            }.getOrElse { "无法连接（${it.message ?: it}）。可能是网络不通、域名无法解析或被拦截" },
        )
        report.append("\n② 接口调用：")
        report.append(
            runCatching {
                val backend = ResponsesLlmBackend(apiKeyProvider = { apiKey }, baseUrl = base, userAgent = userAgent)
                val result = backend.complete(
                    LlmRequest(
                        model = config.modelName,
                        instructions = "用中文回复“连接正常”。",
                        input = listOf(ChatMessage(role = Role.User, content = "hi")),
                        maxOutputTokens = 32,
                        reasoningEffort = "none",
                        apiPlatform = config.apiPlatform,
                        sessionId = "deeptalking-general",
                    ),
                )
                "HTTP 200，正常返回（${result.text.take(40).ifBlank { "空" }}）"
            }.getOrElse { "失败（${it.message ?: it}）" },
        )
        report.append("\n当前端点：$base/responses")
        report.toString()
    }

    /** One-line character/group generation; returns the raw JSON string from the model. */
    suspend fun quickGenerate(config: AppConfig, prompt: String): String? {
        val backend = ResponsesLlmBackend(apiKeyProvider = { apiKeyFor(config.apiPlatform) }, baseUrl = config.apiBaseUrl, userAgent = userAgent)
        val result = backend.complete(
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

    /**
     * Derives a short, free-form tone/emotion instruction for read-aloud TTS from
     * the character's persona plus the spoken line. Returns null when the model is
     * unreachable or returns nothing, so callers can fall back to a neutral voice.
     */
    suspend fun generateToneInstruction(config: AppConfig, spokenText: String, character: Character?): String? {
        if (spokenText.isBlank()) return null
        val backend = ResponsesLlmBackend(
            apiKeyProvider = { apiKeyFor(config.apiPlatform) },
            baseUrl = config.apiBaseUrl,
            userAgent = userAgent,
        )
        val result = runCatching {
            backend.complete(
                LlmRequest(
                    model = config.modelName,
                    instructions = TONE_INSTRUCTION_SYSTEM,
                    input = listOf(
                        ChatMessage(
                            role = Role.User,
                            content = "【角色设定】\n" + buildTonePersona(character) + "\n\n【台词】\n" + spokenText,
                        ),
                    ),
                    temperature = 0.7,
                    maxOutputTokens = 80,
                    reasoningEffort = "none",
                    apiPlatform = config.apiPlatform,
                    sessionId = "deeptalking-general",
                ),
            ).text
        }.getOrNull()
        return result?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Summarizes the persona fields that shape delivery (voice/tone), not content. */
    private fun buildTonePersona(character: Character?): String {
        if (character == null) return "（无角色设定，按台词自然语气朗读）"
        val sp = character.staticProfile
        val ds = character.dynamicState
        val parts = buildList {
            if (character.name.isNotBlank()) add("姓名：" + character.name)
            if (sp.personality.isNotBlank()) add("性格：" + sp.personality)
            if (sp.speakingStyle.isNotBlank()) add("说话风格：" + sp.speakingStyle)
            if (sp.userAddress.isNotBlank()) add("对用户的称呼：" + sp.userAddress)
            if (sp.language.isNotBlank()) add("语言/方言：" + sp.language)
            if (ds.currentMood.isNotBlank()) add("当前情绪：" + ds.currentMood)
            if (ds.currentSituation.isNotBlank()) add("当前处境：" + ds.currentSituation)
        }
        return if (parts.isEmpty()) "（无角色设定，按台词自然语气朗读）" else parts.joinToString("\n")
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
        val backend = ResponsesLlmBackend(apiKeyProvider = { apiKeyFor(config.apiPlatform) }, baseUrl = config.apiBaseUrl, userAgent = userAgent)
        val result = backend.complete(
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
        val backend = ResponsesLlmBackend(apiKeyProvider = { apiKeyFor(config.apiPlatform) }, baseUrl = config.apiBaseUrl, userAgent = userAgent)
        val message = ChatMessage(
            role = Role.User,
            content = "给这张表情包选一个标签。",
            attachments = listOf(com.deeptalking.core.model.MessageAttachment(com.deeptalking.core.model.MessageAttachment.Kind.Image, imageRef)),
        )
        val result = backend.complete(
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
        val tag = result.text.trim().replace(Regex("\\s+"), "").take(6)
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
        val backend = ResponsesLlmBackend(apiKeyProvider = { apiKeyFor(config.apiPlatform) }, baseUrl = config.apiBaseUrl, userAgent = userAgent)
        val otherMembers = group.members
            .filter { it.id != member.id }
            .map { mapOf("name" to it.name, "personality" to it.staticProfile.personality, "roleInGroup" to it.roleInGroup) }
        val userPayload = buildString {
            append("群组信息:\n").append(group.description).append('\n')
            append("群组其他成员:\n").append(otherMembers).append('\n')
            append("待补全成员:\n").append(member.name).append(' ').append(member.staticProfile.personality).append('\n')
            append("用户补充:\n").append(hint)
        }
        val result = backend.complete(
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
    ): ChatOrchestrator {
        val llm = ResponsesLlmBackend(
            apiKeyProvider = { apiKeyFor(config.apiPlatform) },
            baseUrl = config.apiBaseUrl,
            userAgent = userAgent,
        )
        val background = BackgroundTasks(llm, memory, config.modelName, config.apiPlatform, backgroundQueue, config)
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

        val TONE_INSTRUCTION_SYSTEM = """
            你是配音导演。根据角色设定与台词，输出一句简短的中文语气指令，用于指导语音合成（TTS）的语气、情绪、语速与音量。
            只输出指令本身，不要解释、不要引号、不要换行，20 字以内。
            示例：温柔而略带笑意，语速稍慢，音量适中。
        """.trimIndent()

        val FILL_MEMBER_INSTRUCTIONS = """
            你是角色卡补全助手。只返回 JSON。
            只能根据群组设定、现有角色卡和用户的一句话补全空字段，不能覆盖或编造与已有字段冲突的信息。
            已有字段是绝对权威，任何情况下不得改写、润色或替换。
            返回字段：name, avatar, gender, age, race, appearance, personality, values, fears,
            background, keyEvents, speakingStyle, language, userAddress, roleInGroup,
            dynamicState(对象: currentSituation, currentLocation, currentMood, currentOccupation, currentGoal, currentRelationship, currentImportantOthers)。
            只给出有把握的字段，没有把握就省略。成员的说话方式必须与群内其他成员显著不同。
        """.trimIndent()
    }
}

/** Outcome of a world-book migration batch. */
data class LorebookMigrationOutcome(val success: Int, val failed: Int)
