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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: AppConfig,
    hasApiKey: Boolean,
    onSave: (AppConfig, apiKey: String?) -> Unit,
    onTestReminder: () -> Unit,
) {
    var edited by remember(config) { mutableStateOf(config) }
    var apiKey by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = {
                    Text(if (hasApiKey) "已保存密钥" else "未配置密钥")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "平台",
                style = MaterialTheme.typography.titleSmall,
            )
            BuiltinPlatforms.forEach { preset ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = edited.apiPlatform == preset.id,
                        onClick = {
                            edited = edited.copy(
                                apiPlatform = preset.id,
                                apiBaseUrl = preset.baseUrl,
                                modelName = preset.defaultModel,
                            )
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = preset.id, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = preset.baseUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = edited.modelName,
                onValueChange = { edited = edited.copy(modelName = it) },
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                Text(
                    text = "Temperature: ${"%.2f".format(edited.temperature)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = edited.temperature.toFloat(),
                    onValueChange = { edited = edited.copy(temperature = it.toDouble()) },
                    valueRange = 0f..2f,
                )
            }

            SwitchRow(
                label = "流式输出",
                checked = edited.stream,
                onCheckedChange = { edited = edited.copy(stream = it) },
            )
            SwitchRow(
                label = "主动消息",
                checked = edited.proactiveEnabled,
                onCheckedChange = { edited = edited.copy(proactiveEnabled = it) },
            )
            SwitchRow(
                label = "风格批判",
                checked = edited.styleCritique,
                onCheckedChange = { edited = edited.copy(styleCritique = it) },
            )
            SwitchRow(
                label = "快捷回复修复",
                checked = edited.quickReplyRepair,
                onCheckedChange = { edited = edited.copy(quickReplyRepair = it) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { onSave(edited, apiKey.ifBlank { null }) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存")
                }
                OutlinedButton(
                    onClick = onTestReminder,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("测试提醒")
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
