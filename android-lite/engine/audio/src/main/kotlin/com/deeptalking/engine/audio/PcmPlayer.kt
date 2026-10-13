package com.deeptalking.engine.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min

/**
 * Streams 16-bit mono PCM to the speaker with a single reusable [AudioTrack].
 *
 * The track is kept alive across read-aloud calls; [stop] only pauses+flushes
 * it, so [write] must explicitly resume a paused track before writing — otherwise
 * every read-aloud after the first stop would write into a paused track and stay
 * silent. Track (re)creation is guarded by [lock] rather than the whole player,
 * so [stop] from another thread is not blocked behind a long blocking write and
 * can interrupt playback promptly via the volatile [stopped] flag.
 *
 * Two usage shapes are supported:
 *  - one-shot: [play] with a fully synthesized buffer (on-device CosyVoice3);
 *  - streaming: [begin] once, then [write] per network chunk, which blocks as the
 *    track's buffer fills and therefore applies natural backpressure
 *    (`:engine:remote-tts`).
 */
class PcmPlayer : PcmOutput {

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

    /** Ensures a playing track for [rate] (reusing a track left PAUSED by [stop]). */
    private fun rolling(rate: Int): AudioTrack {
        val t = track(rate)
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }
        return t
    }

    /** Starts (or restarts) a stream at [rate]; clears a previous [stop]. */
    override fun begin(rate: Int) {
        stopped = false
        rolling(rate)
    }

    /**
     * Blocking append of [samples] at [rate]; returns false as soon as [stop] was
     * requested (or the track refused the write), so streaming callers can abort.
     */
    override fun write(samples: ShortArray, rate: Int): Boolean {
        if (samples.isEmpty()) return !stopped
        val t = rolling(rate)
        var offset = 0
        while (offset < samples.size && !stopped) {
            val n = min(samples.size - offset, 4096)
            val written = t.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
            if (written < 0) return false
            offset += written
        }
        return !stopped
    }

    /** One-shot blocking playback of float samples in `[-1, 1]`. */
    fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        stopped = false
        write(PcmFormat.floatsToPcm16(samples), rate)
    }

    override fun stop() {
        stopped = true
        val t = track ?: return
        runCatching { t.pause() }
        runCatching { t.flush() }
    }

    override fun release() {
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
