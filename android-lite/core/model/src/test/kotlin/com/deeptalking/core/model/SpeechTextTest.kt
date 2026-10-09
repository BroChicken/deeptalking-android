package com.deeptalking.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {

    @Test
    fun stripsFullWidthActions() {
        assertEquals("你好，今天天气不错。", extractSpeechText("你好，（微笑）今天天气不错。"))
    }

    @Test
    fun stripsHalfWidthActionsAndCollapsesSpaces() {
        assertEquals("Hello there.", extractSpeechText("Hello (smiling) there."))
    }

    @Test
    fun stripsLongParentheses() {
        val long = "啊".repeat(90)
        assertEquals("我你", extractSpeechText("我（$long）你"))
    }

    @Test
    fun stripsInterleavedActions() {
        val reply = "（揉了揉眼睛，打了个哈欠）唔……几点了？（看向窗外，阳光刺眼）啊，都中午了呀。"
        assertEquals("唔……几点了？啊，都中午了呀。", extractSpeechText(reply))
    }

    @Test
    fun stripsBracketAsides() {
        assertEquals("正文", extractSpeechText("【系统】正文"))
    }

    @Test
    fun stripsMarkdownMarkers() {
        assertEquals("重要 内容 代码", extractSpeechText("**重要** ~~内容~~ `代码`"))
    }

    @Test
    fun keepsLinkLabel() {
        assertEquals("点这里", extractSpeechText("[点这里](https://example.com)"))
    }

    @Test
    fun dropsImages() {
        assertEquals("看图", extractSpeechText("看图 ![alt](https://x/y.png)"))
    }

    @Test
    fun stripsHeadingsAndBullets() {
        assertEquals("标题 一 二", extractSpeechText("# 标题\n- 一\n- 二"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("a b", extractSpeechText("a\n\n   b"))
    }

    @Test
    fun blankInput() {
        assertEquals("", extractSpeechText("   \n  "))
    }

    @Test
    fun segmentsSingleCharacterWhenNoMemberLine() {
        val segments = extractSpeechSegments("你好，（微笑）今天天气不错。", listOf("张三", "李四"))
        assertEquals(1, segments.size)
        assertEquals(null, segments[0].speaker)
        assertEquals("你好，今天天气不错。", segments[0].text)
    }

    @Test
    fun segmentsGroupReplyPerMember() {
        val reply = "张三：\"（笑）今天去哪？\"\n李四：\"我随便，听你的。\""
        val segments = extractSpeechSegments(reply, listOf("张三", "李四"))
        assertEquals(2, segments.size)
        assertEquals("张三", segments[0].speaker)
        assertEquals("今天去哪？", segments[0].text)
        assertEquals("李四", segments[1].speaker)
        assertEquals("我随便，听你的。", segments[1].text)
    }

    @Test
    fun keepsMultiLineTurnUnderOneSpeaker() {
        val reply = "张三：\"今天去哪？\n随便走走也行。\"\n李四：\"好。\""
        val segments = extractSpeechSegments(reply, listOf("张三", "李四"))
        assertEquals(2, segments.size)
        assertEquals("张三", segments[0].speaker)
        assertEquals("今天去哪？ 随便走走也行。", segments[0].text)
        assertEquals("李四", segments[1].speaker)
    }

    @Test
    fun fallsBackToSingleSegmentWhenFormatMissing() {
        val reply = "大家安静一下，我有话要说。"
        val segments = extractSpeechSegments(reply, listOf("张三", "李四"))
        assertEquals(1, segments.size)
        assertEquals(null, segments[0].speaker)
        assertEquals("大家安静一下，我有话要说。", segments[0].text)
    }

    @Test
    fun segmentsBlankInputIsEmpty() {
        assertEquals(0, extractSpeechSegments("   ", listOf("张三")).size)
    }
}
