package com.deeptalking.domain.agent

import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.DynamicStateMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The turn worker's per-field bookkeeping: only the keys a pass actually moved get a fresh
 * timestamp. A group's shared state lives in `groupSharedDynamic` but uses the same map, so a
 * scene move must be stamped exactly like a single character's.
 */
class DynamicMetaStampTest {

    @Test
    fun `only changed keys are stamped`() {
        val before = DynamicState(currentSituation = "在教室", currentMood = "平静")
        val after = before.copy(currentMood = "紧张")

        val meta = stampChangedDynamicFields(
            mapOf("currentGoal" to DynamicStateMeta("2026-10-19T08:00:00Z")),
            before,
            after,
            "2026-10-20T12:00:00Z",
        )

        assertEquals("2026-10-20T12:00:00Z", meta.getValue("currentMood").updatedAt)
        assertNull(meta["currentSituation"])
        assertEquals("2026-10-19T08:00:00Z", meta.getValue("currentGoal").updatedAt)
    }

    @Test
    fun `a group shared scene move is stamped`() {
        val before = DynamicState(currentSituation = "在旧楼", currentLocation = "旧楼")
        val after = before.copy(currentSituation = "搬到了天台", currentLocation = "天台")

        val meta = stampChangedDynamicFields(
            mapOf("currentMood" to DynamicStateMeta("2026-10-19T08:00:00Z")),
            before,
            after,
            "2026-10-20T12:00:00Z",
        )

        assertEquals("2026-10-20T12:00:00Z", meta.getValue("currentSituation").updatedAt)
        assertEquals("2026-10-20T12:00:00Z", meta.getValue("currentLocation").updatedAt)
        assertEquals("2026-10-19T08:00:00Z", meta.getValue("currentMood").updatedAt)
    }

    @Test
    fun `an unchanged state stamps nothing`() {
        val state = DynamicState(currentSituation = "在教室")

        val meta = stampChangedDynamicFields(emptyMap(), state, state, "2026-10-20T12:00:00Z")

        assertEquals(emptyMap<String, DynamicStateMeta>(), meta)
    }
}
