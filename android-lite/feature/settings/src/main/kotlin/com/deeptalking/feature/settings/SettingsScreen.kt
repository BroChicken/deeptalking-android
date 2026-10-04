package com.deeptalking.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var edited by remember(config) { mutableStateOf(config) }
    var apiKey by remember { mutableStateOf("") }
    var modelMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("API 配置", style = MaterialTheme.typography.titleSmall)

        Text("API 平台", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BuiltinPlatforms.forEach { preset ->
                FilterChip(
                    selected = edited.apiPlatform == preset.id,
                    onClick = {
                        edited = edited.copy(
                            apiPlatform = preset.id,
                            apiBaseUrl = preset.baseUrl.ifBlank { edited.apiBaseUrl },
                            modelName = preset.defaultModel.ifBlank { edited.modelName },
                        )
                    },
                    label = { Text(preset.id) },
                )
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
            OutlinedButton(
                onClick = { onTestConnection(edited) },
                modifier = Modifier.weight(1f),
            ) { Text("测试连接") }
            Button(
                onClick = { onSave(edited, apiKey.ifBlank { null }) },
                modifier = Modifier.weight(1f),
            ) { Text("保存设置") }
        }

        if (!testResult.isNullOrBlank()) {
            Text(
                testResult,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ExposedDropdownMenuBox(
            expanded = modelMenuOpen,
            onExpandedChange = { modelMenuOpen = !modelMenuOpen },
        ) {
            OutlinedTextField(
                value = edited.modelName,
                onValueChange = { edited = edited.copy(modelName = it) },
                label = { Text("模型名称") },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuOpen) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                val preset = BuiltinPlatforms.firstOrNull { it.id == edited.apiPlatform }
                val presets = preset?.models.orEmpty()
                if (presets.isEmpty()) {
                    DropdownMenuItem(text = { Text("（无预设，请手动输入）") }, onClick = { modelMenuOpen = false })
                } else {
                    presets.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                edited = edited.copy(modelName = model)
                                modelMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        Column {
            Text("Temperature: ${"%.2f".format(edited.temperature)}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = edited.temperature.toFloat(),
                onValueChange = { edited = edited.copy(temperature = it.toDouble()) },
                valueRange = 0f..2f,
            )
        }

        Text("思考强度（DeepSeek）", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            THINKING_LEVELS.forEach { (value, label) ->
                FilterChip(
                    selected = edited.reasoningEffort == value,
                    onClick = { edited = edited.copy(reasoningEffort = value) },
                    label = { Text(label) },
                )
            }
        }

        SwitchRow("流式输出", edited.stream) { edited = edited.copy(stream = it) }
        SwitchRow("角色主动开口", edited.proactiveEnabled) { edited = edited.copy(proactiveEnabled = it) }
        SwitchRow("文风自动校对", edited.styleCritique) { edited = edited.copy(styleCritique = it) }
        SwitchRow("快速回应视角校正", edited.quickReplyRepair) { edited = edited.copy(quickReplyRepair = it) }

        OutlinedButton(onClick = onTestReminder, modifier = Modifier.fillMaxWidth()) {
            Text("测试提醒")
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
