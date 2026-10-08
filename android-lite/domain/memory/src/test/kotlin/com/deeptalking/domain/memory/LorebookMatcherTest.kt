package com.deeptalking.domain.memory

import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookMatcherTest {

    private fun entry(
        id: String,
        name: String,
        keywords: List<String> = emptyList(),
        alwaysActive: Boolean = false,
        content: String = "内容",
        misses: Int = 0,
        mentions: Int = 0,
    ): LorebookEntry = LorebookEntry(
        id = id,
        name = name,
        content = content,
        keywords = keywords,
        alwaysActive = alwaysActive,
        origin = LorebookOrigin.Model,
        misses = misses,
        mentions = mentions,
    )

    @Test
    fun similarityIsNormalizedBigramJaccard() {
        assertEquals(0.5, similarity("银月商会", "银月商行"), 1e-9)
        assertEquals(0.2, similarity("银月商会", "月商银会"), 1e-9)
        assertEquals(1.0, similarity("银月 商会", "银月商会"), 1e-9)
        assertEquals(1.0, similarity("hello", "hello"), 1e-9)
        assertEquals(0.0, similarity("hello", "world"), 1e-9)
        assertEquals(0.0, similarity("", "abc"), 1e-9)
        assertEquals(0.0, similarity(null, "abc"), 1e-9)
    }

    @Test
    fun entriesSimilarUsesBigramThresholdNotUnigrams() {
        val nearName = entry("a", "银月商会") to entry("b", "银月商行")
        assertTrue(lorebookEntriesSimilar(nearName.first, nearName.second))

        // Same unigrams but 1/5 bigram overlap -> 0.2, below the 0.5 name threshold.
        val shuffled = entry("c", "银月商会", content = "") to entry("d", "月商银会", content = "")
        assertFalse(lorebookEntriesSimilar(shuffled.first, shuffled.second))
    }

    @Test
    fun scanMessagesWindowFeedsKeywordHaystack() {
        val hit = entry("hit", "潮汐", keywords = listOf("潮汐镇"))

        assertTrue(matchLorebookEntries(listOf(hit), "潮汐镇").any { it.id == "hit" })

        val older = listOf("潮汐镇", "无关")
        val singleWindow = LorebookLimits(scanMessages = 1)
        assertTrue(matchLorebookEntries(listOf(hit), "你好", older, singleWindow).isEmpty())
        assertTrue(
            matchLorebookEntries(listOf(hit), "你好", older, LorebookLimits(scanMessages = 2))
                .any { it.id == "hit" },
        )
    }

    @Test
    fun selectionSortsAlwaysActiveThenOrderThenName() {
        val always = entry("always", "zzz", alwaysActive = true)
        val low = entry("low", "zeta", keywords = listOf("k"))
        val mid = entry("mid", "mid", keywords = listOf("k"))
        val high = entry("high", "alpha", keywords = listOf("k"))
        val order = mapOf("always" to 99, "low" to 1, "mid" to 3, "high" to 5)

        val selected = matchLorebookEntries(
            listOf(high, low, always, mid),
            "k",
            orderOf = { order.getValue(it.id) },
        )

        assertEquals(listOf("always", "low", "mid", "high"), selected.map { it.id })
    }

    @Test
    fun nameOrderingUsesChineseCollation() {
        val zhao = entry("zhao", "赵", keywords = listOf("k"))
        val a = entry("a", "阿", keywords = listOf("k"))

        val ordered = matchLorebookEntries(listOf(zhao, a), "k")

        assertEquals(listOf("a", "zhao"), ordered.map { it.id })
    }

    @Test
    fun markMentionsBumpsInjectedAndResetsTheirMisses() {
        val injected = entry("a", "甲", keywords = listOf("k"), misses = 2, mentions = 1)
        val untouched = entry("b", "乙", keywords = listOf("k"), misses = 5)
        val entries = listOf(injected, untouched, entry("u", "用户", alwaysActive = true))

        val marked = markLorebookMentions(entries, listOf(injected), nowIso = "2026-01-01T00:00:00Z")

        assertEquals(2, marked[0].mentions)
        assertEquals(0, marked[0].misses)
        assertEquals("2026-01-01T00:00:00Z", marked[0].lastMentionedAt)
        assertEquals(5, marked[1].misses)
        assertEquals(0, marked[1].mentions)
        assertSame(entries, markLorebookMentions(entries, emptyList()))
    }
}
