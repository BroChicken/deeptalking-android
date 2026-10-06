package com.deeptalking.domain.memory

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.ShortTermMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MemoryPolicyTest {

    private fun user(id: String, content: String) = ChatMessage(id = id, role = Role.User, content = content)

    @Test
    fun applyMemoryDecayForgetsOldEventsButKeepsProfile() {
        val now = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
        val character = Character(
            id = "c1",
            longTerm = listOf(
                LongTermMemory(
                    category = MemoryCategory.Events,
                    key = "旧事件",
                    value = "v",
                    importance = 1,
                    lastRecalled = "2026-01-01T00:00:00Z",
                ),
                LongTermMemory(
                    category = MemoryCategory.UserProfile,
                    key = "长期偏好",
                    value = "v",
                    lastRecalled = "2000-01-01T00:00:00Z",
                ),
            ),
        )
        val decayed = applyMemoryDecay(character, now)
        assertTrue(decayed.longTerm.none { it.key == "旧事件" })
        assertTrue(decayed.longTerm.any { it.key == "长期偏好" })
    }

    @Test
    fun applyMemoryDecayDropsStaleResolvedPromises() {
        val now = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
        val character = Character(
            id = "c1",
            longTerm = listOf(
                LongTermMemory(
                    category = MemoryCategory.Promises,
                    key = "旧约定",
                    value = "v",
                    status = PromiseStatus.Resolved,
                    lastRecalled = "2026-01-01T00:00:00Z",
                ),
            ),
        )
        assertTrue(applyMemoryDecay(character, now).longTerm.isEmpty())
    }

    @Test
    fun trimInstantHonorsExtractedAt() {
        val items = (1..5).map { index ->
            ChatMessage(
                id = "m$index",
                role = Role.User,
                content = "x",
                extractedAt = if (index <= 3) "done" else null,
            )
        }
        val trimmed = trimInstant(items, limit = 4, floor = 2)
        assertEquals(listOf("m4", "m5"), trimmed.map { it.id })
    }

    @Test
    fun trimShortTermKeepsUnprocessedOlderItems() {
        val items = listOf(
            ShortTermMemory(id = "oldDone", analyzedAt = "t", lorebookScannedAt = "t"),
            ShortTermMemory(id = "oldPending", analyzedAt = null),
            ShortTermMemory(id = "recent", analyzedAt = "t", lorebookScannedAt = "t"),
        )
        val trimmed = trimShortTerm(items, floor = 1)
        assertEquals(listOf("oldPending", "recent"), trimmed.map { it.id })
    }

    @Test
    fun addShortTermMemoryRequiresResolvableSource() {
        val character = Character(id = "c1", instant = listOf(user("u1", "用户喜欢咖啡")))
        val rejected = addShortTermMemory(
            list = emptyList(),
            draft = ShortTermDraft(content = "用户喜欢咖啡", sourceMessageIds = listOf("ghost")),
            timestamp = null,
            sources = knownSources(character),
        )
        assertFalse(rejected.accepted)

        val accepted = addShortTermMemory(
            list = emptyList(),
            draft = ShortTermDraft(content = "用户喜欢咖啡", sourceMessageIds = listOf("u1")),
            timestamp = "2026-08-01T12:00:00Z",
            sources = knownSources(character),
        )
        assertTrue(accepted.accepted)
        assertEquals(listOf("u1"), accepted.list[0].sourceMessageIds)
    }

    @Test
    fun addShortTermMemoryMergesSameEventIdentity() {
        val character = Character(id = "c1", instant = listOf(user("u1", "事件")))
        val sources = knownSources(character)
        val draft = ShortTermDraft(
            content = "用户在咖啡馆",
            sourceMessageIds = listOf("u1"),
            eventTime = "2026-08-01T12:00:00Z",
        )
        val first = addShortTermMemory(emptyList(), draft, "2026-08-01T12:00:00Z", sources)
        val second = addShortTermMemory(
            first.list,
            draft.copy(sourceMessageIds = listOf("u1")),
            "2026-08-01T12:30:00Z",
            sources,
        )
        assertTrue(second.accepted)
        assertEquals(1, second.list.size)
    }

    @Test
    fun upsertLongTermRejectsUntraceableMemory() {
        val character = Character(id = "c1", instant = listOf(user("u1", "用户喜欢咖啡")))
        val item = LongTermMemory(
            category = MemoryCategory.Habits,
            subject = MemorySubject.User,
            key = "饮品",
            value = "用户喜欢咖啡",
            sourceMessageIds = listOf("ghost"),
            evidence = "用户喜欢咖啡",
        )
        val (list, ok) = upsertLongTermMemory(character, item, character.longTerm)
        assertFalse(ok)
        assertTrue(list.isEmpty())
    }

    @Test
    fun upsertLongTermAcceptsTraceableMemory() {
        val character = Character(id = "c1", instant = listOf(user("u1", "用户喜欢咖啡")))
        val item = LongTermMemory(
            category = MemoryCategory.Habits,
            subject = MemorySubject.User,
            key = "饮品",
            value = "用户喜欢咖啡",
            sourceMessageIds = listOf("u1"),
            evidence = "用户喜欢咖啡",
        )
        val (list, ok) = upsertLongTermMemory(character, item, character.longTerm)
        assertTrue(ok)
        assertEquals(1, list.size)
        assertEquals(5, list[0].importance)
    }

    @Test
    fun upsertLongTermPreservesSemanticallyDifferentValues() {
        val character = Character(id = "c1", instant = listOf(user("u1", "用户喜欢咖啡，但用户害怕打雷。")))
        val existing = LongTermMemory(
            category = MemoryCategory.UserProfile,
            subject = MemorySubject.User,
            key = "喜好",
            value = "用户喜欢咖啡",
            sourceMessageIds = listOf("u1"),
            evidence = "用户喜欢咖啡",
            importance = 5,
        )
        val incoming = LongTermMemory(
            category = MemoryCategory.UserProfile,
            subject = MemorySubject.User,
            key = "喜好",
            value = "用户害怕打雷",
            sourceMessageIds = listOf("u1"),
            evidence = "用户害怕打雷",
            importance = 5,
        )
        val (list, ok) = upsertLongTermMemory(character, incoming, listOf(existing))
        assertTrue(ok)
        assertEquals(1, list.size)
        // Legacy keeps the existing value and records the competitor as a conflict.
        assertEquals("用户喜欢咖啡", list[0].value)
        assertTrue(list[0].conflicts.any { it.value == "用户害怕打雷" })
    }

    @Test
    fun resolveMemoryConflictPrefersNewestThenLongerEvidence() {
        val older = MemoryConflictCandidate("old", "很长的证据内容", listOf("a"), "2026-10-01T00:00:00Z", isMain = true)
        val newer = MemoryConflictCandidate("new", "短证", listOf("b"), "2026-10-02T00:00:00Z", isMain = false)
        assertEquals("new", resolveMemoryConflict(listOf(older, newer))?.value)

        val sameTimeLong = MemoryConflictCandidate("long", "很长的证据内容", listOf("c"), "2026-10-02T00:00:00Z", isMain = false)
        assertEquals("long", resolveMemoryConflict(listOf(newer, sameTimeLong))?.value)
    }

    @Test
    fun dedupeLongTermPreservesConflictingValues() {
        val first = LongTermMemory(
            category = MemoryCategory.UserProfile,
            subject = MemorySubject.User,
            key = "喜好",
            value = "用户喜欢咖啡",
            evidence = "用户喜欢咖啡",
            importance = 5,
        )
        val second = first.copy(value = "用户害怕打雷", evidence = "用户害怕打雷")
        val merged = dedupeLongTerm(listOf(first, second), MemoryCategory.UserProfile)
        assertEquals(1, merged.size)
        // Legacy `dedupeLongTermList`: without conflictedAt the later item's value wins.
        assertEquals("用户害怕打雷", merged[0].value)
    }
}
