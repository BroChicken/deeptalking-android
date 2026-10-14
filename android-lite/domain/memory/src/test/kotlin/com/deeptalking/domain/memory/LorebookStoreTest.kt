package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.Role
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
    fun keywordlessEntryFallsBackToItsNameAsTrigger() {
        val result = upsertLorebookEntry(emptyList(), LorebookProposal("无关键词", "内容"))
        assertTrue(result.ok)
        assertEquals(listOf("无关键词"), result.entry?.keywords)
        assertFalse(result.entry?.alwaysActive ?: true)
    }

    @Test
    fun overLimitContentIsRejectedInsteadOfTruncated() {
        val tooLong = "很长的设定".repeat(200)
        val result = upsertLorebookEntry(emptyList(), LorebookProposal("超长条目", tooLong, listOf("超长")))
        assertFalse(result.ok)
        assertTrue(result.list.isEmpty())
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
    fun nearNameReplacesExistingModelEntryInsteadOfAppending() {
        val first = upsertLorebookEntry(emptyList(), LorebookProposal("银月商会", "西街的商会，经营茶叶。", listOf("银月")))
        val replaced = upsertLorebookEntry(first.list, LorebookProposal("银月商行", "经营药材。", listOf("银月")))
        assertTrue(replaced.ok)
        assertFalse(replaced.created)
        assertEquals(1, replaced.list.size)
        assertEquals("经营药材。", replaced.list[0].content)
        assertFalse(replaced.list[0].content.contains("西街"))
    }

    @Test
    fun sameNameUpdateRewritesContentShorterAndCanClearAlwaysActive() {
        val first = upsertLorebookEntry(
            emptyList(),
            LorebookProposal("赤月王国", "西侧王国，城墙高耸，常年积雪，商队往来不绝。", listOf("赤月"), alwaysActive = true),
        )
        assertTrue(first.list[0].alwaysActive)
        val longContent = first.list[0].content

        val second = upsertLorebookEntry(first.list, LorebookProposal("赤月王国", "西侧王国。", listOf("赤月"), alwaysActive = false))

        assertTrue(second.ok)
        assertEquals(1, second.list.size)
        assertEquals("西侧王国。", second.list[0].content)
        assertTrue(second.list[0].content.length < longContent.length)
        assertFalse(second.list[0].alwaysActive)
    }

    @Test
    fun replaceLorebookEntryTakesIncomingBodyButKeepsIdentityAndBookkeeping() {
        val existing = LorebookEntry(
            id = "e1",
            name = "王都",
            content = "很长的旧内容。",
            keywords = listOf("王都", "旧词"),
            alwaysActive = true,
            origin = LorebookOrigin.Model,
            memberName = "甲",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
            mentions = 3,
            order = 7,
        )
        val incoming = LorebookEntry(
            content = "新内容。",
            keywords = listOf("新词", "新词"),
            alwaysActive = false,
            updatedAt = "2026-02-01T00:00:00Z",
        )

        val replaced = replaceLorebookEntry(existing, incoming)

        assertEquals("新内容。", replaced.content)
        assertEquals(listOf("新词"), replaced.keywords)
        assertFalse(replaced.alwaysActive)
        assertTrue(replaced.enabled)
        assertEquals(0, replaced.misses)
        // Identity/bookkeeping stays with the existing entry.
        assertEquals("e1", replaced.id)
        assertEquals("王都", replaced.name)
        assertEquals("2026-01-01T00:00:00Z", replaced.createdAt)
        assertEquals(3, replaced.mentions)
        assertEquals(7, replaced.order)
        assertEquals("甲", replaced.memberName)
        assertEquals("2026-02-01T00:00:00Z", replaced.updatedAt)
    }

    @Test
    fun replaceLorebookEntryKeepsOldKeywordsOnlyWhenIncomingHasNone() {
        val existing = LorebookEntry(id = "e", name = "城", content = "x", keywords = listOf("旧一", "旧二"))

        val replaced = replaceLorebookEntry(existing, LorebookEntry(name = "城", content = "y"))

        assertEquals(listOf("旧一", "旧二"), replaced.keywords)
    }

    @Test
    fun userEntryWithNearNameIsProtectedOnSimilarPath() {
        val user = LorebookEntry(
            id = "u",
            name = "银月商会",
            content = "用户手写。",
            keywords = listOf("银月"),
            origin = LorebookOrigin.User,
        )

        val result = upsertLorebookEntry(listOf(user), LorebookProposal("银月商行", "AI 版本。", listOf("银月")))

        assertTrue(result.ok)
        assertTrue(result.mergedIntoUser)
        assertEquals(1, result.list.size)
        assertSame(user, result.list[0])
        assertEquals("用户手写。", result.list[0].content)
    }

    @Test
    fun alwaysActiveCapDemotesTheEntryInsteadOfDroppingIt() {
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
        // The ceiling must not throw the content away: the entry is stored keyword-driven.
        val demoted = upsertLorebookEntry(list, LorebookProposal("庚", "庚的内容", alwaysActive = true))
        assertTrue(demoted.ok)
        assertFalse(demoted.entry!!.alwaysActive)
        assertEquals("庚的内容", demoted.list.last().content)
        // …and its own name becomes the trigger, so it is still injectable.
        assertEquals(listOf("庚"), demoted.list.last().keywords)
    }

    @Test
    fun omittedAlwaysActiveKeepsTheStoredValueAndExplicitFalseClearsIt() {
        val stored = listOf(
            LorebookEntry(id = "lore1", name = "世界前提", content = "旧", origin = LorebookOrigin.Model, alwaysActive = true),
        )
        // The model did not echo the field → the entry must stay 常驻.
        val omitted = upsertLorebookEntry(stored, LorebookProposal("世界前提", "新内容"))
        assertTrue(omitted.ok)
        assertTrue(omitted.entry!!.alwaysActive)
        assertEquals("新内容", omitted.entry!!.content)
        // An explicit false is an answer and may switch it off.
        val cleared = upsertLorebookEntry(stored, LorebookProposal("世界前提", "新内容", alwaysActive = false))
        assertFalse(cleared.entry!!.alwaysActive)
    }

    @Test
    fun promotingAnEntryToAlwaysActiveRespectsTheCeilingWithoutLosingContent() {
        val active = (1..AppLimits.Lorebook.MAX_ALWAYS_ACTIVE).map {
            LorebookEntry(id = "a$it", name = "常驻$it", content = "c", origin = LorebookOrigin.Model, alwaysActive = true)
        }
        val plain = LorebookEntry(id = "p1", name = "普通条目", content = "旧内容", origin = LorebookOrigin.Model)
        val promoted = upsertLorebookEntry(active + plain, LorebookProposal("普通条目", "新内容", alwaysActive = true))
        assertTrue(promoted.ok)
        assertFalse(promoted.entry!!.alwaysActive)
        assertEquals("新内容", promoted.entry!!.content)

        // With room it promotes normally, and an already-active entry is never demoted by an
        // update that does not mention the field.
        val room = active.drop(1) + plain
        assertTrue(upsertLorebookEntry(room, LorebookProposal("普通条目", "新内容", alwaysActive = true)).entry!!.alwaysActive)
        assertTrue(upsertLorebookEntry(active, LorebookProposal("常驻1", "改后内容")).entry!!.alwaysActive)
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
    fun generatedNormalizationMarksModelAndPromotesKeywordlessUpToCap() {
        val entries = listOf(
            LorebookEntry(id = "e0", name = "显式常驻", content = "c", origin = LorebookOrigin.User, alwaysActive = true),
            LorebookEntry(id = "e1", name = "无名一", content = "c", origin = LorebookOrigin.User),
            LorebookEntry(id = "e2", name = "有关键词", content = "c", keywords = listOf("k"), origin = LorebookOrigin.User),
            LorebookEntry(id = "e3", name = "无名二", content = "c", origin = LorebookOrigin.User),
        )

        val normalized = normalizeGeneratedLorebook(entries, LorebookLimits(maxAlwaysActive = 2))

        assertTrue(normalized.all { it.origin == LorebookOrigin.Model })
        assertTrue(normalized.first { it.id == "e0" }.alwaysActive)
        assertTrue(normalized.first { it.id == "e1" }.alwaysActive)
        assertFalse(normalized.first { it.id == "e2" }.alwaysActive)
        assertFalse(normalized.first { it.id == "e3" }.alwaysActive)
    }

    @Test
    fun evidenceOverlapAcceptsASingleSubstantiveBigram() {
        assertTrue(lorebookEvidenceOverlaps("银月商会在西街，经营药材", "经营药材贸易"))
        assertFalse(lorebookEvidenceOverlaps("银月商会在西街", "完全无关的内容"))
        assertFalse(lorebookEvidenceOverlaps("xyz", "ab"))
    }

    @Test
    fun resolveLorebookSourcesAcceptsUserAndAssistantMessages() {
        val character = Character(
            id = "c1",
            instant = listOf(
                ChatMessage(id = "m1", role = Role.Assistant, content = "潮汐镇终年多雾"),
                ChatMessage(id = "m2", role = Role.User, content = "我们住在海边"),
            ),
        )
        assertTrue(resolveLorebookSources(character, listOf("m1"), "潮汐镇"))
        assertTrue(resolveLorebookSources(character, listOf("m2"), "海边"))
        assertFalse(resolveLorebookSources(character, listOf("missing"), "潮汐镇"))
        assertFalse(resolveLorebookSources(character, listOf("m1"), "完全无关"))
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
    fun dedupeKeepsLatestModelEntryAndKeepsUserEntryUntouched() {
        val list = listOf(
            LorebookEntry(id = "a", name = "王都", content = "北方的都城。", origin = LorebookOrigin.Model, keywords = listOf("王都", "旧词")),
            LorebookEntry(id = "b", name = "王都", content = "都城有钟楼。", origin = LorebookOrigin.Model, keywords = listOf("王都", "新词")),
            LorebookEntry(id = "c", name = "王都", content = "用户版本。", origin = LorebookOrigin.User),
        )
        val deduped = dedupeLorebook(list)
        assertEquals(2, deduped.size)
        assertEquals(1, deduped.count { it.origin == LorebookOrigin.User })
        val only = deduped.first { it.origin == LorebookOrigin.Model }
        // The later entry wins whole; the earlier content is gone (no appending).
        assertEquals("b", only.id)
        assertEquals("都城有钟楼。", only.content)
        assertFalse(only.content.contains("北方"))
        // Keywords are unioned so nothing becomes unhittable, then capped.
        assertTrue(only.keywords.contains("新词"))
        assertTrue(only.keywords.contains("旧词"))
        assertTrue(only.keywords.size <= AppLimits.Lorebook.KEYWORDS_PER_ENTRY)
    }

    @Test
    fun needsLorebookRewriteOnlyForOverCapModelEntries() {
        val overCap = LorebookEntry(
            id = "ai",
            name = "长",
            content = "x".repeat(AppLimits.Lorebook.CONTENT_CHARS + 1),
            origin = LorebookOrigin.Model,
        )
        assertTrue(needsLorebookRewrite(overCap))

        val atCap = LorebookEntry(
            id = "ok",
            name = "刚好",
            content = "x".repeat(AppLimits.Lorebook.CONTENT_CHARS),
            origin = LorebookOrigin.Model,
        )
        assertFalse(needsLorebookRewrite(atCap))

        val userOverCap = LorebookEntry(
            id = "u",
            name = "用户",
            content = "x".repeat(AppLimits.Lorebook.CONTENT_CHARS + 1),
            origin = LorebookOrigin.User,
        )
        assertFalse(needsLorebookRewrite(userOverCap))
    }
}
