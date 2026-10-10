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
        // Legacy `ensureMessageSequences`: every persisted message gets a monotonic
        // per-conversation sequence (1-based).
        val sequence = if (message.sequence > 0) message.sequence else dao.getForCharacter(characterId).size + 1
        dao.upsert(toEntity(characterId, message.copy(sequence = sequence)))
    }

    suspend fun replace(characterId: String, messages: List<ChatMessage>) {
        dao.replaceForCharacter(characterId, messages.map { toEntity(characterId, it) })
    }

    suspend fun clear(characterId: String) = dao.deleteForCharacter(characterId)

    suspend fun clearAll() = dao.deleteAll()

    /**
     * Copies the memory-task `extractedAt` marks from the live [instant] window back
     * onto the persisted chat rows. The live window is rebuilt from Room every turn
     * (`windowInstant`), so without this write-back the marks would be lost and
     * extraction would re-process the same messages forever. Only rows that already
     * exist are touched; synthesized in-window messages are ignored.
     */
    suspend fun markExtracted(characterId: String, instant: List<ChatMessage>) {
        val marked = instant.filter { it.extractedAt != null }.associateBy { it.id }
        if (marked.isEmpty()) return
        val updates = dao.getForCharacter(characterId).mapNotNull { row ->
            val message = decode(row) ?: return@mapNotNull null
            val source = marked[message.id] ?: return@mapNotNull null
            if (message.extractedAt == source.extractedAt) return@mapNotNull null
            row.copy(payload = AppJson.encodeToString(message.copy(extractedAt = source.extractedAt)))
        }
        if (updates.isNotEmpty()) dao.upsertAll(updates)
    }

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
