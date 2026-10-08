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
        val turn = ResponseParser.parseSubmitResponse(
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
    fun `parseMemoryFromText extracts the tagged update`() {
        val prose = "正文\n<MEM_UPDATE>{\"shortTerm\":[{\"content\":\"用户喜欢咖啡\",\"sourceMessageIds\":[\"m1\"]}]}</MEM_UPDATE>"
        val update = ResponseParser.parseMemoryFromText(prose)
        assertNotNull(update)
        assertEquals(1, update!!.shortTerm.size)
        assertEquals("用户喜欢咖啡", update.shortTerm.first().content)
        assertNull(ResponseParser.parseMemoryFromText("没有标签"))
    }
}
