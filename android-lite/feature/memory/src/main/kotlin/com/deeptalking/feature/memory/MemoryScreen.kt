package com.deeptalking.feature.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.ShortTermMemory

@Composable
fun MemoryScreen(character: Character?) {
    if (character == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "请选择一个角色以查看记忆",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader("长期记忆") }
        if (character.longTerm.isEmpty()) {
            item { EmptyHint("暂无长期记忆") }
        } else {
            items(character.longTerm) { memory -> LongTermRow(memory) }
        }

        item { SectionHeader("世界书") }
        if (character.lorebook.isEmpty()) {
            item { EmptyHint("暂无世界书条目") }
        } else {
            items(character.lorebook) { entry -> LorebookRow(entry) }
        }

        item { SectionHeader("短期记忆") }
        if (character.shortTerm.isEmpty()) {
            item { EmptyHint("暂无短期记忆") }
        } else {
            items(character.shortTerm) { memory -> ShortTermRow(memory) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        HorizontalDivider()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LongTermRow(memory: LongTermMemory) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (memory.key.isNotEmpty()) "${memory.key} — ${memory.value}" else memory.value,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "分类：${memory.category.name}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LorebookRow(entry: LorebookEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = entry.name.ifEmpty { "未命名条目" },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (entry.content.isNotEmpty()) {
            Text(
                text = entry.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShortTermRow(memory: ShortTermMemory) {
    Text(
        text = memory.content,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}
