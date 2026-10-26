package com.deeptalking.domain.agent.sticker

import com.deeptalking.core.model.Character

/** 抓回来、压缩过、已经写进角色表情库的一张表情。 */
data class FetchedSticker(
    val keyword: String,
    val category: String,
    val fileRef: String,
    /** 写入后该角色的表情总数，模型据此才能如实回答“现在库里几张”。 */
    val total: Int = 0,
)

/**
 * 一次主题检索的结果。
 *
 * 失败也要带一句**能直接转述的人话**（[Failed.reason]）：以前这里只有 `null`，
 * 模型只能猜“我看到了但加不进去”，用户也拿不到任何线索。
 */
sealed interface StickerFetchResult {
    data class Stored(val sticker: FetchedSticker) : StickerFetchResult
    data class Failed(val reason: String) : StickerFetchResult
}

/**
 * 主题检索：按一句人话联网找一张表情，压缩后写进角色的表情库，返回它的关键词。
 *
 * 模型在这一步**只负责起关键词和归大类**，不承担审核：图片质量由检索源和域名黑名单把关，
 * 视觉模型给不出关键词时也照样入库（关键词先借用检索词，用户可以在面板里改）。
 *
 * 下载/压缩/起名/落库都需要 Android 与仓库层，所以实现放在 `:app`。
 */
fun interface StickerFetcher {
    suspend fun fetch(character: Character, query: String): StickerFetchResult
}
