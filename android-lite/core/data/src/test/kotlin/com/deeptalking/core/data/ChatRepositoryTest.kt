package com.deeptalking.core.data

import com.deeptalking.core.database.MessageDao
import com.deeptalking.core.database.MessageEntity
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeMessageDao : MessageDao {
    val rows = LinkedHashMap<String, MessageEntity>()

    override fun observeForCharacter(characterId: String): Flow<List<MessageEntity>> =
        flowOf(rows.values.filter { it.characterId == characterId })

    override suspend fun getForCharacter(characterId: String): List<MessageEntity> =
        rows.values.filter { it.characterId == characterId }

    override suspend fun upsert(entity: MessageEntity) {
        rows[entity.id] = entity
    }

    override suspend fun upsertAll(entities: List<MessageEntity>) {
        entities.forEach { rows[it.id] = it }
    }

    override suspend fun deleteForCharacter(characterId: String) {
        rows.entries.removeAll { it.value.characterId == characterId }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

class ChatRepositoryTest {

    @Test
    fun markExtractedPersistsBackToRoom() = runBlocking {
        val dao = FakeMessageDao()
        val repository = ChatRepository(dao)
        val message = ChatMessage(id = "m1", role = Role.User, content = "你好", timestamp = "2026-08-01T12:00:00Z")
        repository.append("c1", message)
        assertNull(repository.getMessages("c1").single().extractedAt)

        repository.markExtracted("c1", listOf(message.copy(extractedAt = "2026-08-01T12:05:00Z")))

        assertEquals("2026-08-01T12:05:00Z", repository.getMessages("c1").single().extractedAt)
    }

    @Test
    fun markExtractedIgnoresMessagesNotYetPersisted() = runBlocking {
        val dao = FakeMessageDao()
        val repository = ChatRepository(dao)
        repository.append("c1", ChatMessage(id = "m1", role = Role.User, content = "你好"))
        repository.markExtracted(
            "c1",
            listOf(ChatMessage(id = "synth", role = Role.Assistant, content = "回复", extractedAt = "done")),
        )
        assertEquals(1, repository.getMessages("c1").size)
        assertNull(repository.getMessages("c1").single().extractedAt)
    }
}
