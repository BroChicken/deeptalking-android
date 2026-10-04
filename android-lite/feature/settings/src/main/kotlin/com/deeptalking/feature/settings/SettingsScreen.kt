package com.deeptalking.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

private val THINKING_LEVELS = listOf(
    "none" to "无",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "max" to "最高",
)

@Composable
fun SettingsScreen(
    config: AppConfig,
    hasApiKey: Boolean,
    testResult: String?,
    onSave: (AppConfig, apiKey: String?) -> Unit,
    onTestReminder: () -> Unit,
    onTestConnection: (AppConfig) -> Unit,
) {
    val context = LocalContext.current
    val legacy = MaterialTheme.legacy
    var edited by remember(config) { mutableStateOf(config) }
    var apiKey by remember { mutableStateOf("") }
    var platformMenuOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var thinkingMenuOpen by remember { mutableStateOf(false) }

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
            selected = edited.apiPlatform,
            options = BuiltinPlatforms.map { it.id },
            expanded = platformMenuOpen,
            onExpandedChange = { platformMenuOpen = it },
            onSelect = { id ->
                val preset = BuiltinPlatforms.firstOrNull { it.id == id }
                edited = edited.copy(
                    apiPlatform = id,
                    apiBaseUrl = preset?.baseUrl?.ifBlank { edited.apiBaseUrl } ?: edited.apiBaseUrl,
                    modelName = preset?.defaultModel?.ifBlank { edited.modelName } ?: edited.modelName,
                )
                platformMenuOpen = false
            },
        )

        FieldLabel("API Base URL（OpenAI 兼容）")
        LegacyField(
            value = edited.apiBaseUrl,
            onValueChange = { edited = edited.copy(apiBaseUrl = it) },
            placeholder = "https://api.example.com/v1",
        )

        FieldLabel("API Key")
        LegacyField(
            value = apiKey,
            onValueChange = { apiKey = it },
            placeholder = if (hasApiKey) "已保存（留空则不改）" else "sk-...",
            visualTransformation = PasswordVisualTransformation(),
            keyboardType = KeyboardType.Password,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LegacyButton("测试连接", modifier = Modifier.weight(1f), onClick = { onTestConnection(edited) })
            LegacyButton("保存设置", modifier = Modifier.weight(1f), onClick = { onSave(edited, apiKey.ifBlank { null }) })
        }

        if (!testResult.isNullOrBlank()) {
            Text(testResult, fontSize = 12.sp, color = legacy.textMuted)
        }

        FieldLabel("模型名称")
        if (models.isEmpty()) {
            LegacyField(value = edited.modelName, onValueChange = { edited = edited.copy(modelName = it) })
        } else {
            LegacyDropdown(
                selected = edited.modelName.ifBlank { models.first() },
                options = models,
                expanded = modelMenuOpen,
                onExpandedChange = { modelMenuOpen = it },
                onSelect = { model -> edited = edited.copy(modelName = model); modelMenuOpen = false },
            )
        }

        FieldLabel("Temperature")
        LegacyField(
            value = edited.temperature.toString(),
            onValueChange = { raw ->
                raw.toDoubleOrNull()?.let { edited = edited.copy(temperature = it.coerceIn(0.0, 2.0)) }
            },
            keyboardType = KeyboardType.Decimal,
        )
        CheckRow("流式回复", edited.stream) { edited = edited.copy(stream = it) }

        FieldLabel("思考强度（DeepSeek）")
        LegacyDropdown(
            selected = THINKING_LEVELS.firstOrNull { it.first == edited.reasoningEffort }?.second ?: "无",
            options = THINKING_LEVELS.map { it.second },
            expanded = thinkingMenuOpen,
            onExpandedChange = { thinkingMenuOpen = it },
            onSelect = { label ->
                THINKING_LEVELS.firstOrNull { it.second == label }?.let { edited = edited.copy(reasoningEffort = it.first) }
                thinkingMenuOpen = false
            },
        )

        CheckRow("角色主动开口", edited.proactiveEnabled) { edited = edited.copy(proactiveEnabled = it) }
        CheckRow("文风自动校对", edited.styleCritique) { edited = edited.copy(styleCritique = it) }
        CheckRow("快速回应视角校正", edited.quickReplyRepair) { edited = edited.copy(quickReplyRepair = it) }

        LegacyButton("测试提醒", modifier = Modifier.fillMaxWidth(), onClick = onTestReminder)

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
private fun LegacyDropdown(
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
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
}

@Composable
private fun DebugInfoPanel(config: AppConfig, context: Context) {
    val legacy = MaterialTheme.legacy
    val parts = buildList {
        if (config.lastReplyDebug.isNotBlank()) add("【最近一次回应】\n" + config.lastReplyDebug)
        if (config.requestMetrics.isNotEmpty()) {
            val recent = config.requestMetrics.takeLast(12).joinToString("\n") {
                "输入 ${it.inputTokens} · 命中 ${it.hitTokens} · 命中率 ${"%.0f".format(it.hitRate * 100)}%"
            }
            add("【最近 API 用量与缓存统计】\n$recent")
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
