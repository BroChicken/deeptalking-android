package com.deeptalking.core.data.legacy

import android.content.Context
import com.deeptalking.core.common.AppLimits
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

    /**
     * Imported message images are re-encoded small: they exist only so the restored chat
     * is readable, and a backup can carry full-resolution payloads. Stickers keep their
     * (already small) bytes as-is.
     */
    override fun imageRefFor(dataUri: String): String {
        val bytes = decode(dataUri) ?: return dataUri
        return store(dataUri, compressForArchive(bytes) ?: bytes, "images")
    }

    private fun store(dataUri: String, directoryName: String): String {
        val bytes = decode(dataUri) ?: return dataUri
        return store(dataUri, bytes, directoryName)
    }

    private fun decode(dataUri: String): ByteArray? {
        val comma = dataUri.indexOf(',')
        if (comma < 0) return null
        return runCatching { Base64.getDecoder().decode(dataUri.substring(comma + 1)) }.getOrNull()
    }

    private fun store(dataUri: String, bytes: ByteArray, directoryName: String): String {
        val directory = File(context.filesDir, directoryName).apply { mkdirs() }
        val name = sha256Hex(dataUri) + ".jpg"
        val file = File(directory, name)
        if (!file.exists()) {
            runCatching { file.writeBytes(bytes) }.onFailure { return dataUri }
        }
        return "$directoryName/$name"
    }

    /** Re-encodes an imported image to a small JPEG; null when it cannot be decoded. */
    private fun compressForArchive(bytes: ByteArray): ByteArray? = runCatching {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return@runCatching null
        val limit = AppLimits.Media.IMPORTED_IMAGE_DIMENSION
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > limit) {
            val ratio = limit.toFloat() / longest
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val stream = java.io.ByteArrayOutputStream()
        scaled.compress(
            android.graphics.Bitmap.CompressFormat.JPEG,
            AppLimits.Media.IMPORTED_IMAGE_QUALITY,
            stream,
        )
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        stream.toByteArray()
    }.getOrNull()

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            builder.append("%02x".format(byte))
        }
        return builder.toString()
    }
}
