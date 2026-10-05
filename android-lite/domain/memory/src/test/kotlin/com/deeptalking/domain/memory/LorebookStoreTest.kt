package com.deeptalking.domain.memory

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.ShortTermMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookStoreTest {

    @Test
    fun createsNewModelEntryWhenKeywordsProvided() {
        val result = upsertLorebookEntry(emptyList(), LorebookProposal("银月商会", "西街的商会。", listOf("银月")))
        assertTrue(result.ok)
        assertTrue(result.created)
        assertEquals(LorebookOrigin.Model, result.entry?.origin)
        assertEquals(1, result.list.size)
    }

    @Test
    fun rejectsNewEntryWithoutKeywordOrAlwaysActive() {
        val result = upsertLorebookEntry(emptyList(), LorebookProposal("无关键词", "内容"))
        assertFalse(result.ok)
    }

    @Test
    fun updatesSameNameInsteadOfDuplicating() {
        val first = upsertLorebookEntry(emptyList(), LorebookProposal("赤月王国", "西侧王国", listOf("赤月")))
        val second = upsertLorebookEntry(first.list, LorebookProposal("赤月王国", "西侧王国（更新）"))
        assertTrue(second.ok)
        assertFalse(second.created)
        assertEquals(1, second.list.size)
        assertEquals("西侧王国（更新）", second.list[0].content)
    }

    @Test
    fun userEntryIsNeverOverwritten() {
        val user = LorebookEntry(id = "u", name = "用户条目", content = "不许改", origin = LorebookOrigin.User)
        val result = upsertLorebookEntry(listOf(user), LorebookProposal("用户条目", "试图覆盖"))
        assertTrue(result.ok)
        assertTrue(result.mergedIntoUser)
        assertSame(user, result.list[0])
        assertEquals("不许改", result.list[0].content)
    }

    @Test
    fun nearNameMergesIntoExistingModelEntry() {
        val first = upsertLorebookEntry(emptyList(), LorebookProposal("银月商会", "西街的商会。", listOf("银月")))
        val merged = upsertLorebookEntry(first.list, LorebookProposal("银月商行", "经营药材。", listOf("银月")))
        assertTrue(merged.ok)
        assertFalse(merged.created)
        assertEquals(1, merged.list.size)
        assertTrue(merged.list[0].content.contains("西街"))
        assertTrue(merged.list[0].content.contains("药材"))
    }

    @Test
    fun alwaysActiveCapIsEnforced() {
        val names = listOf("甲", "乙", "丙", "丁", "戊", "己")
        val list = names.mapIndexed { index, name ->
            LorebookEntry(
                id = "lore$index",
                name = name,
                content = name,
                origin = LorebookOrigin.Model,
                alwaysActive = true,
            )
        }
        val blocked = upsertLorebookEntry(list, LorebookProposal("庚", "庚", alwaysActive = true))
        assertFalse(blocked.ok)
    }

    @Test
    fun resolveLorebookSourcesValidatesIdsAndEvidence() {
        val character = Character(
            id = "c1",
            shortTerm = listOf(
                ShortTermMemory(id = "s1", content = "银月商会在西街，经营药材", sourceMessageIds = listOf("m1")),
            ),
        )
        assertTrue(resolveLorebookSources(character, listOf("s1"), "银月商会"))
        assertFalse(resolveLorebookSources(character, listOf("nope"), "银月商会"))
        assertFalse(resolveLorebookSources(character, listOf("s1"), "完全无关的内容"))
        assertFalse(resolveLorebookSources(character, listOf("s1"), ""))
    }

    @Test
    fun evictionRetiresModelEntryAfterThreeMissesButExemptsUserAndAlwaysActive() {
        val entries = listOf(
            LorebookEntry(id = "ai", name = "AI", content = "c", origin = LorebookOrigin.Model, keywords = listOf("k")),
            LorebookEntry(id = "active", name = "常驻", content = "c", origin = LorebookOrigin.Model, alwaysActive = true),
            LorebookEntry(id = "user", name = "用户", content = "c", origin = LorebookOrigin.User),
        )
        var current = entries
        current = evictStaleLorebookEntries(current).kept
        assertEquals(3, current.size)
        current = evictStaleLorebookEntries(current).kept
        assertEquals(3, current.size)
        val third = evictStaleLorebookEntries(current)
        assertEquals(1, third.removed.size)
        assertEquals("ai", third.removed[0].id)
        assertEquals(2, third.kept.size)
        assertTrue(third.kept.any { it.id == "active" })
        assertTrue(third.kept.any { it.id == "user" })
    }

    @Test
    fun dedupeMergesModelNearDuplicatesAndKeepsUserEntry() {
        val list = listOf(
            LorebookEntry(id = "a", name = "王都", content = "北方的都城。", origin = LorebookOrigin.Model, keywords = listOf("王都")),
            LorebookEntry(id = "b", name = "王都", content = "都城有钟楼。", origin = LorebookOrigin.Model, keywords = listOf("王都")),
            LorebookEntry(id = "c", name = "王都", content = "用户版本。", origin = LorebookOrigin.User),
        )
        val deduped = dedupeLorebook(list)
        assertEquals(2, deduped.size)
        assertEquals(1, deduped.count { it.origin == LorebookOrigin.User })
        val merged = deduped.first { it.origin == LorebookOrigin.Model }
        assertTrue(merged.content.contains("钟楼"))
    }
}
