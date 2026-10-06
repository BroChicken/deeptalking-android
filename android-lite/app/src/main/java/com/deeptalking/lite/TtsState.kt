package com.deeptalking.lite

import com.deeptalking.engine.cosyvoice.VoiceInfo

/** UI state for the on-device read-aloud (CosyVoice3) settings. */
data class TtsState(
    val enabled: Boolean = false,
    val autoRead: Boolean = false,
    val modelReady: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val progressLabel: String = "",
    val voices: List<VoiceInfo> = emptyList(),
    val activeVoice: String = "",
    val style: String = "",
    val speed: Float = 1.0f,
    val busy: Boolean = false,
    val speaking: Boolean = false,
    val status: String = "",
)
