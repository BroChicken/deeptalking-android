package com.deeptalking.feature.settings

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The 待办 rules: which promises are listed at all, which count as user-confirmed, when an item
 * is 已超时, and how a status flip lands on the owning character only.
 *
 * Times are **local** (like `MemoryEvidenceTest`), so the fixtures do not depend on the test
 * JVM's zone: `2026-08-10T09:00:00` is due at 09:00 wherever the tests run.
 */
class PromiseTodoTest {

    /** The one user line every pushable fixture cites. */
    private val userLine = "明天下午三点我做饭给你吃"
    private val charLine = "好，我等你"
    private val sourceAt = "2026-08-09T12:00:00"
    private val dueAt = "2026-08-10T09:00:00"

    private val character = Character(
        id = "c1",
        name = "小夏",
        instant = listOf(
            ChatMessage(id = "m1", role = Role.User, content = userLine, timestamp = sourceAt),
            ChatMessage(id = "m2", role = Role.Assistant, content = charLine, timestamp = sourceAt),
        ),
    )

    private fun userPromise(
        id: String = "p1",
        due: String? = dueAt,
        status: PromiseStatus = PromiseStatus.Active,
        key: String = "给角色做饭",
        value: String = userLine,
    ) = LongTermMemory(
        id = id,
        category = MemoryCategory.Promises,
        subject = MemorySubject.User,
        key = key,
        value = value,
        evidence = userLine,
        sourceMessageIds = listOf("m1"),
        dueAt = due,
        promisor = "user",
        status = status,
    )

    /** Epoch millis of a local time fixture, computed the same way the production code does. */
    private fun at(local: String): Long =
        LocalDateTime.parse(local).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** One hour before / after the fixture's due time. */
    private val beforeDue = at(dueAt) - 3_600_000L
    private val afterDue = at(dueAt) + 3_600_000L

    @Test
    fun dueLabelShowsOnlyAbsoluteTimesAndNeverTheModelsRelativeWording() {
        // 已确认的期限：只渲染成本地绝对时间。
        val confirmed = promiseTodos(character.copy(longTerm = listOf(userPromise())), beforeDue).single()
        assertTrue(Regex("^期限：\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$").matches(dueLabel(confirmed)))

        // 未确认的期限：不得把模型给的相对说法显示出来。
        val vague = userPromise(id = "p2", due = "稍后", key = "做饭", value = "我做饭给你吃")
            .copy(evidence = "我做饭给你吃")
        val label = dueLabel(promiseTodos(character.copy(longTerm = listOf(vague)), beforeDue).single())
        assertTrue(label.contains("待确认期限"))
        assertFalse(label.contains("稍后"))
    }

    @Test
    fun userConfirmedPromiseWithLocatedDueTimeIsOngoing() {
        val todos = promiseTodos(character.copy(longTerm = listOf(userPromise())), beforeDue)

        assertEquals(1, todos.size)
        assertEquals(PromiseTodoSection.ONGOING, todos.single().section)
        assertEquals(at(dueAt), todos.single().dueAtMillis)
        assertNull(todos.single().unconfirmedDueAt)
    }

    @Test
    fun unlocatedDueTimeBecomesNeedsConfirmationAndIsNotScheduled() {
        // No temporal word in the user's line, so the model's `dueAt` is unbacked.
        val bare = Character(
            id = "c1",
            instant = listOf(
                ChatMessage(id = "m1", role = Role.User, content = "我会帮你做饭", timestamp = sourceAt),
            ),
        )
        val promise = userPromise(value = "我会帮你做饭").copy(evidence = "我会帮你做饭")

        val todo = promiseTodos(bare.copy(longTerm = listOf(promise)), beforeDue).single()

        assertEquals(PromiseTodoSection.NEEDS_DUE_CONFIRMATION, todo.section)
        assertNull("待确认期限不排期", todo.dueAtMillis)
        assertEquals(dueAt, todo.unconfirmedDueAt)
    }

    @Test
    fun promiseWithoutADueTimeStaysOngoing() {
        val todos = promiseTodos(character.copy(longTerm = listOf(userPromise(due = null))), beforeDue)

        assertEquals(PromiseTodoSection.ONGOING, todos.single().section)
        assertNull(todos.single().dueAtMillis)
    }

