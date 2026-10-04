package com.deeptalking.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.BuiltinPlatforms

private val THINKING_LEVELS = listOf(
    "none" to "无",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "max" to "最高",
)

@OptIn(ExperimentalMaterial3Api::class)
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
    var edited by remember(config) { mutableStateOf(config) }
    var apiKey by remember { mutableStateOf("") }
    var platformMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("API 配置", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("API 平台", style = MaterialTheme.typography.labelMedium)
        ExposedDropdownMenuBox(expanded = platformMenuOpen, onExpandedChange = { platformMenuOpen = !platformMenuOpen }) {
            OutlinedTextField(
                value = edited.apiPlatform,
                onValueChange = {},
                readOnly = true,
                label = { Text("API 平台") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = platformMenuOpen) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = platformMenuOpen, onDismissRequest = { platformMenuOpen = false }) {
                BuiltinPlatforms.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.id) },
                        onClick = {
                            edited = edited.copy(
                                apiPlatform = preset.id,
                                apiBaseUrl = preset.baseUrl.ifBlank { edited.apiBaseUrl },
                                modelName = preset.defaultModel.ifBlank { edited.modelName },
                            )
                            platformMenuOpen = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = edited.apiBaseUrl,
            onValueChange = { edited = edited.copy(apiBaseUrl = it) },
            label = { Text("API Base URL（OpenAI 兼容）") },
            placeholder = { Text("https://api.example.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text(if (hasApiKey) "已保存密钥" else "未配置密钥") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onTestConnection(edited) }, modifier = Modifier.weight(1f)) { Text("测试连接") }
            Button(onClick = { onSave(edited, apiKey.ifBlank { null }) }, modifier = Modifier.weight(1f)) { Text("保存设置") }
        }

        if (!testResult.isNullOrBlank()) {
            Text(testResult, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedTextField(
            value = edited.modelName,
            onValueChange = { edited = edited.copy(modelName = it) },
            label = { Text("模型名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = edited.temperature.toString(),
            onValueChange = { raw ->
                raw.toDoubleOrNull()?.let { edited = edited.copy(temperature = it.coerceIn(0.0, 2.0)) }
            },
            label = { Text("Temperature") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = edited.stream, onCheckedChange = { edited = edited.copy(stream = it) })
            Text("流式回复", style = MaterialTheme.typography.bodyMedium)
        }

        Text("思考强度（DeepSeek）", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            THINKING_LEVELS.forEach { (value, label) ->
                androidx.compose.material3.FilterChip(
                    selected = edited.reasoningEffort == value,
                    onClick = { edited = edited.copy(reasoningEffort = value) },
                    label = { Text(label) },
                )
            }
        }

        CheckRow("角色主动开口", edited.proactiveEnabled) { edited = edited.copy(proactiveEnabled = it) }
        CheckRow("文风自动校对", edited.styleCritique) { edited = edited.copy(styleCritique = it) }
        CheckRow("快速回应视角校正", edited.quickReplyRepair) { edited = edited.copy(quickReplyRepair = it) }

        OutlinedButton(onClick = onTestReminder, modifier = Modifier.fillMaxWidth()) { Text("测试提醒") }

        DebugInfoPanel(config, context)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DebugInfoPanel(config: AppConfig, context: Context) {
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
    Text("调试信息", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("debug", parts.joinToString("\n\n")))
        }) { Text("复制") }
    }
    Text(
        parts.joinToString("\n\n"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
    )
}
