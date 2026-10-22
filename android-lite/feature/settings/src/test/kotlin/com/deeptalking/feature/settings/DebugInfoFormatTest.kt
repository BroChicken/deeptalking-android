package com.deeptalking.feature.settings

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.RequestMetric
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugInfoFormatTest {

    @Test
    fun `debug info uses Chinese labels and omits reply prose`() {
        val lastReply = """
            {"at":"2026-10-22T10:00:00Z","config":{"model":"m1","reasoning":"medium","stream":true},
             "durationMs":1234,"displayText":"不要显示这段回复","fullText":"raw reply",
             "quickReplies":["甲","乙"]}
        """.trimIndent()
        val metric = RequestMetric(
            at = "2026-10-22T10:00:00Z",
            taskType = "extraction",
            model = "m1",
            platform = "deepseek",
            status = "completed",
            durationMs = 1234,
            prefixChange = "history-extended",
            commonHistoryMessages = 18,
            contextChars = 8_000,
            inputChars = 12_000,
            sectionChars = mapOf("recall" to 3_000, "time" to 120),
            inputTokens = 14_500,
            outputTokens = 200,
            hitTokens = 0,
            missTokens = 14_500,
            hitRate = 0.0,
        )

        val text = formatDebugInfo(AppConfig(lastReplyDebug = lastReply), listOf(metric)).joinToString("\n\n")

        assertTrue(text.contains("模型：m1"))
        assertTrue(text.contains("思考强度：中"))
        assertTrue(text.contains("短期记忆生成"))
        assertTrue(text.contains("历史延长"))
        assertTrue(text.contains("共同历史：18 条"))
        assertTrue(text.contains("记忆召回 3000"))
        assertTrue(text.contains("总计 3120 字符"))
        assertTrue(text.contains("命中率 0%"))
        assertFalse("reply prose must not be shown", text.contains("不要显示这段回复"))
        assertFalse("raw reply must not be shown", text.contains("raw reply"))
    }

    @Test
    fun `missing usage is shown as not returned`() {
        val metric = RequestMetric(
            at = "2026-10-22T10:00:00Z",
            taskType = "analysis",
            status = "failed",
            phase = "request failed",
        )

        val text = formatDebugInfo(AppConfig(), listOf(metric)).joinToString("\n")

        assertTrue(text.contains("长期记忆生成"))
        assertTrue(text.contains("原因：request failed"))
        assertTrue(text.contains("输入：未返回"))
        assertTrue(text.contains("命中率 未返回"))
        assertTrue(text.contains("分段：未记录"))
    }
}
