package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.SourceEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MemoryPolicyTest {

    private fun user(id: String, content: String) = ChatMessage(id = id, role = Role.User, content = content)

    @Test
    fun addShortTermMemoryNeverCompactsTheWindowOnWrite() {
        val character = Character(id = "c1", instant = listOf(user("u1", "用户喜欢咖啡")))
        val full = (1..AppLimits.Memory.SHORT_TERM).map { i ->
            ShortTermMemory(
                id = "s$i",
                content = "用户2026-08-0${(i % 9) + 1}上午的事件$i",
                sourceMessageIds = listOf("u1"),
                eventTime = "2026-08-01T0${i % 10}:00:00Z",
            )
        }
        val result = addShortTermMemory(
            list = full,
            draft = ShortTermDraft(content = "用户2026-08-01下午的新事件", sourceMessageIds = listOf("u1")),
            timestamp = "2026-08-01T15:00:00Z",
            sources = knownSources(character),
        )
        assertTrue(result.accepted)
        // Compaction belongs to the fold pass: a write must only append, or the injected
        // window would change mid-stable-period (and the cached prefix would miss).
        assertEquals(full.size + 1, result.list.size)
    }

    @Test
    fun addShortTermMemoryMergesOnlyWithinTheTailWindow() {
        val character = Character(id = "c1", instant = listOf(user("u1", "用户在家看书")))
        val sources = knownSources(character)
        val head = ShortTermMemory(
            id = "s0",
            content = "用户在家看书",
            sourceMessageIds = listOf("u1"),
            eventTime = "2026-08-01T10:00:00Z",
        )
        val filler = (1..AppLimits.Memory.SHORT_TERM_MERGE_WINDOW + 1).map { i ->
            ShortTermMemory(
                id = "f$i",
                content = "用户2026-08-0${(i % 9) + 1}上午的另一件事",
                sourceMessageIds = listOf("u1"),
                eventTime = "2026-08-0${(i % 9) + 1}T11:00:00Z",
            )
        }
        val draft = ShortTermDraft(
            content = "用户在家看书",
            sourceMessageIds = listOf("u1"),
            eventTime = "2026-08-01T10:00:00Z",
        )
        // Far from the tail: appends instead of rewriting the middle of the block.
        val appended = addShortTermMemory(listOf(head) + filler, draft, "2026-08-01T10:00:00Z", sources)
        assertTrue(appended.accepted)
        assertEquals(filler.size + 2, appended.list.size)
        // At the tail the same event still merges in place.
        val merged = addShortTermMemory(filler + head, draft, "2026-08-01T10:00:00Z", sources)
        assertEquals(filler.size + 1, merged.list.size)
    }

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
    fun shortTermIsConsumedByPositionNotByBookkeeping() {
        // There is no per-entry "已分析 / 已扫过" marker any more: the pass consumes the
        // oldest entries above the low-water floor and drops exactly those. This test pins
        // the contract that made the old marker scheme dead-lock the window: an entry the
        // window still holds is work, an entry that left the window is done.
        val items = (0 until 30).map { index ->
            ShortTermMemory(id = "s$index", content = "事件$index")
        }
        val floor = AppLimits.Memory.SHORT_TERM_TRIM_FLOOR
        val consumable = (items.size - floor).coerceAtLeast(0)
        val batch = items.take(minOf(consumable, AppLimits.Memory.ANALYSIS_BATCH))
        val left = items.filterNot { it.id in batch.map { entry -> entry.id }.toSet() }
        assertEquals(10, batch.size)
        assertEquals(floor, left.size)
        assertEquals(listOf("s0"), batch.map { it.id }.take(1))
        assertEquals("s10", left.first().id)
    }

    @Test
    fun addShortTermMemoryAcceptsAnEntryWithoutAResolvableSource() {
        // The fold summarizes the whole window, so a beat the character narrated alone
        // legitimately has no user line behind it; unknown ids are dropped, not fatal
        // (that gate used to discard an entire fold over one bad citation).
        val character = Character(id = "c1", instant = listOf(user("u1", "用户喜欢咖啡")))
        val accepted = addShortTermMemory(
            list = emptyList(),
            draft = ShortTermDraft(content = "角色独自去了阳台", sourceMessageIds = listOf("ghost")),
            timestamp = null,
            sources = knownSources(character),
        )
        assertTrue(accepted.accepted)
        assertTrue(accepted.list.single().sourceMessageIds.isEmpty())

        val withSource = addShortTermMemory(
            list = accepted.list,
            draft = ShortTermDraft(content = "用户喜欢咖啡", sourceMessageIds = listOf("u1")),
            timestamp = "2026-08-01T12:00:00Z",
            sources = knownSources(character),
        )
        assertTrue(withSource.accepted)
        assertEquals(listOf("u1"), withSource.list.last().sourceMessageIds)
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
    fun upsertLongTermAdjudicatesSemanticallyDifferentValues() {
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
        // The trim embedded in the write adjudicates the conflict: the primary value
        // wins the tie and the competitor is dropped.
        assertEquals("用户喜欢咖啡", list[0].value)
        assertTrue(list[0].conflicts.isEmpty())
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

    @Test
    fun addShortTermMemoryKeepsLongContentUntruncated() {
        val long = "用" + "户".repeat(799) + "。"
        val character = Character(id = "c1", instant = listOf(user("u1", long)))
        val result = addShortTermMemory(
            list = emptyList(),
            draft = ShortTermDraft(content = long, sourceMessageIds = listOf("u1")),
            timestamp = "2026-08-01T12:00:00Z",
            sources = knownSources(character),
        )
        assertTrue(result.accepted)
        assertEquals(long.length, result.list[0].content.length)
    }

    @Test
    fun upsertLongTermKeepsDistinctKeysButMergesExactKey() {
        val character = Character(id = "c1", instant = listOf(user("u1", "用户喜欢咖啡")))
        val existing = LongTermMemory(
            category = MemoryCategory.UserProfile,
            subject = MemorySubject.User,
            key = "用户喜欢喝咖啡",
            value = "用户喜欢喝咖啡",
            sourceMessageIds = listOf("u1"),
            evidence = "用户喜欢咖啡",
            importance = 5,
        )
        // A merely similar key must NOT be folded into the existing entry (legacy exact-key match).
        val similar = existing.copy(id = "new", key = "用户喜欢喝咖啡。")
        val (distinct, okDistinct) = upsertLongTermMemory(character, similar, listOf(existing))
        assertTrue(okDistinct)
        assertEquals(2, distinct.size)

        // The exact same key still merges (updates in place) instead of duplicating.
        val exact = existing.copy(id = "new2", value = "用户喜欢喝咖啡，每天早上一杯。")
        val (merged, okMerged) = upsertLongTermMemory(character, exact, listOf(existing))
        assertTrue(okMerged)
        assertEquals(1, merged.size)
    }

    @Test
    fun dedupeShortTermMergesNearDuplicates() {
        val items = listOf(
            ShortTermMemory(id = "1", content = "用户和小雨在咖啡馆聊天", eventTime = "2026-08-01T12:00:00Z"),
            ShortTermMemory(id = "2", content = "用户和小雨在咖啡馆聊天。", eventTime = "2026-08-01T12:30:00Z"),
        )
        val merged = dedupeShortTerm(items)
        assertEquals(1, merged.size)
    }

    @Test
    fun repairMemoriesCollapsesShortAndLongDuplicates() {
        val character = Character(
            id = "c1",
            shortTerm = listOf(
                ShortTermMemory(id = "s1", content = "用户去了公园", eventTime = "2026-08-01T12:00:00Z"),
                ShortTermMemory(id = "s2", content = "用户去了公园。", eventTime = "2026-08-01T12:30:00Z"),
            ),
            longTerm = listOf(
                LongTermMemory(category = MemoryCategory.Habits, subject = MemorySubject.User, key = "习惯", value = "用户每天跑步"),
                LongTermMemory(category = MemoryCategory.Habits, subject = MemorySubject.User, key = "习惯", value = "用户每天跑步。"),
            ),
        )
        val repaired = repairMemories(character)
        assertEquals(1, repaired.shortTerm.size)
        assertEquals(1, repaired.longTerm.size)
    }

    @Test
    fun repairMemoriesRealignsShortTermSourceRoles() {
        // The old merge bug collapsed `sourceRoles` to ["user","assistant"] while
        // keeping 3 ids, leaving the third role-less and dead-locking analysis.
        val poisoned = ShortTermMemory(
            id = "s1",
            content = "某事件",
            sourceMessageIds = listOf("m1", "m2", "m3"),
            sourceRoles = listOf("user", "assistant"),
            userEvidence = listOf(SourceEvidence(sourceMessageId = "m1", text = "你好")),
        )
        val repaired = repairMemories(Character(id = "c1", shortTerm = listOf(poisoned)))
        val fixed = repaired.shortTerm.single()
        assertEquals(3, fixed.sourceMessageIds.size)
        assertEquals(fixed.sourceMessageIds.size, fixed.sourceRoles.size)
        assertTrue(fixed.sourceRoles.none { it.isBlank() })
        // Sources changed, so the entry is read again by the next pass (positional
        // consumption needs no marker to be cleared for that).
        assertEquals(1, fixed.revision)
    }
}
