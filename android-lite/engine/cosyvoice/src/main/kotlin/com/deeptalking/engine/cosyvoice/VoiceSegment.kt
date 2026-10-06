package com.deeptalking.engine.cosyvoice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Picks the best short, voiced window out of a (possibly very long) reference
 * clip so CosyVoice only ever encodes a few seconds of clean speech. Pure math,
 * no Android APIs, so it is unit-testable.
 */
internal object VoiceSegment {

    const val RATE = 16000

    /** Target length of the reference window handed to the frontend. */
    const val TARGET_SECONDS = 6.0f

    /** Below this much usable speech, voice cloning is unreliable. */
    const val MIN_SECONDS = 2.0f

    fun frameSize(rate: Int = RATE, frameMs: Int = 20): Int = max(1, rate * frameMs / 1000)

    fun meanSquare(frame: FloatArray): Double {
        if (frame.isEmpty()) return 0.0
        var acc = 0.0
        for (v in frame) acc += v.toDouble() * v
        return acc / frame.size
    }

    /** Per-frame mean-square energy; trailing partial frame is ignored. */
    fun frameEnergies(samples: FloatArray, frameSize: Int): FloatArray {
        if (frameSize <= 0) return FloatArray(0)
        val count = samples.size / frameSize
        val out = FloatArray(count)
        for (f in 0 until count) {
            var acc = 0.0
            val base = f * frameSize
            for (i in base until base + frameSize) acc += samples[i].toDouble() * samples[i]
            out[f] = (acc / frameSize).toFloat()
        }
        return out
    }

    /**
     * Inclusive frame range covering speech, using a threshold derived from the
     * clip's own median/peak energy. Returns null when no frame clears it.
     */
    fun voicedRange(energies: FloatArray): IntRange? {
        if (energies.isEmpty()) return null
        val sorted = energies.sortedArray()
        val median = sorted[sorted.size / 2]
        val peak = sorted.last()
        if (peak <= 1e-5f) return null
        val threshold = max(peak * 0.1f, median * 0.4f)
        var first = -1
        var last = -1
        for (i in energies.indices) {
            if (energies[i] >= threshold) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0 || last < first) return null
        return first..last
    }

    /** Peak-normalizes to [target]; returns the input unchanged when silent. */
    fun normalize(samples: FloatArray, target: Float = 0.95f): FloatArray {
        var peak = 0f
        for (v in samples) peak = max(peak, abs(v))
        if (peak <= 1e-6f) return samples
        val gain = target / peak
        return FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }
    }
}

/**
 * Streaming voiced-window picker. Feed mono 16 kHz PCM in arbitrary chunks; it
 * keeps only a sliding [targetSeconds] buffer and remembers the loudest window.
 * [finish] returns the trimmed, normalized best window (throws when the clip has
 * too little usable speech).
 */
internal class SegmentPicker(
    private val rate: Int = VoiceSegment.RATE,
    private val targetSeconds: Float = VoiceSegment.TARGET_SECONDS,
    private val minSeconds: Float = VoiceSegment.MIN_SECONDS,
) {
    private val frame = VoiceSegment.frameSize(rate)
    private val targetFrames = max(1, (targetSeconds * rate / frame).toInt())
    private val minSamples = max(1, (minSeconds * rate).toInt())

    private var pending = FloatArray(0)
    private val ring = ArrayDeque<FloatArray>()
    private val ringEnergy = ArrayDeque<Float>()
    private var running = 0.0
    private var bestScore = -1.0
    private var best: Array<FloatArray>? = null

    fun push(samples: FloatArray) {
        var offset = 0
        if (pending.isNotEmpty()) {
            val need = frame - pending.size
            if (samples.size < need) {
                pending += samples
                return
            }
            val f = FloatArray(frame)
            System.arraycopy(pending, 0, f, 0, pending.size)
            System.arraycopy(samples, 0, f, pending.size, need)
            emit(f)
            offset = need
            pending = FloatArray(0)
        }
        while (offset + frame <= samples.size) {
            emit(samples.copyOfRange(offset, offset + frame))
            offset += frame
        }
        if (offset < samples.size) pending = samples.copyOfRange(offset, samples.size)
    }

    private fun emit(f: FloatArray) {
        ring.addLast(f)
        ringEnergy.addLast(VoiceSegment.meanSquare(f).toFloat())
        running += VoiceSegment.meanSquare(f)
        if (ring.size > targetFrames) {
            running -= ringEnergy.removeFirst()
            ring.removeFirst()
        }
        if (ring.size == targetFrames && running > bestScore) {
            bestScore = running
            best = ring.toList().toTypedArray()
        }
    }

    fun finish(): FloatArray {
        val window: List<FloatArray> = best?.toList() ?: run {
            val list = ring.toMutableList()
            if (pending.isNotEmpty()) list.add(pending)
            list
        }
        if (window.isEmpty()) throw IllegalArgumentException("音频里没有可用的声音片段")
        val flat = FloatArray(window.sumOf { it.size })
        var at = 0
        for (part in window) {
            System.arraycopy(part, 0, flat, at, part.size)
            at += part.size
        }
        val energies = VoiceSegment.frameEnergies(flat, frame)
        val range = VoiceSegment.voicedRange(energies)
            ?: throw IllegalArgumentException("音频里没有检测到清晰人声")
        val from = range.first * frame
        val to = min(flat.size, (range.last + 1) * frame)
        val trimmed = flat.copyOfRange(from, to)
        if (trimmed.size < minSamples) {
            throw IllegalArgumentException("有效人声不足 ${minSeconds.toInt()} 秒，换一段更清晰、人声更连续的录音")
        }
        return VoiceSegment.normalize(trimmed)
    }
}
