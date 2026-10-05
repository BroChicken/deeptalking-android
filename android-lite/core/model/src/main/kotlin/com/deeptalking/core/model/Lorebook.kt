package com.deeptalking.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LorebookOrigin {
    @SerialName("user") User,
    // Legacy JS stores model-authored entries with origin "ai" (see lorebook.js).
    @SerialName("ai") Model,
}

@Serializable
data class LorebookEntry(
    val id: String = "",
    val name: String = "",
    val content: String = "",
    val keywords: List<String> = emptyList(),
    val enabled: Boolean = true,
    val alwaysActive: Boolean = false,
    val origin: LorebookOrigin = LorebookOrigin.Model,
    val memberName: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Times this entry was injected into context (legacy `mentions`). */
    val mentions: Int = 0,
    /** When the entry was last injected (legacy `lastMentionedAt`). */
    val lastMentionedAt: String? = null,
    /** Consecutive consolidation passes without a hit (legacy `misses`). */
    val misses: Int = 0,
    /** Sort priority for injection order (legacy `order`, default 100). */
    val order: Int = 100,
)
