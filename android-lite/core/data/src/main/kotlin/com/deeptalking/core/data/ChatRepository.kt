package com.deeptalking.core.data

import com.deeptalking.core.common.AppLimits
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
        // Legacy `ensureMessageSequences`: every persisted message gets a monotonic
        // per-conversation sequence (1-based), derived from the stored high-water mark
        // rather than the row count. The archive cap deletes old rows, and a row-count
        // sequence would then reuse numbers, collapsing distinct messages onto one id.
        val sequence = if (message.sequence > 0) message.sequence else nextSequence(characterId)
        dao.upsert(toEntity(characterId, message.copy(sequence = sequence)))
        // Archive cap: the chat is a bounded FIFO kept only for looking back. The
        // memory window lives on the character and is never derived from this table.
        dao.deleteOldestBeyond(characterId, AppLimits.History.ARCHIVE_MESSAGES)
    }

    /** Next monotonic sequence for a conversation, from the decoded high-water mark. */
    private suspend fun nextSequence(characterId: String): Int =
        dao.getForCharacter(characterId).mapNotNull(::decode).maxOfOrNull { it.sequence }?.plus(1) ?: 1

    suspend fun replace(characterId: String, messages: List<ChatMessage>) {
        dao.replaceForCharacter(characterId, messages.map { toEntity(characterId, it) })
    }

    suspend fun clear(characterId: String) = dao.deleteForCharacter(characterId)

    suspend fun clearAll() = dao.deleteAll()

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
