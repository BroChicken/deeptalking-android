package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.FieldEditRegistry
import com.deeptalking.core.model.FieldKey
import com.deeptalking.core.model.FieldSection
import com.deeptalking.core.model.StaticProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FieldRevisionTest {

    @Test
    fun `detects an explicit static-field change request`() {
        assertEquals("personality", FieldRevision.detect("把性格改成冷静克制"))
        assertEquals("personality", FieldRevision.detect("性格写成冷静克制，话少一点"))
        assertEquals("speakingStyle", FieldRevision.detect("把说话风格改成简短直接"))
        assertEquals("background", FieldRevision.detect("背景改成海边长大的渔家女孩"))
        assertEquals("appearance", FieldRevision.detect("把你外貌改成短发"))
        assertEquals("age", FieldRevision.detect("年龄改成25岁"))
        assertEquals("gender", FieldRevision.detect("性别设置为女"))
        assertEquals("userAddress", FieldRevision.detect("以后叫我明明"))
        assertEquals("userAddress", FieldRevision.detect("别叫我用户，叫我明明"))
        // A polite question is still an explicit request.
        assertEquals("speakingStyle", FieldRevision.detect("可以把语气改成温柔一点吗？"))
    }

    @Test
    fun `ignores ordinary conversation comparisons and evaluations`() {
        assertNull(FieldRevision.detect("今天天气不错"))
        assertNull(FieldRevision.detect("你说话更温柔了"))
        assertNull(FieldRevision.detect("你的性格比之前更温柔了"))
        assertNull(FieldRevision.detect("你说话应该更随意一点"))
        assertNull(FieldRevision.detect("以后说话更随意点，多用点口头禅"))
        assertNull(FieldRevision.detect("我觉得你现在的说话风格很好"))
        assertNull(FieldRevision.detect("你的说话风格变成这样我有点不习惯"))
        assertNull(FieldRevision.detect("你的性格是什么样的"))
        assertNull(FieldRevision.detect("你多大了"))
        assertNull(FieldRevision.detect("你叫我一声我就来"))
    }

    @Test
    fun `matches field aliases`() {
        assertEquals("speakingStyle", FieldRevision.detect("把你的口吻改成冷淡"))
        assertEquals("speakingStyle", FieldRevision.detect("口头禅改成「好的吧」"))
        assertEquals("language", FieldRevision.detect("把方言改成粤语"))
        assertEquals("userAddress", FieldRevision.detect("称呼改成明明"))
        assertEquals("appearance", FieldRevision.detect("头发改成短发"))
        assertEquals("fears", FieldRevision.detect("把弱点改成怕黑"))
        assertEquals("race", FieldRevision.detect("种族改成精灵"))
        assertEquals("personality", FieldRevision.detect("personality 改成冷静"))
    }

    @Test
    fun `keeps one field per turn`() {
        // Two different fields in one message: skip rather than run two silent rewrites.
        assertNull(FieldRevision.detect("把性格改成冷静，把说话风格改成随意"))
        assertNull(FieldRevision.detect("年龄改成25岁，性别改成女"))
        // An unrelated clause does not block a single explicit field request.
        assertEquals("personality", FieldRevision.detect("把性格改成冷静。顺便问一下明天会下雨吗"))
        // One clause naming two fields resolves to the alias next to the command.
        assertEquals("background", FieldRevision.detect("把性格和背景都改成海边长大"))
    }

    @Test
    fun `ignores refusals and negated instructions`() {
        assertNull(FieldRevision.detect("不要改你的性格"))
        assertNull(FieldRevision.detect("别改说话风格"))
        assertNull(FieldRevision.detect("不用修改背景"))
        assertNull(FieldRevision.detect("我不需要你调整外貌"))
        // Negation is scoped to its own clause; a later explicit request still applies.
        assertEquals("appearance", FieldRevision.detect("不要改性格，把外貌改成短发"))
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

    @Test
    fun `apply leaves a field the user is typing untouched`() {
        val key = FieldKey.of("c1", null, FieldSection.Static, "personality")
        FieldEditRegistry.focus(key, "editor")
        try {
            val before = Character(id = "c1", staticProfile = StaticProfile(personality = "温和"))

            val after = FieldRevision.apply(before, "personality", "冷静克制", "2026-10-20T12:00:00Z")

            assertEquals("温和", after.staticProfile.personality)
            assertEquals(before.staticFieldMeta, after.staticFieldMeta)
        } finally {
            FieldEditRegistry.clearOwner("editor")
        }
    }

    @Test
    fun `apply does not skip a field focused in another section`() {
        val before = Character(id = "c1", staticProfile = StaticProfile(personality = "温和"))
        FieldEditRegistry.focus(
            FieldKey.of("c1", null, FieldSection.Dynamic, "personality"),
            "editor",
        )
        try {
            val after = FieldRevision.apply(before, "personality", "冷静克制", "2026-10-20T12:00:00Z")
            assertEquals("冷静克制", after.staticProfile.personality)
        } finally {
            FieldEditRegistry.clearAll()
        }
    }
}
