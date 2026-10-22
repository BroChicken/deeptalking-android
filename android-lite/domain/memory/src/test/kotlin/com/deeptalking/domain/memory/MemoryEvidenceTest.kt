package com.deeptalking.domain.memory

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class MemoryEvidenceTest {

    private fun userMessage(id: String, content: String, timestamp: String? = null): ChatMessage =
        ChatMessage(id = id, role = Role.User, content = content, timestamp = timestamp)

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

    // ---- promise confirmation eligibility (待办分桶) -------------------------------------

    private fun promise(
        id: String = "p1",
        subject: MemorySubject = MemorySubject.User,
        promisor: String? = "user",
        sourceIds: List<String> = listOf("u1"),
        evidence: String = "我明天要交报告",
        dueAt: String? = null,
        status: PromiseStatus = PromiseStatus.Active,
    ) = LongTermMemory(
        id = id,
        category = MemoryCategory.Promises,
        subject = subject,
        key = "交报告",
        value = "用户明天交报告",
        evidence = evidence,
        sourceMessageIds = sourceIds,
        dueAt = dueAt,
        promisor = promisor,
        promisee = "character",
        status = status,
    )

    @Test
    fun characterSelfPromiseIsStillStoredButNeverPushable() {
        val assistant = assistantMessage("a1", "我会在明天晚上陪你去医院。")
        val character = Character(
            id = "c1",
            instant = listOf(userMessage("u1", "我一个人不敢去。"), assistant),
        )
        val item = promise(
            subject = MemorySubject.Relationship,
            promisor = "character",
            sourceIds = listOf("current_response"),
            evidence = "我会在明天晚上陪你去医院",
            dueAt = "2026-08-10T19:00:00",
        )
        // 角色的单方承诺是真实发生的设定：照常存进记忆……
        assertTrue(isValidAutomaticMemory(character, item, assistantMessage = assistant))
        // ……但它不该变成对用户的待办推送。
        val decision = promisePushDecision(character, item, assistantMessage = assistant)
        assertFalse(decision.pushable)
        assertEquals(PromisePushReason.CHARACTER_SELF_PROMISE, decision.reason)
        assertNull(decision.confirmedDueAt)
        assertNull(decision.confirmedDueAtMillis)
        assertFalse(decision.dueAtNeedsConfirmation)
    }

    @Test
    fun agreementWithOnlyTheUserSideQuotedIsNotPushable() {
        val character = Character(
            id = "c1",
            instant = listOf(
                userMessage("u1", "那我们明天九点见吧。"),
                assistantMessage("a1", "好，明天九点见。"),
            ),
        )
        // 模型只引用了用户的话，却把 subject/promisor 写成"双方约定"。
        val inferred = promise(
            subject = MemorySubject.Relationship,
            promisor = "relationship",
            sourceIds = listOf("u1"),
            evidence = "明天九点见",
        )
        assertTrue("写入闸门本身接受它", isValidAutomaticMemory(character, inferred))
        val inferredDecision = promisePushDecision(character, inferred)
        assertFalse(inferredDecision.pushable)
        assertEquals(PromisePushReason.RELATIONSHIP_NEEDS_BOTH_SIDES, inferredDecision.reason)

        // 双方原话都在（用户 + 角色）才算数。
        val both = inferred.copy(sourceMessageIds = listOf("u1", "a1"))
        val bothDecision = promisePushDecision(character, both)
        assertTrue(bothDecision.pushable)
        assertEquals(PromisePushReason.USER_CONFIRMED, bothDecision.reason)
    }

    @Test
    fun promisePushDecisionRequiresAnActiveUserPromisor() {
        val character = Character(id = "c1", instant = listOf(userMessage("u1", "我明天要交报告")))
        assertEquals(
            PromisePushReason.NOT_ACTIVE_PROMISE,
            promisePushDecision(character, promise(status = PromiseStatus.Resolved)).reason,
        )
        assertEquals(
            PromisePushReason.NOT_USER_PROMISOR,
            promisePushDecision(character, promise(promisor = null)).reason,
        )
        assertEquals(
            PromisePushReason.NOT_USER_PROMISOR,
            promisePushDecision(character, promise(promisor = "小明")).reason,
        )
        assertEquals(
            "用户原话对不上就不是用户说的",
            PromisePushReason.NO_USER_EVIDENCE,
            promisePushDecision(character, promise(evidence = "我明天要写周报")).reason,
        )
        assertTrue(promisePushDecision(character, promise()).pushable)
    }

    @Test
    fun userAgreedPromiseWithLocatableDueTimeIsPushable() {
        val character = Character(
            id = "c1",
            instant = listOf(userMessage("u1", "我明天要交报告。", timestamp = "2026-08-09T12:00:00")),
        )
        val decision = promisePushDecision(character, promise(dueAt = "2026-08-10T09:00:00"))
        assertTrue(decision.pushable)
        assertEquals(PromisePushReason.USER_CONFIRMED, decision.reason)
        assertEquals("2026-08-10T09:00:00", decision.confirmedDueAt)
        assertNotNull(decision.confirmedDueAtMillis)
        assertFalse(decision.dueAtNeedsConfirmation)

        // 没有期限的承诺可以推送，但没有任何可排期的期限。
        val noDue = promisePushDecision(character, promise())
        assertTrue(noDue.pushable)
        assertNull(noDue.confirmedDueAt)
        assertNull(noDue.confirmedDueAtMillis)
        assertFalse(noDue.dueAtNeedsConfirmation)
    }

    @Test
    fun dueTimeTheUserNeverMentionedIsMarkedForConfirmation() {
        val character = Character(
            id = "c1",
            instant = listOf(userMessage("u1", "记得帮我买药", timestamp = "2026-08-01T12:00:00")),
        )
        val item = promise(evidence = "记得帮我买药", dueAt = "2026-08-10T09:00:00")
        val decision = promisePushDecision(character, item)
        assertTrue(decision.pushable)
        assertNull("用户没提时间 → 不建排期", decision.confirmedDueAt)
        assertNull(decision.confirmedDueAtMillis)
        assertTrue("返回里标明待确认期限", decision.dueAtNeedsConfirmation)

        // 期限被模型挪到用户没说的那一天，同样不可信。
        val moved = promisePushDecision(
            character,
            item.copy(dueAt = "2026-09-01T09:00:00"),
        )
        assertTrue(moved.dueAtNeedsConfirmation)
        assertNull(moved.confirmedDueAt)
    }

    @Test
    fun dueAtBackedByEvidenceChecksDatesAndTimeWords() {
        val noTime = listOf(SourceRef("u1", "user", "记得帮我买药。", timestamp = "2026-08-01T12:00:00"))
        assertFalse("用户原话里没有任何时间词", dueAtBackedByEvidence(noTime, "2026-08-10T09:00:00"))
        assertFalse("没有期限就没有依据", dueAtBackedByEvidence(noTime, null))
        assertFalse("没有用户来源", dueAtBackedByEvidence(emptyList(), "2026-08-10T09:00:00"))

        // 相对词按消息自身的时间换算：8-09 说“明天”就是 8-10。
        val tomorrow = listOf(SourceRef("u2", "user", "我明天要交报告。", timestamp = "2026-08-09T12:00:00"))
        assertTrue(dueAtBackedByEvidence(tomorrow, "2026-08-10T09:00:00"))
        assertFalse("挪到另一天就不算", dueAtBackedByEvidence(tomorrow, "2026-08-11T09:00:00"))

        // 绝对日期（ISO 与中文写法）。
        val absolute = listOf(SourceRef("u3", "user", "截止时间是 2026-08-10。", timestamp = "2026-08-01T12:00:00"))
        assertTrue(dueAtBackedByEvidence(absolute, "2026-08-10T18:00:00"))
        val chinese = listOf(SourceRef("u4", "user", "8月10号之前给他。", timestamp = "2026-08-01T12:00:00"))
        assertTrue(dueAtBackedByEvidence(chinese, "2026-08-10T09:00:00"))
        assertFalse(dueAtBackedByEvidence(chinese, "2026-08-12T09:00:00"))

        // 只有粗粒度时间词（没说是哪天）→ 可以接受；没有时间词 → 不行。
        val coarse = listOf(SourceRef("u5", "user", "我晚上去取。", timestamp = "2026-08-01T12:00:00"))
        assertTrue(dueAtBackedByEvidence(coarse, "2026-08-10T20:00:00"))

        // 消息本身没有时间戳时，相对词无法换算，只能要求"出现了时间词"。
        val undated = listOf(SourceRef("u6", "user", "我明天要交报告。"))
        assertTrue(dueAtBackedByEvidence(undated, "2026-08-10T09:00:00"))
    }

    @Test
    fun aShortTermSourceResolvesRelativeWordsAgainstTheMessageTimeNotTheEventTime() {
        // 消息已滚出即时窗口，只剩短期条目：`_sourceTs` 是"当时说的"，eventTime 是事件发生日。
        val character = Character(
            id = "c1",
            shortTerm = listOf(
                ShortTermMemory(
                    id = "s1",
                    content = "用户说明天要交报告",
                    sourceMessageIds = listOf("u1"),
                    sourceRoles = listOf("user"),
                    userEvidence = listOf(SourceEvidence(sourceMessageId = "u1", text = "我明天要交报告")),
                    eventTime = "2026-08-11T09:00:00",
                    sourceTs = "2026-08-09T12:00:00",
                ),
            ),
        )
        val item = promise(sourceIds = listOf("u1"), dueAt = "2026-08-10T09:00:00")

        val decision = promisePushDecision(character, item)

        // 按 8-09 的消息时间换算，"明天"正是 8-10；用事件日（8-11）换算会算成 8-12，把
        // 一条用户明确说过期限的约定误判成「待确认期限」（日期就核对不上）。
        assertTrue(decision.pushable)
        assertFalse(decision.dueAtNeedsConfirmation)
        assertEquals("2026-08-10T09:00:00", decision.confirmedDueAt)
        assertNotNull(decision.confirmedDueAtMillis)
    }

    @Test
    fun containsTemporalExpressionAcceptsRealTimeWordsOnly() {
        assertTrue(containsTemporalExpression("我明天要交报告"))
        assertTrue(containsTemporalExpression("下周三上午开会"))
        assertTrue(containsTemporalExpression("三天后给他"))
        assertTrue(containsTemporalExpression("截止 2026-08-10"))
        assertTrue(containsTemporalExpression("晚上九点见"))
        assertFalse(containsTemporalExpression("记得帮我买药"))
        assertFalse("‘周围’不是时间词", containsTemporalExpression("周围的人都回来了"))
        assertFalse("模糊说法不算可定位期限", containsTemporalExpression("以后再说吧"))
    }

    @Test
    fun persistedConfirmationSurvivesAnUnresolvableSource() {
        // The citing messages have slid out of the window: no source resolves any more.
        val character = Character(id = "c1")
        val item = promise(dueAt = "2026-08-10T09:00:00").copy(
            pushConfirmedAt = "2026-08-09T12:00:00Z",
            pushConfirmedDueAt = "2026-08-10T09:00:00",
        )

        val decision = promisePushDecision(character, item)

        assertTrue("已确认的承诺不得因来源消失而退化", decision.pushable)
        assertEquals(PromisePushReason.USER_CONFIRMED, decision.reason)
        assertEquals("2026-08-10T09:00:00", decision.confirmedDueAt)
        assertNotNull(decision.confirmedDueAtMillis)
        assertFalse(decision.dueAtNeedsConfirmation)
    }

    @Test
    fun persistedConfirmationIsInvalidatedWhenTheDueTimeChanges() {
        val character = Character(id = "c1")
        val confirmed = promise(dueAt = "2026-08-10T09:00:00").copy(
            pushConfirmedAt = "2026-08-09T12:00:00Z",
            pushConfirmedDueAt = "2026-08-10T09:00:00",
        )

        // The due time was rewritten: the marker no longer applies, so the strict gate re-runs
        // and — with no resolvable source — the promise is not pushable.
        val decision = promisePushDecision(character, confirmed.copy(dueAt = "2026-08-11T09:00:00"))

        assertFalse(decision.pushable)
        assertEquals(PromisePushReason.NO_USER_EVIDENCE, decision.reason)
    }

    @Test
    fun persistedConfirmationCannotReviveANonActivePromise() {
        val character = Character(id = "c1")
        val resolved = promise(dueAt = "2026-08-10T09:00:00", status = PromiseStatus.Resolved).copy(
            pushConfirmedAt = "2026-08-09T12:00:00Z",
            pushConfirmedDueAt = "2026-08-10T09:00:00",
        )

        assertEquals(
            PromisePushReason.NOT_ACTIVE_PROMISE,
            promisePushDecision(character, resolved).reason,
        )
    }

    @Test
    fun unmarkedPromisesStillUseTheStrictGate() {
        // No confirmation marker → identical to the pre-existing strict behaviour.
        val decision = promisePushDecision(Character(id = "c1"), promise(dueAt = "2026-08-10T09:00:00"))
        assertFalse("没有确认标记时必须回到严格判定", decision.pushable)
        assertEquals(PromisePushReason.NO_USER_EVIDENCE, decision.reason)

        assertFalse(hasValidPushConfirmation(promise()))
        assertTrue(
            hasValidPushConfirmation(
                promise(dueAt = "2026-08-10T09:00:00")
                    .copy(pushConfirmedAt = "t", pushConfirmedDueAt = "2026-08-10T09:00:00"),
            ),
        )
    }

    @Test
    fun persistedConfirmationCoversAUserAgreedPromiseWithoutADueTime() {
        // No due time at all: the agreement is real, there is simply no date to remind on. The
        // marker (a null due confirmed as null) must still hold once the sources are gone —
        // otherwise the row silently slides into 「不提醒」.
        val character = Character(id = "c1")
        val item = promise().copy(pushConfirmedAt = "t", pushConfirmedDueAt = null)

        assertTrue(hasValidPushConfirmation(item))
        val decision = promisePushDecision(character, item)
        assertTrue(decision.pushable)
        assertTrue(decision.confirmedDueAt == null)
        assertFalse(decision.dueAtNeedsConfirmation)
    }

    @Test
    fun anUnlocatedDueTimeIsNeverTreatedAsAConfirmation() {
        // A stamp whose due no longer matches (here: stamped as "no due", now carrying one) must
        // never promote the model's own date into a confirmed one.
        val character = Character(id = "c1")
        val stamped = promise(dueAt = "2026-08-10T09:00:00")
            .copy(pushConfirmedAt = "t", pushConfirmedDueAt = null)

        assertFalse(hasValidPushConfirmation(stamped))
        assertFalse(promisePushDecision(character, stamped).pushable)
    }
}
