package com.deeptalking.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
 *
 * [saveStickerBytes] / [saveStickerFromRef] / [persistImageFromRef] are the non-picker entries：
 * 联网抓到的图按字节进，对话里的图按已有引用进（“长按收进表情包”），工具产出的临时图按引用复制成
 * 持久对话图片（B 站帧）。
 */
class MediaStore(private val context: Context) {

    suspend fun saveImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        compressToFile(
            source = uri,
            directoryName = "images",
            maxDimension = AppLimits.Media.MAX_IMAGE_DIMENSION,
            maxBytes = AppLimits.Media.MAX_IMAGE_BYTES,
            qualities = QUALITIES,
        )
    }

    suspend fun saveSticker(uri: Uri): String? = withContext(Dispatchers.IO) {
        compressToFile(
            source = uri,
            directoryName = "stickers",
            maxDimension = AppLimits.Sticker.MAX_DIMENSION,
            maxBytes = AppLimits.Sticker.TARGET_BYTES,
            qualities = QUALITIES,
        )
    }

    /** 联网抓到的图：解码 → 压到表情规格 → 存盘。 */
    suspend fun saveStickerBytes(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        storeSticker(decodeBytes(bytes))
    }

    /**
     * 对话里已有的图片 → 复制成一张表情。
     *
     * 注意 [ref] 的形态：当前格式是 `images/<sha>.jpg` 这种**相对** key（相对 `filesDir`），
     * 老数据可能是绝对路径或 `file://`。这里统一解析——以前直接 `File(ref)` 会失败，
     * 表现就是长按收图永远提示“这张图存不下来”。
     */
    suspend fun saveStickerFromRef(ref: String): String? = withContext(Dispatchers.IO) {
        val file = resolveRef(ref) ?: return@withContext null
        storeSticker(decodeFile(file))
    }

    /**
     * 把工具生成的临时图片（B 站截图墙裁出来的帧）落成一条**持久**的对话图片，
     * 免得缓存目录被清掉后聊天记录里留下破图。已经是 `images/` key 时原样返回。
     */
    suspend fun persistImageFromRef(ref: String): String? = withContext(Dispatchers.IO) {
        if (ref.startsWith("images/", ignoreCase = true)) return@withContext ref
        val file = resolveRef(ref) ?: return@withContext null
        val directory = File(context.filesDir, "images").apply { mkdirs() }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        val out = File(directory, sha256Hex(bytes) + ".jpg")
        runCatching {
            if (!out.isFile) out.writeBytes(bytes)
            "images/${out.name}"
        }.getOrNull()
    }

    /** 相对 key（`images/x.jpg`）→ `filesDir`；绝对路径/`file://` 原样。 */
    private fun resolveRef(ref: String): File? = when {
        ref.startsWith("file:", ignoreCase = true) ->
            runCatching { Uri.parse(ref).path }.getOrNull()?.let(::File)
        File(ref).isAbsolute -> File(ref)
        else -> File(context.filesDir, ref)
    }?.takeIf { it.isFile }

    private fun decodeBytes(bytes: ByteArray): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    /** 大图走 decodeFile 直接按文件解码，避免把整张原图读成 byte[] 再解一次。 */
    private fun decodeFile(file: File): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    /** Sticker variant of [compressToFile] that starts from an already decoded bitmap. */
    private fun storeSticker(original: Bitmap?): String? {
        if (original == null) return null
        val scaled = scaleDown(original, AppLimits.Sticker.MAX_DIMENSION)
        val flattened = flattenOnWhite(scaled)
        var bytes = encodeJpeg(flattened, QUALITIES.first())
        for (quality in QUALITIES.drop(1)) {
            if (bytes.size <= AppLimits.Sticker.TARGET_BYTES) break
            bytes = encodeJpeg(flattened, quality)
        }
        flattened.recycle()
        if (scaled !== original) {
            scaled.recycle()
            original.recycle()
        }
        val directory = File(context.filesDir, "stickers").apply { mkdirs() }
        val file = File(directory, sha256Hex(bytes) + ".jpg")
        return runCatching {
            file.writeBytes(bytes)
            "stickers/${file.name}"
        }.getOrNull()
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
        val flattened = flattenOnWhite(scaled)
        var bytes = encodeJpeg(flattened, qualities.first())
        for (quality in qualities.drop(1)) {
            if (bytes.size <= maxBytes) break
            bytes = encodeJpeg(flattened, quality)
        }
        flattened.recycle()
        if (scaled !== original) {
            scaled.recycle()
            original.recycle()
        }

        val directory = File(context.filesDir, directoryName).apply { mkdirs() }
        val file = File(directory, sha256Hex(bytes) + ".jpg")
        return runCatching {
            file.writeBytes(bytes)
            // Relative key (survives reinstall/cross-device restore); `MediaRef` resolves it.
            "$directoryName/${file.name}"
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

    /** Legacy `ctx.fillStyle = '#ffffff'; fillRect(...)` before drawing the scaled image. */
    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return out
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

    companion object {
        /** Legacy quality ramp: 0.82, then −0.12 while > 0.4 (`images.js:39-44`). */
        private val QUALITIES = intArrayOf(82, 70, 58, 46, 34)
    }
}
