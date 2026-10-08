package com.deeptalking.lite

import com.deeptalking.core.model.TtsPhase
import com.deeptalking.engine.cosyvoice.VoiceInfo

/** UI state for the on-device read-aloud (CosyVoice3) settings and chat bubbles. */
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
)
