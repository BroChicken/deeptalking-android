package com.deeptalking.core.data.legacy

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.deeptalking.core.common.AppLimits
import java.io.File

/**
 * Resolves a stored media reference to something Coil can load, and reads it
 * back out as a base64 data URI for backup export.
 *
 * Accepted reference forms (ordered):
 * - `data:image/...;base64,...` (legacy inline payload)
 * - `http(s)://...` (remote markdown image)
 * - `content://` / `file://` (SAF / absolute URI)
 * - absolute filesystem path (pre-Phase-B native data)
 * - relative key `images/<sha>.jpg` / `stickers/<sha>.jpg` (current format)
 */
object MediaRef {

    fun model(context: Context, ref: String): Any = when {
        ref.isBlank() -> ref
        ref.startsWith("data:", ignoreCase = true) -> ref
        ref.startsWith("http", ignoreCase = true) -> ref
        ref.startsWith("content:", ignoreCase = true) || ref.startsWith("file:", ignoreCase = true) -> Uri.parse(ref)
        File(ref).isAbsolute -> File(ref)
        else -> File(context.filesDir, ref)
    }

    fun fileFor(context: Context, ref: String): File? = when {
        ref.isBlank() -> null
        ref.startsWith("data:", ignoreCase = true) -> null
        ref.startsWith("http", ignoreCase = true) -> null
        ref.startsWith("content:", ignoreCase = true) || ref.startsWith("file:", ignoreCase = true) ->
            runCatching { Uri.parse(ref).path?.let(::File) }.getOrNull()
        File(ref).isAbsolute -> File(ref)
        else -> File(context.filesDir, ref)
    }

    /** Reads a local media file back into a base64 `data:` URI (for backup export). */
    fun toDataUri(context: Context, ref: String): String? {
        if (ref.startsWith("data:", ignoreCase = true)) return ref
        val file = fileFor(context, ref) ?: return null
        if (!file.exists()) return null
        val mime = when {
            ref.endsWith(".png", ignoreCase = true) -> "image/png"
            ref.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> "image/jpeg"
        }
        return runCatching {
            "data:$mime;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }

    /** [MediaSource] implementation bound to an application context. */
    fun source(context: Context): MediaSource = object : MediaSource {
        override fun toDataUri(ref: String): String? = toDataUri(context, ref)
        override fun toThumbnailDataUri(ref: String): String? = toThumbnailDataUri(context, ref)
    }

    /**
     * Downscaled JPEG preview for backup export ([AppLimits.Media.EXPORT_PREVIEW_*]).
     * Falls back to the full payload when the bytes cannot be decoded, so an image is
     * never silently dropped from a backup.
     */
    fun toThumbnailDataUri(
        context: Context,
        ref: String,
        maxDimension: Int = AppLimits.Media.THUMBNAIL_DIMENSION,
        quality: Int = AppLimits.Media.THUMBNAIL_QUALITY,
    ): String? {
        if (ref.startsWith("data:", ignoreCase = true)) return ref
        val file = fileFor(context, ref) ?: return toDataUri(context, ref)
        if (!file.exists()) return null
        val encoded = runCatching {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
            val longest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longest > maxDimension) {
                val ratio = maxDimension.toFloat() / longest
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
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            stream.toByteArray()
        }.getOrNull() ?: return toDataUri(context, ref)
        return "data:image/jpeg;base64," + Base64.encodeToString(encoded, Base64.NO_WRAP)
    }
}
