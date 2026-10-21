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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deeptalking.core.designsystem.AppTheme
import com.deeptalking.core.designsystem.DeepTalkingTheme
import com.deeptalking.core.designsystem.legacy
import com.deeptalking.core.data.legacy.decodeImportBytes
import com.deeptalking.core.model.RequestMetric
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
    val memoryCleanupPrompt by vm.memoryCleanupPrompt.collectAsState()
    val memoryCleanupProgress by vm.memoryCleanupProgress.collectAsState()
    val memoryMigrationPrompt by vm.memoryMigrationPrompt.collectAsState()
    val memoryMigration by vm.memoryMigration.collectAsState()
    val lorebookRebuildPrompt by vm.lorebookRebuildPrompt.collectAsState()
    val lorebookRebuild by vm.lorebookRebuild.collectAsState()
    val lorebookRetryPrompt by vm.lorebookRetryPrompt.collectAsState()
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
                        ttsBackend = tts.backend,
                        ttsReady = tts.ready,
                        ttsRemoteUrl = tts.remoteUrl,
                        ttsRemoteModel = tts.remoteModel,
                        ttsRemoteVoice = tts.remoteVoice,
                        ttsRemoteToken = tts.remoteToken,
                        ttsRemoteStatus = tts.remoteStatus,
                        ttsRemoteVoices = tts.remoteVoices,
                        ttsAdvanced = com.deeptalking.feature.settings.TtsAdvanced.fromStored(
                            chunkTokens = tts.remoteChunkTokens,
                            temperature = tts.remoteTemperature,
                            topK = tts.remoteTopK,
                            topP = tts.remoteTopP,
                            winSize = tts.remoteWinSize,
                            tauR = tts.remoteTauR,
                            minTokenTextRatio = tts.remoteMinTokenTextRatio,
                            maxTokenTextRatio = tts.remoteMaxTokenTextRatio,
                        ),
                        onSelectTtsBackend = vm::setTtsBackend,
                        onChangeTtsRemoteUrl = { vm.updateRemoteTts(url = it) },
                        onChangeTtsRemoteModel = { vm.updateRemoteTts(model = it) },
                        onChangeTtsRemoteVoice = { vm.updateRemoteTts(voice = it) },
                        onChangeTtsAdvanced = { adv ->
                            vm.updateRemoteTts(
                                chunkTokens = adv.chunkTokens,
                                temperature = adv.temperature,
                                topK = adv.topK,
                                topP = adv.topP,
                                winSize = adv.winSize,
                                tauR = adv.tauR,
                                minTokenTextRatio = adv.minTokenTextRatio,
                                maxTokenTextRatio = adv.maxTokenTextRatio,
                            )
                        },
                        onSaveTtsRemoteToken = vm::saveTtsToken,
                        onDiscoverTtsRemote = vm::discoverTtsServer,
                        onUploadTtsVoices = vm::uploadVoicesToRemote,
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
                    config = config,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onSelectTheme = vm::setTheme,
                )
                if (status.isNotBlank()) {
                    Text(
                        status,
                        fontSize = 11.sp,
                        color = legacy.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 2.dp),
                    )
                }
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
            onUpdate = vm::saveCharacterEdit,
            onAddLorebook = { characterId, memberId, entry ->
                if (memberId == null) vm.addLorebookEntry(characterId, entry)
                else vm.addMemberLorebookEntry(characterId, memberId, entry)
            },
            onRemoveLorebook = { characterId, memberId, entryId ->
                if (memberId == null) vm.removeLorebookEntry(characterId, entryId)
                else vm.removeMemberLorebookEntry(characterId, memberId, entryId)
            },
            onUpdateLorebook = { characterId, memberId, entry ->
                if (memberId == null) vm.updateLorebookEntry(characterId, entry)
                else vm.updateMemberLorebookEntry(characterId, memberId, entry)
            },
            onArchiveLorebook = { characterId, memberId, entryId ->
                if (memberId == null) vm.archiveLorebookEntry(characterId, entryId)
                else vm.archiveMemberLorebookEntry(characterId, memberId, entryId)
            },
            onRestoreLorebook = { characterId, memberId, entryId ->
                if (memberId == null) vm.restoreLorebookEntry(characterId, entryId)
                else vm.restoreMemberLorebookEntry(characterId, memberId, entryId)
            },
            onGenerateAvatar = { char, cb -> vm.generateEmojiAvatar(char, cb) },
            onRegenerateField = { char, memberIndex, fieldKey, hint, cb ->
                vm.regenerateCharacterField(char, memberIndex, fieldKey, hint, cb)
            },
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

    // One-time cleanup of the short-term pile-up + over-window images. Nothing is
    // truncated: old images are pruned (text stays) and the backlog is *processed*
    // into long-term memory in bounded batches.
    if (fieldMigrationCount == null && lorebookMigrationCount == null) {
        memoryCleanupPrompt?.let { target ->
            var skipForever by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { vm.dismissMemoryCleanup() },
                title = { Text("整理记忆与图片") },
                text = {
                    Column {
                        val lines = mutableListOf<String>()
                        if (target.shortTermPending > 0) {
                            lines += "短期记忆现有 ${target.shortTermTotal} 条，其中 ${target.shortTermPending} 条还没消化成" +
                                "长期记忆（会分批处理，直到降到 " +
                                "${com.deeptalking.core.common.AppLimits.Memory.SHORT_TERM_LOW_WATER} 条左右）。"
                        }
                        if (target.images > 0) {
                            lines += "有 ${target.images} 张较旧的聊天图片可以清理（只删图片，聊天文字保留）。"
                        }
                        Text(lines.joinToString("\n"))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点一次就行：图片立刻清理，短期记忆分批转成长期记忆，之后会在后台自己继续，" +
                                "不会删除内容。",
                            fontSize = 13.sp,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = skipForever, onCheckedChange = { skipForever = it })
                            Text("以后不再询问", fontSize = 13.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (skipForever) vm.skipMemoryCleanupForever() else vm.runMemoryCleanup()
                    }) { Text("开始整理") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        if (skipForever) vm.skipMemoryCleanupForever() else vm.dismissMemoryCleanup()
                    }) { Text("以后再说") }
                },
            )
        }
    }

    if (memoryCleanupProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在整理短期记忆…") },
            text = { Text("分批转成长期记忆，请稍候。") },
            confirmButton = {},
        )
    }

    // A world-book pass failed repeatedly: the batch is held in memory (it would otherwise
    // be dropped with the short-term entries the analysis consumed), so the user decides.
    lorebookRetryPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { vm.dismissLorebookRetry() },
            title = { Text("世界书整理失败") },
            text = {
                Column {
                    Text("「${prompt.characterName}」有一批内容没有整理进世界书。")
                    if (prompt.reason.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("原因：${prompt.reason}", fontSize = 13.sp, color = MaterialTheme.legacy.textMuted)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "重试会再跑一次这次整理；忽略则放弃这批内容（不会影响其它记忆）。",
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.retryLorebookNow() }) { Text("重试") } },
            dismissButton = { TextButton(onClick = { vm.dismissLorebookRetry() }) { Text("忽略") } },
        )
    }

    // One-time catch-up of an oversized (legacy) window: blocking on purpose — the whole
    // backlog is folded oldest-first and no message may be dropped in between.
    memoryMigration?.let { migration ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (migration.running) "正在整理旧记忆…" else "整理旧记忆未完成") },
            text = {
                Column {
                    if (migration.running) {
                        Text("历史过长，正在从最早的一批开始分批压成记忆，请勿退出。")
                        Spacer(Modifier.height(6.dp))
                        val left = if (migration.remaining > 0) "，剩余 ${migration.remaining} 条" else ""
                        Text("已完成 ${migration.batches} 批$left", fontSize = 13.sp)
                    } else {
                        Text(migration.error.ifBlank { "整理未能完成。" })
                        Spacer(Modifier.height(6.dp))
                        Text("已经整理的部分已保存，继续或重试都不会重复。", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                if (!migration.running) {
                    Row {
                        TextButton(onClick = { vm.retryMemoryMigration() }) { Text("重试") }
                        TextButton(onClick = { vm.dismissMemoryMigration() }) { Text("稍后") }
                    }
                }
            },
        )
    }

    // Confirmation for the oversized-window catch-up: it runs before the app is usable, so
    // ask first. "稍后" skips only this run — the next launch asks again.
    memoryMigrationPrompt?.let { target ->
        var skipForever by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { vm.dismissMemoryMigrationPrompt() },
            title = { Text("整理旧记忆") },
            text = {
                Column {
                    Text("有 ${target.characters} 个角色的历史窗口过大（共 ${target.messages} 条，正常只需要 20~60 条）。")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "需要从最早的一批开始分批压成记忆，期间不能使用，可能要几分钟。" +
                            "聊天记录一条都不会删，只是把早期消息压成摘要。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Checkbox(checked = skipForever, onCheckedChange = { skipForever = it })
                        Text("以后不再询问", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (skipForever) vm.skipMemoryWindowMigrationForever() else vm.confirmMemoryMigration()
                    },
                ) { Text("开始整理") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissMemoryMigrationPrompt() }) { Text("稍后") }
            },
        )
    }

    // Confirmation for the one-time world-book rewrite (oversized entries an older version
    // grew by appending instead of replacing). Shown only when no other prompt is up — the
    // view model already serialises these, this is the belt-and-braces check.
    lorebookRebuildPrompt?.let { count ->
        if (memoryCleanupPrompt != null || memoryCleanupProgress ||
            memoryMigration != null || memoryMigrationPrompt != null
        ) {
            return@let
        }
        AlertDialog(
            onDismissRequest = { vm.dismissLorebookRebuildPrompt() },
            title = { Text("整理世界书") },
            text = {
                Column {
                    Text("有 $count 条世界书条目内容过多，把好几件不同的事混在了一起，会一直占着每轮注入的额度。")
                    Spacer(Modifier.height(6.dp))
                    Text("需要交给模型重新整理，按主题拆成一条只讲一件事的条目；用户手写的条目不会被改动。", fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmLorebookRebuild() }) { Text("开始整理") } },
            dismissButton = { TextButton(onClick = { vm.dismissLorebookRebuildPrompt() }) { Text("稍后") } },
        )
    }

    lorebookRebuild?.let { rebuild ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (rebuild.running) "正在整理世界书…" else "整理世界书未完成") },
            text = {
                Column {
                    if (rebuild.running) {
                        Text("正在把过长的条目按主题重写，请勿退出。")
                        Spacer(Modifier.height(6.dp))
                        val left = if (rebuild.remaining > 0) "，剩余 ${rebuild.remaining} 条" else ""
                        Text("已完成 ${rebuild.done} 条$left", fontSize = 13.sp)
                    } else {
                        Text(rebuild.error.ifBlank { "整理未能完成。" })
                        Spacer(Modifier.height(6.dp))
                        Text("已经整理的部分已保存，继续或重试都不会重复。", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                if (!rebuild.running) {
                    Row {
                        TextButton(onClick = { vm.retryLorebookRebuild() }) { Text("重试") }
                        TextButton(onClick = { vm.dismissLorebookRebuild() }) { Text("稍后") }
                    }
                }
            },
        )
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
            Text(
                "v" + version,
                fontSize = 10.sp,
                color = legacy.textMuted,
                modifier = Modifier.padding(end = 2.dp),
            )
            CacheMenu(config)
            ThemeMenu(currentThemeId = currentThemeId, onSelect = onSelectTheme)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(legacy.border).align(Alignment.BottomStart))
    }
}

