package com.deeptalking.engine.audio

/**
 * Sink for 16-bit mono PCM playback. Both TTS engines write through this
 * interface, which keeps their streaming/phase logic unit-testable on the JVM
 * (the Android implementation is [PcmPlayer], tests use a recording fake).
 */
interface PcmOutput {

    /** Starts (or restarts) a stream at [rate]; clears a previous [stop]. */
    fun begin(rate: Int)

    /**
     * Blocking append of [samples]; returns false as soon as [stop] was requested,
     * so streaming callers can abort mid-utterance.
     */
    fun write(samples: ShortArray, rate: Int): Boolean

    /** Stops playback (pauses + flushes); the output stays reusable. */
    fun stop()

    /** Releases the underlying audio resources. */
    fun release()
}
