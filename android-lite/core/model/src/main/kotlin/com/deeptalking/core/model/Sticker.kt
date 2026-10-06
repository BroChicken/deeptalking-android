package com.deeptalking.core.model

import kotlinx.serialization.Serializable

/**
 * A sticker stored as a local file reference. Legacy data URI payloads are
 * migrated to files during the one-time import (see :core:data).
 */
@Serializable
data class Sticker(
    val id: String = "",
    val tag: String = "",
    val fileRef: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