/** One cache row in the header popover. */
private data class CacheLine(
    val label: String,
    val hitTokens: Int?,
    val inputTokens: Int?,
)

private fun cacheLine(label: String, rows: List<RequestMetric>): CacheLine {
    val usable = rows.filter { (it.inputTokens ?: 0) > 0 }
    if (usable.isEmpty()) return CacheLine(label, null, null)
    return CacheLine(
        label = label,
        hitTokens = usable.sumOf { it.hitTokens ?: 0 },
        inputTokens = usable.sumOf { it.inputTokens ?: 0 },
    )
}

/**
 * The newest chat turn: walk the `chat.main` rows backwards until `round == 1`, so tool
 * rounds of the same turn are aggregated instead of showing only the last request.
 * Rows without a round are legacy data; they deliberately produce no line rather than
 * falling back to a single request.
 */
private fun latestMainTurn(metrics: List<RequestMetric>): List<RequestMetric> {
    val main = metrics.filter { it.taskType == "chat.main" }
    if (main.isEmpty() || main.last().round == null) return emptyList()
    val rows = ArrayDeque<RequestMetric>()
    for (row in main.asReversed()) {
        rows.addFirst(row)
        if (row.round == 1) break
    }
    return if (rows.firstOrNull()?.round == 1) rows.toList() else emptyList()
}

