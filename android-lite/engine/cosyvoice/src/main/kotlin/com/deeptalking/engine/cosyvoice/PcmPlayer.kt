package com.deeptalking.engine.cosyvoice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min

/** Streams 16-bit PCM to the speaker with a single reusable [AudioTrack]. */
internal class PcmPlayer {

    private var track: AudioTrack? = null
    private var currentRate = 0

    @Volatile
    private var stopped = false

    @Synchronized
    private fun track(rate: Int): AudioTrack {
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

    /** Blocking write; returns early when [stop] is called. */
    @Synchronized
    fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        stopped = false
        val t = track(rate)
        val buf = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            buf[i] = v.toShort()
        }
        var offset = 0
        while (offset < buf.size && !stopped) {
            val n = min(buf.size - offset, 4096)
            val written = t.write(buf, offset, n, AudioTrack.WRITE_BLOCKING)
            if (written < 0) break
            offset += written
        }
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
}
