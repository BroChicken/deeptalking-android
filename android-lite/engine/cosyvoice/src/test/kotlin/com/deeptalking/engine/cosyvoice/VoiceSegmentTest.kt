package com.deeptalking.engine.cosyvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class VoiceSegmentTest {

    private val rate = VoiceSegment.RATE

    private fun tone(seconds: Double, amplitude: Double = 0.0, freq: Double = 220.0): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i -> (amplitude * sin(2 * Math.PI * freq * i / rate)).toFloat() }
    }

    @Test
    fun frameEnergiesAreMeanSquares() {
        val energies = VoiceSegment.frameEnergies(FloatArray(40) { 0.5f }, 10)
        assertEquals(4, energies.size)
        energies.forEach { assertEquals(0.25f, it, 1e-6f) }
    }

    @Test
    fun voicedRangeSkipsLeadingAndTrailingSilence() {
        val energies = floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f, 0f)
        assertEquals(2..4, VoiceSegment.voicedRange(energies))
    }

    @Test
    fun normalizeScalesPeak() {
        val out = VoiceSegment.normalize(floatArrayOf(0.1f, -0.2f, 0.05f))
        assertEquals(0.95f, out.maxOf { kotlin.math.abs(it) }, 1e-4f)
    }

    @Test
    fun pickerKeepsTheLoudSegment() {
        val picker = SegmentPicker(rate)
        picker.push(tone(1.0))             // silence
        picker.push(tone(2.5, 0.8))        // loud speech
        picker.push(tone(1.0))             // silence
        val out = picker.finish()
        // ~2.5s of selected speech (silence trimmed), normalized to near full scale.
        assertTrue("size=${out.size}", out.size in (rate * 2.2).toInt()..(rate * 2.8).toInt())
        assertTrue(out.maxOf { kotlin.math.abs(it) } > 0.9f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pickerRejectsSilentClip() {
        val picker = SegmentPicker(rate)
        picker.push(tone(3.0))
        picker.finish()
    }

    @Test(expected = IllegalArgumentException::class)
    fun pickerRejectsTooShortClip() {
        val picker = SegmentPicker(rate)
        picker.push(tone(0.5, 0.8))
        picker.finish()
    }

    @Test
    fun pickerTrimsLongClipToTargetSeconds() {
        val picker = SegmentPicker(rate)
        picker.push(tone(20.0, 0.6))
        val out = picker.finish()
        assertTrue(out.size <= (VoiceSegment.TARGET_SECONDS * rate).toInt() + rate)
    }
}
