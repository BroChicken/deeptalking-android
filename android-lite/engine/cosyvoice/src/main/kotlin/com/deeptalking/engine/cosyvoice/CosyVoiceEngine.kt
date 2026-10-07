package com.deeptalking.engine.cosyvoice

import android.util.Log
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.io.File

/**
 * Thin wrapper around the community cosyvoice.cpp runtime. Holds the loaded
 * model context and the ONNX frontend, and exposes the three operations the app
 * needs: encode a reference voice, and synthesize text (zero-shot / instruct).
 *
 * The runtime is not thread-safe; every call is serialized on this instance and
 * callers are expected to run it off the main thread (model load takes seconds).
 */
class CosyVoiceEngine {

    private companion object {
        const val TAG = "CosyVoiceEngine"
        const val PLACEHOLDER_PROMPT_TEXT = "你好"
        // cosyvoice_context_params_t is well under this; we only need a writable
        // buffer that cosyvoice_init_default_context_params can fill.
        const val PARAMS_BYTES = 256L
        const val PARAMS_VERSION = 0
    }

    private val lib by lazy { CosyVoiceLib.instance }
    private var ctx: Pointer? = null
    private var frontend: Pointer? = null

    var sampleRate: Int = 24000
        private set

    val isModelLoaded: Boolean get() = ctx != null

    @Synchronized
    fun loadModel(modelPath: String) {
        if (ctx != null) return
        require(File(modelPath).exists()) { "模型文件不存在: $modelPath" }
        val threads = CpuTopology.performanceCoreCount()
        val started = System.currentTimeMillis()
        // Load through the low-level API so we can pin the thread count: the
        // high-level loader defaults to every core (incl. little ones), which is
        // markedly slower on big.LITTLE SoCs.
        val c = runCatching {
            val params = Memory(PARAMS_BYTES)
            lib.cosyvoice_init_default_context_params(params)
            lib.cosyvoice_load_from_file_ext(modelPath, params, null, threads, PARAMS_VERSION)
        }.getOrNull()
            ?: lib.cosyvoice_load_from_file(modelPath)
            ?: error("模型加载失败: $modelPath")
        sampleRate = lib.cosyvoice_get_sample_rate(c).let { if (it in 8000..96000) it else 24000 }
        ctx = c
        Log.i(TAG, "model loaded in ${System.currentTimeMillis() - started}ms, n_threads=$threads (cores=${Runtime.getRuntime().availableProcessors()})")
    }

    @Synchronized
    fun unloadModel() {
        ctx?.let { runCatching { lib.cosyvoice_free(it) } }
        ctx = null
    }

    private fun ensureFrontend(tokenizerPath: String, campplusPath: String): Pointer {
        frontend?.let { return it }
        val fe = lib.cosyvoice_frontend_load_from_files(tokenizerPath, campplusPath)
            ?: error("前端模型加载失败")
        frontend = fe
        return fe
    }

    /**
     * Encodes a mono 16 kHz reference clip into a reusable prompt-speech GGUF
     * written to [outFile] (needed so the voice survives process restarts).
     * The prompt-speech format always carries a text tensor, so a placeholder is
     * used when [promptText] is blank (instruct mode ignores it anyway).
     */
    @Synchronized
    fun encodeVoice(pcm16k: FloatArray, promptText: String?, outFile: File, tokenizerPath: String, campplusPath: String) {
        require(pcm16k.isNotEmpty()) { "参考音频为空" }
        val fe = ensureFrontend(tokenizerPath, campplusPath)
        val speech = Wav.resample(pcm16k, 16000, 16000)
        val text = promptText?.trim().takeUnless { it.isNullOrEmpty() } ?: PLACEHOLDER_PROMPT_TEXT
        val ps = lib.cosyvoice_frontend_prompt_speech(fe, speech, speech.size, 16000, text)
            ?: error("音色编码失败（参考音频太短或无效）")
        try {
            outFile.parentFile?.mkdirs()
            val ok = lib.cosyvoice_prompt_speech_save_to_file(ps, outFile.absolutePath)
            if (!ok) error("音色写入失败")
        } finally {
            runCatching { lib.cosyvoice_prompt_speech_free(ps) }
        }
        // The saved file must load back, or synthesis would fail later with an
        // opaque "音色加载失败"; catch and drop a corrupt file here instead.
        if (!canLoadPromptSpeech(outFile.absolutePath)) {
            outFile.delete()
            error("音色保存校验失败（生成的音色文件无法读取）")
        }
    }

