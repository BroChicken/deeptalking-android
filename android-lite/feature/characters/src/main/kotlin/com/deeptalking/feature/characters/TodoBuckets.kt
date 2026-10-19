package com.deeptalking.feature.characters

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Todo
import com.deeptalking.core.model.TodoStatus
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 角色编辑页「待办」页签的纯派生逻辑（不改任何字段）。
 *
 * 分组固定为 进行中 → 已超时 → 已完成 → 已取消；「已超时」由 `dueAt < now` 推导、不是新字段。
 * 已完成 / 已取消只展示**关闭未满 [AppLimits.Todo.CLOSED_RETENTION_DAYS] 天**的条目（更老的
 * 由启动维护真正删除，这里再防御性过滤一次）。待办与长期记忆无关，只看 `Character.todos`。
 */
object TodoBuckets {

    enum class TodoSection { ONGOING, OVERDUE, DONE, CANCELLED }

    data class TodoRow(val todo: Todo, val section: TodoSection)

    private const val DAY_MILLIS = 86_400_000L

    /** 列表可见性：仍进行中的，或关闭未满保留期的。 */
    fun visible(todos: List<Todo>, nowMillis: Long = System.currentTimeMillis()): List<Todo> {
        val cutoff = nowMillis - AppLimits.Todo.CLOSED_RETENTION_DAYS * DAY_MILLIS
        return todos.filter { todo ->
            if (todo.status == TodoStatus.Active) return@filter true
            val closed = closedMillis(todo)
            closed == null || closed >= cutoff
        }
    }

    fun rows(character: com.deeptalking.core.model.Character, nowMillis: Long = System.currentTimeMillis()): List<TodoRow> =
        rows(character.todos, nowMillis)

    fun rows(todos: List<Todo>, nowMillis: Long = System.currentTimeMillis()): List<TodoRow> =
        visible(todos, nowMillis)
            .map { todo -> TodoRow(todo, sectionOf(todo, nowMillis)) }
            .sortedWith(
                compareBy<TodoRow>(
                    { it.section.ordinal },
                    { if (it.section == TodoSection.DONE || it.section == TodoSection.CANCELLED) -(closedMillis(it.todo) ?: 0L) else (dueMillis(it.todo) ?: Long.MAX_VALUE) },
                    { it.todo.title },
                    { it.todo.id },
                ),
            )

    fun sectionOf(todo: Todo, nowMillis: Long = System.currentTimeMillis()): TodoSection = when (todo.status) {
        TodoStatus.Done -> TodoSection.DONE
        TodoStatus.Cancelled -> TodoSection.CANCELLED
        TodoStatus.Active ->
            if ((dueMillis(todo) ?: Long.MAX_VALUE) < nowMillis) TodoSection.OVERDUE else TodoSection.ONGOING
    }

    /** Epoch millis for a stored ISO timestamp, or null when absent/unparseable. */
    fun dueMillis(todo: Todo): Long? = parseMillis(todo.dueAt)

    fun closedMillis(todo: Todo): Long? = parseMillis(todo.closedAt) ?: parseMillis(todo.updatedAt)

    private val STAMP: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault())

    /** 本地时间下的期限标签（解析不出来时回落到原串）。 */
    fun dueLabel(todo: Todo): String =
        parseMillis(todo.dueAt)?.let { STAMP.format(Instant.ofEpochMilli(it)) } ?: todo.dueAt

    /** 关闭时刻的本地标签，或空串。 */
    fun closedLabel(todo: Todo): String =
        closedMillis(todo)?.let { STAMP.format(Instant.ofEpochMilli(it)) } ?: ""

    private fun parseMillis(raw: String?): Long? {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }
}
