package com.deeptalking.core.data

import com.deeptalking.core.database.MessageDao
import com.deeptalking.core.database.MessageEntity
import com.deeptalking.core.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.UUID

class ChatRepository(private val dao: MessageDao) {

    fun observeMessages(characterId: String): Flow<List<ChatMessage>> =
        dao.observeForCharacter(characterId).map { rows -> rows.mapNotNull(::decode) }

    suspend fun getMessages(characterId: String): List<ChatMessage> =
        dao.getForCharacter(characterId).mapNotNull(::decode)

    suspend fun append(characterId: String, message: ChatMessage) {
        dao.upsert(toEntity(characterId, message))
    }

    suspend fun replace(characterId: String, messages: List<ChatMessage>) {
        dao.replaceForCharacter(characterId, messages.map { toEntity(characterId, it) })
    }

    suspend fun clear(characterId: String) = dao.deleteForCharacter(characterId)

    private fun toEntity(characterId: String, message: ChatMessage): MessageEntity {
        val timestamp = message.timestamp ?: Instant.now().toString()
        val id = message.id.ifBlank { UUID.randomUUID().toString() }
        val normalized = message.copy(id = id, timestamp = timestamp)
        return MessageEntity(
            id = id,
            characterId = characterId,
            timestamp = timestamp,
            payload = AppJson.encodeToString(normalized),
        )
    }

    private fun decode(entity: MessageEntity): ChatMessage? =
        runCatching { AppJson.decodeFromString<ChatMessage>(entity.payload) }.getOrNull()
}
