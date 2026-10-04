package com.deeptalking.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.deeptalking.core.common.AppLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists user-picked images and stickers under `filesDir`, mirroring the
 * legacy frontend's compress-then-store flow. Images are downscaled/compressed
 * to [AppLimits.Media]; stickers to [AppLimits.Sticker]. Returns the absolute
 * file path, which is what [com.deeptalking.core.model.MessageAttachment.uri]
 * holds for [com.deeptalking.core.model.MessageAttachment.Kind.Image]/Sticker.
 */
class MediaStore(private val context: Context) {

    suspend fun saveImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        compressToFile(
            source = uri,
            directoryName = "images",
            maxDimension = AppLimits.Media.MAX_IMAGE_DIMENSION,
            maxBytes = AppLimits.Media.MAX_IMAGE_BYTES,
            qualities = intArrayOf(82, 72, 62, 52, 44),
        )
    }

    suspend fun saveSticker(uri: Uri): String? = withContext(Dispatchers.IO) {
        compressToFile(
            source = uri,
            directoryName = "stickers",
            maxDimension = AppLimits.Sticker.MAX_DIMENSION,
            maxBytes = AppLimits.Sticker.TARGET_BYTES,
            qualities = intArrayOf(80, 70, 60, 50, 40),
        )
    }

    private fun compressToFile(
        source: Uri,
        directoryName: String,
        maxDimension: Int,
        maxBytes: Int,
        qualities: IntArray,
    ): String? {
        val original = runCatching {
            context.contentResolver.openInputStream(source)?.use(BitmapFactory::decodeStream)
        }.getOrNull() ?: return null

        val scaled = scaleDown(original, maxDimension)
        var bytes = encodeJpeg(scaled, qualities.first())
        for (quality in qualities.drop(1)) {
            if (bytes.size <= maxBytes) break
            bytes = encodeJpeg(scaled, quality)
        }
        if (scaled !== original) original.recycle()

        val directory = File(context.filesDir, directoryName).apply { mkdirs() }
        val file = File(directory, sha256Hex(bytes) + ".jpg")
        return runCatching {
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) { for (b in digest) append("%02x".format(b)) }
    }
}
