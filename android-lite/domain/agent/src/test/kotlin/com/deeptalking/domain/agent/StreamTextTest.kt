package com.deeptalking.domain.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Covers the streaming-bubble display guards ported from
 * `src/js/api/response-parsing.js`: never show raw JSON / literal `\n` / `MEM_UPDATE`
 * in the live reply bubble.
 */
class StreamTextTest {

    @Test
    fun unescapesLiteralNewlines() {
        assertEquals("第一行\n第二行", StreamText.unescapeLiteralNewlines("第一行\\n第二行"))
    }

    @Test
    fun keepsDoubleEscapedNewlineLiteral() {
        assertEquals("a\\nb", StreamText.unescapeLiteralNewlines("a\\\\nb"))
    }

    @Test
    fun stripsMemUpdateTail() {
        assertEquals("正文", StreamText.getDisplayText("正文<MEM_UPDATE>{\"a\":1}</MEM_UPDATE>"))
        assertEquals("正文", StreamText.getDisplayText("正文<MEM_UPDATE>{\"a\":1"))
    }

    @Test
    fun extractsReplyFromCompleteJson() {
        assertEquals("你好", StreamText.extractReplyFromJson("""{"reply":"你好","quickReplies":[]}"""))
    }

    @Test
    fun extractsReplyFromPartialJson() {
        assertEquals("你好，世界", StreamText.extractReplyFromJson("""{"reply":"你好，世界"""))
    }

    @Test
    fun extractsReplyWithRawNewlineViaRepair() {
        assertEquals(
            "line1\nline2",
            StreamText.extractReplyFromJson("{\"reply\":\"line1\nline2\",\"quickReplies\":[]}"),
        )
    }

    @Test
    fun displayForSkipsRawJsonWithoutReply() {
        assertNull(StreamText.displayFor("""{"quickReplies":["a","b"]""", null))
    }

    @Test
    fun displayForShowsPlainText() {
        assertEquals("正在打字", StreamText.displayFor("正在打字", null))
    }

    @Test
    fun displayForPrefersSalvagedReply() {
        assertEquals("草稿", StreamText.displayFor("""{"reply":"最终""", "草稿"))
    }

    // ---------------------------------------------------------------- tone marker

    @Test
    fun stripsToneLineFromDisplayText() {
        assertEquals("正文", StreamText.getDisplayText("正文\n【语气】温柔、语速放慢"))
        assertEquals("正文", StreamText.getDisplayText("正文\n\n【语气】：温柔"))
        assertEquals("正文", StreamText.getDisplayText("正文\u3000【语气】\u3000温柔"))
    }

    @Test
    fun keepsOrdinaryBracketsInBody() {
        assertEquals("【系统提示】今天下雨", ToneText.split("【系统提示】今天下雨\n【语气】平静").body)
        assertEquals("【系统提示】今天下雨", StreamText.getDisplayText("【系统提示】今天下雨\n【语气】平静"))
    }

    @Test
    fun splitWithoutMarkerReturnsTrimmedText() {
        val split = ToneText.split("正文\n\n  ")
        assertEquals("正文", split.body)
        assertNull(split.tone)
    }

    @Test
    fun splitTakesLastMarker() {
        val split = ToneText.split("甲【语气】旧\n正文\n【语气】温柔")
        assertEquals("甲【语气】旧\n正文", split.body)
        assertEquals("温柔", split.tone)
    }

    @Test
    fun splitToleratesColonAndFullWidthSpaces() {
        assertEquals("温柔", ToneText.split("正文\n【 语气 】 ： 温柔").tone)
        assertEquals("温柔", ToneText.split("正文\n【语气】:温柔").tone)
        assertEquals("温柔", ToneText.split("正文\n【语气】温柔\r\n下一段").tone)
        assertNull(ToneText.split("正文\n【语气】").tone)
    }

    @Test
    fun splitCapsToneLength() {
        val tone = ToneText.split("正文\n【语气】" + "啊".repeat(400)).tone
        assertEquals(200, tone!!.length)
    }

    @Test
    fun displayHoldsBackPartialMarkerWhileStreaming() {
        val full = "正文\n【语气】温柔一些"
        for (end in 1..full.length) {
            val prefix = full.substring(0, end)
            val shown = ToneText.display(prefix)
            assertEquals(if (end == 1) "正" else "正文", shown, "前缀 '$prefix'")
            assertFalse(shown.contains("语气") || shown.contains("温柔"), "前缀 '$prefix' 泄露了语气: $shown")
        }
        assertEquals("正文", ToneText.display(full))
    }

    @Test
    fun displayForNeverShowsToneContent() {
        assertEquals("正文", StreamText.displayFor("正文\n【语气】温柔", null))
        assertEquals("正文", StreamText.displayFor("正文\n【语", null))
        assertEquals("正文", StreamText.displayFor("正文【语气", null))
        assertEquals("正文", StreamText.displayFor("""{"reply":"正文\n【语""", null))
    }

    @Test
    fun displayKeepsPlainTextAndDropsDanglingNewline() {
        assertEquals("正在打字", ToneText.display("正在打字\n\n"))
    }
}
