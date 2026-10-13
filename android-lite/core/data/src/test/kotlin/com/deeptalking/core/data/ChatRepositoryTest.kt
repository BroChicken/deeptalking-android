package com.deeptalking.core.data

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.database.MessageDao
import com.deeptalking.core.database.MessageEntity
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    override suspend fun deleteOldestBeyond(characterId: String, keep: Int) {
        rows.values.filter { it.characterId == characterId }
            .sortedBy { it.timestamp }
            .dropLast(keep)
            .forEach { rows.remove(it.id) }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

class ChatRepositoryTest {

    @Test
    fun appendCapsTheArchiveAndKeepsSequencesMonotonic() = runBlocking {
        val dao = FakeMessageDao()
        val repository = ChatRepository(dao)
        val cap = AppLimits.History.ARCHIVE_MESSAGES
        repeat(cap + 5) { index ->
            val stamp = "2026-08-01T00:" + (index / 60).toString().padStart(2, '0') + ":" +
                (index % 60).toString().padStart(2, '0') + "Z"
            repository.append(
                "c1",
                ChatMessage(id = "m$index", role = Role.User, content = "消息$index", timestamp = stamp),
            )
        }
        val stored = repository.getMessages("c1")
        // The archive is a bounded FIFO: only the newest `cap` rows survive.
        assertEquals(cap, stored.size)
        assertTrue(stored.none { it.id == "m0" })
        assertTrue(stored.any { it.id == "m${cap + 4}" })
        // Sequence numbers stay monotonic and unique even though old rows were deleted
        // (a row-count-derived sequence would reuse them).
        val sequences = stored.map { it.sequence }
        assertEquals(sequences.size, sequences.distinct().size)
        assertEquals(sequences.sorted(), sequences)
    }
}
