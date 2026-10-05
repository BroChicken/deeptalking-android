package com.deeptalking.domain.agent

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
