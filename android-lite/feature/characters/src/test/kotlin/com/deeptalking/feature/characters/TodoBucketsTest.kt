package com.deeptalking.feature.characters

import com.deeptalking.core.model.Todo
import com.deeptalking.core.model.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TodoBucketsTest {

    private val now = Instant.parse("2026-08-20T12:00:00Z").toEpochMilli()

    private fun todo(
        id: String,
        dueAt: String = "2026-08-25T09:00:00Z",
        status: TodoStatus = TodoStatus.Active,
        closedAt: String? = null,
    ) = Todo(
        id = id,
        title = id,
        detail = "",
        dueAt = dueAt,
        status = status,
        closedAt = closedAt,
    )

    @Test
    fun `sections are derived and ordered ongoing overdue done cancelled`() {
        val todos = listOf(
            todo("done", status = TodoStatus.Done, closedAt = "2026-08-19T00:00:00Z"),
            todo("ongoing", dueAt = "2026-08-25T09:00:00Z"),
            todo("cancelled", status = TodoStatus.Cancelled, closedAt = "2026-08-18T00:00:00Z"),
            todo("overdue", dueAt = "2026-08-18T09:00:00Z"),
        )
        val rows = TodoBuckets.rows(todos, now)
        assertEquals(
            listOf(
                "ongoing" to TodoBuckets.TodoSection.ONGOING,
                "overdue" to TodoBuckets.TodoSection.OVERDUE,
                "done" to TodoBuckets.TodoSection.DONE,
                "cancelled" to TodoBuckets.TodoSection.CANCELLED,
            ),
            rows.map { it.todo.id to it.section },
        )
    }

    @Test
    fun `closed todos older than the retention window disappear from the list`() {
        val recent = todo("recent", status = TodoStatus.Done, closedAt = "2026-08-15T00:00:00Z")
        val stale = todo("stale", status = TodoStatus.Done, closedAt = "2026-06-01T00:00:00Z")
        val active = todo("active", dueAt = "2026-08-25T09:00:00Z")
        val ids = TodoBuckets.rows(listOf(recent, stale, active), now).map { it.todo.id }
        assertEquals(listOf("active", "recent"), ids)
    }

    @Test
    fun `ongoing todos are sorted by due time`() {
        val rows = TodoBuckets.rows(
            listOf(
                todo("later", dueAt = "2026-08-28T09:00:00Z"),
                todo("sooner", dueAt = "2026-08-22T09:00:00Z"),
            ),
            now,
        )
        assertEquals(listOf("sooner", "later"), rows.map { it.todo.id })
    }
}
