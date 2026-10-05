package com.deeptalking.domain.memory

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
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
}
