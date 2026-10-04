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
    val misses: Int = 0,
)
