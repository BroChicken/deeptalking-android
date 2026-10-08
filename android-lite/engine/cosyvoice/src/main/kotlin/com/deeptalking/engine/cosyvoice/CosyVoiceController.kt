package com.deeptalking.engine.cosyvoice

import android.content.Context
import android.net.Uri
import com.deeptalking.core.model.TtsPhase
import com.deeptalking.engine.ondevice.AudioChunk
import com.deeptalking.engine.ondevice.TtsBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * App-facing facade over the on-device CosyVoice3 runtime: model/voice download,
 * reference-voice import, and read-aloud playback. One instance is owned by the
 * application graph.
 */
class CosyVoiceController(private val context: Context) {

    private val engine = CosyVoiceEngine()
    private val models = CosyVoiceModelManager(context)
    private val player = PcmPlayer()

    val modelManager: CosyVoiceModelManager get() = models

    /** Directory holding imported voice profiles (prompt-speech GGUF files). */
    private val voicesDir: File get() = File(context.filesDir, "cosyvoice/voices").apply { mkdirs() }

    private val store = VoiceStore(voicesDir)

    @Volatile
    var voiceFile: File? = null
        private set

    val isModelReady: Boolean get() = models.isReady()

    /** Wires the on-device engine into the pluggable inference registry. */
    val backend: TtsBackend = object : TtsBackend {
        override val id: String = "cosyvoice3"
        override suspend fun synthesize(text: String, voice: String?, style: String?): Flow<AudioChunk> = flow {
            val pcm = this@CosyVoiceController.synthesize(text, style, voiceFile?.absolutePath ?: voice)
            emit(AudioChunk(toShort(pcm.first), pcm.second, isLast = true))
        }.flowOn(Dispatchers.Default)
    }

    private fun toShort(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    suspend fun prepare() = withContext(Dispatchers.IO) {
        if (!models.isReady()) error("语音模型尚未下载")
        engine.loadModel(models.modelFile.absolutePath)
    }

    suspend fun download(onProgress: (Long, Long, String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        models.downloadAll(onProgress)
    }

    /** All selectable voices (built-in first, then user imports), by display name. */
    fun listVoices(): List<VoiceInfo> = store.list()

    /**
     * Decodes any imported audio file, auto-selects a few seconds of the clearest
     * voiced speech, encodes it into a reusable voice and returns it.
     */
    suspend fun importVoice(uri: Uri, name: String, promptText: String?): VoiceInfo = withContext(Dispatchers.IO) {
        if (!models.isReady()) error("请先下载语音模型")
        val clip = AudioLoader(context).load(uri)
        val display = name.trim().ifBlank { "我的音色" }
        val hash = MessageDigest.getInstance("MD5")
            .digest("$display-${System.currentTimeMillis()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
        val out = File(voicesDir, "voice-$hash.gguf")
        engine.encodeVoice(
            pcm16k = clip.samples,
            promptText = promptText,
            outFile = out,
            tokenizerPath = models.tokenizerFile.absolutePath,
            campplusPath = models.campplusFile.absolutePath,
        )
        val info = store.add(out, display, builtin = false)
        voiceFile = out
        info
    }

    /** Encodes the bundled reference clips once so there are ready-to-use voices. */
    suspend fun ensureDefaultVoices(): Boolean = withContext(Dispatchers.IO) {
        if (!models.isReady()) return@withContext false
        val loader = AudioLoader(context)
        // Drop legacy built-in files from earlier builds.
        runCatching {
            File(voicesDir, "default_1.gguf").delete()
            File(voicesDir, "default_2.gguf").delete()
            File(voicesDir, "builtin_1.gguf").delete()
            File(voicesDir, "builtin_2.gguf").delete()
        }
        val existing = store.list().map { it.file }.toSet()
        for (default in DEFAULTS) {
            val out = File(voicesDir, default.fileName)
            if (out.exists() && existing.contains(default.fileName) && engine.canLoadPromptSpeech(out.absolutePath)) continue
            // Missing, unindexed or corrupt -> rebuild it.
            if (out.exists()) out.delete()
            runCatching {
                val tmp = File(context.cacheDir, "deeptalking-" + default.asset.replace('/', '_'))
                context.assets.open(default.asset).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val clip = try {
                    loader.load(tmp)
                } finally {
                    tmp.delete()
                }
                engine.encodeVoice(
                    pcm16k = clip.samples,
                    promptText = default.prompt,
                    outFile = out,
                    tokenizerPath = models.tokenizerFile.absolutePath,
                    campplusPath = models.campplusFile.absolutePath,
                )
                store.add(out, default.name, builtin = true)
            }
        }
        true
    }

    /** Resolves a stored voice by its file name and makes it active. */
    fun selectVoice(file: String): File? {
        val target = store.list().firstOrNull { it.file == file } ?: return null
        val resolved = store.fileFor(target.file)
        if (!resolved.exists()) return null
        voiceFile = resolved
        return resolved
    }

    fun renameVoice(file: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return store.rename(file, trimmed)
    }

    /** Deletes a user voice; clears the active selection when it was active. */
    fun deleteVoice(file: String): Boolean {
        val removed = store.remove(file)
        if (removed && voiceFile?.name == file) voiceFile = null
        return removed
    }

    fun setVoiceFile(file: File?) {
        voiceFile = file
    }

    /** Synthesizes without playing (used by the read-aloud backend/tests). */
    suspend fun synthesize(text: String, style: String?, voicePath: String?): Pair<FloatArray, Int> =
        withContext(Dispatchers.Default) {
            if (!engine.isModelLoaded) engine.loadModel(models.modelFile.absolutePath)
            val path = voicePath ?: voiceFile?.absolutePath ?: error("尚未选择音色，请先导入参考音频")
            val pcm = engine.synthesize(text, path, style, 1.0f)
            pcm to engine.sampleRate
        }

    /**
     * Synthesizes and plays [text] on the speaker; blocks until playback ends.
     * [onPhase] reports [TtsPhase.Synthesizing] before the (slow) synthesis and
     * [TtsPhase.Playing] once audio starts, so the UI can show live progress.
     */
    suspend fun speak(text: String, style: String? = null, onPhase: (TtsPhase) -> Unit = {}) =
        withContext(Dispatchers.Default) {
            onPhase(TtsPhase.Synthesizing)
            val (pcm, rate) = synthesize(text, style, voiceFile?.absolutePath)
            onPhase(TtsPhase.Playing)
            player.play(pcm, rate)
        }

    /**
     * Preempts any running synthesis and stops playback. [CosyVoiceEngine.cancel]
     * blocks until the native worker exits, so this must not run on the main thread.
     */
    fun cancel() {
        runCatching { engine.cancel() }
        player.stop()
    }

    fun stop() = player.stop()

    fun release() {
        player.release()
        engine.release()
    }

    private data class DefaultVoice(val asset: String, val fileName: String, val name: String, val prompt: String)

    private companion object {
        val DEFAULTS = listOf(
            DefaultVoice("voices/sample_1.wav", "builtin_1_v2.gguf", "内置音色 1", "今天天气真不错，我们一起去公园散步吧。"),
            DefaultVoice("voices/sample_2.wav", "builtin_2_v2.gguf", "内置音色 2", "大家好，很高兴在这里和你聊天。"),
        )
    }
}