    @Test
    fun characterSelfPromiseIsKeptButNeverPushable() {
        val memory = userPromise().copy(promisor = "character", subject = MemorySubject.Character)

        val todo = promiseTodos(character.copy(longTerm = listOf(memory)), beforeDue).single()

        assertEquals(PromiseTodoSection.NOT_PUSHABLE, todo.section)
        assertNull(todo.dueAtMillis)
    }

    @Test
    fun promiseWithoutUserEvidenceIsNotPushable() {
        // The cited source is the assistant's line, not the user's.
        val memory = userPromise().copy(sourceMessageIds = listOf("m2"))

        assertEquals(
            PromiseTodoSection.NOT_PUSHABLE,
            promiseTodos(character.copy(longTerm = listOf(memory)), beforeDue).single().section,
        )
    }

    @Test
    fun resolvedOrCancelledPromiseLeavesTheListEntirely() {
        val resolved = userPromise(status = PromiseStatus.Resolved)
        val cancelled = userPromise(id = "p2", status = PromiseStatus.Cancelled)

        assertTrue(promiseTodos(character.copy(longTerm = listOf(resolved, cancelled)), beforeDue).isEmpty())
    }

    @Test
    fun oneOffPromisePastItsDueTimeMovesToOverdue() {
        val todos = promiseTodos(character.copy(longTerm = listOf(userPromise())), afterDue)

        assertEquals(PromiseTodoSection.OVERDUE, todos.single().section)
    }

    @Test
    fun aDayOnlyDueTimeLastsTheWholeDueDay() {
        val dayLine = "8月10号我做饭给你吃"
        val dayCharacter = character.copy(
            instant = listOf(
                ChatMessage(id = "m1", role = Role.User, content = dayLine, timestamp = sourceAt),
                ChatMessage(id = "m2", role = Role.Assistant, content = charLine, timestamp = sourceAt),
            ),
        )
        val promise = userPromise(due = "2026-08-10", value = dayLine).copy(evidence = dayLine)
        // 当天 03:00：日期已经"到"了，但这一天还没过完。
        val duringTheDueDay = LocalDateTime.parse("2026-08-10T03:00:00")
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val todo = promiseTodos(dayCharacter.copy(longTerm = listOf(promise)), duringTheDueDay).single()

        // 只写到"日"的期限整天才算到期，不能从 00:00 起就显示"已超时"。
        assertEquals(PromiseTodoSection.ONGOING, todo.section)
        assertTrue(todo.dueIsDayOnly)
        assertEquals("期限：2026-08-10", dueLabel(todo))

        // 过了当天才算过期。
        val afterTheDueDay = LocalDateTime.parse("2026-08-11T00:00:01")
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(
            PromiseTodoSection.OVERDUE,
            promiseTodos(dayCharacter.copy(longTerm = listOf(promise)), afterTheDueDay).single().section,
        )
    }

    @Test
    fun aNonPushableRowNeverAsksTheUserToConfirmTheDate() {
        // 不提醒的原因是承诺方不是用户：提示"去对话里说明日期"解决不了问题，也指错了方向。
        val memory = userPromise().copy(promisor = "character", subject = MemorySubject.Character)

        val todo = promiseTodos(character.copy(longTerm = listOf(memory)), beforeDue).single()

        assertEquals(PromiseTodoSection.NOT_PUSHABLE, todo.section)
        assertEquals("期限未确认", dueLabel(todo))
    }

    @Test
    fun recurringAgreementsNeverBecomeOverdue() {
        val nightly = userPromise(key = "每晚十点", value = "每晚十点我给他打电话")

        val todos = promiseTodos(character.copy(longTerm = listOf(nightly)), afterDue)

        assertEquals(PromiseTodoSection.RECURRING, todos.single().section)
        assertTrue(isRecurringPromise(nightly))
    }

    @Test
    fun foreverAgreementsNeverBecomeOverdue() {
        val forever = userPromise(key = "一直陪着", value = "我会一直陪着他")

        assertEquals(
            PromiseTodoSection.RECURRING,
            promiseTodos(character.copy(longTerm = listOf(forever)), afterDue).single().section,
        )
    }

