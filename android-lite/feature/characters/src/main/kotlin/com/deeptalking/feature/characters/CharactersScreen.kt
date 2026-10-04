package com.deeptalking.feature.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.StaticProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

private val STATIC_FIELDS = listOf(
    "gender" to "性别",
    "age" to "年龄",
    "race" to "种族",
    "appearance" to "外貌特征",
    "personality" to "性格特征",
    "values" to "价值观",
    "fears" to "恐惧/弱点",
    "background" to "个人背景",
    "keyEvents" to "关键过往",
    "speakingStyle" to "说话风格",
    "language" to "语言/方言",
    "userAddress" to "对用户的称呼",
)

private val DYNAMIC_FIELDS = listOf(
    "currentSituation" to "当前处境",
    "currentLocation" to "当前位置/场景",
    "currentMood" to "当前情绪",
    "currentOccupation" to "当前职业/身份",
    "currentGoal" to "当前目标",
    "currentRelationship" to "当前关系",
    "currentImportantOthers" to "当前重要他人",
)

@Composable
fun CharactersScreen(
    characters: List<Character>,
    activeId: String?,
    generating: Boolean,
    onSelect: (String) -> Unit,
    onCreateCharacter: (name: String, emoji: String, personality: String, background: String) -> Unit,
    onCreateGroup: (name: String, emoji: String, description: String, scene: String, members: List<GroupMember>) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (Character) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onQuickGenerate: (String, (String?) -> Unit) -> Unit,
    onRepairAvatars: () -> Unit,
    onUpgradeToGroup: (Character) -> Unit,
) {
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Character?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { createOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("新建角色或群组")
            }
            OutlinedButton(onClick = onRepairAvatars, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("补全头像")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (characters.isEmpty()) {
                item { Text("暂无角色\n点击上方按钮创建", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(characters) { character ->
                CharacterCard(
                    character = character,
                    active = character.id == activeId,
                    onSelect = { onSelect(character.id) },
                    onEdit = { onEdit(character) },
                    onUpgrade = { onUpgradeToGroup(character) },
                    onDelete = { deleteTarget = character },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("导出")
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("导入")
            }
        }
    }

    if (createOpen) {
        CreateDialog(
            generating = generating,
            onQuickGenerate = onQuickGenerate,
            onDismiss = { createOpen = false },
            onCreateCharacter = { n, e, p, b ->
                onCreateCharacter(n, e, p, b)
                createOpen = false
            },
            onCreateGroup = { n, e, d, s, m ->
                onCreateGroup(n, e, d, s, m)
                createOpen = false
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除角色") },
            text = { Text("确定删除该角色及其所有记忆数据？") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun CharacterCard(
    character: Character,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onUpgrade: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(character.emoji.ifEmpty { if (character.isGroup) "👥" else "👤" }, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(character.name.ifEmpty { "未命名" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (character.isGroup) {
                            Spacer(Modifier.width(6.dp))
                            Text("群组", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        text = if (character.isGroup) {
                            "${character.members.size} 位成员 · ${character.description.ifEmpty { "群组对话" }}"
                        } else {
                            character.staticProfile.personality.ifEmpty { "暂无描述" }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑角色卡") }
                if (!character.isGroup) {
                    IconButton(onClick = onUpgrade) { Icon(Icons.Default.Groups, contentDescription = "升级为群组") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除角色") }
            }
            if (character.isGroup && character.members.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(modifier = Modifier.padding(start = 24.dp)) {
                    character.members.forEach { member ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(member.emoji.ifEmpty { "👤" }, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                member.name + (if (member.roleInGroup.isNotBlank()) " · ${member.roleInGroup}" else if (member.staticProfile.personality.isNotBlank()) " · ${member.staticProfile.personality}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateDialog(
    generating: Boolean,
    onQuickGenerate: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onCreateCharacter: (String, String, String, String) -> Unit,
    onCreateGroup: (String, String, String, String, List<GroupMember>) -> Unit,
) {
    var isGroup by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("") }
    var personality by remember { mutableStateOf("") }
    var background by remember { mutableStateOf("") }
    var groupDescription by remember { mutableStateOf("") }
    var groupScene by remember { mutableStateOf("") }
    var membersText by remember { mutableStateOf("") }
    var quickGenInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isGroup) "创建新群组" else "创建新角色") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("一句话生成 (可选)", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = quickGenInput,
                        onValueChange = { quickGenInput = it },
                        placeholder = { Text(if (isGroup) "描述你想要的群组和成员..." else "描述你想要的角色...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (quickGenInput.isBlank() || generating) return@Button
                            onQuickGenerate(quickGenInput) { raw ->
                                if (raw != null) {
                                    parseGenerated(raw)?.let { parsed ->
                                        isGroup = parsed.isGroup
                                        name = parsed.name
                                        emoji = parsed.emoji
                                        personality = parsed.personality
                                        background = parsed.background
                                        groupDescription = parsed.description
                                        groupScene = parsed.scene
                                        membersText = parsed.members.joinToString("\n") {
                                            it.name + "｜" + it.staticProfile.personality
                                        }
                                    }
                                }
                            }
                        },
                        enabled = quickGenInput.isNotBlank() && !generating,
                    ) { Text(if (generating) "生成中…" else "生成") }
                }
                Text("AI将根据描述自动填充下方字段", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !isGroup, onClick = { isGroup = false }, label = { Text("单角色") })
                    FilterChip(selected = isGroup, onClick = { isGroup = true }, label = { Text("群组") })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isGroup) "群组名称 *" else "角色名称 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it },
                    label = { Text("头像") },
                    placeholder = { Text("🌸") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isGroup) {
                    OutlinedTextField(
                        value = groupDescription,
                        onValueChange = { groupDescription = it },
                        label = { Text("群组前提") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = groupScene,
                        onValueChange = { groupScene = it },
                        label = { Text("共同场景") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = membersText,
                        onValueChange = { membersText = it },
                        label = { Text("群成员") },
                        placeholder = { Text("每行：名称｜性格简述") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = personality,
                        onValueChange = { personality = it },
                        label = { Text("性格简述") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = background,
                        onValueChange = { background = it },
                        label = { Text("背景故事") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isGroup) {
                        val members = parseMembers(membersText)
                        if (name.isNotBlank() && members.size >= 2) {
                            onCreateGroup(name.trim(), emoji.trim(), groupDescription.trim(), groupScene.trim(), members)
                        }
                    } else if (name.isNotBlank()) {
                        onCreateCharacter(name.trim(), emoji.trim(), personality.trim(), background.trim())
                    }
                },
                enabled = name.isNotBlank(),
            ) { Text(if (isGroup) "创建群组" else "创建角色") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ==================== Character card editor ====================

/** Host wrapper so AppRoot can show the editor for any character (incl. members). */
object CharacterEditorHost {
    @Composable
    operator fun invoke(
        character: Character,
        onDismiss: () -> Unit,
        onUpdate: (Character) -> Unit,
        onAddLorebook: (Character) -> Unit,
        onRemoveLorebook: (Character, String) -> Unit,
        onUpdateLorebook: (Character, LorebookEntry) -> Unit,
        onGenerateAvatar: (Character, (String?) -> Unit) -> Unit,
        onFillMember: (Character, Int, String) -> Unit,
    ) = CharacterEditorDialog(
        character = character,
        onDismiss = onDismiss,
        onUpdate = onUpdate,
        onAddLorebook = onAddLorebook,
        onRemoveLorebook = onRemoveLorebook,
        onUpdateLorebook = onUpdateLorebook,
        onGenerateAvatar = onGenerateAvatar,
        onFillMember = onFillMember,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditorDialog(
    character: Character,
    onDismiss: () -> Unit,
    onUpdate: (Character) -> Unit,
    onAddLorebook: (Character) -> Unit,
    onRemoveLorebook: (Character, String) -> Unit,
    onUpdateLorebook: (Character, LorebookEntry) -> Unit,
    onGenerateAvatar: (Character, (String?) -> Unit) -> Unit,
    onFillMember: (Character, Int, String) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var draft by remember(character.id) { mutableStateOf(character) }
    var memberIndex by remember(character.id) { mutableStateOf<Int?>(null) }
    val editingMember = memberIndex?.let { draft.members.getOrNull(it) }

    val title = when {
        editingMember != null -> "编辑成员: ${editingMember.name}"
        else -> "编辑: ${character.name}"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editingMember != null) {
                    IconButton(onClick = { memberIndex = null }) { Text("←") }
                }
                Text(title)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("基础设定") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("当前状态") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("世界书") })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("记忆") })
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (tab) {
                        0 -> BasicTab(
                            draft = draft,
                            memberIndex = memberIndex,
                            onDraft = { draft = it },
                            onGenerateAvatar = onGenerateAvatar,
                            onFillMember = onFillMember,
                        )
                        1 -> StateTab(draft = draft, memberIndex = memberIndex, onDraft = { draft = it })
                        2 -> LorebookTab(
                            draft = draft,
                            onDraft = { draft = it },
                            onAddLorebook = onAddLorebook,
                            onRemoveLorebook = onRemoveLorebook,
                            onUpdateLorebook = onUpdateLorebook,
                        )
                        else -> MemoryTab(draft = draft, editingMember = editingMember)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (memberIndex != null) {
                    val idx = memberIndex!!
                    val member = draft.members[idx]
                    onUpdate(draft.copy(members = draft.members.toMutableList().also { it[idx] = member }))
                } else {
                    onUpdate(draft)
                }
                onDismiss()
            }) { Text("保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BasicTab(
    draft: Character,
    memberIndex: Int?,
    onDraft: (Character) -> Unit,
    onGenerateAvatar: (Character, (String?) -> Unit) -> Unit,
    onFillMember: (Character, Int, String) -> Unit,
) {
    val member = memberIndex?.let { draft.members.getOrNull(it) }
    var fillHint by remember { mutableStateOf("") }

    fun updateProfile(profile: StaticProfile) {
        if (member == null) onDraft(draft.copy(staticProfile = profile))
        else onDraft(draft.copy(members = draft.members.toMutableList().also { it[memberIndex] = member.copy(staticProfile = profile) }))
    }

    fun updateName(value: String) {
        if (member == null) onDraft(draft.copy(name = value))
        else onDraft(draft.copy(members = draft.members.toMutableList().also { it[memberIndex] = member.copy(name = value) }))
    }

    fun updateEmoji(value: String) {
        if (member == null) onDraft(draft.copy(emoji = value))
        else onDraft(draft.copy(members = draft.members.toMutableList().also { it[memberIndex] = member.copy(emoji = value) }))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (member != null) {
            Text("一句话补全空字段", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = fillHint,
                    onValueChange = { fillHint = it },
                    placeholder = { Text("例如：她是负责医疗支持的沉稳护士") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { onFillMember(draft, memberIndex, fillHint) },
                    enabled = fillHint.isNotBlank(),
                ) { Text("补全") }
            }
            Text("只填充当前为空的字段，不覆盖已编辑内容。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedTextField(
            value = member?.name ?: draft.name,
            onValueChange = { updateName(it) },
            label = { Text(if (member != null) "名称" else if (draft.isGroup) "群组名称" else "名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = member?.emoji ?: draft.emoji,
            onValueChange = { updateEmoji(it) },
            label = { Text("头像") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (draft.isGroup && member == null) {
            OutlinedTextField(
                value = draft.description,
                onValueChange = { onDraft(draft.copy(description = it)) },
                label = { Text("群组前提") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.groupSharedDynamic.currentLocation,
                onValueChange = { onDraft(draft.copy(groupSharedDynamic = draft.groupSharedDynamic.copy(currentLocation = it))) },
                label = { Text("共同场景") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.interactionRules,
                onValueChange = { onDraft(draft.copy(interactionRules = it)) },
                label = { Text("成员互动规则") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "成员：" + draft.members.joinToString("、") { it.name },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val profile = member?.staticProfile ?: draft.staticProfile
            STATIC_FIELDS.forEach { (key, label) ->
                OutlinedTextField(
                    value = staticValue(profile, key),
                    onValueChange = { updateProfile(withStatic(profile, key, it)) },
                    label = { Text(label) },
                    minLines = if (key == "background") 3 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (member != null) {
                OutlinedTextField(
                    value = member.roleInGroup,
                    onValueChange = { role ->
                        onDraft(draft.copy(members = draft.members.toMutableList().also { list -> list[memberIndex] = member.copy(roleInGroup = role) }))
                    },
                    label = { Text("群内定位") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Avatar section with AI emoji generation.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(member?.emoji?.ifEmpty { "👤" } ?: draft.emoji.ifEmpty { if (draft.isGroup) "👥" else "👤" })
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                onGenerateAvatar(draft) { emoji ->
                    if (!emoji.isNullOrBlank()) updateEmoji(emoji)
                }
            }) { Text("AI 生成 emoji 头像") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateTab(draft: Character, memberIndex: Int?, onDraft: (Character) -> Unit) {
    val member = memberIndex?.let { draft.members.getOrNull(it) }
    val state = member?.dynamicState ?: draft.dynamicState
    val fields = if (draft.isGroup && member == null) {
        DYNAMIC_FIELDS.filter { it.first == "currentSituation" || it.first == "currentLocation" }
    } else {
        DYNAMIC_FIELDS
    }

    fun update(newState: com.deeptalking.core.model.DynamicState) {
        if (member == null) onDraft(draft.copy(dynamicState = newState))
        else onDraft(draft.copy(members = draft.members.toMutableList().also { it[memberIndex] = member.copy(dynamicState = newState) }))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "这些字段反映角色当前状态。你可以直接修改；AI 只会根据对话中明确的用户信息更新。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        fields.forEach { (key, label) ->
            OutlinedTextField(
                value = dynamicValue(state, key),
                onValueChange = { update(withDynamic(state, key, it)) },
                label = { Text(label) },
                minLines = if (key == "currentSituation") 3 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LorebookTab(
    draft: Character,
    onDraft: (Character) -> Unit,
    onAddLorebook: (Character) -> Unit,
    onRemoveLorebook: (Character, String) -> Unit,
    onUpdateLorebook: (Character, LorebookEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "世界书是世界层设定的唯一去处（时代与世界观、地点、组织、专有名词、历史、规则）。每条可选常驻（每轮都注入）或关键词命中（被提到才注入，更省 token）。条目由 AI 在剧情推进中自动维护，你可以随时修改、禁用或删除；你手改过的条目会被锁定，AI 不会再覆盖它。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (draft.lorebook.isEmpty()) {
            Text("还没有条目。可以让 AI 在建卡时生成，或在对话中自动补充。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        draft.lorebook.forEach { entry ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = entry.name,
                            onValueChange = { name ->
                                onUpdateLorebook(draft, entry.copy(name = name, origin = LorebookOrigin.User))
                            },
                            placeholder = { Text("条目名（如：赤月王国）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (entry.origin == LorebookOrigin.User) "手写" else "AI 写入",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.origin == LorebookOrigin.User) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        TextButton(onClick = { onRemoveLorebook(draft, entry.id) }) { Text("删除") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = true,
                                onCheckedChange = { checked ->
                                    onUpdateLorebook(draft, entry.copy(alwaysActive = checked))
                                },
                            )
                            Text("常驻", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            lorebookMention(draft, entry),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = entry.keywords.joinToString("、"),
                        onValueChange = { raw ->
                            val keywords = raw.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }.take(20)
                            val alwaysActive = if (keywords.isEmpty()) true else entry.alwaysActive
                            onUpdateLorebook(draft, entry.copy(keywords = keywords, alwaysActive = alwaysActive, origin = LorebookOrigin.User))
                        },
                        placeholder = { Text("触发关键词（用、或逗号分隔；常驻条目可留空）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = entry.content,
                        onValueChange = { content ->
                            onUpdateLorebook(draft, entry.copy(content = content, origin = LorebookOrigin.User))
                        },
                        placeholder = { Text("命中后注入的设定内容") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        OutlinedButton(onClick = { onAddLorebook(draft) }, modifier = Modifier.fillMaxWidth()) {
            Text("+ 添加条目")
        }
    }
}

@Composable
private fun MemoryTab(draft: Character, editingMember: GroupMember?) {
    val character = if (editingMember != null) {
        draft.copy(shortTerm = editingMember.shortTerm, longTerm = editingMember.longTerm)
    } else {
        draft
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("长期记忆", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (character.longTerm.isEmpty()) {
            Text("暂无长期记忆", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            character.longTerm.forEach { memory ->
                Text(
                    if (memory.key.isNotEmpty()) "${memory.key} — ${memory.value}" else memory.value,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text("短期记忆", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (character.shortTerm.isEmpty()) {
            Text("暂无短期记忆", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            character.shortTerm.forEach { memory ->
                Text(memory.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ==================== helpers ====================

private fun lorebookMention(draft: Character, entry: LorebookEntry): String {
    if (entry.misses <= 0) return "未命中过"
    return "命中 ${entry.misses} 次"
}

private data class GeneratedDraft(
    val isGroup: Boolean,
    val name: String,
    val emoji: String,
    val personality: String,
    val background: String,
    val description: String,
    val scene: String,
    val members: List<GroupMember>,
)

private fun parseGenerated(raw: String): GeneratedDraft? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val json = runCatching {
        Json.parseToJsonElement(raw.substring(start, end + 1)) as JsonObject
    }.getOrNull() ?: return null

    fun str(key: String): String = (json[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    val isGroup = str("entityType").equals("group", ignoreCase = true)
    val members = (json["members"] as? JsonArray).orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        fun m(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        GroupMember(
            id = UUID.randomUUID().toString(),
            name = m("name"),
            emoji = m("avatar").ifBlank { "👤" },
            staticProfile = StaticProfile(personality = m("personality")),
        )
    }
    return GeneratedDraft(
        isGroup = isGroup,
        name = str("name"),
        emoji = str("avatar"),
        personality = str("personality"),
        background = str("background"),
        description = str("description"),
        scene = str("scene"),
        members = members,
    )
}

private fun parseMembers(text: String): List<GroupMember> =
    text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split('｜', '|').map { it.trim() }
            GroupMember(
                id = UUID.randomUUID().toString(),
                name = parts.getOrNull(0).orEmpty(),
                emoji = "👤",
                staticProfile = StaticProfile(personality = parts.getOrNull(1).orEmpty()),
            )
        }

private fun staticValue(profile: StaticProfile, key: String): String = when (key) {
    "gender" -> profile.gender
    "age" -> profile.age
    "race" -> profile.race
    "appearance" -> profile.appearance
    "personality" -> profile.personality
    "values" -> profile.values
    "fears" -> profile.fears
    "background" -> profile.background
    "keyEvents" -> profile.keyEvents
    "speakingStyle" -> profile.speakingStyle
    "language" -> profile.language
    "userAddress" -> profile.userAddress
    else -> ""
}

private fun withStatic(profile: StaticProfile, key: String, value: String): StaticProfile = when (key) {
    "gender" -> profile.copy(gender = value)
    "age" -> profile.copy(age = value)
    "race" -> profile.copy(race = value)
    "appearance" -> profile.copy(appearance = value)
    "personality" -> profile.copy(personality = value)
    "values" -> profile.copy(values = value)
    "fears" -> profile.copy(fears = value)
    "background" -> profile.copy(background = value)
    "keyEvents" -> profile.copy(keyEvents = value)
    "speakingStyle" -> profile.copy(speakingStyle = value)
    "language" -> profile.copy(language = value)
    "userAddress" -> profile.copy(userAddress = value)
    else -> profile
}

private fun dynamicValue(state: com.deeptalking.core.model.DynamicState, key: String): String = when (key) {
    "currentSituation" -> state.currentSituation
    "currentLocation" -> state.currentLocation
    "currentMood" -> state.currentMood
    "currentOccupation" -> state.currentOccupation
    "currentGoal" -> state.currentGoal
    "currentRelationship" -> state.currentRelationship
    "currentImportantOthers" -> state.currentImportantOthers
    else -> ""
}

private fun withDynamic(state: com.deeptalking.core.model.DynamicState, key: String, value: String): com.deeptalking.core.model.DynamicState = when (key) {
    "currentSituation" -> state.copy(currentSituation = value)
    "currentLocation" -> state.copy(currentLocation = value)
    "currentMood" -> state.copy(currentMood = value)
    "currentOccupation" -> state.copy(currentOccupation = value)
    "currentGoal" -> state.copy(currentGoal = value)
    "currentRelationship" -> state.copy(currentRelationship = value)
    "currentImportantOthers" -> state.copy(currentImportantOthers = value)
    else -> state
}
