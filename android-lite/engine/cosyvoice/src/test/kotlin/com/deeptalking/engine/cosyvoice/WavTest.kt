package com.deeptalking.engine.cosyvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {

    private fun wav(format: Int, bits: Int, channels: Int, rate: Int, data: ByteArray): ByteArray {
        val bytesPerSample = bits / 8
        val blockAlign = bytesPerSample * channels
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
        fun le32(v: Int) = out.write(
            byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
            ),
        )
        ascii("RIFF"); le32(36 + data.size); ascii("WAVE")
        ascii("fmt "); le32(16); le16(format); le16(channels); le32(rate)
        le32(rate * blockAlign); le16(blockAlign); le16(bits)
        ascii("data"); le32(data.size); out.write(data)
        return out.toByteArray()
    }

    @Test
    fun decodes16BitMono() {
        val bb = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0).putShort(16384).putShort((-16384).toShort())
        val decoded = Wav.decode(wav(1, 16, 1, 16000, bb.array()))
        assertEquals(16000, decoded.sampleRate)
        assertEquals(3, decoded.samples.size)
        assertEquals(0f, decoded.samples[0], 1e-4f)
        assertEquals(0.5f, decoded.samples[1], 1e-4f)
        assertEquals(-0.5f, decoded.samples[2], 1e-4f)
    }

    @Test
    fun decodes24BitMono() {
        val data = byteArrayOf(
            0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0x7F,
            0x00, 0x00, 0x80.toByte(),
        )
        val decoded = Wav.decode(wav(1, 24, 1, 24000, data))
        assertEquals(3, decoded.samples.size)
        assertEquals(0f, decoded.samples[0], 1e-4f)
        assertTrue(decoded.samples[1] > 0.99f)
        assertTrue(decoded.samples[2] < -0.99f)
    }

    @Test
    fun decodes32BitFloatMono() {
        val bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        bb.putFloat(0.25f).putFloat(-0.5f).putFloat(1.0f)
        val decoded = Wav.decode(wav(3, 32, 1, 24000, bb.array()))
        assertEquals(0.25f, decoded.samples[0], 1e-4f)
        assertEquals(-0.5f, decoded.samples[1], 1e-4f)
        assertEquals(1.0f, decoded.samples[2], 1e-4f)
    }

    @Test
    fun resampleUpsamplesLinearly() {
        val out = Wav.resample(floatArrayOf(0f, 1f), 2, 4)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(1f, out[2], 1e-4f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownBitDepth() {
        Wav.decode(wav(1, 12, 1, 8000, ByteArray(48)))
    }
}