    @Test
    fun listIsBucketedBySection() {
        val todos = promiseTodos(
            character.copy(
                longTerm = listOf(
                    userPromise(id = "open", due = null),
                    userPromise(id = "late"),
                    userPromise(id = "nightly", key = "每晚聊天", value = "每晚十点我陪他聊天"),
                    userPromise(id = "self").copy(promisor = "character"),
                ),
            ),
            afterDue,
        )

        assertEquals(
            mapOf(
                PromiseTodoSection.ONGOING to listOf("open"),
                PromiseTodoSection.RECURRING to listOf("nightly"),
                PromiseTodoSection.OVERDUE to listOf("late"),
                PromiseTodoSection.NOT_PUSHABLE to listOf("self"),
            ),
            todos.groupBy { it.section }.mapValues { (_, items) -> items.map { it.id } },
        )
    }

    @Test
    fun sectionsRenderInAFixedOrder() {
        val todos = promiseTodos(
            character.copy(
                longTerm = listOf(
                    userPromise(id = "self").copy(promisor = "character"),
                    userPromise(id = "nightly", key = "每晚聊天", value = "每晚十点我陪他聊天"),
                    userPromise(id = "late"),
                    userPromise(id = "open", due = null),
                ),
            ),
            afterDue,
        )

        assertEquals(
            listOf(
                PromiseTodoSection.ONGOING,
                PromiseTodoSection.RECURRING,
                PromiseTodoSection.OVERDUE,
                PromiseTodoSection.NOT_PUSHABLE,
            ),
            todos.map { it.section },
        )
    }

    // ---- confirmed promises whose sources are gone, and the cross-character list ----------

    @Test
    fun confirmedPromiseWhoseSourcesAreGoneStaysOngoing() {
        // The citing messages have slid out of the window: nothing resolves the sources any more.
        val gone = Character(id = "c1", name = "小夏")
        val memory = userPromise().copy(
            pushConfirmedAt = "2026-08-09T12:00:00Z",
            pushConfirmedDueAt = dueAt,
        )

        val todo = promiseTodos(gone.copy(longTerm = listOf(memory)), beforeDue).single()

        assertEquals(PromiseTodoSection.ONGOING, todo.section)
        assertEquals(at(dueAt), todo.dueAtMillis)
    }

    @Test
    fun crossCharacterListAggregatesAllCharactersWithStableOrdering() {
        val a = character.copy(
            id = "cA",
            name = "甲",
            longTerm = listOf(
                userPromise(id = "a-future", key = "b任务"),
                userPromise(id = "a-nodue", key = "c任务", due = null),
            ),
        )
        val b = character.copy(
            id = "cB",
            name = "乙",
            longTerm = listOf(userPromise(id = "b-nodue", key = "a任务", due = null)),
        )

        val todos = promiseTodos(listOf(a, b), beforeDue)

        // 同一分区内：先按期限（有期限在前），再按 key。
        assertEquals(listOf("a-future", "b-nodue", "a-nodue"), todos.map { it.id })
        assertEquals(listOf("cA", "cB", "cA"), todos.map { it.characterId })
        assertEquals(listOf("甲", "乙", "甲"), todos.map { it.characterName })
    }

    @Test
    fun singleCharacterListStillCarriesItsOwningCharacter() {
        val todo = promiseTodos(character.copy(longTerm = listOf(userPromise())), beforeDue).single()
        assertEquals("c1", todo.characterId)
        assertEquals("小夏", todo.characterName)
    }

    @Test
    fun resolvingOnOneCharacterLeavesTheOtherUntouched() {
        val before = character.copy(id = "cA", name = "甲", longTerm = listOf(userPromise(id = "p1")))
        // Same promise id on a different character: must not be affected by the flip.
        val other = character.copy(id = "cB", name = "乙", longTerm = listOf(userPromise(id = "p1")))

        val after = resolvePromiseIn(before, "p1", PromiseStatus.Resolved, "2026-08-09T13:00:00Z")!!
        assertEquals(PromiseStatus.Resolved, after.longTerm.single().status)
        assertEquals("2026-08-09T13:00:00Z", after.longTerm.single().updatedAt)
        assertEquals(PromiseStatus.Active, other.longTerm.single().status)

        // No-ops: unknown id, an already-non-active promise, or a flip to Active.
        assertNull(resolvePromiseIn(other, "nope", PromiseStatus.Resolved, "t"))
        assertNull(resolvePromiseIn(other, "p1", PromiseStatus.Active, "t"))
        assertNull(resolvePromiseIn(before.copy(longTerm = listOf(userPromise(id = "p1", status = PromiseStatus.Cancelled))), "p1", PromiseStatus.Resolved, "t"))
    }
}
