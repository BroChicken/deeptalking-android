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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deeptalking.core.designsystem.legacy
import com.deeptalking.core.model.TTS_BACKEND_REMOTE

/** One selectable voice as shown to the user. [id] is the stored file name. */
data class VoiceOption(val id: String, val name: String, val builtin: Boolean)

/**
 * Synthesis knobs shared by BOTH engines. They are seeded with the model's own
 * metadata values (`sampling.*` in the GGUF: temperature 1.0, top_k 25, top_p 0.8,
 * win_size 10, tau_r 0.1, ratios 2/20, chunk_tokens 28) so every box shows a real
 * value; the same set goes to the on-device engine and to the PC request body, and
 * "恢复默认" restores exactly these (see `docs/REMOTE_TTS.md` §9).
 */
data class TtsAdvanced(
    val chunkTokens: Int = DEFAULT_CHUNK_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Double = DEFAULT_TOP_P,
    val winSize: Int = DEFAULT_WIN_SIZE,
    val tauR: Double = DEFAULT_TAU_R,
    val minTokenTextRatio: Double = DEFAULT_MIN_TOKEN_TEXT_RATIO,
    val maxTokenTextRatio: Double = DEFAULT_MAX_TOKEN_TEXT_RATIO,
) {
    val isDefault: Boolean
        get() = chunkTokens == DEFAULT_CHUNK_TOKENS && temperature == DEFAULT_TEMPERATURE &&
            topK == DEFAULT_TOP_K && topP == DEFAULT_TOP_P && winSize == DEFAULT_WIN_SIZE &&
            tauR == DEFAULT_TAU_R && minTokenTextRatio == DEFAULT_MIN_TOKEN_TEXT_RATIO &&
            maxTokenTextRatio == DEFAULT_MAX_TOKEN_TEXT_RATIO

    companion object {
        const val DEFAULT_CHUNK_TOKENS = 28
        const val DEFAULT_TEMPERATURE = 1.0
        const val DEFAULT_TOP_K = 25
        const val DEFAULT_TOP_P = 0.8
        const val DEFAULT_WIN_SIZE = 10
        const val DEFAULT_TAU_R = 0.1
        const val DEFAULT_MIN_TOKEN_TEXT_RATIO = 2.0
        const val DEFAULT_MAX_TOKEN_TEXT_RATIO = 20.0

        /**
         * Effective values for the boxes. A stored `0`/`0.0` means "never set" (that is
         * what builds before the knobs were always sent wrote), so the box shows - and
         * both engines use - the model default instead of a rejected value.
         */
        fun fromStored(
            chunkTokens: Int,
            temperature: Double,
            topK: Int,
            topP: Double,
            winSize: Int,
            tauR: Double,
            minTokenTextRatio: Double,
            maxTokenTextRatio: Double,
        ): TtsAdvanced = TtsAdvanced(
            chunkTokens = chunkTokens.takeIf { it > 0 } ?: DEFAULT_CHUNK_TOKENS,
            temperature = temperature.takeIf { it > 0.0 } ?: DEFAULT_TEMPERATURE,
            topK = topK.takeIf { it > 0 } ?: DEFAULT_TOP_K,
            topP = topP.takeIf { it > 0.0 } ?: DEFAULT_TOP_P,
            winSize = winSize.takeIf { it > 0 } ?: DEFAULT_WIN_SIZE,
            tauR = tauR.takeIf { it > 0.0 } ?: DEFAULT_TAU_R,
            minTokenTextRatio = minTokenTextRatio.takeIf { it > 0.0 } ?: DEFAULT_MIN_TOKEN_TEXT_RATIO,
            maxTokenTextRatio = maxTokenTextRatio.takeIf { it > 0.0 } ?: DEFAULT_MAX_TOKEN_TEXT_RATIO,
        )
    }
}

/** Labels for the read-aloud engine picker; ids mirror `AppConfig.ttsBackend`. */
private val TTS_BACKEND_LABELS = listOf(
    "ondevice" to "端侧 CosyVoice3（本机）",
    TTS_BACKEND_REMOTE to "远程服务器（电脑）",
)

