package com.deeptalking.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deeptalking.core.designsystem.legacy
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.BuiltinPlatforms
import com.deeptalking.core.model.PlatformSlot
import org.json.JSONArray
import org.json.JSONObject

private val THINKING_LEVELS = listOf(
    "none" to "无",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "max" to "最高",
)

private val PLATFORM_LABELS = mapOf(
    "deepseek" to "DeepSeek",
    "opencode" to "OpenCode",
    "custom" to "自定义",
)

/** Legacy `normalizeApiBaseUrl` (`normalization.js:50`): trim + drop trailing slashes. */
private fun normalizeApiBaseUrl(value: String): String = value.trim().trimEnd('/')

/** Legacy `changePlatform`: persist the current platform's slot, then load the target's. */
private fun switchPlatform(current: AppConfig, id: String): AppConfig {
    if (id == current.apiPlatform) return current
    val slot = PlatformSlot(baseUrl = normalizeApiBaseUrl(current.apiBaseUrl), modelName = current.modelName)
    val settings = current.platformSettings + (current.apiPlatform to slot)
    val target = settings[id]
    val preset = BuiltinPlatforms.firstOrNull { it.id == id }
    // Legacy `applyPlatformFields`: `slot.baseUrl || preset.baseUrl || ''` (custom falls back empty).
    val baseUrl = normalizeApiBaseUrl(
        target?.baseUrl?.takeIf { it.isNotBlank() }
            ?: preset?.baseUrl?.takeIf { it.isNotBlank() }
            ?: "",
    )
    val model = target?.modelName?.takeIf { it.isNotBlank() }
        ?: preset?.defaultModel?.takeIf { it.isNotBlank() }
        ?: ""
    return current.copy(
        apiPlatform = id,
        apiBaseUrl = baseUrl,
        modelName = model,
        platformSettings = settings,
    )
}

