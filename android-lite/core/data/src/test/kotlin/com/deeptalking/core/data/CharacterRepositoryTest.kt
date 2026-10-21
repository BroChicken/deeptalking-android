package com.deeptalking.core.data

import com.deeptalking.core.database.CharacterDao
import com.deeptalking.core.database.CharacterEntity
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.mergeCharacterChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * In-memory DAO with a suspension point on every read and write, so a missing per-id lock
 * would visibly lose concurrent updates instead of passing by luck on a single thread.
 */
private class FakeCharacterDao : CharacterDao {
    val rows = LinkedHashMap<String, CharacterEntity>()

    override fun observeAll(): Flow<List<CharacterEntity>> = flowOf(rows.values.toList())

    override suspend fun get(id: String): CharacterEntity? {
        delay(1)
        return rows[id]
    }

    override suspend fun upsert(entity: CharacterEntity) {
        delay(1)
        rows[entity.id] = entity
    }

    override suspend fun upsertAll(entities: List<CharacterEntity>) {
        entities.forEach { rows[it.id] = it }
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun count(): Int = rows.size
}

class CharacterRepositoryTest {

    @Test
    fun mutateAppliesTransformToTheLiveRowAndReturnsTheWrittenCharacter() = runBlocking {
        val repository = CharacterRepository(FakeCharacterDao())
        repository.upsert(Character(id = "c1", name = "旧名"))

        val written = repository.mutate("c1") { live -> live.copy(name = "新名") }

        assertNotNull(written)
        assertEquals("新名", written?.name)
        assertNotNull(written?.updatedAt)
        assertEquals("新名", repository.get("c1")?.name)
    }

    @Test
    fun mutateReturnsNullWhenTheRowDoesNotExist() = runBlocking {
        val repository = CharacterRepository(FakeCharacterDao())
        assertNull(repository.mutate("missing") { it.copy(name = "x") })
    }

    @Test
    fun concurrentMutationsOnTheSameIdDoNotLoseUpdates() = runBlocking {
        val repository = CharacterRepository(FakeCharacterDao())
        repository.upsert(Character(id = "c1", name = "角色"))
        val writers = 40

        coroutineScope {
            repeat(writers) {
                launch(Dispatchers.Default) {
                    repository.mutate("c1") { live -> live.copy(revision = live.revision + 1) }
                }
            }
        }

        assertEquals(writers, repository.get("c1")?.revision)
    }

    @Test
    fun mutationsOnDifferentIdsCanRunIndependently() = runBlocking {
        val repository = CharacterRepository(FakeCharacterDao())
        repository.upsert(Character(id = "a", name = "甲"))
        repository.upsert(Character(id = "b", name = "乙"))

        coroutineScope {
            launch(Dispatchers.Default) { repeat(10) { repository.mutate("a") { it.copy(revision = it.revision + 1) } } }
            launch(Dispatchers.Default) { repeat(10) { repository.mutate("b") { it.copy(revision = it.revision + 1) } } }
        }

        assertEquals(10, repository.get("a")?.revision)
        assertEquals(10, repository.get("b")?.revision)
    }

    /**
     * `NativeCore.persistMemory` writes a background pass through `mutate { mergeCharacterChanges }`:
     * the user's edit made while the pass ran must survive next to what the pass changed.
     */
    @Test
    fun aBackgroundPassMergesOntoTheRowWrittenMeanwhile() = runBlocking {
        val repository = CharacterRepository(FakeCharacterDao())
        val original = Character(
            id = "c1",
            dynamicState = DynamicState(currentMood = "平静", currentGoal = "值夜班"),
        )
        repository.upsert(original)
        // The user saves a location edit while the pass is still running on its snapshot.
        repository.mutate("c1") { live ->
            live.copy(dynamicState = live.dynamicState.copy(currentLocation = "美术馆"))
        }
        val passResult = original.copy(dynamicState = original.dynamicState.copy(currentMood = "紧张"))

        repository.mutate(original.id) { fresh -> mergeCharacterChanges(original, passResult, fresh) }

        val stored = repository.get("c1")!!
        assertEquals("紧张", stored.dynamicState.currentMood)
        assertEquals("美术馆", stored.dynamicState.currentLocation)
        assertEquals("值夜班", stored.dynamicState.currentGoal)
    }
}
