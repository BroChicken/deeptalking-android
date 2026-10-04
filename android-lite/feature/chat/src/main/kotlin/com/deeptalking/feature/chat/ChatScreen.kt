package com.deeptalking.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.deeptalking.core.designsystem.appColors
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.Role
import com.deeptalking.feature.richtext.renderMarkdownToHtml
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
    onProactive: () -> Unit,
    onAddImage: (Uri) -> Unit,
    onRemoveImage: (Uri) -> Unit,
    onEditResend: (String, String) -> Unit,
    onRegenerate: (String) -> Unit,
    onUserActivity: () -> Unit,
    onAddSticker: (Uri) -> Unit = {},
    onDeleteSticker: (String) -> Unit = {},
    onSetStickerTag: (String, String) -> Unit = { _, _ -> },
    onSendSticker: (com.deeptalking.core.model.Sticker) -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    var imagePreview by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var stickerPanelOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onAddImage) }

    val stickerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onAddSticker) }

    val visibleMessages = messages.filterNot { it.internalOnly }

    LaunchedEffect(visibleMessages.size, visibleMessages.lastOrNull()?.content) {
        if (visibleMessages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(visibleMessages.size - 1) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (character == null) {
            WelcomeHero()
        } else if (visibleMessages.isEmpty()) {
            CharacterEmptyState(character)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(visibleMessages) { message ->
                    MessageRow(
                        message = message,
                        character = character,
                        onCopy = { copyToClipboard(context, message.content) },
                        onEditResend = { editTarget = message },
                        onRegenerate = { onRegenerate(message.id) },
                        onImageClick = { imagePreview = it },
                    )
                }
            }
        }

        if (quickReplies.isNotEmpty() && !isSending) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "快速回应 · 以用户身份直接回复",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                quickReplies.forEach { reply ->
                    AssistChip(onClick = { onQuickReply(reply) }, label = { Text(reply) })
                }
            }
        }

        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
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
                        IconButton(
                            onClick = { onRemoveImage(uri) },
                            modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "移除", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column {
                if (stickerPanelOpen && character != null) {
                    StickerPanel(
                        stickers = character.stickers,
                        onUpload = { stickerPicker.launch("image/*") },
                        onSend = { sticker ->
                            onSendSticker(sticker)
                            stickerPanelOpen = false
                        },
                        onDelete = { onDeleteSticker(it) },
                        onSetTag = { id, tag -> onSetStickerTag(id, tag) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(onClick = { imagePicker.launch("image/*") }, enabled = !isSending) {
                        Icon(Icons.Default.Image, contentDescription = "添加图片")
                    }
                    if (character != null) {
                        IconButton(onClick = { stickerPanelOpen = !stickerPanelOpen }) {
                            Icon(Icons.Default.EmojiEmotions, contentDescription = "表情包")
                        }
                    }
                    if (character != null) {
                        IconButton(onClick = onProactive, enabled = !isSending) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "角色主动开口")
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            onUserActivity()
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 140.dp),
                        enabled = !isSending,
                        placeholder = { Text("输入消息") },
                        maxLines = 6,
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
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    imagePreview?.let { path ->
        AlertDialog(
            onDismissRequest = { imagePreview = null },
            confirmButton = { TextButton(onClick = { imagePreview = null }) { Text("关闭") } },
            text = {
                AsyncImage(
                    model = File(path),
                    contentDescription = "图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    editTarget?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.content) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("编辑并重新发送") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEditResend(target.id, draft)
                    editTarget = null
                }) { Text("重发") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ColumnScope.WelcomeHero() {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("欢迎使用 DeepTalking", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "一个具有三级记忆系统的深度对话应用。点击左上角菜单按钮，创建或选择角色，开始一段有记忆的对话。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColumnScope.CharacterEmptyState(character: Character) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(character.emoji.ifEmpty { "👤" }, style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(12.dp))
            Text("与 ${character.name} 开始对话", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                character.staticProfile.personality.ifEmpty { "点击下方输入框开始聊天" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            Avatar(emoji = character.emoji.ifEmpty { "👤" })
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Surface(
                color = bubbleColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.bubbleBorder),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (message.isLoading && message.content.isEmpty()) {
                        TypingIndicator(contentColor)
                    } else if (isUser) {
                        if (message.content.isNotBlank()) {
                            SelectionContainer {
                                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        MessageImages(message, onImageClick)
                    } else {
                        if (validText(message.content)) {
                            RichText(
                                html = renderMarkdownToHtml(message.content),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        MessageImages(message, onImageClick)
                    }
                }
            }
            if (!message.isLoading && message.content.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制消息", modifier = Modifier.size(14.dp))
                    }
                    if (isUser) {
                        IconButton(onClick = onEditResend, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑并重发", modifier = Modifier.size(14.dp))
                        }
                    } else {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新生成", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Avatar(emoji: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TypingIndicator(color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("● ● ●", color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MessageImages(message: ChatMessage, onImageClick: (String) -> Unit) {
    val media = message.attachments.filter {
        it.kind == MessageAttachment.Kind.Image || it.kind == MessageAttachment.Kind.Sticker
    }
    media.forEach { attachment ->
        AsyncImage(
            model = File(attachment.uri),
            contentDescription = "图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(top = 6.dp)
                .widthIn(max = if (attachment.kind == MessageAttachment.Kind.Sticker) 160.dp else 280.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onImageClick(attachment.uri) },
        )
    }
}

private fun validText(text: String): Boolean = text.isNotBlank()

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

@Composable
private fun StickerPanel(
    stickers: List<com.deeptalking.core.model.Sticker>,
    onUpload: () -> Unit,
    onSend: (com.deeptalking.core.model.Sticker) -> Unit,
    onDelete: (String) -> Unit,
    onSetTag: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onUpload) { Text("+ 上传表情") }
            Spacer(Modifier.weight(1f))
            Text("${stickers.size} 张", style = MaterialTheme.typography.labelSmall)
        }
        if (stickers.isEmpty()) {
            Text(
                "还没有表情包。点上方「上传表情」加入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(64.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(stickers.size) { index ->
                    val sticker = stickers[index]
                    Box {
                        AsyncImage(
                            model = File(sticker.fileRef),
                            contentDescription = sticker.tag,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSend(sticker) },
                        )
                        IconButton(
                            onClick = { onDelete(sticker.id) },
                            modifier = Modifier.align(Alignment.TopEnd).size(18.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "删除表情", modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }
}
