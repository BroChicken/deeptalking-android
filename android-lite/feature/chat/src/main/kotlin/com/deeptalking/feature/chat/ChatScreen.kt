package com.deeptalking.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.deeptalking.core.designsystem.appColors
import com.deeptalking.core.designsystem.legacy
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.Sticker
import java.io.File
import java.util.Locale

@Composable
fun ChatScreen(
    character: Character?,
    messages: List<ChatMessage>,
    quickReplies: List<String>,
    isSending: Boolean,
    status: String,
    pendingImages: List<Uri>,
    onSend: (String) -> Unit,
    onQuickReply: (String) -> Unit,
    onAddImage: (Uri) -> Unit,
    onRemoveImage: (Uri) -> Unit,
    onEditResend: (String, String) -> Unit,
    onRegenerate: (String) -> Unit,
    onUserActivity: () -> Unit,
    onAddSticker: (Uri) -> Unit = {},
    onDeleteSticker: (String) -> Unit = {},
    onSetStickerTag: (String, String) -> Unit = { _, _ -> },
    onSendSticker: (Sticker) -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    var imagePreview by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var stickerPanelOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(onAddImage) }
    val stickerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(onAddSticker) }

    val visibleMessages = messages.filterNot { it.internalOnly }

    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(visibleMessages.size - 1) }
        }
    }
    LaunchedEffect(visibleMessages.lastOrNull()?.content) {
        if (isSending && visibleMessages.isNotEmpty()) {
            runCatching { listState.scrollToItem(visibleMessages.size - 1) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                character == null -> WelcomeHero()
                visibleMessages.isEmpty() -> CharacterEmptyState(character)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().widthIn(max = 896.dp).align(Alignment.TopCenter),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    items(visibleMessages, key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            character = character,
                            onCopy = { copyToClipboard(context, message.content) },
                            onEditResend = { editTarget = message },
                            onRegenerate = { onRegenerate(message.id) },
                            onImageClick = { imagePreview = it },
                        )
                    }
                    if (quickReplies.isNotEmpty() && !isSending) {
                        item { QuickReplyRow(quickReplies, isSending, onQuickReply) }
                    }
                }
            }
        }

        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pendingImages.forEach { uri ->
                    Box {
                        AsyncImage(
                            model = uri,
                            contentDescription = "待发送图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        IconButton(onClick = { onRemoveImage(uri) }, modifier = Modifier.align(Alignment.TopEnd).size(20.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "移除", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.legacy.border))
        Surface(color = MaterialTheme.legacy.header, tonalElevation = 0.dp) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (stickerPanelOpen && character != null) {
                    StickerPanel(
                        stickers = character.stickers,
                        onUpload = { stickerPicker.launch("image/*") },
                        onSend = { onSendSticker(it); stickerPanelOpen = false },
                        onDelete = onDeleteSticker,
                        onSetTag = onSetStickerTag,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).align(Alignment.CenterHorizontally).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(onClick = { imagePicker.launch("image/*") }, enabled = !isSending, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Image, contentDescription = "添加图片", tint = MaterialTheme.legacy.textSecondary, modifier = Modifier.size(18.dp))
                    }
                    if (character != null) {
                        IconButton(onClick = { stickerPanelOpen = !stickerPanelOpen }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.EmojiEmotions, contentDescription = "表情包", tint = MaterialTheme.legacy.textSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; onUserActivity() },
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp, max = 140.dp),
                        enabled = !isSending,
                        placeholder = { Text("输入消息", color = MaterialTheme.legacy.textMuted) },
                        maxLines = 6,
                        shape = RoundedCornerShape(12.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.legacy.input,
                            unfocusedContainerColor = MaterialTheme.legacy.input,
                            focusedBorderColor = MaterialTheme.legacy.inputBorder,
                            unfocusedBorderColor = MaterialTheme.legacy.inputBorder,
                            focusedTextColor = MaterialTheme.legacy.text,
                            unfocusedTextColor = MaterialTheme.legacy.text,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Default),
                    )
                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty() || pendingImages.isNotEmpty()) {
                                onSend(text)
                                input = ""
                            }
                        },
                        enabled = !isSending && (input.isNotBlank() || pendingImages.isNotEmpty()),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.legacy.btnPrimary),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "发送", tint = MaterialTheme.legacy.onUserBubble)
                    }
                }
            }
        }
    }

    imagePreview?.let { path ->
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xD9000000)).clickable { imagePreview = null },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(model = File(path), contentDescription = "图片", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().padding(24.dp))
        }
    }

    editTarget?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.content) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("编辑并重新发送消息：") },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { onEditResend(target.id, draft); editTarget = null }) { Text("重发") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun WelcomeHero() {
    val legacy = MaterialTheme.legacy
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Surface(shape = CircleShape, color = legacy.accentBg, modifier = Modifier.size(96.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(48.dp), tint = legacy.textMuted)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("欢迎使用 DeepTalking", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = legacy.textSecondary)
            Spacer(Modifier.height(8.dp))
            Text(
                "一个具有三级记忆系统的深度对话应用。点击左上角菜单按钮，创建或选择角色，开始一段有记忆的对话。",
                fontSize = 14.sp,
                color = legacy.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CharacterEmptyState(character: Character) {
    val legacy = MaterialTheme.legacy
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Surface(shape = CircleShape, color = legacy.accentBg, modifier = Modifier.size(80.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(character.emoji.ifEmpty { "👤" }, fontSize = 40.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Text("与 ${character.name} 开始对话", fontSize = 18.sp, color = legacy.textSecondary)
            Spacer(Modifier.height(6.dp))
            Text(
                character.staticProfile.personality.ifEmpty { "点击下方输入框开始聊天" },
                fontSize = 14.sp,
                color = legacy.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    character: Character,
    onCopy: () -> Unit,
    onEditResend: () -> Unit,
    onRegenerate: () -> Unit,
    onImageClick: (String) -> Unit,
) {
    val isUser = message.role == Role.User
    val colors = MaterialTheme.appColors
    val bubbleColor = if (isUser) colors.userBubble else colors.aiBubble
    val contentColor = if (isUser) colors.onUserBubble else colors.onAiBubble
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 12.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            Avatar(character.emoji.ifEmpty { "👤" })
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Surface(
                color = bubbleColor,
                contentColor = contentColor,
                shape = shape,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.84f),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (message.isLoading && message.content.isEmpty()) {
                        TypingIndicator(colors.onAiBubble.copy(alpha = 0.6f))
                    } else {
                        if (isUser) {
                            if (message.content.isNotBlank()) {
                                SelectionContainer {
                                    Text(
                                        message.content,
                                        fontSize = 15.sp,
                                        lineHeight = 1.68.em,
                                    )
                                }
                            }
                        } else if (message.content.isNotBlank()) {
                            RichText(source = message.content, modifier = Modifier.fillMaxWidth())
                        }
                        MessageImages(message, onImageClick)
                        message.staticChanges.takeIf { !isUser && it.isNotEmpty() }?.let {
                            Text("⚡ ${it.joinToString(" · ")}已修改", fontSize = 11.sp, color = colors.warning, modifier = Modifier.padding(top = 4.dp))
                        }
                        message.lorebookChanges.takeIf { !isUser && it.isNotEmpty() }?.let {
                            Text("📖 ${it.joinToString(" · ")}", fontSize = 11.sp, color = colors.warning, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (!message.isLoading && message.content.isNotBlank()) {
                        MessageMeta(message, isUser, onCopy, onEditResend, onRegenerate)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageMeta(
    message: ChatMessage,
    isUser: Boolean,
    onCopy: () -> Unit,
    onEditResend: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val legacy = MaterialTheme.legacy
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime(message.timestamp),
            fontSize = 11.sp,
            color = if (isUser) legacy.onUserBubble.copy(alpha = 0.7f) else legacy.textMuted,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.ContentCopy, contentDescription = "复制消息", tint = legacy.textMuted, modifier = Modifier.size(15.dp))
        }
        if (isUser) {
            IconButton(onClick = onEditResend, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "编辑并重发", tint = legacy.textMuted, modifier = Modifier.size(15.dp))
            }
        } else {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "重新生成", tint = legacy.textMuted, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun QuickReplyRow(replies: List<String>, isSending: Boolean, onQuickReply: (String) -> Unit) {
    val legacy = MaterialTheme.legacy
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(38.dp))
        Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.84f)) {
            Text("快速回应 · 以用户身份直接回复", fontSize = 11.sp, color = legacy.textMuted)
            Spacer(Modifier.height(6.dp))
            replies.forEach { reply ->
                Surface(
                    color = legacy.input,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .border(1.dp, legacy.inputBorder, RoundedCornerShape(10.dp))
                        .clickable(enabled = !isSending) { onQuickReply(reply) },
                ) {
                    Text(
                        reply,
                        fontSize = 12.sp,
                        lineHeight = 1.45.em,
                        color = legacy.text,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Avatar(emoji: String) {
    val legacy = MaterialTheme.legacy
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = legacy.accentBg,
        modifier = Modifier.size(30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 15.sp, color = legacy.text) }
    }
}

@Composable
private fun TypingIndicator(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = 0.5f)))
        }
    }
}

@Composable
private fun MessageImages(message: ChatMessage, onImageClick: (String) -> Unit) {
    val media = message.attachments.filter { it.kind == MessageAttachment.Kind.Image || it.kind == MessageAttachment.Kind.Sticker }
    if (media.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        media.forEach { attachment ->
            AsyncImage(
                model = File(attachment.uri),
                contentDescription = "图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (attachment.kind == MessageAttachment.Kind.Sticker) 96.dp else 160.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onImageClick(attachment.uri) },
            )
        }
    }
}

@Composable
private fun StickerPanel(
    stickers: List<Sticker>,
    onUpload: () -> Unit,
    onSend: (Sticker) -> Unit,
    onDelete: (String) -> Unit,
    onSetTag: (String, String) -> Unit,
) {
    var tagEdit by remember { mutableStateOf<Sticker?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(MaterialTheme.legacy.input)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onUpload) { Text("+ 上传表情", fontSize = 12.sp, color = MaterialTheme.legacy.text) }
            Spacer(Modifier.weight(1f))
            Text("${stickers.size} 张", fontSize = 11.sp, color = MaterialTheme.legacy.textMuted)
        }
        if (stickers.isEmpty()) {
            Text("还没有表情包。点上方「上传表情」加入。", fontSize = 12.sp, color = MaterialTheme.legacy.textMuted, modifier = Modifier.padding(12.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(72.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(stickers.size) { index ->
                    val sticker = stickers[index]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            AsyncImage(
                                model = File(sticker.fileRef),
                                contentDescription = sticker.tag,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).clickable { onSend(sticker) },
                            )
                            IconButton(onClick = { onDelete(sticker.id) }, modifier = Modifier.align(Alignment.TopEnd).size(18.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "删除表情", modifier = Modifier.size(12.dp))
                            }
                        }
                        Text(
                            sticker.tag.ifEmpty { "未分类" },
                            fontSize = 10.sp,
                            color = MaterialTheme.legacy.textMuted,
                            maxLines = 1,
                            modifier = Modifier.clickable { tagEdit = sticker },
                        )
                    }
                }
            }
        }
    }

    tagEdit?.let { sticker ->
        var tag by remember(sticker.id) { mutableStateOf(sticker.tag) }
        AlertDialog(
            onDismissRequest = { tagEdit = null },
            title = { Text("编辑标签") },
            text = { OutlinedTextField(value = tag, onValueChange = { tag = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onSetTag(sticker.id, tag); tagEdit = null }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { tagEdit = null }) { Text("取消") } },
        )
    }
}

private fun formatTime(timestamp: String?): String {
    if (timestamp.isNullOrBlank()) return ""
    return runCatching {
        val instant = java.time.Instant.parse(timestamp)
        val local = instant.atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        String.format(Locale.CHINA, "%02d:%02d", local.hour, local.minute)
    }.getOrElse { "" }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
}
