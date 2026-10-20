package com.deeptalking.core.data

import com.deeptalking.core.database.CharacterDao
import com.deeptalking.core.database.CharacterEntity
import com.deeptalking.core.model.Character
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class CharacterRepository(
    private val dao: CharacterDao,
    /**
     * Invoked once per distinct corrupt row payload so the original JSON can be
     * preserved for recovery (`/persistence.js` keeps the raw string in
     * `STORAGE_RECOVERY_KEY`). Never receives secrets: keys live in
     * `:core:security`.
     */
    private val onCorruptRow: ((id: String, payload: String) -> Unit)? = null,
) {

    private val reportedCorrupt = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * One lock per character id. Every read-modify-write of a row goes through [mutate], so
     * two writers (the chat turn, a background memory pass, the editor) cannot read the same
     * row and silently drop each other's change. Locks are cheap and bounded by the number of
     * characters, so they are kept for the process lifetime (removing one while another
     * waiter holds it would let a third writer create a second lock for the same id).
     */
    private val writeLocks = ConcurrentHashMap<String, Mutex>()

    fun observeAll(): Flow<List<Character>> =
        dao.observeAll().map { rows -> rows.mapNotNull(::decode) }

    /** One-shot snapshot of every character (used by startup maintenance). */
    suspend fun all(): List<Character> = dao.observeAll().first().mapNotNull(::decode)

    suspend fun get(id: String): Character? = dao.get(id)?.let(::decode)

    suspend fun upsert(character: Character): Character {
        val now = Instant.now().toString()
        val stamped = character.copy(updatedAt = now)
        dao.upsert(
            CharacterEntity(
                id = stamped.id,
                updatedAt = now,
                payload = AppJson.encodeToString(stamped),
            )
        )
        return stamped
    }

    /**
     * Read-modify-write of one row under a **per-id** lock: [transform] receives the freshest
     * stored character, never a caller-held snapshot, so two writers cannot overwrite each
     * other. Returns the written character, or null when [id] no longer exists.
     *
     * This is the write entry point for turns, background passes and the card editor.
     * [upsert] stays for creating/importing whole rows.
     */
    suspend fun mutate(id: String, transform: (Character) -> Character): Character? {
        val lock = writeLocks.computeIfAbsent(id) { Mutex() }
        return lock.withLock {
            val live = get(id) ?: return@withLock null
            upsert(transform(live))
        }
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
        runCatching {
            // 旧行里的「价值观」「关键过往」在读取时并入性格/背景（见 RetiredStaticFields）。
            AppJson.decodeFromString<Character>(RetiredStaticFields.foldPayload(entity.payload))
        }.getOrNull()
            ?: run {
                val reporter = onCorruptRow
                if (reporter != null && reportedCorrupt.add((entity.id + "\u0000" + entity.payload).hashCode())) {
                    runCatching { reporter.invoke(entity.id, entity.payload) }
                }
                null
            }
}
