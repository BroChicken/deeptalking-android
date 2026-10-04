package com.deeptalking.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Character / group row. [payload] is the kotlinx-serialization JSON of
 * `com.deeptalking.core.model.Character`; [updatedAt] is duplicated as a
 * column so ordered queries do not have to parse the payload.
 */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val updatedAt: String,
    val payload: String,
)

/** One conversation message. [payload] is the JSON of `ChatMessage`. */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["characterId"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    val timestamp: String,
    val payload: String,
)

/** Single-row table holding the JSON of `AppConfig` (always id = 0). */
@Entity(tableName = "config")
data class ConfigEntity(
    @PrimaryKey val id: Int = 0,
    val payload: String,
)
