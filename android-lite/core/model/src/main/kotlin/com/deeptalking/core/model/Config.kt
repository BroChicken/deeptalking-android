package com.deeptalking.core.model

import com.deeptalking.core.common.AppLimits
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
    /** Latest chat cache/usage metrics, capped at `AppLimits.Api.REQUEST_METRICS` **per task type**. */
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
    /** Read-aloud engine: `ondevice` (CosyVoice3 inside the APK) or `remote` (HTTP server). */
    val ttsBackend: String = TTS_BACKEND_ONDEVICE,
    /**
     * Base URL of the remote read-aloud server, e.g. `http://10.84.116.224:8080`
     * (the PC's address on the phone's USB-tethering subnet).
     */
    val ttsRemoteUrl: String = "",
    /** `--served-model-name` of the remote server (sent as the request `model`). */
    val ttsRemoteModel: String = "cosyvoice-3",
    /**
     * Voice name on the remote server. **Empty = follow the on-device selection**
     * (`ttsVoiceFile`), so the same voice is used by both engines as long as the
     * server exposes a voice under that name (see `docs/REMOTE_TTS.md` §8).
     */
    val ttsRemoteVoice: String = "",
    /** Sample rate from the server's `GET /status`; 0 = not probed yet (24000 assumed). */
    val ttsRemoteSampleRate: Int = 0,

    /* ---- shared synthesis knobs (quality vs speed), used by BOTH engines ---- */

    /**
     * Speech tokens per token->wav chunk. On the server (non-streaming) it trades
     * peak memory for throughput; on the phone it is the same internal chunking.
     * Model default 28.
     */
    val ttsRemoteChunkTokens: Int = 28,
    /** Sampling temperature (> 0); lower is steadier, higher more expressive. Default 1.0. */
    val ttsRemoteTemperature: Double = 1.0,
    /** Top-k sampling (>= 0). Model default 25. */
    val ttsRemoteTopK: Int = 25,
    /** Top-p sampling in [0, 1]. Model default 0.8. */
    val ttsRemoteTopP: Double = 0.8,
    /** Repetition-window size for the built-in sampler (> 0). Model default 10. */
    val ttsWinSize: Int = 10,
    /**
     * Repetition-control coefficient (>= 0): a token is rejected while it repeats
     * more than `win_size * tau_r` times in the window. Model default 0.1.
     */
    val ttsTauR: Double = 0.1,
    /** Minimum generated-token/text-length ratio (>= 0). Model default 2.0. */
    val ttsMinTokenTextRatio: Double = 2.0,
    /**
     * Maximum generated-token/text-length ratio (>= min): too small truncates the
     * utterance, too large lets it drag. Model default 20.0.
     */
    val ttsMaxTokenTextRatio: Double = 20.0,
    /**
     * Resolve hostnames over DNS-over-HTTPS to bypass carrier DNS hijacking
     * (falls back to the system resolver). Provider id: `alidns` / `tencent`.
     */
    val dohEnabled: Boolean = true,
    val dohProvider: String = "alidns",
)

/** Non-secret half of a per-platform config slot (legacy `platformSettings[p]`). */
@Serializable
data class PlatformSlot(
    val baseUrl: String = "",
    val modelName: String = "",
)

/** Task type of a main chat model request (one row per round). */
const val TASK_CHAT_MAIN = "chat.main"

/** Task type of the turn worker's forced `submit_state` request. */
const val TASK_CHAT_WORKER = "chat.worker"

/** One API request's usage/cache summary, mirroring the legacy metric shape. */
@Serializable
data class RequestMetric(
    val at: String = "",
    val characterId: String? = null,
    /**
     * Request kind. The main chat is `chat.main` (one row per model request, tool rounds
     * included), the turn worker is `chat.worker`; background passes use their own names
     * (`memory.*`, `lorebook.*`, `auxiliary`, ...). Legacy rows may still say `chat`.
     */
    val taskType: String = "chat",
    val model: String = "",
    val platform: String = "",
    /** Failure reason (`status=failed` rows); never a hit-rate input. */
    val phase: String = "",
    /** 1-based index of the main-chat request inside its turn (null for other kinds). */
    val round: Int? = null,
    val status: String = "completed",
    val durationMs: Long? = null,
    val instructionsHash: String? = null,
    val toolsHash: String? = null,
    val historyHash: String? = null,
    val prefixChange: String? = null,
    val commonHistoryMessages: Int? = null,
    val contextChars: Int? = null,
    /**
     * Characters per context section of the request (`static` / `volatile` / `history` /
     * `tools` …), keyed by the section name used when the prompt was assembled. Only the
     * reply pipeline's own sections are counted; null on legacy rows and on requests that
     * did not go through prompt assembly. Used to compare section growth between builds.
     */
    val sectionChars: Map<String, Int>? = null,
    val inputChars: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val hitTokens: Int? = null,
    val missTokens: Int? = null,
    val hitRate: Double? = null,
)

/**
 * Trims a rolling metric list to [cap] records **per task type**, keeping the newest of
 * each kind. A chat turn now writes one row per model request (tool rounds included) and
 * background passes write their own rows; a single global cap let the chattiest kind
 * evict every other kind, so e.g. `chat.worker` or `memory.*` never showed up at all.
 * Order is preserved (oldest → newest).
 */
fun List<RequestMetric>.trimMetricsPerTaskType(
    cap: Int = AppLimits.Api.REQUEST_METRICS,
): List<RequestMetric> {
    if (cap <= 0) return emptyList()
    val seen = HashMap<String, Int>()
    val keep = HashSet<Int>()
    for (index in indices.reversed()) {
        val taskType = this[index].taskType
        val count = seen[taskType] ?: 0
        if (count < cap) {
            seen[taskType] = count + 1
            keep += index
        }
    }
    return filterIndexed { index, _ -> index in keep }
}

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

/** `AppConfig.ttsBackend`: read-aloud runs inside the APK (CosyVoice3). */
const val TTS_BACKEND_ONDEVICE = "ondevice"

/** `AppConfig.ttsBackend`: read-aloud is delegated to a remote OpenAI-Speech server. */
const val TTS_BACKEND_REMOTE = "remote"
