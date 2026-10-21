package com.deeptalking.lite

import com.deeptalking.core.model.RequestMetric
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Failed requests are kept for diagnostics, but they must not displace the last successful
 * cache row: they carry no token usage and would otherwise render as “缓存 --”.
 */
class CacheRowsTest {

    private fun metric(
        taskType: String,
        status: String = "completed",
        round: Int? = null,
    ) = RequestMetric(
        at = "2026-10-21T00:00:00Z",
        taskType = taskType,
        status = status,
        round = round,
        inputTokens = if (status == "completed") 100 else null,
        hitTokens = if (status == "completed") 50 else null,
        hitRate = if (status == "completed") 0.5 else null,
    )

    @Test
    fun `a failed main request does not hide the last successful turn`() {
        val ok = metric("chat.main", round = 1)
        val failed = metric("chat.main", status = "failed")
        assertEquals(listOf(ok), latestMainTurn(listOf(ok, failed)))
    }

    @Test
    fun `a failed auxiliary request does not hide its last successful row`() {
        val ok = metric("chat.worker")
        val failed = metric("chat.worker", status = "failed")
        assertEquals(listOf(ok), latestTaskRows(listOf(ok, failed), "chat.worker"))
    }
}
