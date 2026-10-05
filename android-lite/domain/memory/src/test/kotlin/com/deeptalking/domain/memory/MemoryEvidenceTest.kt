package com.deeptalking.domain.memory

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.ShortTermMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class MemoryEvidenceTest {

    private fun userMessage(id: String, content: String): ChatMessage =
        ChatMessage(id = id, role = Role.User, content = content)

    private fun assistantMessage(id: String, content: String): ChatMessage =
        ChatMessage(id = id, role = Role.Assistant, content = content)

    @Test
    fun evidenceMatchesSourceIgnoresWhitespaceAndPunctuation() {
        assertTrue(evidenceMatchesSource("请把说话风格改得更冷淡一些。", "说话风格改得更冷淡"))
        assertFalse(evidenceMatchesSource("今天天气不错", "用户喜欢吃苹果"))
        assertFalse(evidenceMatchesSource("a", "ab"))
    }

    @Test
    fun evidenceMatchesSummaryNeedsMultipleOverlappingFragments() {
        assertTrue(evidenceMatchesSummary("用户明天要去北京出差三天", "用户要去北京出差"))
        assertFalse(evidenceMatchesSummary("用户明天要去北京出差三天", "用户喜欢吃苹果喝牛奶"))
    }

    @Test
    fun hasValidUserEvidenceRequiresRealUserSource() {
        val character = Character(
            id = "c1",
            instant = listOf(
                userMessage("u1", "Please change your speaking style to formal English."),
                assistantMessage("a1", "好的。"),
            ),
        )
        assertNotNull(hasValidUserEvidence(character, listOf("u1"), "change your speaking style to formal English"))
        assertNull("assistant sources are not user evidence", hasValidUserEvidence(character, listOf("a1"), "好的"))
        assertNull("unknown source id is rejected", hasValidUserEvidence(character, listOf("nope"), "change"))
        assertNull("mismatched evidence is rejected", hasValidUserEvidence(character, listOf("u1"), "完全不同的话"))
    }

    @Test
    fun shortTermOnlySourcesCannotSatisfyUserEvidence() {
        // A short-term source id no longer present in the live window has no role.
        val character = Character(
            id = "c1",
            shortTerm = listOf(ShortTermMemory(id = "s1", content = "用户喜欢咖啡", sourceMessageIds = listOf("u9"))),
        )
        assertNull(hasValidUserEvidence(character, listOf("u9"), "用户喜欢咖啡"))
    }

    @Test
    fun staticEditIntentRequiresExplicitUserRequest() {
        val change = listOf(SourceRef("u1", "user", "Please change your speaking style to formal English."))
        val mention = listOf(SourceRef("u2", "user", "I enjoy formal English."))
        val deny = listOf(SourceRef("u3", "user", "不要修改我的说话风格"))
        assertTrue(hasStaticEditIntent("speakingStyle", change))
        assertFalse(hasStaticEditIntent("personality", mention))
        assertFalse(hasStaticEditIntent("speakingStyle", deny))
        assertFalse(hasStaticEditIntent("unknownField", change))
    }

    @Test
    fun memoriesSemanticallyDifferDetectsConflicts() {
        assertTrue(memoriesSemanticallyDiffer("用户喜欢咖啡", "用户害怕打雷"))
        assertFalse(memoriesSemanticallyDiffer("用户每天早晨跑步", "用户每天早上跑步"))
    }

    @Test
    fun selfLearnImportanceBonusesUsageAndAgesLongIdleFacts() {
        val now = Instant.parse("2026-10-05T00:00:00Z")
        val recentUsage = now.minus(5, ChronoUnit.DAYS).toString()
        val staleRecall = now.minus(200, ChronoUnit.DAYS).toString()
        val character = Character(
            id = "c1",
            longTerm = listOf(
                LongTermMemory(
                    id = "used",
                    category = MemoryCategory.Habits,
                    key = "喝茶",
                    value = "用户爱喝茶",
                    usageCount = 6,
                    lastUsageAt = recentUsage,
                    lastRecalled = recentUsage,
                    createdAt = recentUsage,
                ),
                LongTermMemory(
                    id = "stale",
                    category = MemoryCategory.UserProfile,
                    key = "旧信息",
                    value = "用户曾在某地",
                    lastRecalled = staleRecall,
                    createdAt = staleRecall,
                    learnedBonus = 0,
                ),
            ),
        )
        val learned = selfLearnMemoryImportance(character, nowMillis = now.toEpochMilli())
        assertEquals(3, learned.longTerm.first { it.id == "used" }.learnedBonus)
        assertEquals(-3, learned.longTerm.first { it.id == "stale" }.learnedBonus)
    }

    @Test
    fun learnedBonusFeedsEffectiveImportance() {
        val now = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
        val base = LongTermMemory(
            id = "m",
            category = MemoryCategory.Habits,
            key = "k",
            value = "v",
            importance = 4,
            learnedBonus = 3,
            lastRecalled = Instant.ofEpochMilli(now).toString(),
            createdAt = Instant.ofEpochMilli(now).toString(),
        )
        assertEquals(7.0, computeEffectiveImportance(base, now), 0.0001)
    }

    @Test
    fun isValidAutomaticMemoryEnforcesCategorySubjectEvidenceAndRole() {
        val character = Character(
            id = "c1",
            instant = listOf(
                userMessage("u1", "用户喜欢喝美式咖啡。"),
                assistantMessage("a1", "我记住了。"),
            ),
        )
        val valid = LongTermMemory(
            category = MemoryCategory.UserProfile,
            subject = MemorySubject.User,
            key = "饮品",
            value = "用户喜欢咖啡",
            sourceMessageIds = listOf("u1"),
            evidence = "用户喜欢喝美式咖啡",
        )
        assertTrue(isValidAutomaticMemory(character, valid))

        assertFalse(
            "assistant sources are not user evidence",
            isValidAutomaticMemory(character, valid.copy(sourceMessageIds = listOf("a1"))),
        )
        assertFalse("subject=legacy is rejected", isValidAutomaticMemory(character, valid.copy(subject = MemorySubject.Legacy)))
        assertFalse("subject=character is rejected", isValidAutomaticMemory(character, valid.copy(subject = MemorySubject.Character)))
        assertFalse("missing evidence is rejected", isValidAutomaticMemory(character, valid.copy(evidence = "")))
        assertFalse(
            "profile requires all-user sources",
            isValidAutomaticMemory(character, valid.copy(sourceMessageIds = listOf("u1", "a1"))),
        )
    }

    @Test
    fun isValidAutomaticMemoryPromiseRules() {
        val character = Character(
            id = "c1",
            instant = listOf(userMessage("u1", "用户答应明天请客。")),
        )
        val userPromise = LongTermMemory(
            category = MemoryCategory.Promises,
            subject = MemorySubject.User,
            key = "请客",
            value = "用户答应请客",
            sourceMessageIds = listOf("u1"),
            evidence = "用户答应明天请客",
            promisor = "user",
        )
        assertTrue(isValidAutomaticMemory(character, userPromise))
        assertFalse(
            "character-subject promises are rejected",
            isValidAutomaticMemory(character, userPromise.copy(subject = MemorySubject.Character)),
        )
    }

    @Test
    fun resolvePromiseSourcesSupportsCurrentResponse() {
        val character = Character(id = "c1")
        val assistant = assistantMessage("a1", "我会一直陪着你。")
        val item = LongTermMemory(
            category = MemoryCategory.Promises,
            subject = MemorySubject.Relationship,
            key = "陪伴",
            value = "角色承诺陪伴用户",
            sourceMessageIds = listOf("current_response"),
            evidence = "我会一直陪着你",
            promisor = "character",
        )
        assertNull("no assistant message means no resolution", resolvePromiseSources(character, item, null))
        assertNotNull(resolvePromiseSources(character, item, assistant))
        assertNull(resolvePromiseSources(character, item.copy(evidence = "完全不同的话"), assistant))
    }

    @Test
    fun resolveDynamicStateSourcesSupportsCurrentResponse() {
        val character = Character(id = "c1")
        val assistant = assistantMessage("a1", "（笑了笑）我现在心情不错。")
        assertNotNull(
            resolveDynamicStateSources(character, listOf("current_response"), "我现在心情不错", assistant),
        )
        assertNull(
            "current_response must be the only source",
            resolveDynamicStateSources(character, listOf("current_response", "u1"), "我现在心情不错", assistant),
        )
    }

    @Test
    fun shouldCaptureUserTurnSkipsGreetingsAndShortTurns() {
        assertTrue(shouldCaptureUserTurn("我今天想去公园散步"))
        assertFalse(shouldCaptureUserTurn("你好"))
        assertFalse(shouldCaptureUserTurn("嗯嗯"))
        assertFalse(shouldCaptureUserTurn("好"))
    }
}
