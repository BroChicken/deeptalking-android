package com.deeptalking.lite

import com.deeptalking.domain.agent.video.VideoSummaryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频总结的本地缓存：每个 BV 号一个 JSON，默认 7 天。
 *
 * 为什么用文件而不是 Room 表：内容里既有 JSON 又有抽帧图片（`<bvid>-f0.jpg`…），文件天然匹配；
 * 而且当前 Room 库还是 version 1、没有任何迁移历史，为一张缓存表升库并手写 Migration 的风险
 * （列类型/可空性对不上，老用户装完直接崩）远大于收益——缓存本来就允许丢。
 *
 * 只存"这条视频被总结过什么"：命中后不重新抓字幕、不重新调模型。字幕与截图都是站内内容，
 * 只留在本机 cache 目录，不进数据库、不进备份导出。
 */
class BiliVideoCache(
    private val root: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : VideoSummaryCache {

    override suspend fun read(bvid: String): String? = withContext(Dispatchers.IO) {
        val file = jsonFile(bvid) ?: return@withContext null
        if (!file.exists()) return@withContext null
        val age = clock() - file.lastModified()
        // 只判"太旧"：mtime 在未来（时钟被往回拨、或刚写完的几毫秒）不该当成过期。
        if (age > ttlMs) {
            drop(bvid)
            return@withContext null
        }
        runCatching { file.readText() }.getOrNull()
    }

    override suspend fun write(bvid: String, payload: String): Unit = withContext(Dispatchers.IO) {
        val file = jsonFile(bvid) ?: return@withContext
        runCatching {
            root.mkdirs()
            file.writeText(payload)
            pruneExpired()
        }
    }

    /** 删掉所有过期条目（连同它们的抽帧图片）。写入时顺手调用，不需要额外定时任务。 */
    fun pruneExpired(): Int {
        if (!root.isDirectory) return 0
        var removed = 0
        val jsonFiles = root.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        val live = mutableSetOf<String>()
        jsonFiles.forEach { file ->
            val age = clock() - file.lastModified()
            if (age > ttlMs) {
                val bvid = file.name.removeSuffix(".json")
                drop(bvid)
                removed++
            } else {
                live += file.name.removeSuffix(".json")
            }
        }
        // 抽帧图片跟着 JSON 走：JSON 没了，孤立的 jpg 一起清掉。
        root.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }.orEmpty().forEach { frame ->
            val owner = frame.name.substringBefore("-f")
            if (owner !in live) runCatching { frame.delete() }
        }
        return removed
    }

    fun clear() {
        if (!root.isDirectory) return
        root.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** 当前缓存占用的字节数（调试用）。 */
    fun sizeBytes(): Long =
        if (!root.isDirectory) 0L else root.listFiles().orEmpty().sumOf { it.length() }

    private fun drop(bvid: String): Unit {
        jsonFile(bvid)?.let { runCatching { it.delete() } }
        val key = safeKey(bvid) ?: return
        root.listFiles { f -> f.isFile && f.name.startsWith("$key-") }.orEmpty()
            .forEach { runCatching { it.delete() } }
    }

    private fun jsonFile(bvid: String): File? {
        val key = safeKey(bvid) ?: return null
        return File(root, "$key.json")
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** BV 号只含字母数字；顺带挡掉路径穿越。 */
        fun safeKey(bvid: String): String? =
            bvid.trim().takeIf { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() } }
    }
}
