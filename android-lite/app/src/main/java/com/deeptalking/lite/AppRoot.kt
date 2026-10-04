package com.deeptalking.lite

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deeptalking.core.designsystem.DeepTalkingTheme
import com.deeptalking.feature.characters.CharactersScreen
import com.deeptalking.feature.chat.ChatScreen
import com.deeptalking.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(core: NativeCore, vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val characters by vm.characters.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val messages by vm.messages.collectAsState()
    val quickReplies by vm.quickReplies.collectAsState()
    val isSending by vm.isSending.collectAsState()
    val status by vm.status.collectAsState()
    val config by vm.config.collectAsState()
    val pendingImages by vm.pendingImages.collectAsState()
    val generating by vm.isGenerating.collectAsState()

    val activeCharacter = characters.firstOrNull { it.id == activeId }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var sidebarTab by remember { mutableIntStateOf(0) }
    var editingCharacter by remember { mutableStateOf<com.deeptalking.core.model.Character?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { snackbarHostState.showSnackbar(it.message) }
    }

    // Pause/resume the proactive timer with app visibility (legacy visibilitychange).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.setAppVisible(true)
                Lifecycle.Event.ON_PAUSE -> vm.setAppVisible(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                if (text != null) vm.importJson(text) else snackbarHostState.showSnackbar("文件读取失败")
            }
        }
    }
    val export = { exportLauncher.launch("deeptalking_backup_" + java.time.LocalDate.now() + ".json") }
    val import = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                TabRow(selectedTabIndex = sidebarTab) {
                    Tab(selected = sidebarTab == 0, onClick = { sidebarTab = 0 }, text = { Text("角色") })
                    Tab(selected = sidebarTab == 1, onClick = { sidebarTab = 1 }, text = { Text("设置") })
                }
                if (sidebarTab == 0) {
                    CharactersScreen(
                        characters = characters,
                        activeId = activeId,
                        generating = generating,
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
                        onEdit = { editingCharacter = it },
                        onExport = export,
                        onImport = import,
                        onQuickGenerate = { prompt, cb -> vm.quickGenerate(prompt, cb) },
                        onRepairAvatars = vm::repairAvatars,
                        onUpgradeToGroup = { char -> vm.upgradeToGroup(char) },
                    )
                } else {
                    SettingsScreen(
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "角色列表")
                        }
                    },
                    title = {
                        if (activeCharacter == null) {
                            Text(
                                "选择一个角色开始对话",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(activeCharacter!!.emoji.ifEmpty { "👤" })
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        activeCharacter!!.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val occupation = activeCharacter!!.dynamicState.currentOccupation
                                        .ifBlank { "未知身份" }
                                    Text(
                                        occupation,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        ThemeMenu(currentThemeId = config.activeTheme, onSelect = vm::setTheme)
                        Text(
                            "v" + BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                CacheStatsBar(config)
                ChatScreen(
                    character = activeCharacter,
                    messages = messages,
                    quickReplies = quickReplies,
                    isSending = isSending,
                    status = status,
                    pendingImages = pendingImages,
                    onSend = vm::send,
                    onQuickReply = vm::send,
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
            }
        }
    }

    editingCharacter?.let { target ->
        com.deeptalking.feature.characters.CharacterEditorHost(
            character = characters.firstOrNull { it.id == target.id } ?: target,
            onDismiss = { editingCharacter = null },
            onUpdate = vm::updateCharacter,
            onAddLorebook = vm::addLorebookEntry,
            onRemoveLorebook = vm::removeLorebookEntry,
            onUpdateLorebook = vm::updateLorebookEntry,
            onGenerateAvatar = { char, cb -> vm.generateEmojiAvatar(char, cb) },
            onFillMember = { char, index, hint -> vm.fillGroupMember(char, index, hint) },
        )
    }
}

@Composable
private fun CacheStatsBar(config: com.deeptalking.core.model.AppConfig) {
    val metrics = config.requestMetrics
    val text = when {
        metrics.isEmpty() -> "缓存 --"
        metrics.none { it.inputTokens > 0 } -> "缓存未返回命中数据"
        else -> {
            val recent = metrics.takeLast(12).filter { it.inputTokens > 0 }
            val last = recent.lastOrNull()?.hitRate ?: 0.0
            val avg = if (recent.isEmpty()) 0.0 else recent.map { it.hitRate }.average()
            "缓存命中 ${(last * 100).toInt()}% · 近${recent.size}轮均值 ${(avg * 100).toInt()}%"
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

@Composable
private fun ThemeMenu(currentThemeId: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Text("◐", style = MaterialTheme.typography.titleMedium)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            com.deeptalking.core.designsystem.AppTheme.entries.forEach { theme ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(theme.label) },
                    leadingIcon = {
                        Surface(
                            shape = CircleShape,
                            color = swatchColor(theme.id),
                            modifier = Modifier.size(20.dp),
                        ) {}
                    },
                    onClick = {
                        onSelect(theme.id)
                        open = false
                    },
                )
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    currentThemeId
}

private fun swatchColor(id: String) = when (id) {
    "theme-black" -> androidx.compose.ui.graphics.Color(0xFF0F151C)
    "theme-blue" -> androidx.compose.ui.graphics.Color(0xFF0F202C)
    "theme-yellow" -> androidx.compose.ui.graphics.Color(0xFF2A271B)
    else -> androidx.compose.ui.graphics.Color(0xFFEDF0F4)
}
