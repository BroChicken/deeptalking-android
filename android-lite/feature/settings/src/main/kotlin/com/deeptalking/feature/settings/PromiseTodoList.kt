package com.deeptalking.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deeptalking.core.designsystem.legacy

/**
 * The 待办 list: **every** character's promises, bucketed by [PromiseTodoSection].
 *
 * It is a *view* only — 完成/关闭 are handed back through [onComplete]/[onClose] as
 * `(characterId, promiseId)`, which flip the promise's status. No
 * section removes anything: a 已超时 or 不提醒 agreement stays visible in its own bucket and
 * stays stored in memory.
 *
 * The two trailing buckets (已超时, 不提醒) start collapsed so the page stays scannable; the live
 * buckets (进行中 / 待确认期限 / 周期性) are always shown. When the list spans more than one
 * character each row also shows its owning character name (muted, small).
 */
@Composable
fun PromiseTodoList(
    todos: List<PromiseTodo>,
    onComplete: (characterId: String, promiseId: String) -> Unit,
    onClose: (characterId: String, promiseId: String) -> Unit,
) {
    val legacy = MaterialTheme.legacy
    // Hoisted out of the loop: a `remember` inside it would be recycled onto whichever bucket
    // lands in that slot when a bucket empties (overdue items appearing/disappearing).
    var overdueExpanded by remember { mutableStateOf(false) }
    var notPushableExpanded by remember { mutableStateOf(false) }
    // Only disambiguate by character when there is more than one to disambiguate.
    val showCharacter = todos.map { it.characterId }.distinct().size > 1

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("待办约定", fontSize = 12.sp, color = legacy.textMuted)
        if (todos.isEmpty()) {
            Text("暂无待办约定", fontSize = 12.sp, color = legacy.textMuted)
            return@Column
        }
        PromiseTodoSection.entries.forEach { section ->
            val items = todos.filter { it.section == section }
            if (items.isEmpty()) return@forEach
            val collapsible = section == PromiseTodoSection.OVERDUE || section == PromiseTodoSection.NOT_PUSHABLE
            val expanded = when (section) {
                PromiseTodoSection.OVERDUE -> overdueExpanded
                PromiseTodoSection.NOT_PUSHABLE -> notPushableExpanded
                else -> true
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = collapsible) {
                        when (section) {
                            PromiseTodoSection.OVERDUE -> overdueExpanded = !overdueExpanded
                            PromiseTodoSection.NOT_PUSHABLE -> notPushableExpanded = !notPushableExpanded
                            else -> Unit
                        }
                    },
            ) {
                Text(
                    sectionLabel(section) + "（${items.size}）",
                    fontSize = 12.sp,
                    color = if (section == PromiseTodoSection.OVERDUE) legacy.accent else legacy.textSecondary,
                )
                if (collapsible) {
                    Spacer(Modifier.width(6.dp))
                    Text(if (expanded) "收起" else "展开", fontSize = 11.sp, color = legacy.textMuted)
                }
            }
            if (!expanded) return@forEach
            items.forEach { todo ->
                PromiseTodoRow(
                    todo = todo,
                    showCharacter = showCharacter,
                    onComplete = { onComplete(todo.characterId, todo.id) },
                    onClose = { onClose(todo.characterId, todo.id) },
                )
            }
        }
    }
}

@Composable
private fun PromiseTodoRow(
    todo: PromiseTodo,
    showCharacter: Boolean,
    onComplete: () -> Unit,
    onClose: () -> Unit,
) {
    val legacy = MaterialTheme.legacy
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(legacy.input)
            .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (showCharacter && todo.characterName.isNotBlank()) {
            Text(todo.characterName, fontSize = 10.sp, color = legacy.textMuted)
        }
        Text(todo.key.ifBlank { "（无标题）" }, fontSize = 13.sp, color = legacy.text)
        if (todo.value.isNotBlank()) {
            Text(todo.value, fontSize = 11.sp, color = legacy.textMuted, maxLines = 3)
        }
        Text(dueLabel(todo), fontSize = 11.sp, color = legacy.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            TodoAction("完成", onComplete)
            TodoAction("关闭", onClose)
        }
    }
}

@Composable
private fun TodoAction(label: String, onClick: () -> Unit) {
    val legacy = MaterialTheme.legacy
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(legacy.accentBg)
            .border(1.dp, legacy.inputBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(label, fontSize = 11.sp, color = legacy.text)
    }
}

private fun sectionLabel(section: PromiseTodoSection): String = when (section) {
    PromiseTodoSection.ONGOING -> "进行中"
    PromiseTodoSection.NEEDS_DUE_CONFIRMATION -> "待确认期限"
    PromiseTodoSection.RECURRING -> "周期性约定"
    PromiseTodoSection.OVERDUE -> "已超时"
    PromiseTodoSection.NOT_PUSHABLE -> "不提醒（角色单方/证据不足）"
}
