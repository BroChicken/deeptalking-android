package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.StaticProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FieldRevisionTest {

    @Test
    fun `detects an explicit static-field change request`() {
        assertEquals("personality", FieldRevision.detect("你性格应该更冷酷一些"))
        assertEquals("userAddress", FieldRevision.detect("别叫我用户，叫我明明"))
        assertEquals("speakingStyle", FieldRevision.detect("以后说话更随意点，多用点口头禅"))
        assertEquals("appearance", FieldRevision.detect("把你外貌改成短发"))
    }

    @Test
    fun `ignores ordinary conversation and explicit refusals`() {
        assertNull(FieldRevision.detect("今天天气不错"))
        assertNull(FieldRevision.detect("不要改你的性格"))
        assertNull(FieldRevision.detect("你的性格是什么样的"))
    }

    @Test
    fun `cleans quotes and enforces the field length cap`() {
        assertEquals("性格冷静克制，遇事沉稳。", FieldRevision.clean("personality", "「性格冷静克制，遇事沉稳。」"))
        val long = "啊".repeat(200)
        val cleaned = FieldRevision.clean("appearance", long)
        assertNotNull(cleaned)
        assertEquals(120, cleaned!!.length)
    }

    @Test
    fun `apply writes the value and the per-field timestamp`() {
        val before = Character(
            id = "c1",
            staticProfile = StaticProfile(personality = "温和"),
        )
        val after = FieldRevision.apply(before, "personality", "冷静克制", "2026-10-20T12:00:00Z")
        assertEquals("冷静克制", after.staticProfile.personality)
        assertNotNull(after.staticFieldMeta["personality"])
    }
}
