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