/**
 * Read-aloud settings. Two engines share this section:
 *  - on-device CosyVoice3: download the model, import any audio file (the app
 *    auto-selects a few seconds of clear speech), manage voices, set a style;
 *  - remote server (`cosyvoice-server --api` on the PC): address, model, voice
 *    and bearer token, with a connectivity probe and a LAN sweep to find the PC.
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
    backend: String = "ondevice",
    ready: Boolean = true,
    remoteUrl: String = "",
    remoteModel: String = "",
    remoteVoice: String = "",
    remoteToken: String = "",
    remoteStatus: String = "",
    remoteVoices: List<String> = emptyList(),
    remoteVoiceNames: Map<String, String> = emptyMap(),
    advanced: TtsAdvanced = TtsAdvanced(),
    onSelectBackend: (String) -> Unit = {},
    onChangeRemoteUrl: (String) -> Unit = {},
    onChangeRemoteModel: (String) -> Unit = {},
    onChangeRemoteVoice: (String) -> Unit = {},
    onChangeAdvanced: (TtsAdvanced) -> Unit = {},
    onSaveRemoteToken: (String?) -> Unit = {},
    onDiscoverRemote: () -> Unit = {},
    onUploadVoices: () -> Unit = {},
    onSyncVoices: () -> Unit = {},
    onRenameRemoteVoice: (String, String) -> Unit = { _, _ -> },
) {
    val legacy = MaterialTheme.legacy
    val remote = backend == TTS_BACKEND_REMOTE
    var voiceMenuOpen by remember { mutableStateOf(false) }
    var backendMenuOpen by remember { mutableStateOf(false) }
    var remoteVoiceMenuOpen by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingLabel by remember { mutableStateOf("") }
    var voiceName by remember { mutableStateOf("") }
    var promptText by remember { mutableStateOf("") }
    var advancedOpen by remember { mutableStateOf(false) }
    var sharedAdvancedOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VoiceOption?>(null) }
    var remoteRenameId by remember { mutableStateOf("") }
    var remoteRenameName by remember { mutableStateOf("") }
    var testText by remember { mutableStateOf("你好呀，今天过得怎么样？") }
    var tokenDraft by remember(backend) { mutableStateOf("") }

    val active = voices.firstOrNull { it.id == activeVoiceId }
    // The remote engine speaks with the SAME voices the phone has: the server registers
    // one prompt-speech file per voice, named after the on-device file (minus ".gguf"),
    // so the picker below lists the on-device catalog by display name and marks what the
    // PC does not have yet (see docs/REMOTE_TTS.md section 8).
    val followLabel = "跟随端侧音色" + (active?.let { "（${it.name}）" } ?: "")
    val voiceChoices = buildList {
        add(followLabel to "")
        voices.forEach { v ->
            val stem = v.id.removeSuffix(".gguf")
            val missing = remoteVoices.isNotEmpty() && !remoteVoices.contains(stem)
            val shown = if (v.name == stem) stem else "${v.name}（$stem）"
            add((if (missing) "$shown · 服务端未注册" else shown) to stem)
        }
        // Voices that only exist on the PC (registered there, not on the phone).
        remoteVoices.filter { s -> voices.none { it.id.removeSuffix(".gguf") == s } }
            .forEach { add("${remoteVoiceNames[it] ?: it} · 仅服务端" to it) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingUri = uri
            val raw = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
            pendingLabel = raw
            if (voiceName.isBlank()) voiceName = raw
        }
    }

    Text("语音朗读", fontSize = 12.sp, color = legacy.textMuted, fontWeight = FontWeight.Medium)

    CheckRowInline("开启朗读", enabled) { onToggleEnabled(it) }
    CheckRowInline("自动朗读新回复", autoRead) { onToggleAutoRead(it) }

    Text("朗读引擎", fontSize = 12.sp, color = legacy.textMuted)
    LegacyDropdown(
        selected = TTS_BACKEND_LABELS.firstOrNull { it.first == backend }?.second ?: TTS_BACKEND_LABELS.first().second,
        options = TTS_BACKEND_LABELS.map { it.second },
        expanded = backendMenuOpen,
        onExpandedChange = { backendMenuOpen = it },
        onSelect = { label ->
            TTS_BACKEND_LABELS.firstOrNull { it.second == label }?.let { onSelectBackend(it.first) }
            backendMenuOpen = false
        },
    )

    Button(
        onClick = onSyncVoices,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "同步中…" else "同步音色和名称") }

    // Shared synthesis knobs: the very same values are handed to the on-device engine
    // and to the PC request body, so tuning here changes both ends (docs/REMOTE_TTS.md §9).
    TextButton(onClick = { sharedAdvancedOpen = !sharedAdvancedOpen }) {
        Text(if (sharedAdvancedOpen) "▾ 高级选项（两端共用）" else "▸ 高级选项（两端共用）", fontSize = 12.sp)
    }
    if (sharedAdvancedOpen) {
        RemoteNumberField(
            value = advanced.chunkTokens,
            label = "chunk_tokens（每块 token 数：小=省内存，大=吞吐高）",
            onValueChange = { onChangeAdvanced(advanced.copy(chunkTokens = it)) },
        )
        RemoteDecimalField(
            value = advanced.temperature,
            label = "temperature（采样温度：越低越稳，越高越有表现力）",
            onValueChange = { onChangeAdvanced(advanced.copy(temperature = it)) },
        )
        RemoteNumberField(
            value = advanced.topK,
            label = "top_k（采样候选数：越小越稳）",
            onValueChange = { onChangeAdvanced(advanced.copy(topK = it)) },
        )
        RemoteDecimalField(
            value = advanced.topP,
            label = "top_p（采样范围，0-1）",
            onValueChange = { onChangeAdvanced(advanced.copy(topP = it)) },
        )
        RemoteDecimalField(
            value = advanced.tauR,
            label = "tau_r（重复抑制：越大越不容易复读）",
            onValueChange = { onChangeAdvanced(advanced.copy(tauR = it)) },
        )
        RemoteNumberField(
            value = advanced.winSize,
            label = "win_size（重复检查窗口，与 tau_r 配对）",
            onValueChange = { onChangeAdvanced(advanced.copy(winSize = it)) },
        )
        RemoteDecimalField(
            value = advanced.minTokenTextRatio,
            label = "min_token_text_ratio（最少生成量：防漏字）",
            onValueChange = { onChangeAdvanced(advanced.copy(minTokenTextRatio = it)) },
        )
        RemoteDecimalField(
            value = advanced.maxTokenTextRatio,
            label = "max_token_text_ratio（最多生成量：防拖长）",
            onValueChange = { onChangeAdvanced(advanced.copy(maxTokenTextRatio = it)) },
        )
        if (remote) {
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = { raw -> tokenDraft = raw; onSaveRemoteToken(raw.ifBlank { null }) },
                label = { Text("访问口令") },
                placeholder = { Text(if (remoteToken.isBlank()) "留空即可" else "已保存（留空则不改）") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { onChangeAdvanced(TtsAdvanced()) },
                enabled = !advanced.isDefault,
            ) { Text("恢复默认", fontSize = 12.sp) }
        }
    }

    if (remote) {
        Button(
            onClick = onDiscoverRemote,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "查找中…" else "查找并测试电脑") }

        OutlinedTextField(
            value = remoteUrl,
            onValueChange = onChangeRemoteUrl,
            label = { Text("服务器地址") },
            placeholder = { Text("留空则用上面的按钮自动查找") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        OutlinedTextField(
            value = remoteModel,
            onValueChange = onChangeRemoteModel,
            label = { Text("模型名") },
            placeholder = { Text("cosyvoice-3") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
        )

        Text("音色", fontSize = 12.sp, color = legacy.textMuted)
        LegacyDropdown(
            selected = voiceChoices.firstOrNull { it.second == remoteVoice }?.first ?: followLabel,
            options = voiceChoices.map { it.first },
            expanded = remoteVoiceMenuOpen,
            onExpandedChange = { remoteVoiceMenuOpen = it },
            onSelect = { label ->
                voiceChoices.firstOrNull { it.first == label }?.let { onChangeRemoteVoice(it.second) }
                remoteVoiceMenuOpen = false
            },
        )

        TextButton(
            onClick = onUploadVoices,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("只上传本机音色到电脑", fontSize = 12.sp) }
        TextButton(
            onClick = {
                val id = remoteVoice.ifBlank { remoteVoices.firstOrNull().orEmpty() }
                if (id.isNotEmpty()) {
                    remoteRenameId = id
                    remoteRenameName = remoteVoiceNames[id] ?: id
                }
            },
            enabled = !busy && remoteVoices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("重命名电脑音色", fontSize = 12.sp) }

    } else {
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

        Text("导入新音色", fontSize = 12.sp, color = legacy.textMuted)
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
                label = { Text("参考文本") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
            Text(
                "朗读使用 instruct 模式，会自动忽略这段文本；一般留空即可。远程引擎同样忽略它。",
                fontSize = 11.sp,
                color = legacy.textMuted.copy(alpha = 0.8f),
            )
        }
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
            enabled = !speaking && ready && (remote || active != null),
            modifier = Modifier.weight(1f),
        ) { Text(if (speaking) "合成中…" else "试听") }
        Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停止") }
    }

    if (status.isNotBlank()) {
        Text(status, fontSize = 12.sp, color = legacy.textMuted)
    }
    if (remoteStatus.isNotBlank()) {
        Text(remoteStatus, fontSize = 12.sp, color = legacy.textMuted)
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

    if (remoteRenameId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { remoteRenameId = "" },
            title = { Text("重命名电脑音色") },
            text = {
                OutlinedTextField(
                    value = remoteRenameName,
                    onValueChange = { remoteRenameName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameRemoteVoice(remoteRenameId, remoteRenameName)
                        remoteRenameId = ""
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { remoteRenameId = "" }) { Text("取消") } },
        )
    }
}

@Composable
private fun RemoteNumberField(value: Int, label: String, onValueChange: (Int) -> Unit) {
    var draft by remember { mutableStateOf(value.toString()) }
    // Re-sync only when the value changes from outside (e.g. "恢复默认"), so typing
    // is never fought by the round-trip through the config.
    LaunchedEffect(value) { if ((draft.toIntOrNull() ?: 0) != value) draft = value.toString() }
    OutlinedTextField(
        value = draft,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(6)
            draft = filtered
            onValueChange(filtered.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun RemoteDecimalField(value: Double, label: String, onValueChange: (Double) -> Unit) {
    var draft by remember { mutableStateOf(plainNumber(value)) }
    // "1." parses to null while being typed; without this guard the field would drop the
    // dot (the parent stores 0.0 and the draft would be reset).
    LaunchedEffect(value) { if ((draft.toDoubleOrNull() ?: 0.0) != value) draft = plainNumber(value) }
    OutlinedTextField(
        value = draft,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() || it == '.' }.take(6)
            draft = filtered
            onValueChange(filtered.toDoubleOrNull() ?: 0.0)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** `1.0` -> "1", `0.8` -> "0.8" (so the box reads like a plain number). */
private fun plainNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

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
