package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.MemoryCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MemoryServiceImplTest {

    private val now: String = Instant.now().toString()

    @Test
    fun retrievalRanksMoreRelevantAndImportantFirst() = runBlocking {
        val service = MemoryServiceImpl()
        val important = LongTermMemory(
            id = "important",
            category = MemoryCategory.UserProfile,
            key = "咖啡",
            value = "用户每天都喝咖啡",
            importance = 9,
            createdAt = now,
        )
        val weak = LongTermMemory(
            id = "weak",
            category = MemoryCategory.UserProfile,
            key = "咖啡",
            value = "用户偶尔喝咖啡",
            importance = 1,
            createdAt = now,
        )
        val unrelated = LongTermMemory(
            id = "unrelated",
            category = MemoryCategory.Events,
            key = "天气",
            value = "外面在下雨",
            importance = 10,
            createdAt = now,
        )
        val character = Character(id = "c1", longTerm = listOf(weak, unrelated, important))

        val ranked = service.retrieve(character, "咖啡", limit = 6)

        assertTrue(ranked.isNotEmpty())
        assertEquals("important", ranked.first().id)
        assertTrue(ranked.indexOfFirst { it.id == "important" } < ranked.indexOfFirst { it.id == "weak" })
        assertFalse(ranked.any { it.id == "unrelated" })
    }

    @Test
    fun lorebookSelectPutsAlwaysActiveFirstAndRespectsEntryCap() {
        val always = LorebookEntry(id = "always", name = "世界", content = "设定", alwaysActive = true)
        val hit = LorebookEntry(id = "hit", name = "魔法", content = "魔法设定", keywords = listOf("魔法"))
        val miss = LorebookEntry(id = "miss", name = "剑", content = "剑设定", keywords = listOf("宝剑"))

        val selected = select(
            listOf(hit, miss, always),
            "他抬手施放了魔法",
            LorebookLimits(injectEntries = 6, injectChars = 10_000),
        )
        assertEquals(listOf("always", "hit"), selected.map { it.id })
        assertTrue(selected.first().alwaysActive)

        val alwaysActive = (1..5).map { index ->
            LorebookEntry(id = "w$index", name = "设定$index", content = "内容$index", alwaysActive = true)
        }
        val capped = select(alwaysActive, "", LorebookLimits(injectEntries = 2, injectChars = 10_000))
        assertEquals(2, capped.size)
        assertTrue(capped.all { it.alwaysActive })
    }

    @Test
    fun applyTurnDeduplicatesIdenticalLongTermKeyAndTrimsOverCap() {
        val service = MemoryServiceImpl()
        val character = Character(id = "c1")

        val first = LongTermMemory(
            id = "1",
            category = MemoryCategory.Habits,
            key = "早晨跑步",
            value = "用户每天早晨跑步",
            importance = 5,
            createdAt = now,
        )
        val duplicate = first.copy(id = "2")

        val deduped = service.applyTurn(character, emptyList(), listOf(first, duplicate))
        assertEquals(1, deduped.longTerm.count { it.key == "早晨跑步" })
        assertEquals(1, deduped.longTerm.size)

        val many = (1..(AppLimits.Memory.LONG_TERM_PER_CATEGORY + 5)).map { index ->
            LongTermMemory(
                id = "m$index",
                category = MemoryCategory.Habits,
                key = "习惯$index",
                value = "值$index",
                importance = index % 11,
                createdAt = now,
            )
        }
        val trimmed = service.applyTurn(character, emptyList(), many)
        assertEquals(AppLimits.Memory.LONG_TERM_PER_CATEGORY, trimmed.longTerm.size)
    }

    @Test
    fun listMemoriesFiltersByCategoryAndKeyword() {
        val service = MemoryServiceImpl()
        val profile = LongTermMemory(
            id = "profile",
            category = MemoryCategory.UserProfile,
            key = "喜欢的饮品",
            value = "用户爱喝咖啡",
            tags = listOf("饮品"),
            createdAt = now,
        )
        val habit = LongTermMemory(
            id = "habit",
            category = MemoryCategory.Habits,
            key = "晨跑",
            value = "用户每天早晨跑步",
            tags = listOf("运动"),
            createdAt = now,
        )
        val character = Character(id = "c1", longTerm = listOf(profile, habit))

        assertEquals(listOf("profile"), service.listMemories(character, category = "userProfile").map { it.id })
        assertEquals(listOf("habit"), service.listMemories(character, keyword = "跑步").map { it.id })
        assertEquals(listOf("profile"), service.listMemories(character, keyword = "饮品").map { it.id })
        assertEquals(2, service.listMemories(character).size)
        assertEquals(listOf("habit"), service.listMemories(character, category = "habits", keyword = "晨跑").map { it.id })
    }

    @Test
    fun pendingRecallPersistsOnCharacterAndBoostsRetrieval() = runBlocking {
        val service = MemoryServiceImpl()
        val coffee = LongTermMemory(
            id = "coffee",
            category = MemoryCategory.UserProfile,
            key = "饮品",
            value = "用户爱喝咖啡",
            tags = listOf("饮品"),
            importance = 3,
            createdAt = now,
        )
        val running = LongTermMemory(
            id = "running",
            category = MemoryCategory.Habits,
            key = "运动",
            value = "用户每天跑步",
            tags = listOf("运动"),
            importance = 9,
            createdAt = now,
        )
        val character = Character(id = "c1", longTerm = listOf(running, coffee))

        val updated = service.setPendingRecall(character, category = "userProfile", tags = listOf("饮品"))

        assertEquals(MemoryCategory.UserProfile, updated.pendingRecall.single().category)
        assertEquals(listOf("饮品"), updated.pendingRecall.single().tags)
        assertEquals(1, updated.longTerm.first { it.id == "coffee" }.recallCount)
        assertTrue(character.pendingRecall.isEmpty())

        val afterTurn = service.applyTurn(updated, emptyList(), emptyList())
        assertEquals(MemoryCategory.UserProfile, afterTurn.pendingRecall.single().category)

        val retrieved = service.retrieve(updated, "随便聊聊", limit = 5)
        assertEquals("coffee", retrieved.first().id)
    }
}
