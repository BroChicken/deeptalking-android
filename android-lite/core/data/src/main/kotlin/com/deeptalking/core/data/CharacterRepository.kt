package com.deeptalking.core.data

import com.deeptalking.core.database.CharacterDao
import com.deeptalking.core.database.CharacterEntity
import com.deeptalking.core.model.Character
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant

class CharacterRepository(private val dao: CharacterDao) {

    fun observeAll(): Flow<List<Character>> =
        dao.observeAll().map { rows -> rows.mapNotNull(::decode) }

    /** One-shot snapshot of every character (used by startup maintenance). */
    suspend fun all(): List<Character> = dao.observeAll().first().mapNotNull(::decode)

    suspend fun get(id: String): Character? = dao.get(id)?.let(::decode)

    suspend fun upsert(character: Character) {
        val now = Instant.now().toString()
        val stamped = character.copy(updatedAt = now)
        dao.upsert(
            CharacterEntity(
                id = stamped.id,
                updatedAt = now,
                payload = AppJson.encodeToString(stamped),
            )
        )
    }

    suspend fun replaceAll(characters: List<Character>) {
        val now = Instant.now().toString()
        val entities = characters.map { character ->
            val stamped = character.copy(updatedAt = now)
            CharacterEntity(
                id = stamped.id,
                updatedAt = now,
                payload = AppJson.encodeToString(stamped),
            )
        }
        dao.replaceAll(entities)
    }

    suspend fun delete(id: String) = dao.delete(id)

    private fun decode(entity: CharacterEntity): Character? =
        runCatching { AppJson.decodeFromString<Character>(entity.payload) }.getOrNull()
}
