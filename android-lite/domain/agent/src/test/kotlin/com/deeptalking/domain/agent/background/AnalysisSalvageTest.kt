package com.deeptalking.domain.agent.background

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply cut off by the provider's output cap must still yield progress: strict
 * parsing fails, but the elements that did arrive are usable. Before this, one
 * truncated reply discarded the whole batch, nothing was ever marked analyzed, and
 * short-term memory could never be trimmed.
 */
class AnalysisSalvageTest {

    private fun ids(root: JsonObject): List<String> =
        (root["analyzedShortTermIds"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content }

    private fun elementCount(root: JsonObject): Int = (root["longTerm"] as JsonArray).size

    @Test
    fun keepsBothTheIdsAndTheCompleteElementsOfATruncatedReply() {
        val truncated =
            """{"status":"ok","analyzedShortTermIds":["s1","s2"],"longTerm":[""" +
                """{"category":"userProfile","subject":"user","key":"饮品","value":"用户喜欢美式咖啡。"},""" +
                """{"category":"events","subject":"legacy","key":"未完"""
        val root = salvageAnalysisReply(truncated)!!
        assertEquals("ok", (root["status"] as JsonPrimitive).content)
        assertEquals(listOf("s1", "s2"), ids(root))
        // The half-written element is dropped; the complete one is kept.
        assertEquals(1, elementCount(root))
        val kept = (root["longTerm"] as JsonArray)[0] as JsonObject
        assertEquals("userProfile", (kept["category"] as JsonPrimitive).content)
    }

    @Test
    fun derivesTheAcknowledgementFromSurvivingElementsWhenTheIdListWasCut() {
        val truncated =
            """{"status":"ok","longTerm":[""" +
                """{"category":"events","key":"a","sourceShortTermIds":["s7"]},""" +
                """{"category":"events","key":"b","sourceShortTermIds":["s8"]}]"""
        val root = salvageAnalysisReply(truncated)!!
        assertEquals(listOf("s7", "s8"), ids(root))
        assertEquals(2, elementCount(root))
    }

    @Test
    fun keepsACompleteArrayThatWasOnlyMissingItsClosingBrace() {
        val truncated = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[]"""
        val root = salvageAnalysisReply(truncated)!!
        assertEquals(listOf("s1"), ids(root))
        assertTrue((root["longTerm"] as JsonArray).isEmpty())
    }

    @Test
    fun returnsNullWhenNothingUsableArrived() {
        assertNull(salvageAnalysisReply("抱歉，我无法完成这个请求。"))
        assertNull(salvageAnalysisReply(""))
        assertNull(salvageAnalysisReply("""{"status":"ok","analyzedShortTermIds":["""))
    }

    @Test
    fun keepsACompleteElementFollowedByACutOne() {
        val element = """{"category":"userProfile","subject":"user","key":"饮品",""" +
            """"value":"用户喜欢喝美式咖啡。","sourceShortTermIds":["s1"],"sourceMessageIds":["u1"],""" +
            """"evidence":"用户喜欢喝美式咖啡。"}"""
        val truncated = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[$element,""" +
            """{"category":"events","subject":"legacy","key":"未完"""
        val root = salvageAnalysisReply(truncated)!!
        assertEquals(listOf("s1"), ids(root))
        assertEquals(1, elementCount(root))
    }
}
