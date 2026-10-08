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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.deeptalking.core.data.legacy.decodeImportBytes
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
    val fieldMigrationCount by vm.fieldMigrationPrompt.collectAsState()
    val lorebookMigrationCount by vm.lorebookMigrationPrompt.collectAsState()
    val avatarRepairPrompt by vm.avatarRepairPrompt.collectAsState()
    val avatarRepairProgress by vm.avatarRepairProgress.collectAsState()
    val tts by vm.tts.collectAsState()

    val activeCharacter = characters.firstOrNull { it.id == activeId }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var sidebarTab by remember { mutableIntStateOf(0) }
    var editingCharacter by remember { mutableStateOf<com.deeptalking.core.model.Character?>(null) }
    var editingMemberIndex by remember { mutableStateOf<Int?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Legacy mobile sidebar is min(88vw, 21rem); mirror that here.
    val sidebarWidth = minOf(LocalConfiguration.current.screenWidthDp.dp * 0.88f, 340.dp)

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
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        }
                    }.onSuccess { snackbarHostState.showSnackbar("已导出备份") }
                        .onFailure { snackbarHostState.showSnackbar("导出失败：${it.message}") }
                }
            }
        }
    }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // Legacy `decodeImportBuffer`: UTF-8 → GBK → lenient UTF-8.
                val text = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { stream: java.io.InputStream ->
                            stream.readBytes()
                        }
                        bytes?.let { decodeImportBytes(it) }
                    }
                }.getOrNull()
                if (text != null) pendingImport = text else snackbarHostState.showSnackbar("文件读取失败")
            }
        }
    }
    val export = { exportLauncher.launch("deeptalking_backup_" + java.time.LocalDate.now() + ".json") }
    val import = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(sidebarWidth).statusBarsPadding().navigationBarsPadding(),
                drawerContainerColor = legacy.sidebar,
            ) {
                // .tab-btn row (角色 | 设置)
                Row(modifier = Modifier.fillMaxWidth().background(legacy.sidebar)) {
                    SidebarTab("角色", Icons.Default.Group, selected = sidebarTab == 0, modifier = Modifier.weight(1f)) { sidebarTab = 0 }
                    SidebarTab("设置", Icons.Default.Tune, selected = sidebarTab == 1, modifier = Modifier.weight(1f)) { sidebarTab = 1 }
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
                        onCreateCharacter = { name, emoji, personality, background, draft ->
                            vm.createCharacter(name, emoji, personality, background, draft)
                        },
                        onCreateGroup = { name, emoji, description, scene, rules, members, lorebook ->
                            vm.createGroup(name, emoji, description, scene, rules, members, lorebook)
                        },
                        onDelete = vm::deleteCharacter,
                        onEdit = { editingCharacter = it; editingMemberIndex = null },
                        onEditMember = { char, index -> editingCharacter = char; editingMemberIndex = index },
                        onExport = export,
                        onImport = import,
                        onQuickGenerate = { prompt, cb -> vm.quickGenerate(prompt, cb) },
                        onRepairAvatars = vm::requestAvatarRepair,
                        onUpgradeToGroup = { char -> vm.upgradeToGroup(char) },
                    )
                } else {
                    SettingsScreen(
                        config = config,
                        hasApiKey = vm.hasApiKey(config.apiPlatform),
                        loadApiKey = { platform -> vm.storedApiKey(platform) },
                        testResult = vm.testResult.collectAsState().value,
                        onSave = vm::saveSettings,
                        onTestReminder = vm::testReminder,
                        onTestConnection = { cfg, key -> vm.testApiConnection(cfg, key) },
                        ttsEnabled = tts.enabled,
                        ttsAutoRead = tts.autoRead,
                        ttsModelReady = tts.modelReady,
                        ttsDownloading = tts.downloading,
                        ttsProgress = tts.progress,
                        ttsProgressLabel = tts.progressLabel,
                        ttsVoices = tts.voices.map {
                            com.deeptalking.feature.settings.VoiceOption(it.file, it.name, it.builtin)
                        },
                        ttsActiveVoice = tts.activeVoice,
                        ttsStatus = tts.status,
                        ttsSpeaking = tts.speaking,
                        ttsBusy = tts.busy,
                        onToggleTtsEnabled = vm::setTtsEnabled,
                        onToggleTtsAutoRead = vm::setTtsAutoRead,
                        onDownloadTtsModel = vm::downloadVoiceModel,
                        onImportTtsVoice = vm::importVoice,
                        onSelectTtsVoice = vm::selectVoice,
                        onRenameTtsVoice = vm::renameVoice,
                        onDeleteTtsVoice = vm::deleteVoice,
                        onTestTtsSpeak = vm::speakText,
                        onStopTts = vm::stopSpeaking,
                    )
                }
            }
        },
    ) {
        Scaffold(
            containerColor = legacy.bg,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
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
                    onSendSticker = { sticker, text -> vm.sendSticker(sticker, text) },
                    onReadAloud = { msg -> vm.onBubbleReadAloud(msg, activeCharacter) },
                    ttsActiveMessageId = tts.activeMessageId,
                    ttsPhase = tts.phase,
                    ttsElapsedMs = tts.elapsedMs,
                )
            }
        }
    }

    editingCharacter?.let { target ->
        com.deeptalking.feature.characters.CharacterEditorHost(
            character = characters.firstOrNull { it.id == target.id } ?: target,
            onDismiss = { editingCharacter = null; editingMemberIndex = null },
            onUpdate = vm::updateCharacter,
            onAddLorebook = vm::addLorebookEntry,
            onRemoveLorebook = vm::removeLorebookEntry,
            onUpdateLorebook = vm::updateLorebookEntry,
            onGenerateAvatar = { char, cb -> vm.generateEmojiAvatar(char, cb) },
            onFillMember = { char, index, hint -> vm.fillGroupMember(char, index, hint) },
            initialMemberIndex = editingMemberIndex,
        )
    }

    // Field-structure migration is asked first; the world-book migration follows once it is gone.
    fieldMigrationCount?.let {
        var skipForever by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { vm.dismissFieldMigration() },
            title = { Text("字段结构升级") },
            text = {
                Column {
                    Text("检测到旧版字段结构。是否用 AI 把旧字段内容整理迁移到新版结构？（不迁移也可用，只是内容可能有些重复或错位）")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = skipForever, onCheckedChange = { skipForever = it })
                        Text("以后不再询问", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (skipForever) vm.skipFieldMigrationForever() else vm.runFieldMigration() }) {
                    Text("开始迁移")
                }
            },
            dismissButton = {
                TextButton(onClick = { if (skipForever) vm.skipFieldMigrationForever() else vm.dismissFieldMigration() }) {
                    Text("跳过")
                }
            },
        )
    }

    if (fieldMigrationCount == null) {
        lorebookMigrationCount?.let { count ->
            var trimSource by remember { mutableStateOf(false) }
            var skipForever by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { vm.dismissLorebookMigration() },
                title = { Text("世界书整理") },
                text = {
                    Column {
                        Text("检测到 $count 个角色/群组的描述里含有世界观类内容。世界书已改为由 AI 自动维护：是否用 AI 把这些内容整理成世界书条目？（不整理也能用，只是世界观会一直常驻占字数）")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = trimSource, onCheckedChange = { trimSource = it })
                            Text("同时把已迁出的内容从原文里精简掉", fontSize = 13.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = skipForever, onCheckedChange = { skipForever = it })
                            Text("以后不再询问", fontSize = 13.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (skipForever) vm.skipLorebookMigrationForever() else vm.runLorebookMigration(trimSource)
                    }) { Text("开始整理") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        if (skipForever) vm.skipLorebookMigrationForever() else vm.dismissLorebookMigration()
                    }) { Text("跳过") }
                },
            )
        }
    }

    // Legacy `repairAllAvatars`: a confirmation before the manual batch, then a
    // progress dialog showing `processed/total` while it runs.
    avatarRepairPrompt?.let { count ->
        AlertDialog(
            onDismissRequest = { vm.dismissAvatarRepair() },
            title = { Text("补全头像") },
            text = { Text("将为 $count 个默认/损坏头像生成贴切 emoji，确定继续？") },
            confirmButton = { TextButton(onClick = { vm.confirmAvatarRepair() }) { Text("开始补全") } },
            dismissButton = { TextButton(onClick = { vm.dismissAvatarRepair() }) { Text("取消") } },
        )
    }

    avatarRepairProgress?.let { (processed, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在补全头像…") },
            text = { Text("已处理 $processed/$total") },
            confirmButton = {},
        )
    }

    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入备份") },
            text = { Text("导入会覆盖当前所有角色与对话，且不可撤销。确定继续吗？") },
            confirmButton = {
                TextButton(onClick = { pendingImport = null; vm.importJson(text) }) { Text("覆盖导入") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SidebarTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val legacy = MaterialTheme.legacy
    Column(
        modifier = modifier
            .height(44.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) legacy.accent else legacy.textSecondary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) legacy.accent else legacy.textSecondary,
            )
        }
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
            // Legacy `#activityStatusBar` and `#cacheStatsBar` are mutually exclusive
            // siblings in the header row; keep both inline so nothing overlaps.
            if (status.isNotBlank()) {
                Text(
                    status,
                    fontSize = 11.sp,
                    color = legacy.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 6.dp),
                )
            } else {
                CachePill(config, Modifier.padding(end = 6.dp))
            }
            ThemeMenu(currentThemeId = currentThemeId, onSelect = onSelectTheme)
            Text(
                "v" + version,
                fontSize = 10.sp,
                color = legacy.textMuted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(legacy.border).align(Alignment.BottomStart))
    }
}