    /** True when a prompt-speech GGUF can be loaded by the native runtime. */
    @Synchronized
    fun canLoadPromptSpeech(path: String): Boolean {
        if (!File(path).exists()) return false
        val ps = runCatching { lib.cosyvoice_prompt_speech_load_from_file(path) }.getOrNull() ?: return false
        runCatching { lib.cosyvoice_prompt_speech_free(ps) }
        return true
    }

    /** Synthesizes [text] with an optional free-form style instruction. */
    @Synchronized
    fun synthesize(text: String, promptSpeechFile: String, instruction: String?, speed: Float): FloatArray {
        val c = ctx ?: error("模型尚未加载")
        require(File(promptSpeechFile).exists()) { "音色文件不存在: $promptSpeechFile" }
        val ps = lib.cosyvoice_prompt_speech_load_from_file(promptSpeechFile) ?: error("音色加载失败")
        var prompt: Pointer? = null
        var tts: Pointer? = null
        try {
            prompt = lib.cosyvoice_prompt_init_from_prompt_speech(c, ps) ?: error("音色初始化失败")
            tts = lib.cosyvoice_tts_context_new(c, prompt) ?: error("TTS 会话创建失败")
            val result = GeneratedSpeech()
            val started = System.currentTimeMillis()
            val ok = lib.cosyvoice_tts_instruct(tts, text, instruction, speed, result)
            if (!ok) error("语音合成失败")
            result.read()
            val length = result.length
            val data = result.data
            if (length <= 0 || data == null) return FloatArray(0)
            val pcm = data.getFloatArray(0, length)
            logRtf("synth", text.length, pcm.size, started)
            return pcm
        } finally {
            tts?.let { runCatching { lib.cosyvoice_tts_context_free(it) } }
            prompt?.let { runCatching { lib.cosyvoice_prompt_free(it) } }
            runCatching { lib.cosyvoice_prompt_speech_free(ps) }
        }
    }

    /**
     * Synthesizes [text] incrementally, handing each audio chunk to [onChunk]
     * as it becomes ready so playback can start long before the whole reply is
     * done. [onChunk] returns false to abort. Returns true when the runtime
     * reported success.
     */
    @Synchronized
    fun synthesizeStream(
        text: String,
        promptSpeechFile: String,
        instruction: String?,
        speed: Float,
        onChunk: (FloatArray) -> Boolean,
    ): Boolean {
        val c = ctx ?: error("模型尚未加载")
        require(File(promptSpeechFile).exists()) { "音色文件不存在: $promptSpeechFile" }
        val ps = lib.cosyvoice_prompt_speech_load_from_file(promptSpeechFile) ?: error("音色加载失败")
        var prompt: Pointer? = null
        var tts: Pointer? = null
        val started = System.currentTimeMillis()
        var samples = 0
        try {
            prompt = lib.cosyvoice_prompt_init_from_prompt_speech(c, ps) ?: error("音色初始化失败")
            tts = lib.cosyvoice_tts_context_new(c, prompt) ?: error("TTS 会话创建失败")
            val callback = object : TtsAudioCallback {
                override fun invoke(audio: Pointer, nSamples: Int, userData: Pointer?): Boolean {
                    if (nSamples <= 0) return true
                    val chunk = audio.getFloatArray(0, nSamples)
                    samples += chunk.size
                    return onChunk(chunk)
                }
            }
            val ok = lib.cosyvoice_tts_instruct_stream(tts, text, instruction, speed, callback, null)
            if (ok) logRtf("stream", text.length, samples, started)
            return ok
        } finally {
            tts?.let { runCatching { lib.cosyvoice_tts_context_free(it) } }
            prompt?.let { runCatching { lib.cosyvoice_prompt_free(it) } }
            runCatching { lib.cosyvoice_prompt_speech_free(ps) }
        }
    }

    private fun logRtf(kind: String, chars: Int, samples: Int, startedMs: Long) {
        val elapsed = (System.currentTimeMillis() - startedMs) / 1000.0
        val audio = samples.toDouble() / sampleRate
        val rtf = if (audio > 0) elapsed / audio else 0.0
        Log.i(TAG, "$kind: ${chars}字 -> ${"%.1f".format(audio)}s 音频, 用时 ${"%.1f".format(elapsed)}s, RTF=${"%.2f".format(rtf)}")
    }

    @Synchronized
    fun release() {
        frontend?.let { runCatching { lib.cosyvoice_frontend_free(it) } }
        frontend = null
        unloadModel()
        Log.i(TAG, "released")
    }
}