@Composable
fun SettingsScreen(
    config: AppConfig,
    hasApiKey: Boolean,
    loadApiKey: (String) -> String? = { null },
    testResult: String?,
    onSave: (AppConfig, apiKey: String?) -> Unit,
    onTestReminder: () -> Unit,
    onTestConnection: (AppConfig, String?) -> Unit,
    ttsEnabled: Boolean = false,
    ttsAutoRead: Boolean = false,
    ttsModelReady: Boolean = false,
    ttsDownloading: Boolean = false,
    ttsProgress: Float = 0f,
    ttsProgressLabel: String = "",
    ttsVoices: List<VoiceOption> = emptyList(),
    ttsActiveVoice: String = "",
    ttsStatus: String = "",
    ttsSpeaking: Boolean = false,
    ttsBusy: Boolean = false,
    onToggleTtsEnabled: (Boolean) -> Unit = {},
    onToggleTtsAutoRead: (Boolean) -> Unit = {},
    onDownloadTtsModel: () -> Unit = {},
    onImportTtsVoice: (android.net.Uri, String, String?) -> Unit = { _, _, _ -> },
    onSelectTtsVoice: (String) -> Unit = {},
    onRenameTtsVoice: (String, String) -> Unit = { _, _ -> },
    onDeleteTtsVoice: (String) -> Unit = {},
    onTestTtsSpeak: (String) -> Unit = {},
    onStopTts: () -> Unit = {},
) {
    val context = LocalContext.current
    val legacy = MaterialTheme.legacy
    var edited by remember { mutableStateOf(config) }
    var apiKey by remember { mutableStateOf("") }
    var platformMenuOpen by rememberSaveable { mutableStateOf(false) }
    var thinkingMenuOpen by rememberSaveable { mutableStateOf(false) }
    // Once the user edits, local state wins: the config flow's delayed echo of our own
    // per-change saves must never roll the field back mid-typing.
    var userEdited by remember { mutableStateOf(false) }

    // Legacy bound every field's `change` to `saveSettings`; persist each edit right
    // away so switching tabs or leaving settings never drops a toggle/field edit.
    val persist: (AppConfig) -> Unit = { newConfig ->
        userEdited = true
        edited = newConfig
        onSave(newConfig, null)
    }

    // Adopt external config changes (initial load/import) until the user starts editing.
    LaunchedEffect(config) { if (!userEdited && config != edited) edited = config }

    // Legacy `loadSettingsForm`: refill the target platform's stored key into the
    // field on entry/platform switch (the input stays masked).
    LaunchedEffect(edited.apiPlatform) { apiKey = loadApiKey(edited.apiPlatform).orEmpty() }

    val models = BuiltinPlatforms.firstOrNull { it.id == edited.apiPlatform }?.models.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(legacy.sidebar)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("API 配置")

        FieldLabel("API 平台")
        LegacyDropdown(
            selected = PLATFORM_LABELS[edited.apiPlatform] ?: edited.apiPlatform,
            options = BuiltinPlatforms.map { PLATFORM_LABELS[it.id] ?: it.id },
            expanded = platformMenuOpen,
            onExpandedChange = { platformMenuOpen = it },
            onSelect = { label ->
                val id = PLATFORM_LABELS.entries.firstOrNull { it.value == label }?.key ?: label
                persist(switchPlatform(edited, id))
                platformMenuOpen = false
            },
        )

        FieldLabel("API Base URL（OpenAI 兼容）")
        LegacyField(
            value = edited.apiBaseUrl,
            onValueChange = { persist(edited.copy(apiBaseUrl = normalizeApiBaseUrl(it))) },
            placeholder = "https://api.example.com/v1",
        )

        FieldLabel("API Key")
        LegacyField(
            value = apiKey,
            onValueChange = { raw -> apiKey = raw; onSave(edited, raw.ifBlank { null }) },
            placeholder = if (hasApiKey) "已保存（留空则不改）" else "sk-...",
            visualTransformation = PasswordVisualTransformation(),
            keyboardType = KeyboardType.Password,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LegacyButton("测试连接", modifier = Modifier.weight(1f), onClick = { onTestConnection(edited, apiKey) })
            LegacyButton("保存设置", modifier = Modifier.weight(1f), onClick = { onSave(edited, apiKey.ifBlank { null }) })
        }

        if (!testResult.isNullOrBlank()) {
            Text(testResult, fontSize = 12.sp, color = legacy.textMuted)
        }

        FieldLabel("模型名称")
        LegacyModelField(
            value = edited.modelName,
            suggestions = models,
            onValueChange = { persist(edited.copy(modelName = it)) },
        )

        FieldLabel("Temperature")
        LegacyField(
            value = edited.temperature.toString(),
            onValueChange = { raw ->
                raw.toDoubleOrNull()?.let { persist(edited.copy(temperature = it.coerceIn(0.0, 2.0))) }
            },
            keyboardType = KeyboardType.Decimal,
        )
        CheckRow("流式回复", edited.stream) { persist(edited.copy(stream = it)) }

        FieldLabel("思考强度（DeepSeek）")
        LegacyDropdown(
            selected = THINKING_LEVELS.firstOrNull { it.first == edited.reasoningEffort }?.second ?: "无",
            options = THINKING_LEVELS.map { it.second },
            expanded = thinkingMenuOpen,
            onExpandedChange = { thinkingMenuOpen = it },
            onSelect = { label ->
                THINKING_LEVELS.firstOrNull { it.second == label }?.let { persist(edited.copy(reasoningEffort = it.first)) }
                thinkingMenuOpen = false
            },
        )

        CheckRow(
            "角色主动开口",
            edited.proactiveEnabled,
            hint = "打开面板停留约 1 分钟未对话时，角色主动发起开场",
        ) { persist(edited.copy(proactiveEnabled = it)) }
        CheckRow(
            "文风自动校对",
            edited.styleCritique,
            hint = "回复命中语气/风格问题时，追加一次只改文风的修订请求",
        ) { persist(edited.copy(styleCritique = it)) }
        CheckRow(
            "快速回应视角校正",
            edited.quickReplyRepair,
            hint = "快速回应不像用户会说的话时，后台用“用户本人”身份重写一次",
        ) { persist(edited.copy(quickReplyRepair = it)) }

        LegacyButton("测试提醒", modifier = Modifier.fillMaxWidth(), onClick = onTestReminder)

        VoiceSettingsSection(
            enabled = ttsEnabled,
            autoRead = ttsAutoRead,
            modelReady = ttsModelReady,
            downloading = ttsDownloading,
            progress = ttsProgress,
            progressLabel = ttsProgressLabel,
            voices = ttsVoices,
            activeVoiceId = ttsActiveVoice,
            status = ttsStatus,
            speaking = ttsSpeaking,
            busy = ttsBusy,
            onToggleEnabled = onToggleTtsEnabled,
            onToggleAutoRead = onToggleTtsAutoRead,
            onDownload = onDownloadTtsModel,
            onImportVoice = onImportTtsVoice,
            onSelectVoice = onSelectTtsVoice,
            onRenameVoice = onRenameTtsVoice,
            onDeleteVoice = onDeleteTtsVoice,
            onTestSpeak = onTestTtsSpeak,
            onStop = onStopTts,
        )

        DebugInfoPanel(config, context)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.legacy.textMuted)
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.legacy.textMuted, modifier = Modifier.padding(bottom = 1.dp))
}

