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
 * The 待办 rules: which promises are listed at all, which may be pushed, when an item is 已超时,
 * and which promises have their reminder retracted on a status flip.
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

    @Test
    fun flippedPromiseIsRetractedAndUnchangedOnesAreNot() {
        val before = character.copy(
            longTerm = listOf(userPromise(id = "kept"), userPromise(id = "flipped")),
        )
        val after = before.copy(
            longTerm = listOf(
                userPromise(id = "kept"),
                userPromise(id = "flipped", status = PromiseStatus.Resolved),
            ),
        )

        assertEquals(listOf("flipped"), promisesLosingActiveStatus(before, after))
    }

    @Test
    fun deletedPromiseAlsoCountsAsRetracted() {
        val before = character.copy(longTerm = listOf(userPromise(id = "gone"), userPromise(id = "kept")))
        val after = before.copy(longTerm = listOf(userPromise(id = "kept")))

        assertEquals(listOf("gone"), promisesLosingActiveStatus(before, after))
    }

    @Test
    fun alreadyInactivePromisesAreNotRetractedAgain() {
        val before = character.copy(longTerm = listOf(userPromise(id = "old", status = PromiseStatus.Cancelled)))
        val after = before.copy(longTerm = listOf(userPromise(id = "old", status = PromiseStatus.Resolved)))

        assertTrue(promisesLosingActiveStatus(before, after).isEmpty())
    }

    @Test
    fun unrelatedMemoryEditsChangeNothing() {
        val before = character.copy(longTerm = listOf(userPromise(id = "p1")))
        val after = before.copy(
            dynamicState = before.dynamicState.copy(currentSituation = "在厨房"),
            longTerm = listOf(userPromise(id = "p1", key = "给角色做饭（改）")),
        )

        assertTrue(promisesLosingActiveStatus(before, after).isEmpty())
    }

    @Test
    fun activePromiseIdsCoversEveryLivePromiseOfALeavingCharacter() {
        val character = character.copy(
            longTerm = listOf(
                userPromise(id = "a"),
                userPromise(id = "b", status = PromiseStatus.Resolved),
                userPromise(id = "c"),
            ),
        )

        assertEquals(listOf("a", "c"), activePromiseIds(character))
    }
}
