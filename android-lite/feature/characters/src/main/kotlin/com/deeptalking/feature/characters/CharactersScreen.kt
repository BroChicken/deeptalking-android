package com.deeptalking.feature.characters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
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
import com.deeptalking.core.model.DynamicState
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

/** Legacy 基础设定 labels (STATIC_PROFILE_FIELDS), keyed by model field. */
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

/** Legacy 当前状态 labels (DYNAMIC_STATE_FIELDS). */
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
    onSelect: (String) -> Unit,
    onCreateCharacter: (name: String, emoji: String, personality: String, background: String) -> Unit,
    onCreateGroup: (name: String, emoji: String, description: String, scene: String, members: List<GroupMember>) -> Unit,
    onDelete: (String) -> Unit,
    onUpdate: (Character) -> Unit,
    onAddLorebook: (Character) -> Unit,
    onRemoveLorebook: (Character, String) -> Unit,
    onUpdateLorebook: (Character, LorebookEntry) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onQuickGenerate: (String, (String?) -> Unit) -> Unit = { _, cb -> cb(null) },
    onRepairAvatars: () -> Unit = {},
) {
    var createOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Character?>(null) }
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
                    onEdit = { editing = character },
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

    editing?.let { target ->
        CharacterEditorDialog(
            character = characters.firstOrNull { it.id == target.id } ?: target,
            onDismiss = { editing = null },
            onUpdate = onUpdate,
            onAddLorebook = onAddLorebook,
            onRemoveLorebook = onRemoveLorebook,
            onUpdateLorebook = onUpdateLorebook,
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
                Text(character.emoji.ifEmpty { if (character.isGroup) "👥" else "👤" }, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(character.name.ifEmpty { "未命名" }, style = MaterialTheme.typography.titleMedium)
                        if (character.isGroup) {
                            Spacer(Modifier.width(6.dp))
                            Text("群组", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                                member.name + (if (member.staticProfile.personality.isNotBlank()) " · ${member.staticProfile.personality}" else ""),
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
    var generating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isGroup) "创建新群组" else "创建新角色") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("一句话生成（可选）", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = quickGenInput,
                        onValueChange = { quickGenInput = it },
                        placeholder = { Text("描述你想要的角色...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (quickGenInput.isBlank() || generating) return@Button
                            generating = true
                            onQuickGenerate(quickGenInput) { raw ->
                                generating = false
                                if (raw != null) {
                                    val parsed = parseGenerated(raw)
                                    if (parsed != null) {
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
                Text("AI 将根据描述自动填充下方字段", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    } else {
                        if (name.isNotBlank()) {
                            onCreateCharacter(name.trim(), emoji.trim(), personality.trim(), background.trim())
                        }
                    }
                },
                enabled = name.isNotBlank(),
            ) { Text(if (isGroup) "创建群组" else "创建角色") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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

/** Parses the model's quick-generate JSON (tolerating code fences / prose). */
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
            val name = parts.getOrNull(0).orEmpty()
            val personality = parts.getOrNull(1).orEmpty()
            GroupMember(
                id = UUID.randomUUID().toString(),
                name = name,
                emoji = "👤",
                staticProfile = StaticProfile(personality = personality),
            )
        }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterEditorDialog(
    character: Character,
    onDismiss: () -> Unit,
    onUpdate: (Character) -> Unit,
    onAddLorebook: (Character) -> Unit,
    onRemoveLorebook: (Character, String) -> Unit,
    onUpdateLorebook: (Character, LorebookEntry) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var draft by remember(character.id) { mutableStateOf(character) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑：${character.name.ifEmpty { "角色卡" }}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("基础设定") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("当前状态") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("世界书") })
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (tab) {
                        0 -> BasicTab(draft = draft, onDraft = { draft = it })
                        1 -> StateTab(draft = draft, onDraft = { draft = it })
                        else -> LorebookTab(
                            draft = draft,
                            onUpdate = onUpdate,
                            onAddLorebook = onAddLorebook,
                            onRemoveLorebook = onRemoveLorebook,
                            onUpdateLorebook = onUpdateLorebook,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onUpdate(draft)
                onDismiss()
            }) { Text("保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BasicTab(draft: Character, onDraft: (Character) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraft(draft.copy(name = it)) },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.emoji,
            onValueChange = { onDraft(draft.copy(emoji = it)) },
            label = { Text("头像") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        STATIC_FIELDS.forEach { (key, label) ->
            val value = staticValue(draft.staticProfile, key)
            OutlinedTextField(
                value = value,
                onValueChange = { onDraft(draft.copy(staticProfile = withStatic(draft.staticProfile, key, it))) },
                label = { Text(label) },
                minLines = if (key == "background") 3 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StateTab(draft: Character, onDraft: (Character) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DYNAMIC_FIELDS.forEach { (key, label) ->
            OutlinedTextField(
                value = dynamicValue(draft.dynamicState, key),
                onValueChange = { onDraft(draft.copy(dynamicState = withDynamic(draft.dynamicState, key, it))) },
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
    onUpdate: (Character) -> Unit,
    onAddLorebook: (Character) -> Unit,
    onRemoveLorebook: (Character, String) -> Unit,
    onUpdateLorebook: (Character, LorebookEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "世界书为角色/世界的设定库，命中关键词后注入对话。AI 写入的条目可被覆盖，用户手写的条目优先级更高。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        draft.lorebook.forEach { entry ->
            Card {
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
                            color = MaterialTheme.colorScheme.primary,
                        )
                        IconButton(onClick = { onRemoveLorebook(draft, entry.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                    OutlinedTextField(
                        value = entry.keywords.joinToString("、"),
                        onValueChange = { raw ->
                            val keywords = raw.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                            onUpdateLorebook(draft, entry.copy(keywords = keywords, origin = LorebookOrigin.User))
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
        Text(
            "提示：请在“基础设定/当前状态”页修改后点“保存修改”。世界书改动即时生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Persist any pending basic/state edits when leaving via save.
        Spacer(Modifier.height(2.dp))
        TextButton(onClick = { onUpdate(draft) }) { Text("立即保存角色属性") }
    }
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

private fun dynamicValue(state: DynamicState, key: String): String = when (key) {
    "currentSituation" -> state.currentSituation
    "currentLocation" -> state.currentLocation
    "currentMood" -> state.currentMood
    "currentOccupation" -> state.currentOccupation
    "currentGoal" -> state.currentGoal
    "currentRelationship" -> state.currentRelationship
    "currentImportantOthers" -> state.currentImportantOthers
    else -> ""
}

private fun withDynamic(state: DynamicState, key: String, value: String): DynamicState = when (key) {
    "currentSituation" -> state.copy(currentSituation = value)
    "currentLocation" -> state.copy(currentLocation = value)
    "currentMood" -> state.copy(currentMood = value)
    "currentOccupation" -> state.copy(currentOccupation = value)
    "currentGoal" -> state.copy(currentGoal = value)
    "currentRelationship" -> state.copy(currentRelationship = value)
    "currentImportantOthers" -> state.copy(currentImportantOthers = value)
    else -> state
}