private fun latestTaskRows(metrics: List<RequestMetric>, taskType: String): List<RequestMetric> =
    listOfNotNull(metrics.lastOrNull { it.taskType == taskType })

/** Small triangle next to the version; opens the cache summary, not a persistent pill. */
@Composable
private fun CacheMenu(config: com.deeptalking.core.model.AppConfig) {
    var open by remember { mutableStateOf(false) }
    val legacy = MaterialTheme.legacy
    val main = remember(config.requestMetrics) {
        cacheLine("主聊天", latestMainTurn(config.requestMetrics))
    }
    val worker = remember(config.requestMetrics) {
        cacheLine("后台处理", latestTaskRows(config.requestMetrics, "chat.worker"))
    }
    val extraction = remember(config.requestMetrics) {
        cacheLine("短期提取", latestTaskRows(config.requestMetrics, "extraction"))
    }
    val analysis = remember(config.requestMetrics) {
        cacheLine("记忆整理", latestTaskRows(config.requestMetrics, "analysis"))
    }
    val lorebook = remember(config.requestMetrics) {
        cacheLine("世界书整理", latestTaskRows(config.requestMetrics, "lorebook"))
    }

    Box {
        Text(
            "▾",
            fontSize = 14.sp,
            color = legacy.textSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 230.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("缓存命中率", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = legacy.text)
                CacheLineRow(main)
                CacheLineRow(worker)
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(legacy.border))
                CacheLineRow(extraction)
                CacheLineRow(analysis)
                CacheLineRow(lorebook)
            }
        }
    }
}

