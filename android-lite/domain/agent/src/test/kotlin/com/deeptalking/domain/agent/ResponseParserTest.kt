package com.deeptalking.domain.agent

import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseParserTest {

    @Test
    fun `parseLongTerm keeps identity fields and arc`() {
        val turn = ResponseParser.buildTurn(
            ResponseParser.parseJsonLenient(
                """{"reply":"好","longTerm":[{"category":"events","subject":"relationship",
                   "key":"一起看展","value":"用户与角色2026-10-11去看展","eventTime":"2026-10-11T02:00:00Z",
                   "participants":["用户","林晚"],"location":"美术馆","arcOf":"看展线","arcStage":"发展",
                   "sourceMessageIds":["m1"],"evidence":"一起去"}]}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertEquals(1, turn.longTerm.size)
        val item = turn.longTerm.first()
        assertEquals(MemoryCategory.Events, item.category)
        assertEquals(MemorySubject.Relationship, item.subject)
        assertEquals(listOf("用户", "林晚").sorted(), item.participants.sorted())
        assertEquals("美术馆", item.location)
        assertEquals("看展线", item.arcOf)
        assertEquals("发展", item.arcStage)
    }

    @Test
    fun `buildTurn parses timeRef for short and long memory`() {
        val turn = ResponseParser.parseTurnFromText(
            """{"reply":"好","quickReplies":["一","二"],
               "shortTerm":[{"content":"用户后天下午面试","sourceMessageIds":["m1"],
                             "timeRef":{"anchor":"day_after_tomorrow","slot":"下午"}}],
               "longTerm":[{"category":"events","subject":"user","key":"面试","value":"用户2026-10-12下午面试",
                            "sourceMessageIds":["m1"],"evidence":"后天下午面试",
                            "timeRef":{"anchor":"day_after_tomorrow","slot":"下午"}}]}""",
        )
        assertNotNull(turn)
        val shortRef = turn!!.shortTermTimeRefs.firstOrNull()
        assertEquals("day_after_tomorrow", shortRef?.anchor)
        assertEquals("下午", shortRef?.slot)
        val longRef = turn.longTermTimeRefs.firstOrNull()
        assertEquals("day_after_tomorrow", longRef?.anchor)
    }

    @Test
    fun `parseTurnFromText strips hidden MEM_UPDATE from prose`() {
        val prose = "（轻轻点头）我知道了。\n<MEM_UPDATE>{\"shortTerm\":[]}</MEM_UPDATE>"
        val turn = ResponseParser.parseTurnFromText(prose)
        assertTrue(turn.reply.contains("我知道了"))
        assertTrue(!turn.reply.contains("MEM_UPDATE"))
        assertNull(turn.raw)
    }

    @Test
    fun `parseTurnFromText splits the trailing tone line from plain prose`() {
        val turn = ResponseParser.parseTurnFromText("（轻轻点头）我知道了。\n\n【语气】放轻、放慢，尾音上扬")
        assertEquals("（轻轻点头）我知道了。", turn.reply)
        assertEquals("放轻、放慢，尾音上扬", turn.tone)
        assertNull(turn.raw)
    }

    @Test
    fun `parseTurnFromText keeps ordinary brackets and has no tone without a marker`() {
        val turn = ResponseParser.parseTurnFromText("【系统提示】外面在下雨。")
        assertEquals("【系统提示】外面在下雨。", turn.reply)
        assertNull(turn.tone)
    }

    @Test
    fun `parseTurnFromText takes the last tone marker and tolerates width variants`() {
        val turn = ResponseParser.parseTurnFromText("正文里提到【语气】这个词\n补充一句\n【 语气 】：温柔")
        assertEquals("正文里提到【语气】这个词\n补充一句", turn.reply)
        assertEquals("温柔", turn.tone)
    }

    @Test
    fun `parseTurnFromText reads tone out of a legacy JSON reply`() {
        val json = """{"reply":"好。\n【语气】温柔","quickReplies":["一","二"]}"""
        val turn = ResponseParser.parseTurnFromText(json)
        assertEquals("好。", turn.reply)
        assertEquals("温柔", turn.tone)
        assertEquals(listOf("一", "二"), turn.quickReplies)
        assertNotNull(turn.raw)
    }

    @Test
    fun `parseTurnFromText only treats a whole JSON object as JSON`() {
        val prose = "先说结论：{\"reply\":\"好\"} 大概是这样。"
        val turn = ResponseParser.parseTurnFromText(prose)
        assertEquals(prose, turn.reply)
        assertNull(turn.raw)

        val withTone = "{\"reply\":\"好\"}\n【语气】温柔"
        assertEquals("{\"reply\":\"好\"}", ResponseParser.parseTurnFromText(withTone).reply)
        assertEquals("温柔", ResponseParser.parseTurnFromText(withTone).tone)
    }
}
