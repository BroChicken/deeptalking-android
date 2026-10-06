package com.deeptalking.engine.cosyvoice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono 32-bit float PCM samples decoded from a WAV file. */
internal data class DecodedWav(val samples: FloatArray, val sampleRate: Int)

/** Minimal RIFF/WAVE decoder (PCM 8/16/32-bit int and 32-bit float). */
internal object Wav {
    fun decode(bytes: ByteArray): DecodedWav {
        require(bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
            "不是有效的 WAV 文件"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var sampleRate = 24000
        var bits = 16
        var format = 1
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = buf.getShort(body).toInt()
                    channels = buf.getShort(body + 2).toInt().coerceAtLeast(1)
                    sampleRate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataLen = size.coerceAtMost(bytes.size - body)
                }
            }
            pos = body + size + (size and 1)
        }
        require(dataOffset >= 0 && dataLen > 0) { "WAV 缺少 data 段" }
        val bytesPerSample = when {
            format == 3 && bits == 32 -> 4
            bits == 16 -> 2
            bits == 24 -> 3
            bits == 8 -> 1
            bits == 32 -> 4
            else -> throw IllegalArgumentException("不支持的 WAV 位深: $bits")
        }
        val frames = dataLen / (bytesPerSample * channels)
        val out = FloatArray(frames)
        val bb = ByteBuffer.wrap(bytes, dataOffset, dataLen).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += when {
                    format == 3 && bits == 32 -> bb.getFloat()
                    bits == 16 -> bb.getShort() / 32768f
                    bits == 24 -> readInt24(bb) / 8388608f
                    bits == 8 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
                    bits == 32 -> bb.getInt() / 2147483648f
                    else -> 0f
                }
            }
            out[i] = acc / channels
        }
        return DecodedWav(out, sampleRate)
    }

    /** Reads a little-endian signed 24-bit sample and advances [bb] by 3 bytes. */
    private fun readInt24(bb: ByteBuffer): Int {
        val b0 = bb.get().toInt() and 0xFF
        val b1 = bb.get().toInt() and 0xFF
        val b2 = bb.get().toInt()
        return (b2 shl 16) or (b1 shl 8) or b0
    }

    /** Linear resampler to [targetRate]; adequate for prompt encoding. */
    fun resample(samples: FloatArray, srcRate: Int, targetRate: Int): FloatArray {
        if (srcRate == targetRate || samples.isEmpty()) return samples
        val ratio = targetRate.toDouble() / srcRate
        val outLen = (samples.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = samples[i0] * (1 - frac) + samples[i1] * frac
        }
        return out
    }
}
