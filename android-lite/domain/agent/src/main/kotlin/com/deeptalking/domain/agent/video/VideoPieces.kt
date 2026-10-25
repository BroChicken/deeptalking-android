package com.deeptalking.domain.agent.video

import com.deeptalking.core.network.BiliStoryboard
import com.deeptalking.core.network.BiliVideoText

/**
 * 视频数据的本地缓存（默认 7 天，由实现决定 TTL）。键是 BV 号，值就是工具结果的 JSON 原文。
 *
 * 有缓存以后，"这个视频讲了啥"第二次问就是零成本：不重新抓字幕、不重新调模型。字幕与截图
 * 属于站内内容，只留在本机缓存目录，不进备份导出。
 */
interface VideoSummaryCache {
    /** 未过期的缓存结果；没有或已过期返回 null（实现负责顺便清理过期项）。 */
    suspend fun read(bvid: String): String?

    /** 存一次工具结果。实现负责按 TTL 清理旧项，失败不该影响主流程。 */
    suspend fun write(bvid: String, payload: String)
}

/**
 * 抽帧：从 B 站截图墙（`x/player/videoshot`）里裁出几帧，写成本地文件并返回引用。
 *
 * 放在这个接口后面是因为裁图要用 Android 的 Bitmap 解码，而 `:domain:agent` 是纯 JVM 模块；
 * 测试里给个返回固定引用的替身即可。实现失败（没有截图墙、下载超时、内存不足）应返回空表，
 * 让总结退化为纯文字，而不是让整次调用失败。
 */
fun interface VideoFrameProvider {
    /** [storyboard] 为 null 表示没有可用截图墙；返回空表表示"这次没有画面可用"。 */
    suspend fun frames(text: BiliVideoText, storyboard: BiliStoryboard?): List<String>
}
