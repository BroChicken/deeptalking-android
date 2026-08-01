package com.deeptalking.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.Executors

private data class VoicePack(
    val id: String,
    val basePath: String,
    val kind: Kind,
    val model: String,
    val lexicon: String,
    val ruleFsts: String = "",
    val dataDir: String = "",
    val vocoder: String = ""
) {
    enum class Kind { VITS, MATCHA, KOKORO }
}

private const val TAG = "VoiceManager"

class VoiceManager(context: Context, private val onError: (String) -> Unit = {}) {
    private val assets = context.assets
    private val executor = Executors.newSingleThreadExecutor()
    private var activePack: VoicePack? = null
    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null

    private val packs = mapOf(
        "xiaoya" to VoicePack(
            id = "xiaoya", basePath = "voices/xiaoya", kind = VoicePack.Kind.VITS,
            model = "zh_CN-xiao_ya-medium.onnx", lexicon = "lexicon.txt",
            ruleFsts = "voices/xiaoya/phone.fst,voices/xiaoya/date.fst,voices/xiaoya/number.fst"
        ),
        "huayan" to VoicePack(
            id = "huayan", basePath = "voices/huayan", kind = VoicePack.Kind.VITS,
            model = "zh_CN-huayan-medium.onnx", lexicon = "",
            dataDir = "voices/huayan/espeak-ng-data"
        ),
        "matcha" to VoicePack(
            id = "matcha", basePath = "voices/matcha", kind = VoicePack.Kind.MATCHA,
            model = "model-steps-3.onnx", lexicon = "lexicon.txt",
            vocoder = "voices/matcha/vocos-22khz-univ.onnx",
            ruleFsts = "voices/matcha/phone.fst,voices/matcha/date.fst,voices/matcha/number.fst"
        ),
        "kokoro" to VoicePack(
            id = "kokoro", basePath = "voices/kokoro", kind = VoicePack.Kind.KOKORO,
            model = "model.int8.onnx", lexicon = "lexicon-zh.txt",
            dataDir = "espeak-ng-data",
            ruleFsts = "voices/kokoro/date-zh.fst,voices/kokoro/number-zh.fst,voices/kokoro/phone-zh.fst"
        )
    )

    fun speak(packId: String, text: String, speed: Float, playbackRate: Float, volume: Float, sid: Int = 0) {
        if (text.isBlank()) return
        executor.execute {
            try {
                val pack = packs[packId] ?: packs.getValue("xiaoya")
                ensurePack(pack)
                val audio = tts?.generate(text = text, sid = sid, speed = speed.coerceIn(0.75f, 1.25f))
                    ?: throw IllegalStateException("generate returned null")
                play(audio.samples, audio.sampleRate, playbackRate.coerceIn(0.85f, 1.15f), volume.coerceIn(0f, 1f))
            } catch (t: Throwable) {
                Log.e(TAG, "speak failed for pack=$packId", t)
                onError("speak($packId) failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun stop() {
        synchronized(this) {
            track?.stop()
            track?.release()
            track = null
        }
    }

    fun close() {
        stop()
        synchronized(this) {
            tts?.release()
            tts = null
            activePack = null
        }
        executor.shutdownNow()
    }

    private fun ensurePack(pack: VoicePack) {
        synchronized(this) {
            if (activePack?.id == pack.id && tts != null) return
            stop()
            tts?.release()
            tts = null
            activePack = null
            try {
                tts = OfflineTts(assets, createConfig(pack))
            } catch (t: Throwable) {
                Log.e(TAG, "OfflineTts init failed for ${pack.id}", t)
                throw IllegalStateException("初始化 ${pack.id} 模型失败: ${t.message ?: t.javaClass.simpleName}", t)
            }
            activePack = pack
        }
    }

    private fun createConfig(pack: VoicePack): OfflineTtsConfig {
        val emptyVits = OfflineTtsVitsModelConfig()
        val emptyMatcha = OfflineTtsMatchaModelConfig()
        val model = when (pack.kind) {
            VoicePack.Kind.VITS -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "${pack.basePath}/${pack.model}",
                    lexicon = pack.lexicon.takeIf { it.isNotBlank() }?.let { "${pack.basePath}/$it" } ?: "",
                    tokens = "${pack.basePath}/tokens.txt",
                    dataDir = pack.dataDir,
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f
                ),
                matcha = emptyMatcha,
                numThreads = 2,
                provider = "cpu"
            )
            VoicePack.Kind.MATCHA -> OfflineTtsModelConfig(
                vits = emptyVits,
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = "${pack.basePath}/${pack.model}",
                    vocoder = pack.vocoder,
                    lexicon = "${pack.basePath}/${pack.lexicon}",
                    tokens = "${pack.basePath}/tokens.txt",
                    dataDir = "",
                    noiseScale = 0.667f,
                    lengthScale = 1.0f
                ),
                numThreads = 2,
                provider = "cpu"
            )
            VoicePack.Kind.KOKORO -> OfflineTtsModelConfig(
                vits = emptyVits,
                matcha = emptyMatcha,
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "${pack.basePath}/${pack.model}",
                    voices = "${pack.basePath}/voices.bin",
                    tokens = "${pack.basePath}/tokens.txt",
                    dataDir = "${pack.basePath}/${pack.dataDir}",
                    lexicon = "${pack.basePath}/${pack.lexicon}",
                    lang = "b",
                    lengthScale = 1.0f
                ),
                numThreads = 2,
                provider = "cpu"
            )
        }
        return OfflineTtsConfig(model = model, ruleFsts = pack.ruleFsts, maxNumSentences = 1)
    }

    private fun play(samples: FloatArray, sampleRate: Int, playbackRate: Float, volume: Float) {
        synchronized(this) {
            stop()
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples.size * Float.SIZE_BYTES)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            audioTrack.setVolume(volume)
            audioTrack.playbackParams = PlaybackParams().setSpeed(playbackRate).setPitch(playbackRate)
            audioTrack.play()
            track = audioTrack
        }
    }
}
