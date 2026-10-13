package com.deeptalking.engine.cosyvoice

import android.util.Log
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.io.File

/**
 * Sampling and chunking knobs shared by the on-device engine and the PC server
 * (the server exposes the same names in `POST /v1/audio/speech`). Defaults are the
 * values baked into the model metadata, so "untouched" means "identical to both
 * engines' out-of-the-box behaviour"; see `docs/REMOTE_TTS.md` section 9.
 */
data class GenerationSettings(
    val temperature: Double = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Double = DEFAULT_TOP_P,
    val winSize: Int = DEFAULT_WIN_SIZE,
    val tauR: Double = DEFAULT_TAU_R,
    val minTokenTextRatio: Double = DEFAULT_MIN_TOKEN_TEXT_RATIO,
    val maxTokenTextRatio: Double = DEFAULT_MAX_TOKEN_TEXT_RATIO,
    val chunkTokens: Int = DEFAULT_CHUNK_TOKENS,
) {

    /**
     * The native setters reject out-of-range values (the server returns 400 for the
     * same ones), so clamp here - one place - before either engine sees them.
     */
    fun coerceValid(): GenerationSettings = copy(
        temperature = temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE),
        topK = topK.coerceIn(0, MAX_TOP_K),
        topP = topP.coerceIn(0.0, 1.0),
        winSize = winSize.coerceIn(1, MAX_WIN_SIZE),
        tauR = tauR.coerceAtLeast(0.0),
        minTokenTextRatio = minTokenTextRatio.coerceAtLeast(0.0),
        maxTokenTextRatio = maxTokenTextRatio.coerceIn(minTokenTextRatio.coerceAtLeast(0.0), MAX_TOKEN_TEXT_RATIO),
        chunkTokens = chunkTokens.coerceIn(1, MAX_CHUNK_TOKENS),
    )

    companion object {
        /** Model metadata (`sampling.*` / `pre_lookahead_len`), i.e. the untouched defaults. */
        const val DEFAULT_TEMPERATURE = 1.0
        const val DEFAULT_TOP_K = 25
        const val DEFAULT_TOP_P = 0.8
        const val DEFAULT_WIN_SIZE = 10
        const val DEFAULT_TAU_R = 0.1
        const val DEFAULT_MIN_TOKEN_TEXT_RATIO = 2.0
        const val DEFAULT_MAX_TOKEN_TEXT_RATIO = 20.0
        const val DEFAULT_CHUNK_TOKENS = 28

        const val MIN_TEMPERATURE = 0.05
        const val MAX_TEMPERATURE = 3.0
        const val MAX_TOP_K = 1000
        const val MAX_WIN_SIZE = 1000
        const val MAX_TOKEN_TEXT_RATIO = 200.0
        const val MAX_CHUNK_TOKENS = 512

        /**
         * Builds from stored config values, where `0`/`0.0` means "never set" (older
         * builds stored 0 for all of them), mapping those to the model defaults.
         */
        fun fromStored(
            temperature: Double,
            topK: Int,
            topP: Double,
            winSize: Int,
            tauR: Double,
            minTokenTextRatio: Double,
            maxTokenTextRatio: Double,
            chunkTokens: Int,
        ): GenerationSettings = GenerationSettings(
            temperature = temperature.takeIf { it > 0.0 } ?: DEFAULT_TEMPERATURE,
            topK = topK.takeIf { it > 0 } ?: DEFAULT_TOP_K,
            topP = topP.takeIf { it > 0.0 } ?: DEFAULT_TOP_P,
            winSize = winSize.takeIf { it > 0 } ?: DEFAULT_WIN_SIZE,
            tauR = tauR.takeIf { it > 0.0 } ?: DEFAULT_TAU_R,
            minTokenTextRatio = minTokenTextRatio.takeIf { it > 0.0 } ?: DEFAULT_MIN_TOKEN_TEXT_RATIO,
            maxTokenTextRatio = maxTokenTextRatio.takeIf { it > 0.0 } ?: DEFAULT_MAX_TOKEN_TEXT_RATIO,
            chunkTokens = chunkTokens.takeIf { it > 0 } ?: DEFAULT_CHUNK_TOKENS,
        ).coerceValid()
    }
}

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

        // cosyvoice_context_params_v3_t (verified with a compile-time offsetof check
        // against the C header; a wrong offset here corrupts the context silently).
        const val PARAMS_V3_BYTES = 80L
        const val PARAMS_VERSION_V3 = 2
        const val LLM_KV_CACHE_TYPE_OFFSET = 4L
        const val BUFFER_POLICY_OFFSET = 12L
        const val N_MAX_SEQ_OFFSET = 20L
        const val N_WORKERS_OFFSET = 48L
        const val DIT_KV_CACHE_TYPE_OFFSET = 56L
        const val DIT_ALLOW_FALLBACK_OFFSET = 60L
        const val DIT_KV_FIXED_SLOTS_OFFSET = 64L
        const val DIT_KV_OFFLOADABLE_SLOTS_OFFSET = 68L
        const val DIT_KV_CACHE_LENGTH_OFFSET = 72L

        /**
         * The PC server is launched with the same values, so both engines load the
         * model identically: the v0 parameter path would instead force
         * `dit_kv_cache_length = n_max_seq * 10 = 40960` (2.9 GB of host RAM).
         */
        const val N_MAX_SEQ = 4096
        const val DIT_KV_FIXED_SLOTS = 2
        const val DIT_KV_CACHE_LENGTH = 8192

        /** `COSYVOICE_MAKE_SEPARATE_KV_CACHE(q8_0, q4_0, q8_0)` = 0x800008C2. */
        const val DIT_KV_CACHE_TYPE = 0x800008C2.toInt()

        // cosyvoice_generation_config_t
        const val GENERATION_CONFIG_BYTES = 28L
        const val TEMPERATURE_OFFSET = 0L
        const val TOP_K_OFFSET = 4L
        const val TOP_P_OFFSET = 8L
        const val WIN_SIZE_OFFSET = 12L
        const val TAU_R_OFFSET = 16L
        const val MIN_RATIO_OFFSET = 20L
        const val MAX_RATIO_OFFSET = 24L
    }

    private val lib by lazy { CosyVoiceLib.instance }
    private var ctx: Pointer? = null
    private var frontend: Pointer? = null

    /** Knobs requested before the model was loaded; replayed right after it is. */
    private var pendingGeneration: GenerationSettings? = null

    // Per-voice cache: the loaded prompt-speech GGUF and the prompt/TTS context
    // derived from it, reused across read-aloud calls for the same voice.
    private var voiceCachePath: String? = null
    private var voiceCachePs: Pointer? = null
    private var voiceCachePrompt: Pointer? = null
    private var voiceCacheTts: Pointer? = null

    var sampleRate: Int = 24000
        private set

    val isModelLoaded: Boolean get() = ctx != null

    @Synchronized
    fun loadModel(modelPath: String) {
        if (ctx != null) return
        require(File(modelPath).exists()) { "模型文件不存在: $modelPath" }
        runCatching { lib.cosyvoice_init_backend() }
        val threads = CpuTopology.performanceCoreCount()
        val started = System.currentTimeMillis()
        // Load through the low-level API so we can pin the thread count: the
        // high-level loader defaults to every core (incl. little ones), which is
        // markedly slower on big.LITTLE SoCs. V3 params carry the same load-time
        // values the PC server uses (see the constants above).
        val c = runCatching {
            val params = Memory(PARAMS_V3_BYTES)
            lib.cosyvoice_init_default_context_params(params)
            params.setInt(N_MAX_SEQ_OFFSET, N_MAX_SEQ)
            params.setInt(N_WORKERS_OFFSET, 1)
            params.setInt(DIT_KV_CACHE_TYPE_OFFSET, DIT_KV_CACHE_TYPE)
            params.setByte(DIT_ALLOW_FALLBACK_OFFSET, 1)
            params.setInt(DIT_KV_FIXED_SLOTS_OFFSET, DIT_KV_FIXED_SLOTS)
            params.setInt(DIT_KV_OFFLOADABLE_SLOTS_OFFSET, 0)
            params.setInt(DIT_KV_CACHE_LENGTH_OFFSET, DIT_KV_CACHE_LENGTH)
            lib.cosyvoice_load_from_file_ext(modelPath, params, null, threads, PARAMS_VERSION_V3)
        }.getOrNull()
            ?: lib.cosyvoice_load_from_file(modelPath)
            ?: error("模型加载失败: $modelPath")
        sampleRate = lib.cosyvoice_get_sample_rate(c).let { if (it in 8000..96000) it else 24000 }
        ctx = c
        Log.i(TAG, "model loaded in ${System.currentTimeMillis() - started}ms, n_threads=$threads (cores=${Runtime.getRuntime().availableProcessors()})")
        logEffectiveParams(c)
        pendingGeneration?.let { applyGeneration(it) }
    }

    /**
     * Read-back of the effective base context params: proves on-device that the byte
     * offsets JNA wrote are the ones the native struct really has (n_max_seq 4096 and
     * llm_kv_cache_type 2 = q8_0 are what the PC server is launched with).
     */
    private fun logEffectiveParams(c: Pointer) {
        runCatching {
            val out = Memory(PARAMS_V3_BYTES)
            lib.cosyvoice_get_context_params(c, out)
            Log.i(
                TAG,
                "effective context params: n_max_seq=${out.getInt(N_MAX_SEQ_OFFSET)} " +
                    "llm_kv_cache_type=${out.getInt(LLM_KV_CACHE_TYPE_OFFSET)} " +
                    "buffer_policy=${out.getInt(BUFFER_POLICY_OFFSET)}",
            )
        }.onFailure { Log.w(TAG, "context param read-back failed: ${it.message}") }
    }

    /**
     * Applies the shared sampling/chunking knobs to the active worker. Applied now when
     * the model is loaded, otherwise replayed by [loadModel].
     */
    @Synchronized
    fun applyGeneration(settings: GenerationSettings) {
        pendingGeneration = settings
        val c = ctx ?: return
        val valid = settings.coerceValid()
        runCatching {
            val cfg = Memory(GENERATION_CONFIG_BYTES)
            cfg.setFloat(TEMPERATURE_OFFSET, valid.temperature.toFloat())
            cfg.setInt(TOP_K_OFFSET, valid.topK)
            cfg.setFloat(TOP_P_OFFSET, valid.topP.toFloat())
            cfg.setInt(WIN_SIZE_OFFSET, valid.winSize)
            cfg.setFloat(TAU_R_OFFSET, valid.tauR.toFloat())
            cfg.setFloat(MIN_RATIO_OFFSET, valid.minTokenTextRatio.toFloat())
            cfg.setFloat(MAX_RATIO_OFFSET, valid.maxTokenTextRatio.toFloat())
            val ok = lib.cosyvoice_set_generation_config(c, cfg)
            lib.cosyvoice_set_chunk_tokens(c, valid.chunkTokens)
            if (!ok) Log.w(TAG, "generation config rejected by the runtime; keeping the model defaults")
        }.onFailure { Log.w(TAG, "generation config failed: ${it.message}") }
    }

    @Synchronized
    fun unloadModel() {
        freeVoiceCache()
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
        // The prompt-speech GGUF and the derived prompt/TTS context only depend on
        // the voice, so load them once and reuse across read-aloud calls.
        if (voiceCachePath != promptSpeechFile) {
            freeVoiceCache()
            val ps = lib.cosyvoice_prompt_speech_load_from_file(promptSpeechFile) ?: error("音色加载失败")
            val prompt = lib.cosyvoice_prompt_init_from_prompt_speech(c, ps)
                ?: run { runCatching { lib.cosyvoice_prompt_speech_free(ps) }; error("音色初始化失败") }
            val tts = lib.cosyvoice_tts_context_new(c, prompt)
                ?: run { runCatching { lib.cosyvoice_prompt_free(prompt) }; runCatching { lib.cosyvoice_prompt_speech_free(ps) }; error("TTS 会话创建失败") }
            voiceCachePs = ps
            voiceCachePrompt = prompt
            voiceCacheTts = tts
            voiceCachePath = promptSpeechFile
        }
        val tts = voiceCacheTts ?: error("TTS 会话创建失败")
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
    }

    @Synchronized
    private fun freeVoiceCache() {
        voiceCacheTts?.let { runCatching { lib.cosyvoice_tts_context_free(it) } }
        voiceCachePrompt?.let { runCatching { lib.cosyvoice_prompt_free(it) } }
        voiceCachePs?.let { runCatching { lib.cosyvoice_prompt_speech_free(it) } }
        voiceCacheTts = null
        voiceCachePrompt = null
        voiceCachePs = null
        voiceCachePath = null
    }

    /**
     * Requests the running synthesis to stop and waits for it to unwind.
     *
     * Safe to call from any thread EXCEPT the one currently inside [synthesize]
     * (the native worker blocks until the running job exits). Callers should run
     * this off the main thread.
     */
    fun cancel() {
        val c = ctx ?: return
        runCatching { lib.cosyvoice_request_stop(c) }
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