@Composable
private fun CacheLineRow(line: CacheLine) {
    val legacy = MaterialTheme.legacy
    val hit = line.hitTokens
    val input = line.inputTokens
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(line.label, fontSize = 11.sp, color = legacy.textSecondary, modifier = Modifier.width(68.dp))
        val text = if (hit == null || input == null || input <= 0) {
            "缓存 --"
        } else {
            val rate = Math.round(hit * 100.0 / input).toInt()
            "缓存命中 $rate%  ${compactTokens(hit)}/${compactTokens(input)}"
        }
        Text(text, fontSize = 11.sp, color = legacy.text, modifier = Modifier.weight(1f))
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
            val recent = config.requestMetrics
                .filter { it.taskType.startsWith("chat") && it.hitRate != null }
                .takeLast(10)
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

/** Rows one task-type group averages over in the breakdown dialog. */
private const val METRIC_WINDOW = 10

/** One task type's cache summary (newest [METRIC_WINDOW] rows that reported usage). */
private data class MetricGroup(
    val taskType: String,
    val rows: Int,
    val hitTokens: Int?,
    val inputTokens: Int?,
    val hitRate: Int?,
    val recentRates: List<Int>,
    val lastAt: String,
)

private fun metricGroups(metrics: List<RequestMetric>): List<MetricGroup> =
    metrics.groupBy { it.taskType }
        .map { (taskType, all) ->
            val usable = all.filter { (it.inputTokens ?: 0) > 0 }.takeLast(METRIC_WINDOW)
            val hit = usable.sumOf { it.hitTokens ?: 0 }
            val input = usable.sumOf { it.inputTokens ?: 0 }
            MetricGroup(
                taskType = taskType,
                rows = all.size,
                hitTokens = if (usable.isEmpty()) null else hit,
                inputTokens = if (usable.isEmpty()) null else input,
                hitRate = if (input > 0) Math.round(hit * 100.0 / input).toInt() else null,
                recentRates = usable.mapNotNull { it.hitRate?.let { rate -> Math.round(rate * 100).toInt() } },
                lastAt = all.lastOrNull()?.at.orEmpty(),
            )
        }
        .sortedByDescending { it.lastAt }

/** Compact token count for the breakdown dialog (`1.2k` / `3.4M`). */
private fun compactTokens(value: Int): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

/**
 * Small button next to the cache pill. Opens the per-`taskType` cache breakdown: the pill
 * itself stays the 10-request average of [`chat.*`]; this dialog separates `chat.main`
 * (one row per model request, tool rounds included), `chat.worker` and the background
 * kinds (`memory.*`, `lorebook.*`, legacy `chat`/`auxiliary`...), all shown as stored.
 */
@Composable
private fun MetricBreakdownButton(config: com.deeptalking.core.model.AppConfig) {
    var open by remember { mutableStateOf(false) }
    val legacy = MaterialTheme.legacy
    Text(
        "指标",
        fontSize = 10.sp,
        color = legacy.textMuted,
        maxLines = 1,
        modifier = Modifier
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(legacy.bg)
            .border(1.dp, legacy.border, RoundedCornerShape(4.dp))
            .clickable { open = true }
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
    if (open) {
        val groups = metricGroups(config.requestMetrics)
        Dialog(onDismissRequest = { open = false }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = legacy.panel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "缓存指标 · 按任务类型",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = legacy.text,
                    )
                    if (groups.isEmpty()) {
                        Text("还没有请求记录", fontSize = 12.sp, color = legacy.textMuted)
                    }
                    groups.forEach { group -> MetricGroupRow(group) }
                    Text(
                        "每类保留最近 ${com.deeptalking.core.common.AppLimits.Api.REQUEST_METRICS} 条",
                        fontSize = 10.sp,
                        color = legacy.textMuted,
                    )
                    TextButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End)) {
                        Text("关闭", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricGroupRow(group: MetricGroup) {
    val legacy = MaterialTheme.legacy
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.taskType, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = legacy.text)
            Spacer(Modifier.weight(1f))
            Text("${group.rows} 条", fontSize = 10.sp, color = legacy.textMuted)
        }
        val hit = group.hitTokens
        val input = group.inputTokens
        val rate = group.hitRate
        Text(
            if (hit == null || input == null || rate == null) {
                "未返回命中数据"
            } else {
                "命中 ${compactTokens(hit)} / 输入 ${compactTokens(input)} · 命中率 $rate%"
            },
            fontSize = 11.sp,
            color = legacy.textSecondary,
        )
        if (group.recentRates.isNotEmpty()) {
            Text(
                "最近 ${group.recentRates.size} 次：" + group.recentRates.joinToString(" · ") { "$it%" },
                fontSize = 10.sp,
                color = legacy.textMuted,
            )
        }
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
