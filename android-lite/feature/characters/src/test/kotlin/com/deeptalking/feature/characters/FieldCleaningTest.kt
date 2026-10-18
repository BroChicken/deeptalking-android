package com.deeptalking.feature.characters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FieldCleaningTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val base: ZonedDateTime = ZonedDateTime.of(2026, 10, 5, 12, 0, 0, 0, zone)

    @Test
    fun stripsLeadingFieldNamePrefix() {
        assertEquals("教师", FieldCleaning.cleanFieldValue("currentOccupation", "职业：教师"))
        assertEquals("冷静", FieldCleaning.cleanFieldValue("personality", "性格特征：冷静"))
    }

    @Test
    fun stripsMetaParentheticalsOnly() {
        assertEquals("冷静", FieldCleaning.cleanFieldValue("personality", "冷静（用户要求）"))
        assertEquals("那天下着暴雨（他记得很清楚）", FieldCleaning.cleanFieldValue("background", "那天下着暴雨（他记得很清楚）"))
    }

    @Test
    fun removesExplanatorySentenceForShortFactFields() {
        assertEquals("学校", FieldCleaning.cleanFieldValue("currentLocation", "学校。用户希望我留在这里。"))
        assertEquals("中尉", FieldCleaning.cleanFieldValue("currentOccupation", "中尉，这是用户的要求。"))
    }

    @Test
    fun normalizeUserAddressCollapsesWhitespaceAndCaps() {
        assertEquals("明明", FieldCleaning.normalizeUserAddress(" 明 明 \n"))
        assertEquals(20, FieldCleaning.normalizeUserAddress("a".repeat(40)).length)
    }

    @Test
    fun parseRelativeTextResolvesDayAndSlot() {
        assertEquals("2026-10-06", FieldCleaning.parseRelativeText("明天", base, zone))
        assertEquals("2026-10-06 晚上", FieldCleaning.parseRelativeText("明天晚上", base, zone))
        assertEquals("2026-10-05", FieldCleaning.parseRelativeText("今天", base, zone))
    }

    @Test
    fun sanitizeDynamicStateFieldNormalizesRelativeTime() {
        assertEquals("2026-10-06在公园", FieldCleaning.sanitizeDynamicStateField("currentLocation", "明天在公园", base, zone))
    }

    @Test
    fun normalizeStaticFieldValueHandlesUserAddressSeparately() {
        assertEquals("明明", FieldCleaning.normalizeStaticFieldValue("userAddress", " 明 明 ", base, zone))
        assertEquals("2026-10-06", FieldCleaning.normalizeStaticFieldValue("background", "明天", base, zone))
    }

    @Test
    fun queryKeepsBlockersIntact() {
        val result = FieldCleaning.parseRelativeText("今天气不错", base, zone)
        assertTrue(result.startsWith("今天气"))
    }

    // ------------------------------------------------ 用户手写通道（编辑器保存）

    @Test
    fun userWrittenChannelKeepsContrastSentencesAndReportsWhy() {
        val result = FieldCleaning.cleanUserWrittenField("personality", "他不是冷漠，而是懒得解释。")
        assertEquals("他不是冷漠，而是懒得解释。", result.value)
        assertTrue(result.reasons.any { it.contains("对比句") })
    }

    @Test
    fun userWrittenChannelDoesNotTruncateLongText() {
        val longBackground = "他走过很多地方。".repeat(40)
        val background = FieldCleaning.cleanUserWrittenField("background", longBackground)
        assertEquals(longBackground, background.value)
        assertTrue(background.reasons.isEmpty())

        val longPersonality = "性格冷静克制。".repeat(40)
        assertEquals(longPersonality, FieldCleaning.cleanUserWrittenField("personality", longPersonality).value)
    }

    @Test
    fun userWrittenChannelReportsPrefixAndParentheticalStripping() {
        val prefix = FieldCleaning.cleanUserWrittenField("personality", "性格：冷静")
        assertEquals("冷静", prefix.value)
        assertTrue(prefix.reasons.any { it.contains("字段名前缀") })

        val parenthetical = FieldCleaning.cleanUserWrittenField("personality", "冷静（用户要求）")
        assertEquals("冷静", parenthetical.value)
        assertTrue(parenthetical.reasons.any { it.contains("括号") })
    }

    @Test
    fun strictChannelStillRejectsContrastAndClamps() {
        // 建卡 / AI 草稿通道的严格语义不变。
        assertEquals("", FieldCleaning.cleanFieldValue("personality", "他不是冷漠，而是懒得解释。"))
        val longPersonality = "性格冷静克制。".repeat(40)
        assertTrue(FieldCleaning.cleanFieldValue("personality", longPersonality).length <= 120)
    }
}