@Composable
private fun LegacyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val legacy = MaterialTheme.legacy
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(legacy.input)
            .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty() && placeholder != null) {
            Text(placeholder, fontSize = 13.sp, color = legacy.textMuted)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 13.sp, color = legacy.text),
            cursorBrush = SolidColor(legacy.accent),
            singleLine = true,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun LegacyDropdown(
    selected: String,
    options: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    val legacy = MaterialTheme.legacy
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(legacy.input)
                .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
                .clickable { onExpandedChange(true) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected, fontSize = 13.sp, color = legacy.text, modifier = Modifier.weight(1f))
            Text("▾", fontSize = 13.sp, color = legacy.textMuted)
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            options.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(option, color = legacy.text) },
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun LegacyButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val legacy = MaterialTheme.legacy
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(legacy.accentBg)
            .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, color = legacy.text)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.legacy.accent,
                    uncheckedColor = MaterialTheme.legacy.textMuted,
                    checkmarkColor = MaterialTheme.legacy.panel,
                ),
            )
            Text(label, fontSize = 12.sp, color = MaterialTheme.legacy.textMuted)
        }
        if (hint != null) {
            Text(
                hint,
                fontSize = 11.sp,
                color = MaterialTheme.legacy.textMuted.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 40.dp, top = 1.dp),
            )
        }
    }
}

/** Legacy `<input list>` model field: typable, with a dropdown of platform presets. */
@Composable
private fun LegacyModelField(value: String, suggestions: List<String>, onValueChange: (String) -> Unit) {
    val legacy = MaterialTheme.legacy
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(legacy.input)
                .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(fontSize = 13.sp, color = legacy.text),
                cursorBrush = SolidColor(legacy.accent),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (suggestions.isNotEmpty()) {
                Text("▾", fontSize = 13.sp, color = legacy.textMuted, modifier = Modifier.clickable { open = true })
            }
        }
        if (suggestions.isNotEmpty()) {
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                suggestions.forEach { suggestion ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(suggestion, color = legacy.text) },
                        onClick = { onValueChange(suggestion); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugInfoPanel(config: AppConfig, context: Context) {
    val legacy = MaterialTheme.legacy
    // Legacy `renderDebugInfo` (`status-settings.js:67-99`): the last reply as a
    // pretty JSON payload plus its raw (pre-processing) text, then the last 12
    // usage/cache metrics as JSON. `lastReplyDebug` holds the JSON object.
    val parts = buildList {
        val raw = config.lastReplyDebug
        if (raw.isNotBlank()) {
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && obj.has("displayText")) {
                add("【最近一次回应】\n" + obj.toString(2))
                val full = obj.optString("fullText")
                if (full.isNotBlank()) add("【原始回应（未处理）】\n" + full)
            } else {
                add("【最近一次回应】\n" + raw)
            }
        }
        if (config.requestMetrics.isNotEmpty()) {
            val array = JSONArray()
            config.requestMetrics.takeLast(12).forEach { metric ->
                val entry = JSONObject()
                entry.put("at", metric.at)
                entry.put("taskType", metric.taskType)
                entry.put("characterId", metric.characterId ?: JSONObject.NULL)
                entry.put("inputTokens", metric.inputTokens ?: JSONObject.NULL)
                entry.put("hitTokens", metric.hitTokens ?: JSONObject.NULL)
                entry.put("missTokens", metric.missTokens ?: JSONObject.NULL)
                entry.put("hitRate", metric.hitRate ?: JSONObject.NULL)
                array.put(entry)
            }
            add("【最近 API 用量与缓存统计】\n" + array.toString(2))
        }
    }
    if (parts.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("调试信息", fontSize = 12.sp, color = legacy.textMuted, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(legacy.input)
                .border(1.dp, legacy.inputBorder, RoundedCornerShape(6.dp))
                .clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("debug", parts.joinToString("\n\n")))
                    Toast.makeText(context, "调试信息已复制", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text("复制", fontSize = 11.sp, color = legacy.text)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(legacy.input)
            .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Text(
            parts.joinToString("\n\n"),
            fontSize = 10.sp,
            color = legacy.text,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}