@Composable
private fun CachePill(config: com.deeptalking.core.model.AppConfig, modifier: Modifier = Modifier) {
    val legacy = MaterialTheme.legacy
    // Legacy `renderCacheStats` (`stickers.js:266-288`): the pill reads
    // `config.cacheStats`, shows `缓存 --` when unset, and averages the last 10
    // chat requests' hit rate.
    val stats = config.cacheStats
    val text = when {
        stats == null -> "缓存 --"
        stats.hitTokens == null || stats.missTokens == null -> "缓存未返回命中数据"
        else -> {
            val hit = stats.hitTokens ?: 0
            val miss = stats.missTokens ?: 0
            val total = hit + miss
            val rate = if (total > 0) Math.round(hit * 100.0 / total).toInt() else 0
            val recent = config.requestMetrics.filter { it.taskType == "chat" && it.hitRate != null }.takeLast(10)
            val average = if (recent.isNotEmpty()) {
                Math.round(recent.sumOf { it.hitRate ?: 0.0 } * 100.0 / recent.size).toInt()
            } else {
                rate
            }
            "缓存命中 $rate% · 近${recent.size}轮均值 $average%"
        }
    }
    Text(
        text,
        fontSize = 10.sp,
        color = legacy.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 200.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(legacy.bg)
            .border(1.dp, legacy.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
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
