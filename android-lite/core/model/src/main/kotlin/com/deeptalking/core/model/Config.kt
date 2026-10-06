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
    /** Latest chat cache-usage snapshot (legacy `cacheStats`). */
    val cacheStats: CacheStats? = null,
    /** Latest reply debug payload (legacy DEBUG_REPLY_STORAGE_KEY). */
    val lastReplyDebug: String = "",
    /**
     * Per-platform base URL / model slots (legacy `platformSettings`). Switching
     * the platform must not overwrite another platform's configuration; the API
     * key half lives in `:core:security` `SecretStore` (never in this payload).
     */
    val platformSettings: Map<String, PlatformSlot> = emptyMap(),
    /** On-device read-aloud (CosyVoice3). */
    val ttsEnabled: Boolean = false,
    val ttsAutoRead: Boolean = false,
    /** Path (relative to filesDir) of the active prompt-speech voice profile. */
    val ttsVoiceFile: String = "",
    /** Free-form natural-language emotion/style instruction used for read-aloud. */
    val ttsStyle: String = "",
    /** CosyVoice3 speech speed multiplier (0.5–2.0). */
    val ttsSpeed: Float = 1.0f,
)

/** Non-secret half of a per-platform config slot (legacy `platformSettings[p]`). */
@Serializable
data class PlatformSlot(
    val baseUrl: String = "",
    val modelName: String = "",
)

/** One API request's usage/cache summary, mirroring the legacy metric shape. */
@Serializable
data class RequestMetric(
    val at: String = "",
    val characterId: String? = null,
    val taskType: String = "chat",
    val model: String = "",
    val platform: String = "",
    val phase: String = "",
    val status: String = "completed",
    val durationMs: Long? = null,
    val instructionsHash: String? = null,
    val toolsHash: String? = null,
    val historyHash: String? = null,
    val prefixChange: String? = null,
    val commonHistoryMessages: Int? = null,
    val contextChars: Int? = null,
    val inputChars: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val hitTokens: Int? = null,
    val missTokens: Int? = null,
    val hitRate: Double? = null,
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
