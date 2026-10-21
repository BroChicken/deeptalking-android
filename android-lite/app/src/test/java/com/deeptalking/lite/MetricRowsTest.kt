package com.deeptalking.lite

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.TASK_CHAT_WORKER
import com.deeptalking.domain.agent.OrchestratorResult
import com.deeptalking.engine.ondevice.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure app-layer metric mapping: the worker notes/failure trail and the per-section
 * character accounting that `AppViewModel` hands to the independent metrics store.
 */
class MetricRowsTest {

    private val cfg = AppConfig(modelName = "m", apiPlatform = "custom")

    private fun result(
        workerOk: Boolean = true,
        usage: TokenUsage? = null,
        notes: List<String> = emptyList(),
        sections: Map<String, Int> = emptyMap(),
    ) = OrchestratorResult(
        reply = "hi",
        quickReplies = emptyList(),
        updatedCharacter = Character(id = "c1"),
        workerUsage = usage,
        workerOk = workerOk,
        workerNotes = notes,
        sectionChars = sections,
    )

    @Test
    fun workerPhaseKeepsFailureMarkerAndNotes() {
        assertEquals(
            "worker-failed：todos[0] 缺 proposer；quickReplies 数量不对",
            workerPhase(
                result(workerOk = false, notes = listOf("todos[0] 缺 proposer", "quickReplies 数量不对")),
            ),
        )
        assertEquals("worker-failed", workerPhase(result(workerOk = false)))
        assertEquals("", workerPhase(result()))
    }

    @Test
    fun workerNotesAreRecordedEvenWithoutUsage() {
        val row = workerMetricRow(
            at = "t1",
            cfg = cfg,
            characterId = "c1",
            result = result(workerOk = true, notes = listOf("quickReplies 数量不对")),
        )
        assertNotNull(row)
        assertEquals(TASK_CHAT_WORKER, row!!.taskType)
        assertEquals("completed", row.status)
        assertEquals("worker-note：quickReplies 数量不对", row.phase)
    }

    @Test
    fun workerFailureWithUsageKeepsFailedStatusAndPhase() {
        val row = workerMetricRow(
            at = "t1",
            cfg = cfg,
            characterId = "c1",
            result = result(
                workerOk = false,
                usage = TokenUsage(inputTokens = 100, outputTokens = 10, cachedTokens = 40),
                notes = listOf("没有解析出 submit_state"),
            ),
        )
        assertNotNull(row)
        assertEquals("failed", row!!.status)
        assertEquals("worker-failed：没有解析出 submit_state", row.phase)
        assertEquals(100, row.inputTokens)
        assertEquals(40, row.hitTokens)
        assertEquals(60, row.missTokens)
        assertEquals(0.4, row.hitRate!!, 1e-9)
    }

    @Test
    fun silentWorkerWritesNoRow() {
        assertNull(workerMetricRow("t1", cfg, "c1", result()))
    }

    @Test
    fun sectionCharsKeepOnlyMeasuredSections() {
        assertEquals(
            mapOf("time" to 12, "state" to 30),
            metricSectionChars(result(sections = mapOf("time" to 12, "state" to 30))),
        )
        // A dropped section is 0 chars: it must not become a "tiny section".
        assertEquals(mapOf("time" to 12), metricSectionChars(result(sections = mapOf("time" to 12, "lorebook" to 0))))
        assertNull(metricSectionChars(result()))
        assertTrue(metricSectionChars(result(sections = mapOf("scene" to 0))) == null)
    }
}
