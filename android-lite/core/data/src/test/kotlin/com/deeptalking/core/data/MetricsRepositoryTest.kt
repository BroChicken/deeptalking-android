package com.deeptalking.core.data

import com.deeptalking.core.model.RequestMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory [MetricsStore] with a suspension point on every write, so a missing atomic
 * read-modify-write visibly loses concurrent appends instead of passing by luck.
 */
private class FakeMetricsStore(initial: String? = null) : MetricsStore {
    private val mutex = Mutex()
    private val raw = MutableStateFlow(initial)

    override fun observeRaw(): Flow<String?> = raw

    override suspend fun updateRaw(transform: (String?) -> String) {
        mutex.withLock {
            delay(1)
            raw.value = transform(raw.value)
        }
    }

    fun rawValue(): String? = raw.value
}

private fun metric(taskType: String, at: String) = RequestMetric(at = at, taskType = taskType)

/** Metrics live in their own store: rolling per-taskType retention, import/export, legacy adoption. */
class MetricsRepositoryTest {

    @Test
    fun keepsOnlyTheNewestRowsPerTaskType() = runBlocking {
        val repository = MetricsRepository(FakeMetricsStore(), cap = 3)

        (1..5).forEach { repository.append(metric("chat.main", "c$it")) }
        (1..2).forEach { repository.append(metric("memory.analysis", "m$it")) }

        // Oldest chat rows are dropped; the quiet task type keeps everything it has.
        assertEquals(listOf("c3", "c4", "c5", "m1", "m2"), repository.all().map { it.at })
    }

    @Test
    fun appendsKeepInsertionOrderOldestFirst() = runBlocking {
        val repository = MetricsRepository(FakeMetricsStore(), cap = 60)

        repository.append(metric("chat.main", "a"))
        repository.append(metric("chat.worker", "b"))
        repository.append(metric("chat.main", "c"))

        assertEquals(listOf("a", "b", "c"), repository.all().map { it.at })
    }

    @Test
    fun concurrentAppendsDoNotLoseRows() = runBlocking {
        val repository = MetricsRepository(FakeMetricsStore(), cap = 60)
        val writers = 40

        coroutineScope {
            repeat(writers) { index ->
                launch(Dispatchers.Default) { repository.append(metric("chat.main", "m$index")) }
            }
        }

        val rows = repository.all()
        assertEquals(writers, rows.size)
        // Every writer's row survived; only the order of racing writers is unspecified.
        assertEquals((0 until writers).map { "m$it" }.toSet(), rows.map { it.at }.toSet())
    }

    @Test
    fun concurrentAppendsStillRespectThePerTaskTypeCap() = runBlocking {
        val repository = MetricsRepository(FakeMetricsStore(), cap = 5)

        coroutineScope {
            repeat(30) { index ->
                launch(Dispatchers.Default) { repository.append(metric("chat.main", "m$index")) }
            }
        }

        val rows = repository.all()
        assertEquals(5, rows.size)
        // The racing order is unspecified, but no row may be duplicated or dropped twice.
        assertEquals(5, rows.map { it.at }.toSet().size)

        // A later sequential append lands at the newest end and still respects the cap.
        repository.append(metric("chat.main", "sentinel"))
        val after = repository.all()
        assertEquals(5, after.size)
        assertEquals("sentinel", after.last().at)
    }

    @Test
    fun exportImportRoundTripsEveryRow() = runBlocking {
        val source = MetricsRepository(FakeMetricsStore(), cap = 60)
        source.append(metric("chat.main", "a"))
        source.append(metric("chat.worker", "b"))
        val json = source.exportJson()

        assertTrue(json.trim().startsWith("["))

        val target = MetricsRepository(FakeMetricsStore(), cap = 60)
        assertEquals(2, target.importJson(json))
        assertEquals(listOf("a", "b"), target.all().map { it.at })
        assertEquals(json, target.exportJson())
    }

