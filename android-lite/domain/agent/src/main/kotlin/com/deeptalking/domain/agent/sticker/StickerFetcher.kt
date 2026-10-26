package com.deeptalking.domain.agent.sticker

import com.deeptalking.core.model.Character

/** 抓回来、压缩过、已经写进角色表情库的一张表情。 */
data class FetchedSticker(
    val keyword: String,
    val category: String,
    val fileRef: String,
)

/**
 * 一次主题检索的结果。
 *
 * 失败也要带一句**能直接转述的人话**（[Failed.reason]）与卡住的环节（[Failed.step]：
 * `search` / `download` / `save`），模型照实转述、用户也能一眼看出是搜不到还是下载不下来。
 */
sealed interface StickerFetchResult {
    /** [total] 是写入后该角色的表情总数，模型据此才能如实回答“现在库里几张”。 */
    data class Stored(val stickers: List<FetchedSticker>, val total: Int) : StickerFetchResult

    data class Failed(val reason: String, val step: String = "fetch") : StickerFetchResult
}

/**
 * 主题检索：按一句人话联网找图，压缩后写进角色的表情库，返回它们的关键词。
 *
 * 模型在这一步**只负责起关键词和归大类**，不承担审核：图片质量由检索源和域名黑名单把关，
 * 视觉模型给不出关键词时也照样入库（关键词先借用检索词，用户可以在面板里改）。
 *
 * 下载/压缩/起名/落库都需要 Android 与仓库层，所以实现放在 `:app`。
 */
fun interface StickerFetcher {
    /**
     * @param count 这次最多入库几张；调用方按 `AppLimits.Sticker.FETCH_MAX_COUNT` 收敛过。
     */
    suspend fun fetch(character: Character, query: String, count: Int): StickerFetchResult
}
