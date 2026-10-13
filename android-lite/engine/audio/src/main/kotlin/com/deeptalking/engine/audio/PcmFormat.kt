package com.deeptalking.engine.audio

import kotlin.math.roundToInt

/**
 * Pure PCM helpers shared by every TTS engine. Kept free of Android types so the
 * byte/sample framing can be unit-tested on the JVM.
 */
object PcmFormat {

    /** Float samples in `[-1, 1]` → clamped 16-bit little-endian PCM samples. */
    fun floatsToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) out[i] = (samples[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        return out
    }
}

/**
 * Incremental decoder for a raw 16-bit little-endian mono PCM byte stream
 * (`Content-Type: audio/L16`, what `cosyvoice-server` returns for
 * `response_format: "pcm"`).
 *
 * HTTP chunk boundaries are arbitrary and need not be even, so a single trailing
 * byte is carried over to the next [push] instead of being dropped. Without that
 * carry every odd split would shift the whole stream by one byte and turn the
 * audio into noise.
 */
class Pcm16ByteStream {

    /** Lone low byte of a sample split across two reads, or null when none. */
    private var carry: Byte? = null

    /** Decodes [length] bytes from [bytes] and returns the newly completed samples. */
    fun push(bytes: ByteArray, length: Int = bytes.size): ShortArray {
        require(length in 0..bytes.size) { "length must be within the buffer" }
        if (length == 0) return ShortArray(0)
        val pending = carry
        val samples = ShortArray((length + if (pending != null) 1 else 0) / 2)
        var out = 0
        var i = 0
        if (pending != null) {
            samples[out++] = sample(pending, bytes[0])
            i = 1
            carry = null
        }
        while (i + 1 < length) {
            samples[out++] = sample(bytes[i], bytes[i + 1])
            i += 2
        }
        if (i < length) carry = bytes[i]
        return if (out == samples.size) samples else samples.copyOf(out)
    }

    /** Flushes a dangling half-sample (drops it: an odd trailing byte is not audio). */
    fun flush(): ShortArray {
        carry = null
        return ShortArray(0)
    }

    private fun sample(low: Byte, high: Byte): Short =
        (((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)).toShort()
}
