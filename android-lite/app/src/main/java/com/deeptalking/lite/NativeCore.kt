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
import com.deeptalking.domain.agent.ChatOrchestrator
import com.deeptalking.domain.agent.ToolRegistry
import com.deeptalking.domain.agent.background.BackgroundTaskQueue
import com.deeptalking.domain.agent.background.BackgroundTasks
import com.deeptalking.domain.agent.defaultTools
import com.deeptalking.domain.memory.MemoryServiceImpl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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

    /** Only LLM is wired today; embedding/asr/tts stay null until their milestones. */
    val inference: InferenceRegistry = InferenceRegistry(
        llm = ResponsesLlmBackend(apiKeyProvider = { secrets.getApiKey() }, userAgent = userAgent),
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
        )

    /**
     * Startup maintenance mirroring the legacy on-load tasks: silently fill empty
     * static-profile fields (bounded to 3 jobs). Best-effort; never throws. World-book
     * / field migrations are interactive in the legacy UI and exposed separately via
     * [runWorldLoreMigration] / [remapCharacterFields].
     */
    suspend fun runStartupMaintenance(config: AppConfig) {
        if (apiKeyFor(config.apiPlatform).isNullOrBlank()) return
        runCatching {
            val tasks = backgroundTasks(config)
            val characters = data.characters.all()
            val updated = tasks.autoFillStaticFields(characters)
            updated.forEachIndexed { index, character ->
                if (character != characters.getOrNull(index)) data.characters.upsert(character)
            }
        }
    }

    /** One-time world-book migration for characters whose background has not been migrated. */
    suspend fun runWorldLoreMigration(config: AppConfig, trimSource: Boolean): Int {
        if (apiKeyFor(config.apiPlatform).isNullOrBlank()) return 0
        return runCatching {
            val tasks = backgroundTasks(config)
            var count = 0
            data.characters.all().forEach { character ->
                val source = if (character.isGroup) character.description else character.staticProfile.background
                if (character.lorebookMigratedAt == null && source.trim().length >= 40) {
                    val updated = tasks.migrateWorldLore(character, trimSource)
                    if (updated != character) {
                        data.characters.upsert(updated)
                        count++
                    }
                }
            }
            count
        }.getOrDefault(0)
    }

    /** One-time field-structure remap for characters not yet on the current schema. */
    suspend fun remapCharacterFields(config: AppConfig): Int {
        if (apiKeyFor(config.apiPlatform).isNullOrBlank()) return 0
        return runCatching {
            val tasks = backgroundTasks(config)
            var count = 0
            data.characters.all().forEach { character ->
                if (character.fieldsMigrationVersion != "1.2.0") {
                    val updated = tasks.remapFields(character)
                    if (updated != character) {
                        data.characters.upsert(updated)
                        count++
                    }
                }
            }
            count
        }.getOrDefault(0)
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

    /** Generates a single emoji avatar from a character's description. */
    suspend fun generateEmojiAvatar(config: AppConfig, character: Character): String? {
        val backend = ResponsesLlmBackend(apiKeyProvider = { apiKeyFor(config.apiPlatform) }, baseUrl = config.apiBaseUrl, userAgent = userAgent)
        val description = buildString {
            append(character.name).append(' ')
            append(character.staticProfile.appearance).append(' ')
            append(character.staticProfile.personality)
        }.trim()
        val result = backend.complete(
            LlmRequest(
                model = config.modelName,
                instructions = "You output exactly ONE emoji character that best represents the person. " +
                    "Output only the emoji, nothing else.",
                input = listOf(ChatMessage(role = Role.User, content = description.ifBlank { character.name })),
                temperature = 1.2,
                maxOutputTokens = 16,
                reasoningEffort = "none",
                apiPlatform = config.apiPlatform,
                sessionId = "deeptalking-general",
            ),
        )
        return result.text.trim().takeIf { it.isNotBlank() }?.take(4)
    }

    /** Parses the model's group/member JSON into [GroupMember]s (tolerating prose/fences). */
    fun parseGroupMembers(raw: String): List<GroupMember> {
        val obj = extractJsonObject(raw) ?: return emptyList()
        val array = obj["members"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val member = element as? JsonObject ?: return@mapNotNull null
            val name = member.str("name")
            if (name.isBlank()) return@mapNotNull null
            GroupMember(
                id = UUID.randomUUID().toString(),
                name = name,
                emoji = member.str("avatar").ifBlank { member.str("emoji").ifBlank { "👤" } },
                roleInGroup = member.str("roleInGroup"),
                staticProfile = StaticProfile(
                    personality = member.str("personality"),
                    speakingStyle = member.str("speakingStyle"),
                    background = member.str("background"),
                ),
            )
        }
    }

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
    fun applyMemberFill(member: GroupMember, raw: String): GroupMember {
        val obj = extractJsonObject(raw) ?: return member
        fun fill(current: String, key: String) = current.ifBlank { obj.str(key) }
        var profile = member.staticProfile
        profile = profile.copy(
            gender = fill(profile.gender, "gender"),
            age = fill(profile.age, "age"),
            race = fill(profile.race, "race"),
            appearance = fill(profile.appearance, "appearance"),
            personality = fill(profile.personality, "personality"),
            values = fill(profile.values, "values"),
            fears = fill(profile.fears, "fears"),
            background = fill(profile.background, "background"),
            keyEvents = fill(profile.keyEvents, "keyEvents"),
            speakingStyle = fill(profile.speakingStyle, "speakingStyle"),
            language = fill(profile.language, "language"),
            userAddress = fill(profile.userAddress, "userAddress"),
        )
        return member.copy(
            emoji = member.emoji.ifBlank { obj.str("avatar").ifBlank { obj.str("emoji") } },
            roleInGroup = fill(member.roleInGroup, "roleInGroup"),
            staticProfile = profile,
        )
    }

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
        val background = BackgroundTasks(llm, memory, config.modelName, config.apiPlatform, backgroundQueue)
        return ChatOrchestrator(
            llm = llm,
            tools = tools,
            memory = memory,
            config = config,
            backgroundTasks = background,
            onCharacterUpdated = onCharacterUpdated,
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
        val QUICK_GEN_INSTRUCTIONS = """
            你是角色设定助手。根据用户的一句话描述，生成一个角色或群组设定，只输出 JSON，不要任何解释或代码块标记。
            单角色 JSON 结构：
            {"entityType":"character","name":"","avatar":"单个emoji","personality":"性格简述","background":"背景故事"}
            群组 JSON 结构：
            {"entityType":"group","name":"","avatar":"单个emoji","description":"群组前提","scene":"共同场景","members":[{"name":"","personality":"","avatar":"单个emoji"}]}
            判断：描述涉及多个角色/团队时输出群组（至少 2 名成员），否则输出单角色。
        """.trimIndent()

        val FILL_MEMBER_INSTRUCTIONS = """
            你是角色卡补全助手。只返回 JSON。
            只能根据群组设定、现有角色卡和用户的一句话补全空字段，不能覆盖或编造与已有字段冲突的信息。
            已有字段是绝对权威，任何情况下不得改写、润色或替换。
            返回字段：name, avatar, gender, age, race, appearance, personality, values, fears,
            background, keyEvents, speakingStyle, language, userAddress, roleInGroup。
            只给出有把握的字段，没有把握就省略。成员的说话方式必须与群内其他成员显著不同。
        """.trimIndent()
    }
}
