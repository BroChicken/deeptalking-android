package com.deeptalking.lite

import com.deeptalking.core.common.AppLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "整理记忆" prompt is driven by the high watermark only. A window sitting between
 * the low and the high watermark (20..40) is normal operation and must never be reported
 * as pending work — that old `size - lowWater` count is what made an image-only cleanup
 * announce "短期记忆现有 N 条" and run a needless analysis pass.
 */
class MemoryCleanupTargetTest {

    private val high = AppLimits.Memory.SHORT_TERM
    private val low = AppLimits.Memory.SHORT_TERM_LOW_WATER

    @Test
    fun normalWindowBetweenWatermarksIsNotABacklog() {
        for (size in low..high) {
            val target = MemoryCleanupTarget.of(listOf(size))
            assertFalse("size=$size must not ask for memory cleanup", target.overLimit)
            assertFalse("size=$size has no backlog", target.hasWork)
            assertEquals("size=$size", 0, target.shortTermPending)
        }
    }

    @Test
    fun onlyTheOverflowAboveTheHighWatermarkCountsAsPending() {
        val target = MemoryCleanupTarget.of(listOf(high + 5))
        assertTrue(target.overLimit)
        assertTrue(target.hasWork)
        assertEquals(5, target.shortTermPending)
        assertEquals(high + 5, target.shortTermTotal)
    }

    @Test
    fun pendingIsSummedPerCharacterAndExcludesNormalWindows() {
        val target = MemoryCleanupTarget.of(listOf(high + 3, low, high))
        assertEquals(1, target.characters)
        assertEquals(3, target.shortTermPending)
        assertEquals(high + 3 + low + high, target.shortTermTotal)
        assertTrue(target.overLimit)
    }

    @Test
    fun emptySaveHasNothingToDo() {
        val target = MemoryCleanupTarget.of(emptyList())
        assertFalse(target.overLimit)
        assertFalse(target.hasWork)
        assertEquals(0, target.shortTermTotal)
        assertEquals(0, target.shortTermPending)
    }
}
