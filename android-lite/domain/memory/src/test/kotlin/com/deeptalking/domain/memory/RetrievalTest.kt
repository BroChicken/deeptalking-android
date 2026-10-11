package com.deeptalking.domain.memory

import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalTest {

    private fun mem(
        id: String,
        key: String = "标识",
        value: String = "内容",
        tags: List<String> = emptyList(),
        eventTime: String? = null,
    ) = LongTermMemory(
        id = id,
        category = MemoryCategory.UserProfile,
        subject = MemorySubject.User,
        key = key,
        value = value,
        tags = tags,
        importance = 5,
        eventTime = eventTime,
        createdAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun searchTermsExpandsChineseBigrams() {
        val terms = searchTerms("九号晚上")
        assertTrue(terms.contains("九号"))
        assertTrue(terms.contains("号晚"))
        assertTrue(terms.contains("晚上"))
    }

    @Test
    fun rareTermOutranksCommonTerm() {
        val common = (1..8).map { mem("c$it", value = "用户喜欢咖啡") }
        val specific = mem("s1", value = "用户会做咖啡拉花")
        val ranked = rankMemories("咖啡拉花", common + specific)
        assertEquals("s1", ranked.first().memory.id)
    }

    @Test
    fun singleCommonTermIsFilteredWhenASpecificHitExists() {
        val common = (1..8).map { mem("c$it", value = "用户喜欢咖啡") }
        val specific = mem("s1", value = "用户会做咖啡拉花")
        val kept = topK("咖啡拉花", common + specific, 5)
        assertEquals(1, kept.size)
        assertEquals("s1", kept.single().id)
    }

    @Test
    fun topKNeverReturnsEmptyWhenOnlyCommonTermsMatch() {
        // Every memory shares the only query term, so the floor would empty the
        // result; topK must fall back to the ranked list rather than return nothing.
        val all = (1..50).map { mem("m$it", value = "用户晚上在家") }
        val kept = topK("晚上", all, 5)
        assertTrue(kept.isNotEmpty())
        assertTrue(kept.size <= 5)
    }

    @Test
    fun topKCollapsesSameEventIdentity() {
        val eventTime = "2026-10-09T14:00:00Z"
        val a = mem("a", value = "事件甲", tags = listOf("稀有甲"), eventTime = eventTime)
        val b = mem("b", value = "事件乙", tags = listOf("稀有乙"), eventTime = eventTime)
        val c = mem("c", value = "事件丙", tags = listOf("稀有丙"), eventTime = eventTime)
        val kept = topK("稀有甲 稀有乙 稀有丙", listOf(a, b, c), 3)
        // Same day + slot collapses to one entry so the budget is not filled by one beat.
        assertEquals(1, kept.size)
    }
}
