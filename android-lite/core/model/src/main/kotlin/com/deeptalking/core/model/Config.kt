package com.deeptalking.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val apiPlatform: String = "deepseek",
    val apiBaseUrl: String = "https://api.deepseek.com/v1",
    val modelName: String = "deepseek-flash",
    val temperature: Double = 0.8,
    val stream: Boolean = true,
    val reasoningEffort: String = "medium",
    val proactiveEnabled: Boolean = true,
    val styleCritique: Boolean = true,
    val quickReplyRepair: Boolean = true,
    /** Empty = 清浅; otherwise `theme-black` / `theme-blue` / `theme-yellow`. */
    val activeTheme: String = "",
    /** Rolling API usage/cache metrics (legacy `requestMetrics`, capped at 60). */
    val requestMetrics: List<RequestMetric> = emptyList(),
    /** Latest reply debug payload (legacy DEBUG_REPLY_STORAGE_KEY). */
    val lastReplyDebug: String = "",
)

/** One API request's usage/cache summary, mirroring the legacy metric shape. */
@Serializable
data class RequestMetric(
    val at: String = "",
    val taskType: String = "",
    val characterId: String = "",
    val inputTokens: Int = 0,
    val hitTokens: Int = 0,
    val missTokens: Int = 0,
    val hitRate: Double = 0.0,
)

@Serializable
data class PlatformPreset(
    val id: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: List<String> = emptyList(),
    val keepV1InResponses: Boolean = false,
)

val BuiltinPlatforms: List<PlatformPreset> = listOf(
    PlatformPreset(
        "deepseek",
        "https://api.deepseek.com/v1",
        "deepseek-flash",
        listOf(
            "deepseek-chat",
            "deepseek-reasoner",
            "deepseek-flash",
        ),
    ),
    PlatformPreset(
        "opencode",
        "https://opencode.ai/zen/go/v1",
        "deepseek-flash",
        listOf(
            "deepseek-flash",
            "deepseek-v3.2",
            "gpt-5.6",
        ),
        keepV1InResponses = true,
    ),
    PlatformPreset(
        "custom",
        "",
        "",
        emptyList(),
    ),
)
