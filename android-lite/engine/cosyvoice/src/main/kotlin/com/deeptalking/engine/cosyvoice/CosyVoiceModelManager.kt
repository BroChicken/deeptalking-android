package com.deeptalking.engine.cosyvoice

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One downloadable file of the CosyVoice3 runtime. */
data class CosyVoiceAsset(
    val id: String,
    val label: String,
    val relativePath: String,
    val approxBytes: Long,
    val url: String,
)

/**
 * Downloads the CosyVoice3 GGUF model and the ONNX frontend (voice encoder)
 * from ModelScope on demand, keeping the APK small.
 */
class CosyVoiceModelManager(private val context: Context) {

    val root: File get() = File(context.filesDir, "cosyvoice")

    val modelFile: File get() = File(root, MODEL.relativePath)
    val tokenizerFile: File get() = File(root, TOKENIZER.relativePath)
    val campplusFile: File get() = File(root, CAMPPLUS.relativePath)

    fun fileFor(asset: CosyVoiceAsset): File = File(root, asset.relativePath)

    val all: List<CosyVoiceAsset> = listOf(MODEL, TOKENIZER, CAMPPLUS)

    fun isReady(): Boolean = all.all { fileFor(it).length() > 0 }

    fun totalBytes(): Long = all.sumOf { it.approxBytes }

    /**
     * Downloads every missing asset. [onProgress] receives (downloadedBytes,
     * totalBytes, currentLabel). Returns true when everything is present.
     */
    fun downloadAll(onProgress: (Long, Long, String) -> Unit): Boolean {
        root.mkdirs()
        val total = totalBytes()
        var done = all.filter { fileFor(it).length() > 0 }.sumOf { it.approxBytes }
        for (asset in all) {
            val target = fileFor(asset)
            if (target.length() > 0) continue
            target.parentFile?.mkdirs()
            download(asset.url, target) { read ->
                onProgress(done + read, total, asset.label)
            }
            done += asset.approxBytes
        }
        return isReady()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DeepTalking-Lite/1.5")
        }

    private fun download(url: String, target: File, onRead: (Long) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".part")
        var conn: HttpURLConnection = open(url)
        try {
            var code = conn.responseCode
            var redirects = 0
            while (code in 301..308 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = open(loc)
                code = conn.responseCode
                redirects++
            }
            require(code in 200..299) { "下载失败 HTTP $code: $url" }
            var readSoFar = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        readSoFar += n
                        onRead(readSoFar)
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://modelscope.cn/models/Lourdle/Fun-CosyVoice3-0.5B-2512-GGUF/resolve/master"

        val MODEL = CosyVoiceAsset(
            id = "model",
            label = "CosyVoice3 语音模型 (Q4_K_M)",
            relativePath = "CosyVoice3-2512_Q4_K_M.gguf",
            approxBytes = 629_141_792,
            url = "$BASE/CosyVoice3-2512_Q4_K_M.gguf",
        )
        val TOKENIZER = CosyVoiceAsset(
            id = "tokenizer",
            label = "语音编码器 (speech_tokenizer)",
            relativePath = "frontend-onnx/speech_tokenizer_v3.int8.onnx",
            approxBytes = 244_237_335,
            url = "$BASE/frontend-onnx/speech_tokenizer_v3.int8.onnx",
        )
        val CAMPPLUS = CosyVoiceAsset(
            id = "campplus",
            label = "说话人编码器 (campplus)",
            relativePath = "frontend-onnx/campplus.int8.onnx",
            approxBytes = 8_654_637,
            url = "$BASE/frontend-onnx/campplus.int8.onnx",
        )
    }
}
