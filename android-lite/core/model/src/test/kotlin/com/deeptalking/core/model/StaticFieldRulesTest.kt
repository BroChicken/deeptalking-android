package com.deeptalking.core.model

import com.deeptalking.core.common.AppLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基础设定字段的写作规则：只写明确的简短陈述句（禁对比句）、按字段上限收敛。
 */
class StaticFieldRulesTest {

    @Test
    fun contrastSentencesAreRejectedForBasicProfileFields() {
        assertEquals("对比句（不是…而是…）", StaticFieldRules.contrastReason("personality", "他不是冷漠，而是懒得解释。"))
        assertEquals("对比句（不是…而是…）", StaticFieldRules.contrastReason("background", "并非出身名门，而是靠自己打拼。"))
        assertEquals("对比句（不是…而是…）", StaticFieldRules.contrastReason("speakingStyle", "与其说是冷淡，不如说是不擅长表达。"))
    }

    @Test
    fun plainStatementsPassAndDynamicFieldsAreOutOfScope() {
        assertNull(StaticFieldRules.contrastReason("personality", "性格冷静克制，遇事沉稳。"))
        assertNull(StaticFieldRules.contrastReason("personality", ""))
        // 动态状态等非基础设定字段不受这条规则约束（原文照旧）。
        assertNull(StaticFieldRules.contrastReason("currentMood", "不是开心，而是平静"))
    }

    @Test
    fun clampingUsesThePerFieldLimitAndPrefersSentenceBoundaries() {
        val longPersonality = "性格冷静克制。".repeat(40)
        val clamped = StaticFieldRules.clamp("personality", longPersonality)
        assertTrue(clamped.length <= AppLimits.StaticProfile.PROSE_CHARS)
        assertTrue("应落在句末标点上", clamped.endsWith("。"))

        val longBackground = "他走过很多地方".repeat(80)
        assertTrue(StaticFieldRules.clamp("background", longBackground).length <= AppLimits.StaticProfile.BACKGROUND_CHARS)

        assertEquals("男", StaticFieldRules.clamp("gender", "男"))

        // 非基础设定字段原样返回，由调用方用自己的上限。
        val untouched = "在城东的茶馆".repeat(200)
        assertEquals(untouched, StaticFieldRules.clamp("currentSituation", untouched))
    }

    @Test
    fun hardCutWithoutSentenceEndStillRespectsTheLimit() {
        val noPunctuation = "啊".repeat(500)
        val clamped = StaticFieldRules.clamp("personality", noPunctuation)
        assertTrue(clamped.length <= AppLimits.StaticProfile.PROSE_CHARS)
        assertTrue(clamped.endsWith("…"))
    }
}
