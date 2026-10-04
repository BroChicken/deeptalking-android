package com.deeptalking.lite

import android.content.Context
import com.deeptalking.core.data.CoreDataContainer
import com.deeptalking.core.data.legacy.BackupService
import com.deeptalking.core.data.legacy.FileStickerSink
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.network.HttpWebContentProvider
import com.deeptalking.core.network.ResponsesLlmBackend
import com.deeptalking.core.security.SecretStore
import com.deeptalking.domain.agent.ChatOrchestrator
import com.deeptalking.domain.agent.ToolRegistry
import com.deeptalking.domain.agent.background.BackgroundTaskQueue
import com.deeptalking.domain.agent.background.BackgroundTasks
import com.deeptalking.domain.agent.defaultTools
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.engine.ondevice.InferenceRegistry

/** Manual DI graph for the native app, created once from [DeepTalkingApp]. */
class NativeCore(context: Context) {

    val appContext: Context = context.applicationContext

    val secrets: SecretStore = SecretStore(appContext)
    val data: CoreDataContainer = CoreDataContainer(appContext)
    val memory: MemoryServiceImpl = MemoryServiceImpl()
    val web: HttpWebContentProvider = HttpWebContentProvider()
    val tools: ToolRegistry = ToolRegistry(defaultTools(memory, web, stickersEnabled = true))

    /** Only LLM is wired today; embedding/asr/tts stay null until their milestones. */
    val inference: InferenceRegistry = InferenceRegistry(
        llm = ResponsesLlmBackend(apiKeyProvider = { secrets.getApiKey() }),
    )

    val stickerSink: FileStickerSink = FileStickerSink(appContext)
    val backup: BackupService = BackupService(
        characters = data.characters,
        chat = data.chat,
        config = data.config,
        stickerSink = stickerSink,
    )

    suspend fun currentConfig(): AppConfig = data.config.current()

    /**
     * Two-phase connectivity probe mirroring the legacy `testApiConnection`:
     * ① site reachability, ② a minimal Responses call. Returns a human-readable
     * multi-line report. Never throws.
     */
    suspend fun testApiConnection(config: AppConfig, apiKey: String): String {
        val base = config.apiBaseUrl.trimEnd('/')
        val report = StringBuilder()
        report.append("① 站点可达性：")
        report.append(
            runCatching {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(okhttp3.Request.Builder().url(base.ifBlank { "https://api.deepseek.com/v1" }).build())
                    .execute()
                    .use { "${it.code} ${it.message}" }
            }.getOrElse { "失败（${it.message ?: it}）" },
        )
        report.append("\n② 接口调用：")
        report.append(
            runCatching {
                val backend = ResponsesLlmBackend(apiKeyProvider = { apiKey }, baseUrl = base)
                val result = backend.complete(
                    com.deeptalking.engine.ondevice.LlmRequest(
                        model = config.modelName,
                        instructions = "用中文回复“连接正常”。",
                        input = listOf(
                            com.deeptalking.core.model.ChatMessage(role = com.deeptalking.core.model.Role.User, content = "hi"),
                        ),
                        maxOutputTokens = 32,
                        reasoningEffort = "none",
                    ),
                )
                "正常（返回 ${result.text.take(40).ifBlank { "空" }}）"
            }.getOrElse { "失败（${it.message ?: it}）" },
        )
        report.append("\n当前端点：$base/responses")
        return report.toString()
    }

    /** One-line character/group generation; returns the raw JSON string from the model. */
    suspend fun quickGenerate(config: AppConfig, prompt: String): String? {
        val backend = ResponsesLlmBackend(apiKeyProvider = { secrets.getApiKey() }, baseUrl = config.apiBaseUrl)
        val result = backend.complete(
            com.deeptalking.engine.ondevice.LlmRequest(
                model = config.modelName,
                instructions = QUICK_GEN_INSTRUCTIONS,
                input = listOf(
                    com.deeptalking.core.model.ChatMessage(
                        role = com.deeptalking.core.model.Role.User,
                        content = prompt,
                    ),
                ),
                temperature = 1.0,
                maxOutputTokens = 1200,
                reasoningEffort = "none",
            ),
        )
        return result.text.takeIf { it.isNotBlank() }
    }

    /** Generates a single emoji avatar from a character's description. */
    suspend fun generateEmojiAvatar(config: AppConfig, character: com.deeptalking.core.model.Character): String? {
        val backend = ResponsesLlmBackend(apiKeyProvider = { secrets.getApiKey() }, baseUrl = config.apiBaseUrl)
        val description = buildString {
            append(character.name).append(' ')
            append(character.staticProfile.appearance).append(' ')
            append(character.staticProfile.personality)
        }.trim()
        val result = backend.complete(
            com.deeptalking.engine.ondevice.LlmRequest(
                model = config.modelName,
                instructions = "You output exactly ONE emoji character that best represents the person. Output only the emoji, nothing else.",
                input = listOf(
                    com.deeptalking.core.model.ChatMessage(
                        role = com.deeptalking.core.model.Role.User,
                        content = description.ifBlank { character.name },
                    ),
                ),
                temperature = 1.2,
                maxOutputTokens = 16,
                reasoningEffort = "none",
            ),
        )
        return result.text.trim().takeIf { it.isNotBlank() }?.take(4)
    }

    private companion object {
        val QUICK_GEN_INSTRUCTIONS = """
            你是角色设定助手。根据用户的一句话描述，生成一个角色或群组设定，只输出 JSON，不要任何解释或代码块标记。
            单角色 JSON 结构：
            {"entityType":"character","name":"","avatar":"单个emoji","personality":"性格简述","background":"背景故事"}
            群组 JSON 结构：
            {"entityType":"group","name":"","avatar":"单个emoji","description":"群组前提","scene":"共同场景","members":[{"name":"","personality":"","avatar":"单个emoji"}]}
            判断：描述涉及多个角色/团队时输出群组（至少 2 名成员），否则输出单角色。
        """.trimIndent()
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

    /**
     * Builds a per-turn orchestrator using the current config snapshot so model,
     * base URL, temperature and stream settings always reflect the latest
     * choices from Settings. Background tasks (prose memory extraction, style
     * critique, quick-reply repair) are wired through [BackgroundTasks]' own
     * serial queue.
     */
    fun createOrchestrator(
        config: AppConfig,
        onCharacterUpdated: (com.deeptalking.core.model.Character) -> Unit = {},
    ): ChatOrchestrator {
        val llm = ResponsesLlmBackend(
            apiKeyProvider = { secrets.getApiKey() },
            baseUrl = config.apiBaseUrl,
        )
        val background = BackgroundTasks(llm, memory, config.modelName)
        return ChatOrchestrator(
            llm = llm,
            tools = tools,
            memory = memory,
            config = config,
            backgroundTasks = background,
            onCharacterUpdated = onCharacterUpdated,
        )
    }
}
