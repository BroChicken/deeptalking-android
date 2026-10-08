package com.deeptalking.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deeptalking.core.designsystem.legacy

/** One selectable voice as shown to the user. [id] is the stored file name. */
data class VoiceOption(val id: String, val name: String, val builtin: Boolean)

/**
 * On-device read-aloud settings (CosyVoice3): download the model, import any
 * audio file (the app auto-selects a few seconds of clear speech), name/select/
 * rename/delete voices, set a free-form emotion/style, and preview.
 */
@Composable
fun VoiceSettingsSection(
    enabled: Boolean,
    autoRead: Boolean,
    modelReady: Boolean,
    downloading: Boolean,
    progress: Float,
    progressLabel: String,
    voices: List<VoiceOption>,
    activeVoiceId: String,
    status: String,
    speaking: Boolean,
    busy: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAutoRead: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onImportVoice: (Uri, String, String?) -> Unit,
    onSelectVoice: (String) -> Unit,
    onRenameVoice: (String, String) -> Unit,
    onDeleteVoice: (String) -> Unit,
    onTestSpeak: (String) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val legacy = MaterialTheme.legacy
    var voiceMenuOpen by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingLabel by remember { mutableStateOf("") }
    var voiceName by remember { mutableStateOf("") }
    var promptText by remember { mutableStateOf("") }
    var advancedOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VoiceOption?>(null) }
    var testText by remember { mutableStateOf("你好呀，今天过得怎么样？") }

    val active = voices.firstOrNull { it.id == activeVoiceId }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingUri = uri
            val raw = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
            pendingLabel = raw
            if (voiceName.isBlank()) voiceName = raw
        }
    }

    Text("语音朗读（端侧 CosyVoice3）", fontSize = 12.sp, color = legacy.textMuted, fontWeight = FontWeight.Medium)

    CheckRowInline("开启朗读", enabled) { onToggleEnabled(it) }
    CheckRowInline("自动朗读新回复", autoRead) { onToggleAutoRead(it) }

    if (!modelReady) {
        Button(onClick = onDownload, enabled = !downloading, modifier = Modifier.fillMaxWidth()) {
            Text(if (downloading) "下载中…" else "下载语音模型（约 880MB）")
        }
        if (downloading) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${(progress * 100).toInt()}%  $progressLabel", fontSize = 11.sp, color = legacy.textMuted)
        }
    } else {
        Text("模型已就绪", fontSize = 12.sp, color = legacy.textMuted)
    }

    Text("当前音色", fontSize = 12.sp, color = legacy.textMuted)
    LegacyDropdown(
        selected = active?.name ?: "（未选择音色）",
        options = voices.map { it.name }.ifEmpty { listOf("（未选择音色）") },
        expanded = voiceMenuOpen,
        onExpandedChange = { voiceMenuOpen = it },
        onSelect = { name ->
            voices.firstOrNull { it.name == name }?.let { onSelectVoice(it.id) }
            voiceMenuOpen = false
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { active?.let { renameTarget = it } },
            enabled = active != null,
            modifier = Modifier.weight(1f),
        ) { Text("重命名", fontSize = 12.sp) }
        TextButton(
            onClick = { active?.let { onDeleteVoice(it.id) } },
            enabled = active != null && !active.builtin,
            modifier = Modifier.weight(1f),
        ) { Text(if (active?.builtin == true) "内置音色" else "删除", fontSize = 12.sp) }
    }

    Text("导入新音色（支持 mp3 / m4a / wav 等，自动截取人声片段）", fontSize = 12.sp, color = legacy.textMuted)
    OutlinedTextField(
        value = voiceName,
        onValueChange = { voiceName = it },
        label = { Text("音色名称") },
        placeholder = { Text("例如：我的声音") },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
            modifier = Modifier.weight(1f),
        ) { Text(if (pendingUri != null) "已选：$pendingLabel" else "选择音频文件", maxLines = 1) }
        Button(
            onClick = {
                pendingUri?.let { onImportVoice(it, voiceName, promptText.trim().ifBlank { null }) }
                pendingUri = null
                pendingLabel = ""
            },
            enabled = pendingUri != null && modelReady && !busy,
            modifier = Modifier.weight(1f),
        ) { Text(if (busy) "编码中…" else "导入音色") }
    }

    TextButton(onClick = { advancedOpen = !advancedOpen }) {
        Text(if (advancedOpen) "▾ 高级选项" else "▸ 高级选项", fontSize = 12.sp)
    }
    if (advancedOpen) {
        OutlinedTextField(
            value = promptText,
            onValueChange = { promptText = it },
            label = { Text("参考音频文本（仅零样本克隆用，可留空）") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        Text(
            "朗读使用 instruct 模式，会自动忽略这段文本；一般留空即可。",
            fontSize = 11.sp,
            color = legacy.textMuted.copy(alpha = 0.8f),
        )
    }

    OutlinedTextField(
        value = testText,
        onValueChange = { testText = it },
        label = { Text("试听文本") },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { onTestSpeak(testText) },
            enabled = !speaking && modelReady && active != null,
            modifier = Modifier.weight(1f),
        ) { Text(if (speaking) "合成中…" else "试听") }
        Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停止") }
    }

    if (status.isNotBlank()) {
        Text(status, fontSize = 12.sp, color = legacy.textMuted)
    }

    renameTarget?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名音色") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { onRenameVoice(target.id, draft); renameTarget = null }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun CheckRowInline(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.legacy.text)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