    @Test
    fun importMergeAppendsAndNonMergeReplaces() = runBlocking {
        val repository = MetricsRepository(FakeMetricsStore(), cap = 60)
        repository.append(metric("chat.main", "old"))
        val json = """[{"at":"new","taskType":"chat.main"}]"""

        assertEquals(1, repository.importJson(json))
        assertEquals(listOf("old", "new"), repository.all().map { it.at })

        assertEquals(1, repository.importJson(json, merge = false))
        assertEquals(listOf("new"), repository.all().map { it.at })
    }

    @Test
    fun unparseableImportLeavesStoredRowsAlone() = runBlocking {
        val repository = MetricsRepository(FakeMetricsStore(), cap = 60)
        repository.append(metric("chat.main", "keep"))

        assertEquals(0, repository.importJson("not json", merge = false))
        assertEquals(0, repository.importJson("""{"taskType":"chat.main"}""", merge = false))

        assertEquals(listOf("keep"), repository.all().map { it.at })
    }

    @Test
    fun migrateLegacySeedsAnEmptyStoreOnceAndNeverOverwrites() = runBlocking {
        val repository = MetricsRepository(FakeMetricsStore(), cap = 2)
        val legacy = (1..3).map { metric("chat", "l$it") }

        assertEquals(2, repository.migrateLegacy(legacy))
        assertEquals(listOf("l2", "l3"), repository.all().map { it.at })

        // A second call (or an app update) must not duplicate or re-add trimmed rows.
        assertEquals(0, repository.migrateLegacy(legacy))
        assertEquals(listOf("l2", "l3"), repository.all().map { it.at })

        // Rows written by the new path are never replaced by the legacy copy either.
        assertEquals(0, repository.migrateLegacy(listOf(metric("chat", "brand-new"))))
        assertEquals(listOf("l2", "l3"), repository.all().map { it.at })
    }

    @Test
    fun migrateLegacyOnAnEmptyLegacyListIsANoOp() = runBlocking {
        val store = FakeMetricsStore()
        val repository = MetricsRepository(store, cap = 60)

        assertEquals(0, repository.migrateLegacy(emptyList()))
        assertNull(store.rawValue())
    }

    @Test
    fun clearEmptiesTheStoreAndBlocksLegacyReMigration() = runBlocking {
        val repository = MetricsRepository(FakeMetricsStore(), cap = 60)
        repository.append(metric("chat.main", "a"))

        repository.clear()

        assertTrue(repository.all().isEmpty())
        assertEquals(0, repository.migrateLegacy(listOf(metric("chat", "old"))))
        assertTrue(repository.all().isEmpty())
    }

    /** Old backups/configs have no [RequestMetric.sectionChars]: they must still load. */
    @Test
    fun legacyJsonWithoutNewFieldsStillLoads() = runBlocking {
        val legacyJson = """
            [{"at":"2025-01-01T00:00:00Z","characterId":"c1","taskType":"chat",
              "model":"deepseek-chat","platform":"deepseek","status":"completed",
              "durationMs":1200,"contextChars":900,"inputChars":800,"inputTokens":400,
              "outputTokens":90,"hitTokens":300,"missTokens":100,"hitRate":0.75,
              "oldUnknownField":"dropped"}]
        """.trimIndent()
        val repository = MetricsRepository(FakeMetricsStore(legacyJson), cap = 60)

        val row = repository.all().single()

        assertEquals("chat", row.taskType)
        assertEquals(0.75, row.hitRate!!, 0.0)
        assertNull(row.sectionChars)
    }

    @Test
    fun sectionCharsSurviveTheStore() = runBlocking {
        val repository = MetricsRepository(FakeMetricsStore(), cap = 60)
        val sections = mapOf("static" to 120, "volatile" to 40, "history" to 600)

        repository.append(RequestMetric(at = "a", taskType = "chat.main", sectionChars = sections))

        assertEquals(sections, repository.all().single().sectionChars)
        assertEquals(sections, MetricsRepository(FakeMetricsStore(repository.exportJson())).all().single().sectionChars)
    }
}
