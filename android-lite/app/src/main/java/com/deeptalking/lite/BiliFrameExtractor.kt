package com.deeptalking.lite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.deeptalking.core.network.BiliStoryboard
import com.deeptalking.core.network.BiliVideoText
import com.deeptalking.core.network.pickStoryboardFrames
import com.deeptalking.domain.agent.video.VideoFrameProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 从 B 站的「截图墙」（`x/player/videoshot`，免登录）里抽几帧给视觉模型看。
 *
 * 不下载视频流：一个视频的截图墙是 2~4 张 10×10 的拼图（每张约 0.5~1MB），用
 * [BitmapRegionDecoder] 只解出目标区域，内存里只有 480×270 的一小块——比"下视频再解码"
 * 便宜两个数量级，也不用 ffmpeg。
 *
 * 失败一律降级为空表（总结退化成纯文字），不让抽帧把整次调用拖垮。
 */
class BiliFrameExtractor(
    private val client: OkHttpClient,
    private val root: File,
    private val frameCount: Int = DEFAULT_FRAME_COUNT,
) : VideoFrameProvider {

    override suspend fun frames(text: BiliVideoText, storyboard: BiliStoryboard?): List<String> =
        withContext(Dispatchers.IO) {
            if (storyboard == null || storyboard.frameCount == 0) return@withContext emptyList()
            val picked = pickStoryboardFrames(storyboard, frameCount)
            if (picked.isEmpty()) return@withContext emptyList()
            runCatching { root.mkdirs() }

            val saved = mutableListOf<String>()
            // 同一张拼图里的帧只下载一次。
            picked.groupBy { frame -> storyboard.locationOf(frame)?.imageIndex ?: -1 }
                .filterKeys { it >= 0 }
                .toSortedMap()
                .forEach { (imageIndex, framesInImage) ->
                    val spriteUrl = storyboard.images.getOrNull(imageIndex) ?: return@forEach
                    val sprite = download(spriteUrl, referer = VIDEO_REFERER + text.bvid) ?: return@forEach
                    val decoder = runCatching {
                        BitmapRegionDecoder.newInstance(sprite, 0, sprite.size, false)
                    }.getOrNull() ?: return@forEach
                    try {
                        framesInImage.forEach { frame ->
                            val file = cropFrame(decoder, storyboard, frame, text.bvid) ?: return@forEach
                            saved += file.absolutePath
                        }
                    } finally {
                        runCatching { decoder.recycle() }
                    }
                }
            saved
        }

    private fun cropFrame(
        decoder: BitmapRegionDecoder,
        storyboard: BiliStoryboard,
        frame: Int,
        bvid: String,
    ): File? {
        val location = storyboard.locationOf(frame) ?: return null
        val left = location.column * storyboard.frameWidth
        val top = location.row * storyboard.frameHeight
        val rect = Rect(left, top, left + storyboard.frameWidth, top + storyboard.frameHeight)
        val region = runCatching {
            decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = 1 })
        }.getOrNull() ?: return null
        val scaled = if (region.width > MAX_FRAME_WIDTH) {
            val height = (region.height.toLong() * MAX_FRAME_WIDTH / region.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(region, MAX_FRAME_WIDTH, height, true).also { region.recycle() }
        } else {
            region
        }
        val bytes = runCatching {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }.getOrNull()
        scaled.recycle()
        if (bytes == null || bytes.isEmpty()) return null
        val file = File(root, "$bvid-f$frame.jpg")
        return runCatching {
            file.writeBytes(bytes)
            file
        }.getOrNull()
    }

    private fun download(url: String, referer: String): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        client.newBuilder().callTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_SPRITE_BYTES) return null
                body.bytes()
            }
    }.getOrNull()

    companion object {
        /** 6 帧够覆盖开场/中段/结尾的画面变化，再多就是白花钱。 */
        const val DEFAULT_FRAME_COUNT = 6
        private const val MAX_FRAME_WIDTH = 640
        private const val JPEG_QUALITY = 82
        private const val MAX_SPRITE_BYTES = 8L * 1024 * 1024
        private const val DOWNLOAD_TIMEOUT_SECONDS = 20L
        private const val VIDEO_REFERER = "https://www.bilibili.com/video/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
