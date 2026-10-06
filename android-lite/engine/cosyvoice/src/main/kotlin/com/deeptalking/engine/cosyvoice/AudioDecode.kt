package com.deeptalking.engine.cosyvoice

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono reference clip selected from an imported audio file. */
internal data class DecodedVoice(val samples: FloatArray, val sampleRate: Int)

/**
 * Decodes an imported audio file (mp3 / m4a / aac / ogg / opus / flac / webm /
 * wav, any bit depth) into a short, clean mono 16 kHz reference clip.
 *
 * Decoding is streamed through MediaExtractor + MediaCodec and only a sliding
 * window is kept, so arbitrarily large files never sit in memory. Falls back to
 * the in-process WAV reader for small RIFF files when a platform decoder is
 * missing.
 */
internal class AudioLoader(private val context: Context) {

    fun load(uri: Uri): DecodedVoice = load(uri, null)

    fun load(file: File): DecodedVoice = load(null, file)

    private fun load(uri: Uri?, file: File?): DecodedVoice {
        val failure = try {
            val extractor = MediaExtractor()
            try {
                if (file != null) extractor.setDataSource(file.absolutePath)
                else extractor.setDataSource(context, requireNotNull(uri), null)
                return decode(extractor)
            } finally {
                runCatching { extractor.release() }
            }
        } catch (t: Throwable) {
            t
        }
        val wav = runCatching { readRiff(uri, file) }.getOrNull()
        if (wav != null) {
            val decoded = Wav.decode(wav)
            val pcm = Wav.resample(decoded.samples, decoded.sampleRate, VoiceSegment.RATE)
            val picker = SegmentPicker(VoiceSegment.RATE)
            picker.push(pcm)
            return DecodedVoice(picker.finish(), VoiceSegment.RATE)
        }
        throw when (failure) {
            is IllegalArgumentException -> failure
            else -> IllegalArgumentException("音频解码失败：${failure.message ?: "不支持的格式"}")
        }
    }

    private fun decode(extractor: MediaExtractor): DecodedVoice {
        val track = selectAudioTrack(extractor)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("无法识别音频格式")
        var srcRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0).takeIf { it > 0 } ?: VoiceSegment.RATE
        var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        extractor.selectTrack(track)

        val codec = MediaCodec.createDecoderByType(mime)
        val picker = SegmentPicker(VoiceSegment.RATE)
        var resampler: StreamResampler? = null
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val mono = toMonoFloat(outBuf, encoding, channels)
                            val rs = resampler ?: StreamResampler(srcRate, VoiceSegment.RATE).also { resampler = it }
                            rs.feed(mono) { picker.push(it) }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        srcRate = out.intOr(MediaFormat.KEY_SAMPLE_RATE, srcRate).takeIf { it > 0 } ?: srcRate
                        channels = out.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        encoding = out.pcmEncoding()
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return DecodedVoice(picker.finish(), VoiceSegment.RATE)
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        throw IllegalArgumentException("文件里没有音频轨道")
    }

    private fun toMonoFloat(buf: ByteBuffer, encoding: Int, channels: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val frames = fb.remaining() / ch
            val out = FloatArray(frames)
            for (i in 0 until frames) {
                var acc = 0f
                for (c in 0 until ch) acc += fb.get()
                out[i] = acc / ch
            }
            return out
        }
        val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = sb.remaining() / ch
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until ch) acc += sb.get() / 32768f
            out[i] = acc / ch
        }
        return out
    }

    /** Reads a small RIFF/WAVE file wholesale; returns null for other formats. */
    private fun readRiff(uri: Uri?, file: File?): ByteArray? {
        val maxBytes = 128L * 1024 * 1024
        val stream = file?.inputStream() ?: context.contentResolver.openInputStream(uri!!) ?: return null
        stream.use { input ->
            val head = ByteArray(12)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n <= 0) return null
                read += n
            }
            val riff = String(head, 0, 4, Charsets.US_ASCII)
            val wave = String(head, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE") return null
            val body = java.io.ByteArrayOutputStream()
            body.write(head)
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                if (body.size() + n > maxBytes) throw IllegalArgumentException("音频过大，无法用兜底解码器处理")
                body.write(buffer, 0, n)
            }
            return body.toByteArray()
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int {
        if (!containsKey(key)) return fallback
        return runCatching { getInteger(key) }.getOrDefault(fallback).takeIf { it > 0 } ?: fallback
    }

    private fun MediaFormat.pcmEncoding(): Int {
        if (!containsKey(MediaFormat.KEY_PCM_ENCODING)) return AudioFormat.ENCODING_PCM_16BIT
        return runCatching { getInteger(MediaFormat.KEY_PCM_ENCODING) }
            .getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
    }
}

/** Streaming linear resampler with cross-buffer carry so chunks join smoothly. */
internal class StreamResampler(private val srcRate: Int, private val dstRate: Int) {

    private val step = srcRate.toDouble() / dstRate
    private var pending = FloatArray(0)
    private var pendingStart = 0L
    private var nextOut = 0.0
    private val out = GrowableFloat()

    fun feed(input: FloatArray, emit: (FloatArray) -> Unit) {
        if (srcRate <= 0 || dstRate <= 0 || srcRate == dstRate) {
            if (input.isNotEmpty()) emit(input)
            return
        }
        val merged = FloatArray(pending.size + input.size)
        System.arraycopy(pending, 0, merged, 0, pending.size)
        System.arraycopy(input, 0, merged, pending.size, input.size)
        val lastAbs = pendingStart + merged.size - 1
        out.reset()
        while (true) {
            val i0Abs = Math.floor(nextOut).toLong()
            if (i0Abs + 1 > lastAbs) break
            val i0 = (i0Abs - pendingStart).toInt()
            val frac = (nextOut - i0Abs).toFloat()
            out.add(merged[i0] * (1 - frac) + merged[i0 + 1] * frac)
            nextOut += step
        }
        if (out.size > 0) emit(out.toArray())
        val keepFrom = Math.floor(nextOut).toLong().coerceAtMost(lastAbs + 1)
        val keepIndex = (keepFrom - pendingStart).toInt().coerceIn(0, merged.size)
        pending = merged.copyOfRange(keepIndex, merged.size)
        pendingStart += keepIndex
    }
}

/** Minimal growable float buffer (avoids boxing on hot decode loops). */
internal class GrowableFloat(capacity: Int = 1024) {
    private var values = FloatArray(capacity.coerceAtLeast(1))
    var size = 0
        private set

    fun add(value: Float) {
        ensure(size + 1)
        values[size++] = value
    }

    fun reset() {
        size = 0
    }

    fun toArray(): FloatArray = values.copyOf(size)

    private fun ensure(needed: Int) {
        if (needed <= values.size) return
        var next = values.size * 2
        while (next < needed) next *= 2
        values = values.copyOf(next)
    }
}
