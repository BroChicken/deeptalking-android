package com.deeptalking.domain.memory

import com.deeptalking.core.model.Todo
import com.deeptalking.core.model.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TodoPolicyTest {

    private val dayMillis = 86_400_000L

    private fun todo(
        id: String = "t1",
        status: TodoStatus = TodoStatus.Active,
        closedAt: String? = null,
        updatedAt: String? = null,
    ) = Todo(
        id = id,
        title = "交报告",
        detail = "详情",
        dueAt = "2026-08-10T09:00:00.000Z",
        status = status,
        closedAt = closedAt,
        updatedAt = updatedAt,
    )

    @Test
    fun `pruneClosedTodos keeps active and recent closed todos`() {
        val now = Instant.parse("2026-08-20T00:00:00Z").toEpochMilli()
        val recent = todo(id = "recent", status = TodoStatus.Done, closedAt = "2026-08-15T00:00:00Z")
        val old = todo(id = "old", status = TodoStatus.Cancelled, closedAt = "2026-07-01T00:00:00Z")
        val active = todo(id = "active")
        val kept = TodoPolicy.pruneClosedTodos(listOf(active, recent, old), now).map { it.id }
        assertEquals(listOf("active", "recent"), kept)
    }

    @Test
    fun `a closed todo whose time cannot be parsed is kept rather than silently dropped`() {
        val now = Instant.parse("2026-08-20T00:00:00Z").toEpochMilli()
        val unknown = todo(id = "unknown", status = TodoStatus.Done, closedAt = "not-a-time")
        assertEquals(1, TodoPolicy.pruneClosedTodos(listOf(unknown), now).size)
    }

    @Test
    fun `todoStatusOf maps the model's terminal tokens`() {
        assertEquals(TodoStatus.Done, TodoPolicy.todoStatusOf("done"))
        assertEquals(TodoStatus.Done, TodoPolicy.todoStatusOf("completed"))
        assertEquals(TodoStatus.Cancelled, TodoPolicy.todoStatusOf("cancelled"))
        assertEquals(TodoStatus.Cancelled, TodoPolicy.todoStatusOf("关闭"))
        assertEquals(null, TodoPolicy.todoStatusOf("active"))
        assertEquals(null, TodoPolicy.todoStatusOf("whatever"))
    }

    @Test
    fun `isUserAgreement accepts clear yes and rejects refusals or hedging`() {
        assertTrue(TodoPolicy.isUserAgreement("好的，那就明天下午三点"))
        assertTrue(TodoPolicy.isUserAgreement("可以"))
        assertTrue(TodoPolicy.isUserAgreement("嗯"))
        assertTrue(TodoPolicy.isUserAgreement("一言为定"))
        assertFalse(TodoPolicy.isUserAgreement("不可以"))
        assertFalse(TodoPolicy.isUserAgreement("算了，不去了"))
        assertFalse(TodoPolicy.isUserAgreement("我考虑一下"))
        assertFalse(TodoPolicy.isUserAgreement(""))
    }

    @Test
    fun `isUserRefusal is the keyword stage's only veto and misses non-covering wording`() {
        assertTrue(TodoPolicy.isUserRefusal("不可以"))
        assertTrue(TodoPolicy.isUserRefusal("不用了，我不想去"))
        assertTrue(TodoPolicy.isUserRefusal("算了，改天吧"))
        // 关键词覆盖不到的说法不否决——交给模型判断。
        assertFalse(TodoPolicy.isUserRefusal("那就听你的吧"))
        assertFalse(TodoPolicy.isUserRefusal("嗯嗯"))
        assertFalse(TodoPolicy.isUserRefusal(""))
    }

    @Test
    fun `isClosedWithinRetention mirrors the retention window`() {
        val now = Instant.parse("2026-08-20T00:00:00Z").toEpochMilli()
        val boundary = Instant.ofEpochMilli(now - 29 * dayMillis).toString()
        val tooOld = Instant.ofEpochMilli(now - 31 * dayMillis).toString()
        assertTrue(TodoPolicy.isClosedWithinRetention(todo(id = "a"), now))
        assertTrue(TodoPolicy.isClosedWithinRetention(todo(id = "b", status = TodoStatus.Done, closedAt = boundary), now))
        assertFalse(TodoPolicy.isClosedWithinRetention(todo(id = "c", status = TodoStatus.Done, closedAt = tooOld), now))
    }
}
