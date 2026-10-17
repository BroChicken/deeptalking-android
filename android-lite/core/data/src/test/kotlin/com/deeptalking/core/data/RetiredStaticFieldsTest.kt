package com.deeptalking.core.data

import com.deeptalking.core.model.Character
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「价值观」「关键过往」已从 `StaticProfile` 移除（分别与「性格特征」「背景故事」重叠）：
 * 旧行在读取时并入幸存字段，用户已有的人设不会因为删字段而消失。
 */
class RetiredStaticFieldsTest {

    @Test
    fun retiredFieldsAreFoldedIntoTheSurvivingOnes() {
        val payload = """
            {"id":"c1","name":"小雨","staticProfile":{"personality":"冷静","values":"重视承诺",
            "background":"孤儿","keyEvents":"十岁觉醒"},
            "members":[{"id":"m1","name":"阿岩","staticProfile":{"personality":"热血","values":"讲义气"}}]}
        """.trimIndent()

        val folded = RetiredStaticFields.foldPayload(payload)
        val character = AppJson.decodeFromString<Character>(folded)

        assertEquals("冷静；重视承诺", character.staticProfile.personality)
        assertEquals("孤儿；十岁觉醒", character.staticProfile.background)
        assertEquals("热血；讲义气", character.members.single().staticProfile.personality)
        assertFalse("退休键应从 payload 里摘掉", folded.contains("\"values\""))
        assertFalse("退休键应从 payload 里摘掉", folded.contains("\"keyEvents\""))
    }

    @Test
    fun aPayloadWithoutTheRetiredKeysIsReturnedUntouched() {
        val payload = """{"id":"c1","staticProfile":{"personality":"冷静"}}"""
        assertSame(payload, RetiredStaticFields.foldPayload(payload))
    }

    @Test
    fun textAlreadyPresentIsNotDuplicated() {
        val payload = """{"id":"c1","staticProfile":{"personality":"重视承诺，也重感情","values":"重视承诺"}}"""

        val folded = AppJson.decodeFromString<Character>(RetiredStaticFields.foldPayload(payload))

        assertEquals("重视承诺，也重感情", folded.staticProfile.personality)
    }

    @Test
    fun emptyRetiredKeysAreSimplyDropped() {
        val payload = """{"id":"c1","staticProfile":{"personality":"冷静","values":"","keyEvents":""}}"""

        val folded = RetiredStaticFields.foldPayload(payload)

        assertFalse(folded.contains("\"values\""))
        assertEquals("冷静", AppJson.decodeFromString<Character>(folded).staticProfile.personality)
    }

    @Test
    fun malformedJsonIsLeftToTheCorruptRowReporter() {
        val payload = """{"id":"c1","values":"""
        val folded = RetiredStaticFields.foldPayload(payload)
        assertTrue(folded.isNotEmpty())
    }
}
