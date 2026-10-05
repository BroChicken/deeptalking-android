package com.deeptalking.core.data.legacy

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Decodes legacy `data:image/...;base64,...` payloads into
 * `filesDir/<kind>/<sha256>.jpg` and returns the **relative key**
 * (`images/<sha>.jpg` / `stickers/<sha>.jpg`) stored in the model.
 *
 * Relative keys survive reinstall/cross-device backups (the absolute
 * `filesDir` path does not); resolution to a real file lives in the app's
 * `MediaRef` helper. The hash is taken over the data URI so repeated identical
 * payloads map to the same file. Decoding failures fall back to the original
 * URI so media is never silently dropped.
 */
class FileStickerSink(private val context: Context) : StickerSink {

    override fun refFor(dataUri: String): String = store(dataUri, "stickers")

    override fun imageRefFor(dataUri: String): String = store(dataUri, "images")

    private fun store(dataUri: String, directoryName: String): String {
        val comma = dataUri.indexOf(',')
        if (comma < 0) return dataUri
        val payload = dataUri.substring(comma + 1)
        val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return dataUri

        val directory = File(context.filesDir, directoryName).apply { mkdirs() }
        val name = sha256Hex(dataUri) + ".jpg"
        val file = File(directory, name)
        if (!file.exists()) {
            runCatching { file.writeBytes(bytes) }.onFailure { return dataUri }
        }
        return "$directoryName/$name"
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            builder.append("%02x".format(byte))
        }
        return builder.toString()
    }
}
