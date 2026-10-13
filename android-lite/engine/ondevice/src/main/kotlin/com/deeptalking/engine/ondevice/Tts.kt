package com.deeptalking.engine.ondevice

import com.deeptalking.core.model.TtsPhase

/**
 * One spoken turn: the text plus an optional free-form tone instruction.
 *
 * The instruction maps to CosyVoice "instruct" mode on-device and to the
 * `instructions` field of an OpenAI Speech-compatible server, so both engines
 * share the same contract (see `AppViewModel.toneFor`).
 */
data class TtsTurn(val text: String, val style: String? = null)

/**
 * Playback facade for read-aloud. Callers depend on this interface only, never on
 * a concrete engine, so the on-device CosyVoice3 runtime (`:engine:cosyvoice`) and
 * a remote HTTP TTS server (`:engine:remote-tts`) are interchangeable — the active
 * one is picked from `AppConfig.ttsBackend` in `NativeCore`.
 *
 * Implementations own their own audio output: [speak] blocks until playback ends,
 * which is how the caller's phase timer measures real duration.
 */
interface TtsSpeaker {

    /** Stable backend id (`cosyvoice3` / `remote-tts`), for logs and diagnostics. */
    val id: String

    /**
     * Synthesizes and plays every turn in order, blocking until playback finishes.
     * [onPhase] reports [TtsPhase.Synthesizing] before audio starts and
     * [TtsPhase.Playing] once it does, so the UI can show live progress.
     */
    suspend fun speak(turns: List<TtsTurn>, onPhase: (TtsPhase) -> Unit = {})

    /**
     * Preempts a running synthesis and stops playback. May block until the native
     * worker exits, so it must not be called on the main thread.
     */
    fun cancel()

    /** Stops playback only (no synthesis teardown). */
    fun stop()
}
