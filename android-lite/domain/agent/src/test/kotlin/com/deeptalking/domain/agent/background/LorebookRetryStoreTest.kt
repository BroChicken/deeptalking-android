package com.deeptalking.domain.agent.background

import com.deeptalking.core.model.ShortTermMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed world-book batch is held in memory (it would otherwise be dropped together with
 * the short-term entries the analysis consumed), retried on the next opportunity, and handed
 * to the user once it has failed enough times — dropping it is then the user's call.
 */
class LorebookRetryStoreTest {

    private fun batch(id: String = "s1") = listOf(ShortTermMemory(id = id, content = "内容"))

    @Test
    fun failuresAccumulateUntilTheUserIsAsked() {
        val store = LorebookRetryStore(maxAttempts = 2)

        assertFalse(store.awaitingUser("c1"))

        val first = store.recordFailure("c1", batch())
        assertEquals(1, first.attempts)
        assertFalse(first.awaitingUser)
        assertFalse(store.awaitingUser("c1"))

        val second = store.recordFailure("c1", batch())
        assertEquals(2, second.attempts)
        assertTrue(store.awaitingUser("c1"))
        assertEquals(batch(), store.batch("c1"))
    }

    @Test
    fun theLatestBatchIsTheOneThatIsHeld() {
        val store = LorebookRetryStore()

        store.recordFailure("c1", batch("old"))
        store.recordFailure("c1", batch("old") + batch("new"))

        assertEquals(listOf("old", "new"), store.batch("c1").map { it.id })
    }

    @Test
    fun successClearsAndDropReportsWhatWasLost() {
        val store = LorebookRetryStore()
        store.recordFailure("c1", batch())

        store.clear("c1")
        assertTrue(store.batch("c1").isEmpty())

        store.recordFailure("c1", batch())
        assertEquals(1, store.drop("c1").size)
        assertTrue(store.batch("c1").isEmpty())
        assertFalse(store.awaitingUser("c1"))
    }

    @Test
    fun charactersAreTrackedIndependently() {
        val store = LorebookRetryStore()
        store.recordFailure("c1", batch())

        assertTrue(store.batch("c2").isEmpty())
        assertTrue(store.batch("c1").isNotEmpty())
    }
}
