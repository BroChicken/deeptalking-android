package com.deeptalking.core.data.legacy

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Decodes legacy `data:image/...;base64,...` sticker payloads into
 * `filesDir/stickers/<sha256>.jpg` and returns the absolute file path.
 *
 * The hash is taken over the data URI so repeated identical stickers map to the
 * same file. Decoding failures fall back to the original URI so a sticker is
 * never silently dropped.
 */
class FileStickerSink(private val context: Context) : StickerSink {

    override fun refFor(dataUri: String): String {
        val comma = dataUri.indexOf(',')
        if (comma < 0) return dataUri
        val payload = dataUri.substring(comma + 1)
        val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return dataUri

        val directory = File(context.filesDir, "stickers").apply { mkdirs() }
        val file = File(directory, sha256Hex(dataUri) + ".jpg")
        if (!file.exists()) {
            runCatching { file.writeBytes(bytes) }.onFailure { return dataUri }
        }
        return file.absolutePath
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
