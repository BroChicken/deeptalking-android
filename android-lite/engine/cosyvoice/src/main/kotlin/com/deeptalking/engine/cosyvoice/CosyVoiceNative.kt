package com.deeptalking.engine.cosyvoice

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Raw JNA bindings to the community cosyvoice.cpp C API (`libcosyvoice.so`).
 *
 * Only the subset needed by the app is bound. All handles are opaque pointers
 * owned by the native library; callers must free them via the matching `*_free`.
 */
internal interface CosyVoiceLib : Library {

    // -------- model context --------
    fun cosyvoice_init_backend()
    fun cosyvoice_load_from_file(filename: String): Pointer?
    fun cosyvoice_load_from_file_ext(
        filename: String,
        params: Pointer,
        backend: Pointer?,
        nThreads: Int,
        paramsVersion: Int,
    ): Pointer?
    fun cosyvoice_init_default_context_params(params: Pointer)
    fun cosyvoice_free(ctx: Pointer)
    fun cosyvoice_get_sample_rate(ctx: Pointer): Int

    // -------- prompt speech (encoded voice) --------
    fun cosyvoice_prompt_speech_load_from_file(filename: String): Pointer?
    fun cosyvoice_prompt_speech_save_to_file(promptSpeech: Pointer, filename: String): Boolean
    fun cosyvoice_prompt_speech_free(promptSpeech: Pointer)
    fun cosyvoice_prompt_init_from_prompt_speech(ctx: Pointer, promptSpeech: Pointer): Pointer?
    fun cosyvoice_prompt_free(prompt: Pointer)

    // -------- tts session --------
    fun cosyvoice_tts_context_new(ctx: Pointer, prompt: Pointer): Pointer?
    fun cosyvoice_tts_context_free(tts: Pointer)
    fun cosyvoice_tts_instruct(tts: Pointer, text: String, instruction: String?, speed: Float, result: GeneratedSpeech): Boolean

    // -------- stop request (preempt a running synthesis) --------
    fun cosyvoice_request_stop(ctx: Pointer)
    fun cosyvoice_is_stop_requested(ctx: Pointer): Boolean

    // -------- frontend (reference audio -> prompt speech) --------
    fun cosyvoice_frontend_load_from_files(speechTokenizer: String, campplus: String): Pointer?
    fun cosyvoice_frontend_prompt_speech(
        fe: Pointer,
        speech: FloatArray,
        speechLen: Int,
        sampleRate: Int,
        promptText: String?,
    ): Pointer?
    fun cosyvoice_frontend_free(fe: Pointer)

    companion object {
        const val LIB_NAME = "cosyvoice"
        val instance: CosyVoiceLib by lazy { Native.load(LIB_NAME, CosyVoiceLib::class.java) }
    }
}

/** Mirrors `cosyvoice_generated_speech { float* data; uint32_t length; }`. */
@Structure.FieldOrder("data", "length")
internal class GeneratedSpeech : Structure() {
    @JvmField var data: Pointer? = null
    @JvmField var length: Int = 0
}
