package com.deeptalking.engine.cosyvoice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min

/**
 * Streams 16-bit PCM to the speaker with a single reusable [AudioTrack].
 *
 * Supports both whole-buffer playback ([play]) and incremental streaming
 * ([begin]/[write]/[finish]) so read-aloud can start as soon as the first
 * synthesized chunk arrives.
 */
internal class PcmPlayer {

    private var track: AudioTrack? = null
    private var currentRate = 0
    private var totalWritten = 0L

    @Volatile
    private var stopped = false

    val isStopped: Boolean get() = stopped

    @Synchronized
    private fun ensureTrack(rate: Int): AudioTrack {
        val existing = track
        if (existing != null && currentRate == rate) return existing
        existing?.release()
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).let { if (it > 0) it else rate }
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        currentRate = rate
        return t
    }

    /** Prepares the track for a new streaming playback. */
    @Synchronized
    fun begin(rate: Int) {
        stopped = false
        totalWritten = 0
        ensureTrack(rate)
    }

    /** Blocking write of one chunk; no-op once [stop] has been called. */
    @Synchronized
    fun write(samples: FloatArray) {
        if (samples.isEmpty() || stopped) return
        val t = track ?: return
        val buf = ShortArray(samples.size)
        for (i in samples.indices) {
            buf[i] = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        var offset = 0
        while (offset < buf.size && !stopped) {
            val n = min(buf.size - offset, 4096)
            val written = t.write(buf, offset, n, AudioTrack.WRITE_BLOCKING)
            if (written < 0) break
            offset += written
        }
        totalWritten += offset
    }

    /** Waits (bounded) for the queued audio to finish playing. */
    @Synchronized
    fun finish() {
        val t = track ?: return
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (!stopped && t.playbackHeadPosition.toLong() < totalWritten && System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(20) }
        }
    }

    /** Blocking whole-buffer playback; returns early when [stop] is called. */
    @Synchronized
    fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        begin(rate)
        write(samples)
        finish()
    }

    @Synchronized
    fun stop() {
        stopped = true
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    @Synchronized
    fun release() {
        stopped = true
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        currentRate = 0
    }

    private companion object {
        const val DRAIN_TIMEOUT_MS = 5000L
    }
}
