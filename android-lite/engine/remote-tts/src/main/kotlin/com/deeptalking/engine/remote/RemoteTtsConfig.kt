package com.deeptalking.engine.remote

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Immutable snapshot of the remote read-aloud settings for one request. The app
 * rebuilds it before every [RemoteTtsSpeaker.speak] from `AppConfig`, so config
 * edits take effect without recreating the speaker.
 */
data class RemoteTtsConfig(
    val baseUrl: String = "",
    /** Value for `--api-key` on the server; null when the server has no auth. */
    val token: String? = null,
    /** `--served-model-name` of the server. */
    val model: String = DEFAULT_MODEL,
    /** Voice name registered on the server (`--voice-prompt <name>=...`). */
    val voice: String = DEFAULT_VOICE,
    /** Speech-rate multiplier; the server rejects values <= 0. */
    val speed: Float = 1.0f,
    /** Sample rate reported by `GET /status`; 0 means "not discovered yet". */
    val sampleRate: Int = 0,
    /**
     * Shared synthesis knobs. They carry the model's own defaults (`sampling.*` in the
     * GGUF: temperature 1.0, top_k 25, top_p 0.8, win_size 10, tau_r 0.1, ratios 2/20,
     * chunk_tokens 28) and are always sent, so the settings screen shows real values and
     * the on-device engine can be given the identical set (see `docs/REMOTE_TTS.md` §9).
     */
    val chunkTokens: Int = DEFAULT_CHUNK_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Double = DEFAULT_TOP_P,
    val winSize: Int = DEFAULT_WIN_SIZE,
    val tauR: Double = DEFAULT_TAU_R,
    val minTokenTextRatio: Double = DEFAULT_MIN_TOKEN_TEXT_RATIO,
    val maxTokenTextRatio: Double = DEFAULT_MAX_TOKEN_TEXT_RATIO,
) {

    /** Sample rate to decode/play at, falling back to the CosyVoice3 default. */
    val resolvedSampleRate: Int get() = if (sampleRate > 0) sampleRate else DEFAULT_SAMPLE_RATE

    /** Base URL without a trailing slash, or empty when unset. */
    val normalizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')

    companion object {
        const val DEFAULT_MODEL = "cosyvoice-3"
        const val DEFAULT_VOICE = "alloy"

        /** CosyVoice3 synthesizes 24 kHz mono throughout (`GET /status` → `sample_rate`). */
        const val DEFAULT_SAMPLE_RATE = 24000

        /** Speech-rate bounds the server accepts (`speed` must be > 0). */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        /** Server defaults reported by `GET /model/defaults`; shown in the settings UI. */
        const val DEFAULT_CHUNK_TOKENS = 28
        const val DEFAULT_TEMPERATURE = 1.0
        const val DEFAULT_TOP_K = 25
        const val DEFAULT_TOP_P = 0.8

        /** Model metadata (`sampling.*`); the phone and the server share these values. */
        const val DEFAULT_WIN_SIZE = 10
        const val DEFAULT_TAU_R = 0.1
        const val DEFAULT_MIN_TOKEN_TEXT_RATIO = 2.0
        const val DEFAULT_MAX_TOKEN_TEXT_RATIO = 20.0
    }
}

/** Read-aloud failed against the configured server (network, HTTP or protocol level). */
class RemoteTtsException(message: String) : Exception(message)

/**
 * Builds the OpenAI Speech-compatible request body
 * (`POST /v1/audio/speech`, see `docs/TOOLS.md` of `cosyvoice.cpp`).
 *
 * `response_format: "pcm"` is deliberate: it is raw 16-bit little-endian mono
 * PCM (`Content-Type: audio/L16`), which decodes straight into `AudioChunk` with
 * no WAV/demux step and needs no FFmpeg on the server.
 *
 * `stream: false` is deliberate too: with `stream: true` the server emits audio
 * while it is still generating, and the phone plays each chunk as it arrives - so
 * whenever the PC generates slower than real time the playback buffer underruns
 * and the read-aloud stutters. Non-streaming returns one complete payload, and it
 * makes the server run the very same entry point as the on-device engine
 * (`cosyvoice_tts_instruct`), so both ends behave identically.
 *
 * A non-blank [style] selects the server's instruct mode (it overrides the
 * zero-shot reference), which is exactly how the app already maps a character's
 * `currentTone` onto the on-device engine.
 */
internal fun buildSpeechBody(text: String, config: RemoteTtsConfig, style: String?): String =
    buildJsonObject {
        put("model", config.model)
        put("voice", config.voice)
        put("input", text)
        put("response_format", RESPONSE_FORMAT_PCM)
        put("stream", false)
        put("speed", config.speed.coerceIn(RemoteTtsConfig.MIN_SPEED, RemoteTtsConfig.MAX_SPEED))
        if (!style.isNullOrBlank()) put("instructions", style)
        // The shared knobs are sent with the values shown in the settings screen (they are
        // seeded from the model's own metadata), so the phone and the PC always agree on
        // what was used. A stored 0 means "never set" - builds before the knobs were always
        // sent wrote 0, and the server rejects temperature 0 - so it falls back to the
        // default instead.
        put("chunk_tokens", config.chunkTokens.takeIf { it > 0 } ?: RemoteTtsConfig.DEFAULT_CHUNK_TOKENS)
        put("temperature", config.temperature.takeIf { it > 0.0 } ?: RemoteTtsConfig.DEFAULT_TEMPERATURE)
        put("top_k", config.topK.takeIf { it > 0 } ?: RemoteTtsConfig.DEFAULT_TOP_K)
        put("top_p", config.topP.takeIf { it > 0.0 } ?: RemoteTtsConfig.DEFAULT_TOP_P)
        put("win_size", config.winSize.takeIf { it > 0 } ?: RemoteTtsConfig.DEFAULT_WIN_SIZE)
        put("tau_r", config.tauR.takeIf { it > 0.0 } ?: RemoteTtsConfig.DEFAULT_TAU_R)
        put(
            "min_token_text_ratio",
            config.minTokenTextRatio.takeIf { it > 0.0 } ?: RemoteTtsConfig.DEFAULT_MIN_TOKEN_TEXT_RATIO,
        )
        put(
            "max_token_text_ratio",
            config.maxTokenTextRatio.takeIf { it > 0.0 } ?: RemoteTtsConfig.DEFAULT_MAX_TOKEN_TEXT_RATIO,
        )
    }.toString()

const val RESPONSE_FORMAT_PCM = "pcm"

/** `POST {base}/v1/audio/speech`. */
internal fun speechUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/v1/audio/speech"

/** `POST {base}/v1/voices?name=<name>` (uploads one prompt-speech gguf). */
internal fun voiceUploadUrl(baseUrl: String, name: String): String =
    baseUrl.trim().trimEnd('/') + "/v1/voices?name=" +
        java.net.URLEncoder.encode(name, Charsets.UTF_8.name())

/** Whole-value header token, tolerant of a user pasting `Bearer sk-...`. */
internal fun bearerToken(token: String?): String? {
    val trimmed = token?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    // Normalize the scheme so a pasted lowercase/mixed-case variant still yields one
    // canonical header value ("Bearer " with no token means "no auth").
    val raw = if (trimmed.regionMatches(0, BEARER_SCHEME, 0, BEARER_SCHEME.length, ignoreCase = true)) {
        trimmed.substring(BEARER_SCHEME.length).trim()
    } else {
        trimmed
    }
    return if (raw.isEmpty()) null else "$BEARER_SCHEME $raw"
}

private const val BEARER_SCHEME = "Bearer"
