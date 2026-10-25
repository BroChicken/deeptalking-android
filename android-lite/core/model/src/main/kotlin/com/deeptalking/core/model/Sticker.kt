package com.deeptalking.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A sticker stored as a local file reference.
 *
 * Two levels: a coarse [category] (navigation / prompt grouping, see
 * [StickerCategory]) and a free-form [keyword] the model writes when the sticker
 * is added (≤7 chars). Sending picks by keyword, not by a fixed vocabulary.
 *
 * The JSON key stays `tag` on purpose: old backups and old database rows carry
 * `"tag"`, and renaming the key would break the one-time import chain.
 */
@Serializable
data class Sticker(
    val id: String = "",
    /** 具体关键词（≤7 字，模型在入库时写），发送时按它选择。 */
    @SerialName("tag") val keyword: String = "",
    /** 大类 id（[StickerCategory]）；空 = 旧数据，按关键词推断。 */
    val category: String = "",
    val fileRef: String = "",
    /** `user` 用户上传/从对话里收的；`web` 联网抓的；空 = 旧数据。 */
    val source: String = "",
    /** 网图的来源页面，便于溯源和删除。 */
    val origin: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
