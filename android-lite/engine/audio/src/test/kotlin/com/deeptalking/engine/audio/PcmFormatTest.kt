package com.deeptalking.engine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFormatTest {

    @Test
    fun `floats are converted to clamped 16-bit samples`() {
        val out = PcmFormat.floatsToPcm16(floatArrayOf(0f, 1f, -1f, 0.5f, 2f, -2f))
        assertEquals(0, out[0].toInt())
        assertEquals(32767, out[1].toInt())
        assertEquals(-32767, out[2].toInt())
        assertEquals(16384, out[3].toInt())
        assertEquals(32767, out[4].toInt())
        assertEquals(-32768, out[5].toInt())
    }

    @Test
    fun `even-sized chunks decode little-endian`() {
        val stream = Pcm16ByteStream()
        val samples = stream.push(byteArrayOf(0x34, 0x12, 0x00, 0x80.toByte()))
        assertArrayEquals(shortArrayOf(0x1234, -32768), samples)
    }

    @Test
    fun `an odd trailing byte is carried into the next chunk`() {
        val stream = Pcm16ByteStream()
        val first = stream.push(byteArrayOf(0x34))
        assertEquals(0, first.size)

        val second = stream.push(byteArrayOf(0x12, 0xAA.toByte(), 0xBB.toByte()))
        assertArrayEquals(shortArrayOf(0x1234, 0xBBAA.toShort()), second)
    }

    @Test
    fun `byte-per-push stream reassembles exactly`() {
        val stream = Pcm16ByteStream()
        val bytes = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        val collected = mutableListOf<Short>()
        for (byte in bytes) collected += stream.push(byteArrayOf(byte)).toList()
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), collected.toShortArray())
    }

    @Test
    fun `flush drops a dangling half sample`() {
        val stream = Pcm16ByteStream()
        assertEquals(1, stream.push(byteArrayOf(0x01, 0x00, 0x09)).size)
        assertEquals(0, stream.flush().size)
        // After a flush the decoder is aligned again.
        assertArrayEquals(shortArrayOf(0x0102), stream.push(byteArrayOf(0x02, 0x01)))
    }

    @Test
    fun `an empty push never fabricates samples`() {
        val stream = Pcm16ByteStream()
        assertEquals(0, stream.push(ByteArray(0)).size)
        assertEquals(0, stream.push(byteArrayOf(7, 0), 0).size)
        assertTrue(stream.push(byteArrayOf(7, 0)).isNotEmpty())
    }
}
