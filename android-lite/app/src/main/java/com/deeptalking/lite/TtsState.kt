package com.deeptalking.lite

import com.deeptalking.core.model.TTS_BACKEND_ONDEVICE
import com.deeptalking.core.model.TtsPhase
import com.deeptalking.engine.cosyvoice.VoiceInfo

/** UI state for read-aloud settings and chat bubbles (on-device CosyVoice3 or a remote server). */
data class TtsState(
    val enabled: Boolean = false,
    val autoRead: Boolean = false,
    val modelReady: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val progressLabel: String = "",
    val voices: List<VoiceInfo> = emptyList(),
    val activeVoice: String = "",
    val speed: Float = 1.0f,
    val busy: Boolean = false,
    val speaking: Boolean = false,
    val status: String = "",
    /** Chat message currently being read aloud (null for settings preview). */
    val activeMessageId: String? = null,
    /** Lifecycle phase of the current read-aloud request. */
    val phase: TtsPhase = TtsPhase.Idle,
    /** Milliseconds elapsed since the current synthesis started. */
    val elapsedMs: Long = 0L,

    /* ------------------------- remote (HTTP) backend ------------------------- */

    /** Active engine (`ondevice` / `remote`), mirroring `AppConfig.ttsBackend`. */
    val backend: String = TTS_BACKEND_ONDEVICE,
    val remoteUrl: String = "",
    val remoteModel: String = "",
    val remoteVoice: String = "",
    /** Sample rate discovered from the server's `GET /status` (0 = not probed). */
    val remoteSampleRate: Int = 0,
    /** Masked-field seed; the real token lives in `SecretStore`. */
    val remoteToken: String = "",
    /** True when the remote engine has the minimum it needs to speak. */
    val remoteReady: Boolean = false,
    /** Voice names reported by the server (WebUI mode only), for the picker. */
    val remoteVoices: List<String> = emptyList(),
    /** Latest "test connection" / "find server" report. */
    val remoteStatus: String = "",

    /* ---- shared synthesis knobs (0 = never set -> model default), both engines ---- */
    val remoteChunkTokens: Int = 0,
    val remoteTemperature: Double = 0.0,
    val remoteTopK: Int = 0,
    val remoteTopP: Double = 0.0,
    val remoteWinSize: Int = 0,
    val remoteTauR: Double = 0.0,
    val remoteMinTokenTextRatio: Double = 0.0,
    val remoteMaxTokenTextRatio: Double = 0.0,
) {

    /** Whether the selected engine can start a read-aloud right now. */
    val ready: Boolean get() = if (backend == TTS_BACKEND_ONDEVICE) modelReady else remoteReady
}
