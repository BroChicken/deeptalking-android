package com.deeptalking.domain.agent.tools

import com.deeptalking.core.common.AppLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `cleanFieldValue` 对基础设定字段的两条新约束：只写明确的简短陈述句（禁对比句）、
 * 按字段上限收敛。角色卡编辑器的手动重新生成与建卡通道都走这里。
 */
class StaticFieldCleaningTest {

    @Test
    fun longNarrativeValuesAreClampedToTheFieldLimit() {
        val long = "性格冷静克制。".repeat(40)
        val cleaned = MemoryToolSupport.cleanFieldValue("personality", long)
        assertTrue(cleaned.length <= AppLimits.StaticProfile.PROSE_CHARS)
    }

    @Test
    fun backgroundGetsTheWiderLimit() {
        val long = "他走过很多地方。".repeat(40)
        val cleaned = MemoryToolSupport.cleanFieldValue("background", long)
        assertTrue(cleaned.length <= AppLimits.StaticProfile.BACKGROUND_CHARS)
        assertTrue(cleaned.length > AppLimits.StaticProfile.PROSE_CHARS)
    }

    @Test
    fun shortFieldsAreKeptShort() {
        val cleaned = MemoryToolSupport.cleanFieldValue("userAddress", "叫他明明") 
        assertTrue(cleaned.length <= AppLimits.StaticProfile.SHORT_CHARS)
    }

    @Test
    fun contrastSentencesAreRejectedSoTheToolCanAskForARewrite() {
        assertEquals("", MemoryToolSupport.cleanFieldValue("personality", "他不是冷漠，而是懒得解释。"))
    }

    @Test
    fun dynamicFieldsKeepTheirOldCeiling() {
        val long = "在城东的茶馆".repeat(100)
        val cleaned = MemoryToolSupport.cleanFieldValue("currentSituation", long)
        assertTrue(cleaned.length > AppLimits.StaticProfile.PROSE_CHARS)
    }
}
