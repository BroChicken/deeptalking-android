package com.deeptalking.domain.agent.sticker

import com.deeptalking.core.model.Character

/** 抓回来、压缩过、已经写进角色表情库的一张表情。 */
data class FetchedSticker(
    val keyword: String,
    val category: String,
    val fileRef: String,
)

/**
 * 主题检索：按一句人话联网找一张表情，压缩后写进角色的表情库，返回它的关键词。
 *
 * 返回 `null` 表示这次没找到（库满、检索为空、下载失败、视觉审核没过），
 * 工具据此回退成“报可用关键词”，不打断对话。
 *
 * 下载/压缩/审图/落库都需要 Android 与仓库层，所以实现放在 `:app`。
 */
fun interface StickerFetcher {
    suspend fun fetch(character: Character, query: String): FetchedSticker?
}
