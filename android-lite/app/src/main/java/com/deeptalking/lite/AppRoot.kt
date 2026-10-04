package com.deeptalking.lite

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deeptalking.core.designsystem.AppTheme
import com.deeptalking.core.designsystem.DeepTalkingTheme
import com.deeptalking.core.designsystem.legacy
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

@Composable
private fun AppContent(core: NativeCore, vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val legacy = MaterialTheme.legacy

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
            ModalDrawerSheet(
                modifier = Modifier.width(258.dp),
                drawerContainerColor = legacy.sidebar,
            ) {
                // .tab-btn row (角色 | 设置)
                Row(modifier = Modifier.fillMaxWidth().background(legacy.sidebar)) {
                    SidebarTab("角色", selected = sidebarTab == 0, modifier = Modifier.weight(1f)) { sidebarTab = 0 }
                    SidebarTab("设置", selected = sidebarTab == 1, modifier = Modifier.weight(1f)) { sidebarTab = 1 }
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(legacy.border))
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
            containerColor = legacy.bg,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                AppHeader(
                    activeCharacter = activeCharacter,
                    currentThemeId = config.activeTheme,
                    version = BuildConfig.VERSION_NAME,
                    status = status,
                    config = config,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onSelectTheme = vm::setTheme,
                )
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
private fun SidebarTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val legacy = MaterialTheme.legacy
    Column(
        modifier = modifier
            .height(44.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) legacy.accent else legacy.textSecondary,
        )
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .width(48.dp)
                .height(2.dp)
                .background(if (selected) legacy.accent else androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun AppHeader(
    activeCharacter: com.deeptalking.core.model.Character?,
    currentThemeId: String,
    version: String,
    status: String,
    config: com.deeptalking.core.model.AppConfig,
    onOpenDrawer: () -> Unit,
    onSelectTheme: (String) -> Unit,
) {
    val legacy = MaterialTheme.legacy
    Box(modifier = Modifier.fillMaxWidth().background(legacy.header).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.Menu, contentDescription = "角色列表", tint = legacy.textSecondary)
            }
            Spacer(Modifier.width(10.dp))
            if (activeCharacter == null) {
                Text("选择一个角色开始对话", fontSize = 14.sp, color = legacy.textMuted)
            } else {
                Surface(
                    shape = CircleShape,
                    color = legacy.accentBg,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(activeCharacter.emoji.ifEmpty { "👤" }, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        activeCharacter.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = legacy.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        activeCharacter.dynamicState.currentOccupation.ifBlank { "未知身份" },
                        fontSize = 12.sp,
                        color = legacy.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            ThemeMenu(currentThemeId = currentThemeId, onSelect = onSelectTheme)
            Text(
                "v" + version,
                fontSize = 10.sp,
                color = legacy.textMuted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        // #activityStatusBar (bottom-left) and #cacheStatsBar (bottom-right) pills
        StatusPills(status = status, config = config, modifier = Modifier.align(Alignment.BottomEnd))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(legacy.border).align(Alignment.BottomStart))
    }
}

@Composable
private fun StatusPills(
    status: String,
    config: com.deeptalking.core.model.AppConfig,
    modifier: Modifier = Modifier,
) {
    val legacy = MaterialTheme.legacy
    val metrics = config.requestMetrics
    val cacheText = when {
        metrics.isEmpty() -> "缓存 --"
        metrics.none { it.inputTokens > 0 } -> "缓存未返回命中数据"
        else -> {
            val recent = metrics.takeLast(12).filter { it.inputTokens > 0 }
            val last = recent.lastOrNull()?.hitRate ?: 0.0
            val avg = if (recent.isEmpty()) 0.0 else recent.map { it.hitRate }.average()
            "缓存命中 ${(last * 100).toInt()}% · 近${recent.size}轮均值 ${(avg * 100).toInt()}%"
        }
    }
    Row(modifier = modifier.padding(end = 8.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status.isNotBlank()) {
            Text(
                status,
                fontSize = 11.sp,
                color = legacy.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Text(
            cacheText,
            fontSize = 10.sp,
            color = legacy.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(legacy.header)
                .border(1.dp, legacy.border, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun ThemeMenu(currentThemeId: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val legacy = MaterialTheme.legacy
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(42.dp)) {
            Text("◐", fontSize = 18.sp, color = legacy.textSecondary)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppTheme.entries.forEach { theme ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            theme.label,
                            color = if (theme.id == currentThemeId) legacy.accent else legacy.textSecondary,
                        )
                    },
                    leadingIcon = {
                        Surface(
                            shape = CircleShape,
                            color = theme.swatch,
                            modifier = Modifier.size(20.dp).border(1.dp, legacy.textMuted, CircleShape),
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
}
