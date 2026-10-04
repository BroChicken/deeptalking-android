package com.deeptalking.lite

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deeptalking.core.designsystem.AppTheme
import com.deeptalking.core.designsystem.DeepTalkingTheme
import com.deeptalking.feature.characters.CharactersScreen
import com.deeptalking.feature.chat.ChatScreen
import com.deeptalking.feature.memory.MemoryScreen
import com.deeptalking.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(core: NativeCore) {
    val vm: AppViewModel = viewModel(
        factory = viewModelFactory { initializer { AppViewModel(core) } },
    )

    val theme by vm.theme.collectAsState()
    DeepTalkingTheme(theme = theme) {
        AppContent(core, vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(core: NativeCore, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val characters by vm.characters.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val messages by vm.messages.collectAsState()
    val quickReplies by vm.quickReplies.collectAsState()
    val isSending by vm.isSending.collectAsState()
    val status by vm.status.collectAsState()
    val config by vm.config.collectAsState()
    val pendingImages by vm.pendingImages.collectAsState()
    val theme by vm.theme.collectAsState()

    val activeCharacter = characters.firstOrNull { it.id == activeId }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var sidebarTab by remember { mutableIntStateOf(0) }
    var screen by remember { mutableStateOf(Screen.Chat) }
    var themeMenuOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { snackbarHostState.showSnackbar(it.message) }
    }

    // --- Import / Export via Storage Access Framework ---
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            vm.exportJson { json ->
                scope.launch {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    }.onSuccess { snackbarHostState.showSnackbar("已导出备份") }
                        .onFailure { snackbarHostState.showSnackbar("导出失败：${it.message}") }
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                if (text != null) vm.importJson(text)
                else snackbarHostState.showSnackbar("文件读取失败")
            }
        }
    }
    val export = {
        val name = "deeptalking_backup_" + java.time.LocalDate.now() + ".json"
        exportLauncher.launch(name)
    }
    val import = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                TabRow(selectedTabIndex = sidebarTab) {
                    Tab(selected = sidebarTab == 0, onClick = { sidebarTab = 0 }, text = { Text("角色") })
                    Tab(selected = sidebarTab == 1, onClick = { sidebarTab = 1 }, text = { Text("设置") })
                }
                when (sidebarTab) {
                    0 -> CharactersScreen(
                        characters = characters,
                        activeId = activeId,
                        onSelect = {
                            vm.select(it)
                            scope.launch { drawerState.close() }
                        },
                        onCreateCharacter = { name, emoji, personality, background ->
                            vm.createCharacter(name, emoji, personality, background)
                        },
                        onCreateGroup = { name, emoji, description, scene, members ->
                            vm.createGroup(name, emoji, description, scene, members)
                        },
                        onDelete = vm::deleteCharacter,
                        onUpdate = vm::updateCharacter,
                        onAddLorebook = vm::addLorebookEntry,
                        onRemoveLorebook = vm::removeLorebookEntry,
                        onUpdateLorebook = vm::updateLorebookEntry,
                        onExport = export,
                        onImport = import,
                        onQuickGenerate = { prompt, cb -> vm.quickGenerate(prompt, cb) },
                        onRepairAvatars = vm::repairAvatars,
                    )
                    else -> SettingsScreen(
                        config = config,
                        hasApiKey = vm.hasApiKey(),
                        testResult = vm.testResult.collectAsState().value,
                        onSave = vm::saveSettings,
                        onTestReminder = vm::testReminder,
                        onTestConnection = vm::testApiConnection,
                    )
                }
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "角色列表")
                        }
                    },
                    title = {
                        Column {
                            val title = when (screen) {
                                Screen.Chat -> activeCharacter?.name ?: "选择一个角色开始对话"
                                Screen.Memory -> "记忆"
                                Screen.Settings -> "设置"
                            }
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            if (status.isNotEmpty()) {
                                Text(
                                    status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { themeMenuOpen = true }) {
                            Icon(Icons.Default.Contrast, contentDescription = "切换主题")
                        }
                        DropdownMenu(expanded = themeMenuOpen, onDismissRequest = { themeMenuOpen = false }) {
                            AppTheme.entries.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.label) },
                                    onClick = {
                                        vm.setTheme(preset.id)
                                        themeMenuOpen = false
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    NavChip("聊天", screen == Screen.Chat) { screen = Screen.Chat }
                    NavChip("记忆", screen == Screen.Memory) { screen = Screen.Memory }
                    NavChip("设置", screen == Screen.Settings) { screen = Screen.Settings }
                }
                when (screen) {
                    Screen.Chat -> ChatScreen(
                        character = activeCharacter,
                        messages = messages,
                        quickReplies = quickReplies,
                        isSending = isSending,
                        status = status,
                        pendingImages = pendingImages,
                        onSend = vm::send,
                        onQuickReply = vm::send,
                        onProactive = vm::promptProactive,
                        onAddImage = vm::addPendingImage,
                        onRemoveImage = vm::removePendingImage,
                        onEditResend = vm::editAndResend,
                        onRegenerate = vm::regenerate,
                        onUserActivity = vm::noteUserActivity,
                        onAddSticker = vm::addSticker,
                        onDeleteSticker = { id -> activeCharacter?.let { vm.deleteSticker(it, id) } },
                        onSetStickerTag = { id, tag -> activeCharacter?.let { vm.setStickerTag(it, id, tag) } },
                        onSendSticker = vm::sendSticker,
                    )
                    Screen.Memory -> MemoryScreen(
                        character = activeCharacter,
                        onDeleteLongTerm = { id ->
                            activeCharacter?.let { vm.deleteLongTerm(it, id) }
                        },
                        onDeleteShortTerm = { id ->
                            activeCharacter?.let { vm.deleteShortTerm(it, id) }
                        },
                    )
                    Screen.Settings -> SettingsScreen(
                        config = config,
                        hasApiKey = vm.hasApiKey(),
                        testResult = vm.testResult.collectAsState().value,
                        onSave = vm::saveSettings,
                        onTestReminder = vm::testReminder,
                        onTestConnection = vm::testApiConnection,
                    )
                }
            }
        }
    }
}

private enum class Screen { Chat, Memory, Settings }

@Composable
private fun NavChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Tab(selected = selected, onClick = onClick, text = { Text(label) })
}
