package com.deeptalking.engine.cosyvoice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min

/**
 * Streams 16-bit PCM to the speaker with a single reusable [AudioTrack].
 *
 * The track is kept alive across read-aloud calls; [stop] only pauses+flushes
 * it, so [play] must explicitly resume a paused track before writing — otherwise
 * every read-aloud after the first stop would write into a paused track and stay
 * silent. Track (re)creation is guarded by [lock] rather than the whole player,
 * so [stop] from another thread is not blocked behind a long blocking write and
 * can interrupt playback promptly via the volatile [stopped] flag.
 */
internal class PcmPlayer {

    private val lock = Any()

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var currentRate = 0

    @Volatile
    private var stopped = false

    private fun track(rate: Int): AudioTrack {
        synchronized(lock) {
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
            track = t
            currentRate = rate
            return t
        }
    }

    /** Blocking write; returns early when [stop] is called. */
    fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        stopped = false
        val t = track(rate)
        // Reusing a track left PAUSED by a previous stop() would swallow the
        // audio, so always make sure it is actually rolling before writing.
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { t.play() }
        }
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

    fun stop() {
        stopped = true
        val t = track ?: return
        runCatching { t.pause() }
        runCatching { t.flush() }
    }

    fun release() {
        stopped = true
        val t = synchronized(lock) {
            val previous = track
            track = null
            currentRate = 0
            previous
        }
        runCatching { t?.stop() }
        runCatching { t?.release() }
    }
}
