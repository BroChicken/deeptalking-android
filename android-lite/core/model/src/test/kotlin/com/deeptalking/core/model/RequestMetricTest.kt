package com.deeptalking.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-taskType metric retention: one request kind must never evict another. */
class RequestMetricTest {

    private fun metric(taskType: String, at: String) = RequestMetric(at = at, taskType = taskType)

    @Test
    fun keepsTheNewestRowsOfEveryTaskType() {
        val metrics = (1..5).map { metric("chat.main", "c$it") } +
            (1..5).map { metric("memory.analysis", "m$it") }

        val trimmed = metrics.trimMetricsPerTaskType(cap = 3)

        assertEquals(
            listOf("c3", "c4", "c5", "m3", "m4", "m5"),
            trimmed.map { it.at },
        )
    }

    @Test
    fun oneChattyTaskTypeCannotEvictAnother() {
        val metrics = (1..100).map { metric("chat.main", "c$it") } +
            listOf(metric("lorebook", "l1"))

        val trimmed = metrics.trimMetricsPerTaskType(cap = 60)

        assertEquals(61, trimmed.size)
        assertEquals(60, trimmed.count { it.taskType == "chat.main" })
        assertTrue(trimmed.any { it.taskType == "lorebook" })
        // Oldest chat rows are the ones dropped; order stays oldest -> newest.
        assertEquals("c41", trimmed.first().at)
        assertEquals("l1", trimmed.last().at)
    }

    @Test
    fun legacyTaskTypesAreKeptVerbatim() {
        val metrics = listOf(metric("chat", "a"), metric("auxiliary", "b"), metric("chat", "c"))

        val trimmed = metrics.trimMetricsPerTaskType(cap = 1)

        assertEquals(listOf("auxiliary", "chat"), trimmed.map { it.taskType })
        assertEquals(listOf("b", "c"), trimmed.map { it.at })
    }

    @Test
    fun nonPositiveCapDropsEverything() {
        val metrics = listOf(metric("chat.main", "a"))

        assertTrue(metrics.trimMetricsPerTaskType(cap = 0).isEmpty())
    }
}
