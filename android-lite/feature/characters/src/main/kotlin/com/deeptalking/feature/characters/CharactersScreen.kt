package com.deeptalking.feature.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.designsystem.legacy
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.StaticProfile
private val STATIC_FIELDS = listOf(
    "gender" to "性别",
    "age" to "年龄",
    "race" to "种族",
    "appearance" to "外貌特征",
    "personality" to "性格特征",
    "fears" to "恐惧/弱点",
    "background" to "个人背景",
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
    "currentTone" to "当前语气",
)

@Composable
fun CharactersScreen(
    characters: List<Character>,
    activeId: String?,
    generating: Boolean,
    onSelect: (String) -> Unit,
    onCreateCharacter: (name: String, emoji: String, personality: String, background: String, draft: CharacterParity.GeneratedDraft?) -> Unit,
    onCreateGroup: (name: String, emoji: String, description: String, scene: String, rules: String, members: List<GroupMember>, lorebook: List<LorebookEntry>) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (Character) -> Unit,
    onEditMember: (Character, Int) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onQuickGenerate: (String, (String?) -> Unit) -> Unit,
    onRepairAvatars: () -> Unit,
    onUpgradeToGroup: (Character) -> Unit,
) {
    val legacy = MaterialTheme.legacy
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Character?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(legacy.sidebar)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LegacyButton("+  新建角色或群组", primary = true, onClick = { createOpen = true })
            LegacyButton("⟳  补全头像", primary = false, onClick = onRepairAvatars)
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (characters.isEmpty()) {
                item {
                    Text(
                        "暂无角色\n点击上方按钮创建",
                        fontSize = 14.sp,
                        color = legacy.textMuted,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            }
            items(characters, key = { it.id }) { character ->
                CharacterCard(
                    character = character,
                    active = character.id == activeId,
                    onSelect = { onSelect(character.id) },
                    onEdit = { onEdit(character) },
                    onEditMember = { index -> onEditMember(character, index) },
                    onUpgrade = { onUpgradeToGroup(character) },
                    onDelete = { deleteTarget = character },
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(legacy.border))
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LegacyButton("导出", primary = false, modifier = Modifier.weight(1f), onClick = onExport)
            LegacyButton("导入", primary = false, modifier = Modifier.weight(1f), onClick = onImport)
        }
    }

    if (createOpen) {
        CreateDialog(
            generating = generating,
            onQuickGenerate = onQuickGenerate,
            onDismiss = { createOpen = false },
            onCreateCharacter = { n, e, p, b, d ->
                onCreateCharacter(n, e, p, b, d)
                createOpen = false
            },
            onCreateGroup = { n, e, d, s, r, m, lb ->
                onCreateGroup(n, e, d, s, r, m, lb)
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
private fun LegacyButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val legacy = MaterialTheme.legacy
    val bg = if (primary) legacy.btnPrimary else legacy.input
    val fg = if (primary) legacy.onUserBubble else legacy.textSecondary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(if (primary) 12.dp else 8.dp))
            .background(bg)
            .then(if (primary) Modifier else Modifier.border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, color = fg, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun CharacterCard(
    character: Character,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onEditMember: (Int) -> Unit,
    onUpgrade: () -> Unit,
    onDelete: () -> Unit,
) {
    val legacy = MaterialTheme.legacy
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) legacy.accentBg else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = legacy.accentBg,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(character.emoji.ifEmpty { if (character.isGroup) "👥" else "👤" }, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        character.name.ifEmpty { "未命名" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = legacy.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (character.isGroup) {
                        Spacer(Modifier.width(6.dp))
                        Text("群组", fontSize = 12.sp, color = legacy.textMuted)
                    }
                }
                Text(
                    text = if (character.isGroup) {
                        "${character.members.size} 位成员 · ${character.description.ifEmpty { "群组对话" }}"
                    } else {
                        character.staticProfile.personality.ifEmpty { "暂无描述" }
                    },
                    fontSize = 12.sp,
                    color = legacy.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CardAction(Icons.Default.Edit, "编辑角色卡", legacy.textMuted, onEdit)
            if (!character.isGroup) {
                CardAction(Icons.Default.Groups, "升级为群组", legacy.textMuted, onUpgrade)
            }
            CardAction(Icons.Default.Delete, "删除角色", androidx.compose.ui.graphics.Color(0xFFEF4444), onDelete)
        }
        if (character.isGroup && character.members.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 20.dp)) {
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(legacy.border))
                Column(
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    character.members.forEachIndexed { index, member ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Surface(shape = CircleShape, color = legacy.accentBg, modifier = Modifier.size(24.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(member.emoji.ifEmpty { "👤" }, fontSize = 13.sp)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    member.name.ifEmpty { "未命名成员" },
                                    fontSize = 12.sp,
                                    color = legacy.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val sub = member.roleInGroup.ifEmpty { member.staticProfile.personality }
                                if (sub.isNotBlank()) {
                                    Text(
                                        sub,
                                        fontSize = 11.sp,
                                        color = legacy.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            CardAction(Icons.Default.Edit, "编辑成员角色卡", legacy.textMuted) { onEditMember(index) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun CreateDialog(
    generating: Boolean,
    onQuickGenerate: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onCreateCharacter: (String, String, String, String, CharacterParity.GeneratedDraft?) -> Unit,
    onCreateGroup: (String, String, String, String, String, List<GroupMember>, List<LorebookEntry>) -> Unit,
) {
    val legacy = MaterialTheme.legacy
    var isGroup by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf("") }
    var personality by rememberSaveable { mutableStateOf("") }
    var background by rememberSaveable { mutableStateOf("") }
    var groupDescription by rememberSaveable { mutableStateOf("") }
    var groupScene by rememberSaveable { mutableStateOf("") }
    var groupRules by rememberSaveable { mutableStateOf("") }
    var membersText by rememberSaveable { mutableStateOf("") }
    var quickGenInput by rememberSaveable { mutableStateOf("") }
    var createTypeExpanded by rememberSaveable { mutableStateOf(false) }
    var generatedMembers by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var generatedDraft by remember { mutableStateOf<CharacterParity.GeneratedDraft?>(null) }

    fun effectiveMembers(): List<GroupMember> =
        generatedMembers.ifEmpty { CharacterParity.parseGroupMembersText(membersText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = legacy.panel,
        title = { Text(if (isGroup) "创建新群组" else "创建新角色", color = legacy.text) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("一句话生成 (可选)", fontSize = 14.sp, color = legacy.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegacyField(
                        value = quickGenInput,
                        onValueChange = { quickGenInput = it },
                        placeholder = if (isGroup) "描述你想要的群组和成员..." else "描述你想要的角色...",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    LegacyButton(
                        label = if (generating) "生成中…" else "生成",
                        primary = false,
                        modifier = Modifier.width(72.dp),
                        onClick = {
                            if (quickGenInput.isBlank() || generating) return@LegacyButton
                            onQuickGenerate(CharacterParity.buildQuickGeneratePrompt(quickGenInput, isGroup)) { raw ->
                                if (raw != null) {
                                    CharacterParity.parseGeneratedDraft(raw)?.let { parsed ->
                                        generatedDraft = parsed
                                        isGroup = parsed.isGroup
                                        name = parsed.name
                                        emoji = parsed.emoji
                                        personality = parsed.personality
                                        background = parsed.background
                                        groupDescription = parsed.description
                                        groupScene = parsed.scene
                                        groupRules = parsed.interactionRules
                                        generatedMembers = parsed.members
                                        membersText = parsed.members.joinToString("\n") {
                                            it.name + "｜" + it.staticProfile.personality
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
                Text("AI将根据描述自动填充下方字段", fontSize = 12.sp, color = legacy.textMuted)

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(legacy.border))

                Text("创建类型", fontSize = 14.sp, color = legacy.textSecondary)
                LegacyDropdown(
                    selected = if (isGroup) "群组" else "单角色",
                    options = listOf("单角色" to false, "群组" to true),
                    expanded = createTypeExpanded,
                    onExpandedChange = { createTypeExpanded = it },
                    onSelect = { isGroup = it; createTypeExpanded = false },
                )
                LegacyField(
                    value = name,
                    onValueChange = { name = it },
                    label = if (isGroup) "群组名称 *" else "角色名称 *",
                )
                LegacyField(value = emoji, onValueChange = { emoji = it }, label = "头像", placeholder = "🌸")
                if (isGroup) {
                    LegacyField(value = groupDescription, onValueChange = { groupDescription = it }, label = "群组前提", minLines = 2)
                    LegacyField(value = groupScene, onValueChange = { groupScene = it }, label = "共同场景", minLines = 2)
                    LegacyField(value = groupRules, onValueChange = { groupRules = it }, label = "成员互动规则", minLines = 2)
                    LegacyField(
                        value = membersText,
                        onValueChange = { membersText = it; generatedMembers = emptyList() },
                        label = "群成员",
                        placeholder = "每行：名称｜性格简述",
                        minLines = 3,
                    )
                    if (membersText.isNotBlank() && effectiveMembers().size < 2) {
                        Text("群组至少需要两名成员", fontSize = 12.sp, color = legacy.warning)
                    }
                } else {
                    val generated = generatedDraft
                    if (generated == null) {
                        LegacyField(value = personality, onValueChange = { personality = it }, label = "性格简述", minLines = 2)
                        LegacyField(value = background, onValueChange = { background = it }, label = "背景故事", minLines = 3)
                    } else {
                        Text("生成结果 · 全部字段可直接修改", fontSize = 13.sp, color = legacy.textSecondary)
                        STATIC_FIELDS.forEach { (key, label) ->
                            LegacyField(
                                value = staticValue(generated.staticProfile, key),
                                onValueChange = { value ->
                                    generatedDraft = generated.copy(
                                        staticProfile = withStatic(generated.staticProfile, key, value),
                                    )
                                    if (key == "personality") personality = value
                                    if (key == "background") background = value
                                },
                                label = label,
                                minLines = if (key == "background") 3 else 1,
                            )
                        }
                        Text("当前状态", fontSize = 13.sp, color = legacy.textSecondary)
                        DYNAMIC_FIELDS.forEach { (key, label) ->
                            LegacyField(
                                value = dynamicValue(generated.dynamicState, key),
                                onValueChange = { value ->
                                    generatedDraft = generated.copy(
                                        dynamicState = withDynamic(generated.dynamicState, key, value),
                                    )
                                },
                                label = label,
                                minLines = if (key == "currentSituation" || key == "currentLocation") 2 else 1,
                            )
                        }
                        if (generated.lorebook.isNotEmpty()) {
                            Text(
                                "世界书 " + generated.lorebook.size + " 条：" +
                                    generated.lorebook.joinToString("、") { it.name },
                                fontSize = 12.sp,
                                color = legacy.textMuted,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isGroup) {
                        val members = effectiveMembers().map { CharacterParity.normalizeMember(it) }
                        if (name.isNotBlank() && members.size >= 2) {
                            onCreateGroup(name.trim(), emoji.trim(), groupDescription.trim(), groupScene.trim(), groupRules.trim(), members, generatedDraft?.lorebook.orEmpty())
                        }
                    } else if (name.isNotBlank()) {
                        onCreateCharacter(name.trim(), emoji.trim(), personality.trim(), background.trim(), generatedDraft)
                    }
                },
                enabled = name.isNotBlank(),
            ) { Text("创建角色") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ==================== Character card editor ====================

object CharacterEditorHost {
    @Composable
    operator fun invoke(
        character: Character,
        onDismiss: () -> Unit,
        onUpdate: (Character) -> Unit,
        // 世界书写通：第二个参数是"这本书属于谁"——成员 id，null = 本体（共享世界书）。
        onAddLorebook: (String, String?, LorebookEntry) -> Unit,
        onRemoveLorebook: (String, String?, String) -> Unit,
        onUpdateLorebook: (String, String?, LorebookEntry) -> Unit,
        onArchiveLorebook: (String, String?, String) -> Unit,
        onRestoreLorebook: (String, String?, String) -> Unit,
        onGenerateAvatar: (Character, (String?) -> Unit) -> Unit,
        onRegenerateField: (Character, Int?, String, String, (String?) -> Unit) -> Unit,
        onFillMember: (Character, Int, String) -> Unit,
        initialMemberIndex: Int? = null,
    ) = CharacterEditorDialog(
        character = character,
        onDismiss = onDismiss,
        onUpdate = onUpdate,
        onAddLorebook = onAddLorebook,
        onRemoveLorebook = onRemoveLorebook,
        onUpdateLorebook = onUpdateLorebook,
        onArchiveLorebook = onArchiveLorebook,
        onRestoreLorebook = onRestoreLorebook,
        onGenerateAvatar = onGenerateAvatar,
        onRegenerateField = onRegenerateField,
        onFillMember = onFillMember,
        initialMemberIndex = initialMemberIndex,
    )
}

@Composable
fun CharacterEditorDialog(
    character: Character,
    onDismiss: () -> Unit,
    onUpdate: (Character) -> Unit,
    onAddLorebook: (String, String?, LorebookEntry) -> Unit,
    onRemoveLorebook: (String, String?, String) -> Unit,
    onUpdateLorebook: (String, String?, LorebookEntry) -> Unit,
    onArchiveLorebook: (String, String?, String) -> Unit,
    onRestoreLorebook: (String, String?, String) -> Unit,
    onGenerateAvatar: (Character, (String?) -> Unit) -> Unit,
    onRegenerateField: (Character, Int?, String, String, (String?) -> Unit) -> Unit,
    onFillMember: (Character, Int, String) -> Unit,
    initialMemberIndex: Int? = null,
) {
    val legacy = MaterialTheme.legacy
    var tab by remember { mutableIntStateOf(0) }
    var draft by remember(character.id) { mutableStateOf(character) }
    var memberIndex by remember(character.id) { mutableStateOf(initialMemberIndex) }
    val editingMember = memberIndex?.let { draft.members.getOrNull(it) }
    // 世界书页签跟随"正在编辑的对象"：编辑成员时落该成员的世界书，否则落本体。
    // 成员 id 为空（历史数据）时不装作有成员上下文——按今天的本体行为处理，避免把编辑写丢。
    val editingMemberId = editingMember?.id?.takeIf { it.isNotBlank() }
    // 保存时被规范化 / 命中对比句的字段：先给一条可见提示，再让用户确认保存（不要静默清洗）。
    // 按 **正在编辑的对象** 记忆：切到别的成员（或退回本体）后，上一份提示就不再适用——
    // 否则它会替另一个对象"预先确认"，把这次的规范化提示跳过去。
    var pendingNotices by remember(character.id, memberIndex) {
        mutableStateOf<List<CharacterParity.EditNotice>>(emptyList())
    }

    fun applyDraft(next: Character) {
        draft = next
        pendingNotices = emptyList()
    }

    val title = when {
        editingMember != null -> "编辑成员: ${editingMember.name}"
        else -> "编辑: ${character.name}"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = legacy.panel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editingMember != null) {
                    IconButton(onClick = { memberIndex = null }) { Text("←", color = legacy.textSecondary) }
                }
                Text(title, color = legacy.text)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ModalTab("基础设定", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    ModalTab("当前状态", tab == 1, Modifier.weight(1f)) { tab = 1 }
                    ModalTab("世界书", tab == 2, Modifier.weight(1f)) { tab = 2 }
                    // 待办只挂在角色本体上（成员页签不显示）。
                    if (editingMember == null) {
                        ModalTab("待办", tab == 3, Modifier.weight(1f)) { tab = 3 }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(legacy.border))
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 成员页签没有「待办」：停在 tab 3 时回落到基础设定。
                    val shownTab = if (editingMember == null) tab else minOf(tab, 2)
                    when (shownTab) {
                        0 -> BasicTab(
                            draft = draft,
                            memberIndex = memberIndex,
                            onDraft = ::applyDraft,
                            onGenerateAvatar = onGenerateAvatar,
                            onRegenerateField = onRegenerateField,
                            onFillMember = onFillMember,
                        )
                        1 -> StateTab(draft = draft, memberIndex = memberIndex, onDraft = ::applyDraft)
                        2 -> LorebookTab(
                            draft = draft,
                            memberId = editingMemberId,
                            // A world-book edit is applied to the draft **and** patched onto
                            // the stored row: the dialog's draft is a snapshot from when it
                            // opened, so without the local update the checkbox/text the user
                            // just touched keeps rendering the old value — and the next edit
                            // (computed from the same snapshot) would undo this one. The
                            // member branch patches the member addressed **by id** on the live
                            // row, so a changed member list cannot put the edit on someone else.
                            onAddLorebook = {
                                // Capacity is judged globally (shared book + members) and the
                                // overflow is archived, exactly like the store does — so the
                                // draft never shows an entry the store refused.
                                val entry = CharacterParity.newLorebookEntry()
                                val target = editingMemberId
                                applyDraft(
                                    if (target == null) CharacterParity.addLorebookEntry(draft, entry)
                                    else CharacterParity.addMemberLorebookEntry(draft, target, entry),
                                )
                                onAddLorebook(draft.id, target, entry)
                            },
                            onRemoveLorebook = { entryId ->
                                val target = editingMemberId
                                applyDraft(
                                    if (target == null) {
                                        draft.copy(lorebook = CharacterParity.removeLorebookEntry(draft.lorebook, entryId))
                                    } else {
                                        CharacterParity.removeMemberLorebookEntry(draft, target, entryId)
                                    },
                                )
                                onRemoveLorebook(draft.id, target, entryId)
                            },
                            onUpdateLorebook = { entry ->
                                val target = editingMemberId
                                applyDraft(
                                    if (target == null) {
                                        draft.copy(lorebook = CharacterParity.updateLorebookEntry(draft.lorebook, entry))
                                    } else {
                                        CharacterParity.updateMemberLorebookEntry(draft, target, entry)
                                    },
                                )
                                onUpdateLorebook(draft.id, target, entry)
                            },
                            onArchiveLorebook = { entryId ->
                                val target = editingMemberId
                                applyDraft(
                                    if (target == null) CharacterParity.archiveLorebookEntry(draft, entryId)
                                    else CharacterParity.archiveMemberLorebookEntry(draft, target, entryId),
                                )
                                onArchiveLorebook(draft.id, target, entryId)
                            },
                            onRestoreLorebook = { entryId ->
                                val target = editingMemberId
                                applyDraft(
                                    if (target == null) CharacterParity.restoreLorebookEntry(draft, entryId)
                                    else CharacterParity.restoreMemberLorebookEntry(draft, target, entryId),
                                )
                                onRestoreLorebook(draft.id, target, entryId)
                            },
                        )
                        3 -> TodoTab(draft = draft, onDraft = ::applyDraft)
                    }
                    if (pendingNotices.isNotEmpty()) {
                        SaveNoticesPanel(pendingNotices)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = CharacterParity.normalizeUserEditedDraft(draft, memberIndex = memberIndex)
                // 有规范化 / 对比句时先亮出提示，再让用户按第二下确认保存；用户接着改了字段
                // （applyDraft 清空提示）就重新提示，绝不静默改掉用户手写的内容。
                if (result.notices.isEmpty() || pendingNotices.isNotEmpty()) {
                    onUpdate(result.character)
                    onDismiss()
                } else {
                    pendingNotices = result.notices
                }
            }) { Text(if (pendingNotices.isEmpty()) "保存修改" else "继续保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TodoTab(draft: Character, onDraft: (Character) -> Unit) {
    val legacy = MaterialTheme.legacy
    val nowMillis = System.currentTimeMillis()
    val rows = TodoBuckets.rows(draft, nowMillis)
    var closedExpanded by remember { mutableStateOf(false) }

    fun patchTodo(id: String, transform: (com.deeptalking.core.model.Todo) -> com.deeptalking.core.model.Todo) {
        onDraft(draft.copy(todos = draft.todos.map { if (it.id == id) transform(it) else it }))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rows.isEmpty()) {
            Text("暂无待办约定", fontSize = 12.sp, color = legacy.textMuted)
            return@Column
        }
        TodoBuckets.TodoSection.entries.forEach { section ->
            val items = rows.filter { it.section == section }
            if (items.isEmpty()) return@forEach
            val collapsible = section == TodoBuckets.TodoSection.DONE ||
                section == TodoBuckets.TodoSection.CANCELLED
            if (collapsible) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { closedExpanded = !closedExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (closedExpanded) "▾" else "▸", fontSize = 12.sp, color = legacy.textMuted)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${sectionLabel(section)}（${items.size}）",
                        fontSize = 12.sp,
                        color = legacy.textMuted,
                    )
                }
                if (closedExpanded) items.forEach { TodoRowView(it.todo, section, ::patchTodo, onDraft, draft) }
            } else {
                Text("${sectionLabel(section)}（${items.size}）", fontSize = 12.sp, color = legacy.textSecondary)
                items.forEach { TodoRowView(it.todo, section, ::patchTodo, onDraft, draft) }
            }
        }
    }
}

private fun sectionLabel(section: TodoBuckets.TodoSection): String = when (section) {
    TodoBuckets.TodoSection.ONGOING -> "进行中"
    TodoBuckets.TodoSection.OVERDUE -> "已超时"
    TodoBuckets.TodoSection.DONE -> "已完成"
    TodoBuckets.TodoSection.CANCELLED -> "已取消"
}

@Composable
private fun TodoRowView(
    todo: com.deeptalking.core.model.Todo,
    section: TodoBuckets.TodoSection,
    patchTodo: (String, (com.deeptalking.core.model.Todo) -> com.deeptalking.core.model.Todo) -> Unit,
    onDraft: (Character) -> Unit,
    draft: Character,
) {
    val legacy = MaterialTheme.legacy
    val closed = section == TodoBuckets.TodoSection.DONE || section == TodoBuckets.TodoSection.CANCELLED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(legacy.input)
            .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(todo.title.ifBlank { "（未命名）" }, fontSize = 13.sp, color = legacy.text)
            if (todo.detail.isNotBlank()) {
                Text(todo.detail, fontSize = 12.sp, color = legacy.textMuted)
            }
            val stamp = if (closed) {
                val whenText = TodoBuckets.closedLabel(todo)
                if (whenText.isBlank()) "" else (if (section == TodoBuckets.TodoSection.DONE) "完成于 " else "取消于 ") + whenText
            } else {
                "期限 " + TodoBuckets.dueLabel(todo)
            }
            Text(
                stamp,
                fontSize = 12.sp,
                color = if (section == TodoBuckets.TodoSection.OVERDUE) legacy.warning else legacy.textMuted,
            )
        }
        if (closed) {
            TextButton(onClick = { onDraft(draft.copy(todos = draft.todos.filterNot { it.id == todo.id })) }) {
                Text("删除", fontSize = 12.sp, color = legacy.textSecondary)
            }
        } else {
            TextButton(onClick = {
                val now = java.time.Instant.now().toString()
                patchTodo(todo.id) { it.copy(status = com.deeptalking.core.model.TodoStatus.Done, closedAt = now, updatedAt = now) }
            }) { Text("完成", fontSize = 12.sp, color = legacy.textSecondary) }
            TextButton(onClick = {
                val now = java.time.Instant.now().toString()
                patchTodo(todo.id) { it.copy(status = com.deeptalking.core.model.TodoStatus.Cancelled, closedAt = now, updatedAt = now) }
            }) { Text("取消", fontSize = 12.sp, color = legacy.textSecondary) }
        }
    }
}

@Composable
private fun ModalTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val legacy = MaterialTheme.legacy
    Box(
        modifier = modifier.height(40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) legacy.accent else legacy.textSecondary,
        )
        if (selected) {
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).width(40.dp).height(2.dp).background(legacy.accent),
            )
        }
    }
}

@Composable
private fun BasicTab(
    draft: Character,
    memberIndex: Int?,
    onDraft: (Character) -> Unit,
    onGenerateAvatar: (Character, (String?) -> Unit) -> Unit,
    onRegenerateField: (Character, Int?, String, String, (String?) -> Unit) -> Unit,
    onFillMember: (Character, Int, String) -> Unit,
) {
    val member = memberIndex?.let { draft.members.getOrNull(it) }
    var fillHint by remember { mutableStateOf("") }
    var regenField by rememberSaveable { mutableStateOf("emoji") }
    var regenHint by rememberSaveable { mutableStateOf("") }
    var regenExpanded by rememberSaveable { mutableStateOf(false) }
    var regenBusy by remember { mutableStateOf(false) }

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
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.legacy.border))
            Text("一句话补全空字段", fontSize = 14.sp, color = MaterialTheme.legacy.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegacyField(
                    value = fillHint,
                    onValueChange = { fillHint = it },
                    placeholder = "例如：她是负责医疗支持的沉稳护士",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                LegacyButton("补全", primary = false, modifier = Modifier.width(64.dp), onClick = { onFillMember(draft, memberIndex, fillHint) })
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.legacy.border))
        }

        LegacyField(value = member?.name ?: draft.name, onValueChange = { updateName(it) }, label = if (draft.isGroup && member == null) "群组名称" else "名称")
        LegacyField(value = member?.emoji ?: draft.emoji, onValueChange = { updateEmoji(it) }, label = "头像")

        if (draft.isGroup && member == null) {
            LegacyField(value = draft.description, onValueChange = { onDraft(draft.copy(description = it)) }, label = "群组前提", minLines = 2)
            LegacyField(value = draft.interactionRules, onValueChange = { onDraft(draft.copy(interactionRules = it)) }, label = "成员互动规则", minLines = 3)
            Text("成员：" + draft.members.joinToString("、") { it.name }, fontSize = 12.sp, color = MaterialTheme.legacy.textMuted)
        } else {
            val profile = member?.staticProfile ?: draft.staticProfile
            STATIC_FIELDS.forEach { (key, label) ->
                LegacyField(
                    value = staticValue(profile, key),
                    onValueChange = { updateProfile(withStatic(profile, key, it)) },
                    label = label,
                    minLines = if (key == "background") 2 else 1,
                )
            }
            if (member != null) {
                LegacyField(
                    value = member.roleInGroup,
                    onValueChange = { role ->
                        onDraft(draft.copy(members = draft.members.toMutableList().also { list -> list[memberIndex] = member.copy(roleInGroup = role) }))
                    },
                    label = "群内定位",
                )
            }
        }

        Text("重新生成字段", fontSize = 13.sp, color = MaterialTheme.legacy.textSecondary)
        val fieldOptions = listOf("emoji" to "头像 emoji") + STATIC_FIELDS
        LegacyDropdown(
            selected = fieldOptions.firstOrNull { it.first == regenField }?.second ?: "头像 emoji",
            options = fieldOptions,
            expanded = regenExpanded,
            onExpandedChange = { regenExpanded = it },
            onSelect = { key ->
                regenField = key
                regenExpanded = false
            },
        )
        LegacyField(
            value = regenHint,
            onValueChange = { regenHint = it },
            placeholder = "修改意见（可选），例如：更冷淡、保留医生身份",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.legacy.accentBg, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(member?.emoji?.ifEmpty { "👤" } ?: draft.emoji.ifEmpty { if (draft.isGroup) "👥" else "👤" }, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            LegacyButton(
                label = if (regenBusy) "生成中…" else "重新生成",
                primary = false,
                modifier = Modifier.width(120.dp),
                onClick = {
                    if (regenBusy) return@LegacyButton
                    val key = regenField
                    regenBusy = true
                    onRegenerateField(draft, memberIndex, key, regenHint) { value ->
                        regenBusy = false
                        if (!value.isNullOrBlank()) {
                            if (key == "emoji") {
                                updateEmoji(value)
                            } else {
                                updateProfile(withStatic(member?.staticProfile ?: draft.staticProfile, key, value))
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun StateTab(draft: Character, memberIndex: Int?, onDraft: (Character) -> Unit) {
    val member = memberIndex?.let { draft.members.getOrNull(it) }
    val isGroupEntity = draft.isGroup && member == null
    val state = when {
        member != null -> member.dynamicState
        isGroupEntity -> draft.groupSharedDynamic
        else -> draft.dynamicState
    }
    val fields = if (isGroupEntity) {
        DYNAMIC_FIELDS.filter { it.first == "currentSituation" || it.first == "currentLocation" }
    } else {
        DYNAMIC_FIELDS
    }

    fun update(newState: com.deeptalking.core.model.DynamicState) {
        when {
            member != null -> onDraft(draft.copy(members = draft.members.toMutableList().also { it[memberIndex] = member.copy(dynamicState = newState) }))
            isGroupEntity -> onDraft(draft.copy(groupSharedDynamic = newState))
            else -> onDraft(draft.copy(dynamicState = newState))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { (key, label) ->
            LegacyField(
                value = dynamicValue(state, key),
                onValueChange = { update(withDynamic(state, key, it)) },
                label = label,
                minLines = if (key == "currentSituation") 3 else 1,
            )
        }
    }
}

@Composable
private fun LorebookTab(
    draft: Character,
    memberId: String?,
    onAddLorebook: () -> Unit,
    onRemoveLorebook: (String) -> Unit,
    onUpdateLorebook: (LorebookEntry) -> Unit,
    onArchiveLorebook: (String) -> Unit,
    onRestoreLorebook: (String) -> Unit,
) {
    val legacy = MaterialTheme.legacy
    // 页签跟随"正在编辑的对象"：成员视图显示该成员的世界书 + 该成员的归档，本体视图不变。
    val entries = CharacterParity.lorebookOf(draft, memberId)
    val archive = CharacterParity.lorebookArchiveOf(draft, memberId)
    var archiveExpanded by remember(memberId) { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (entries.isEmpty()) {
            Text(
                "暂无条目，可在对话中自动补充或手动添加",
                fontSize = 12.sp,
                color = legacy.textMuted,
            )
        }
        entries.forEach { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(legacy.input)
                    .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegacyField(
                        value = entry.name,
                        onValueChange = { name ->
                            onUpdateLorebook(entry.copy(name = CharacterParity.capLorebookName(name)))
                        },
                        placeholder = "条目名（如：赤月王国）",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    LorebookOwnerChip(entry.origin) { origin ->
                        onUpdateLorebook(entry.copy(origin = origin))
                    }
                    TextButton(onClick = { onArchiveLorebook(entry.id) }) { Text("归档", fontSize = 12.sp, color = legacy.textSecondary) }
                    TextButton(onClick = { onRemoveLorebook(entry.id) }) { Text("删除", fontSize = 12.sp, color = legacy.textSecondary) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegacyCheck("启用", entry.enabled) { checked ->
                        onUpdateLorebook(entry.copy(enabled = checked))
                    }
                    LegacyCheck("常驻", entry.alwaysActive) { checked ->
                        onUpdateLorebook(entry.copy(alwaysActive = checked))
                    }
                    Text(lorebookMention(entry), fontSize = 12.sp, color = legacy.textMuted)
                }
                LegacyField(
                    value = entry.keywords.joinToString("、"),
                    onValueChange = { raw ->
                        val keywords = CharacterParity.splitLorebookKeywords(raw)
                        // 关键词与常驻的自动联动只对 AI 维护的条目生效。
                        val alwaysActive = if (entry.origin == LorebookOrigin.User) {
                            entry.alwaysActive
                        } else {
                            CharacterParity.keywordsToAlwaysActive(entry.alwaysActive, keywords)
                        }
                        onUpdateLorebook(entry.copy(keywords = keywords, alwaysActive = alwaysActive))
                    },
                    placeholder = "触发关键词（用、或逗号分隔；常驻条目可留空）",
                )
                LegacyField(
                    value = entry.content,
                    onValueChange = { content ->
                        // 不截断：超过单条上限的本次输入直接丢弃，文本框保持原值。
                        if (content.length <= AppLimits.Lorebook.CONTENT_CHARS) {
                            onUpdateLorebook(entry.copy(content = content))
                        }
                    },
                    placeholder = "命中后注入的设定内容",
                    minLines = 2,
                )
                Text(
                    "${entry.content.length}/${AppLimits.Lorebook.CONTENT_CHARS}",
                    fontSize = 12.sp,
                    color = if (entry.content.length >= AppLimits.Lorebook.CONTENT_CHARS) legacy.warning else legacy.textMuted,
                )
            }
        }
        LegacyButton("+ 添加条目", primary = false, onClick = onAddLorebook)
        // 归档区：成员视图同样要出现（成员世界书为空、归档非空时以前完全看不见），
        // 本体视图保持原有渲染（标题 + 每行一条，直接展开）。
        if (archive.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            if (memberId == null) {
                Text(
                    "归档（可恢复）",
                    fontSize = 12.sp,
                    color = legacy.textMuted,
                )
                archive.forEach { entry ->
                    LorebookArchiveRow(entry) { onRestoreLorebook(entry.id) }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { archiveExpanded = !archiveExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (archiveExpanded) "▾" else "▸", fontSize = 12.sp, color = legacy.textMuted)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "归档（可恢复）",
                        fontSize = 12.sp,
                        color = legacy.textMuted,
                    )
                }
                if (archiveExpanded) {
                    archive.forEach { entry ->
                        LorebookArchiveRow(entry) { onRestoreLorebook(entry.id) }
                    }
                }
            }
        }
    }
}

/** 归档区的一行：条目名/内容预览 + 「恢复」。本体与成员视图共用同一渲染。 */
@Composable
private fun LorebookArchiveRow(entry: LorebookEntry, onRestore: () -> Unit) {
    val legacy = MaterialTheme.legacy
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(legacy.panel)
            .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name.ifBlank { "（未命名）" }, fontSize = 12.sp, color = legacy.text)
            if (entry.content.isNotBlank()) {
                Text(entry.content, fontSize = 11.sp, color = legacy.textMuted, maxLines = 2)
            }
        }
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = onRestore) {
            Text("恢复", fontSize = 12.sp, color = legacy.accent)
        }
    }
}

/**
 * 条目归属：AI 维护 ↔ 人工维护。来源只由这个开关决定——**人工**的条目模型不会改动（也不会被
 * 建卡/整理重写），切回 **AI** 之后模型就能继续维护它。编辑内容本身不再改变归属。
 */
@Composable
private fun LorebookOwnerChip(origin: LorebookOrigin, onSwitch: (LorebookOrigin) -> Unit) {
    val legacy = MaterialTheme.legacy
    val manual = origin == LorebookOrigin.User
    val accent = if (manual) legacy.warning else legacy.textMuted
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (manual) legacy.warning.copy(alpha = 0.18f) else legacy.panel)
            .border(1.dp, if (manual) legacy.warning else legacy.inputBorder, RoundedCornerShape(6.dp))
            .clickable { onSwitch(if (manual) LorebookOrigin.Model else LorebookOrigin.User) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(if (manual) "人工维护" else "AI 维护", fontSize = 12.sp, color = accent)
    }
}

/** 保存前的可见提示：哪些字段被规范化 / 命中了对比句（不要静默清洗用户手写内容）。 */
@Composable
private fun SaveNoticesPanel(notices: List<CharacterParity.EditNotice>) {
    val legacy = MaterialTheme.legacy
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(legacy.warning.copy(alpha = 0.12f))
            .border(1.dp, legacy.warning, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("保存前确认：以下字段会被处理", fontSize = 12.sp, color = legacy.warning)
        notices.forEach { notice ->
            Text("· ${notice.fieldLabel}：${notice.reason}", fontSize = 12.sp, color = legacy.text)
        }
        Text("确认无误请点「继续保存」。", fontSize = 12.sp, color = legacy.textMuted)
    }
}

@Composable
private fun LegacyCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.legacy.accent,
                uncheckedColor = MaterialTheme.legacy.textMuted,
                checkmarkColor = MaterialTheme.legacy.panel,
            ),
        )
        Text(label, fontSize = 12.sp, color = MaterialTheme.legacy.textMuted)
    }
}

// ==================== legacy-styled input primitives ====================

@Composable
private fun LegacyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    minLines: Int = 1,
) {
    val legacy = MaterialTheme.legacy
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label != null) {
            Text(label, fontSize = 12.sp, color = legacy.textMuted)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(legacy.input)
                .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, fontSize = 13.sp, color = legacy.textMuted)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(fontSize = 13.sp, color = legacy.text),
                cursorBrush = SolidColor(legacy.accent),
                minLines = minLines,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun <T> LegacyDropdown(
    selected: String,
    options: List<Pair<String, T>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (T) -> Unit,
) {
    val legacy = MaterialTheme.legacy
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(legacy.input)
                .border(1.dp, legacy.inputBorder, RoundedCornerShape(8.dp))
                .clickable { onExpandedChange(true) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected, fontSize = 13.sp, color = legacy.text, modifier = Modifier.weight(1f))
            Text("▾", fontSize = 13.sp, color = legacy.textMuted)
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            options.forEach { (label, value) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, color = legacy.text) },
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun Surface(shape: androidx.compose.ui.graphics.Shape, color: androidx.compose.ui.graphics.Color, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.clip(shape).background(color), contentAlignment = Alignment.Center) { content() }
}

// ==================== helpers ====================

private fun lorebookMention(entry: LorebookEntry): String {
    if (entry.mentions <= 0) return "未命中过"
    val whenText = relativeFrom(entry.lastMentionedAt) ?: return "命中 ${entry.mentions} 次"
    return "命中 ${entry.mentions} 次 · $whenText"
}

private fun relativeFrom(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val instant = runCatching { java.time.Instant.parse(iso) }.getOrNull() ?: return null
    val minutes = java.time.Duration.between(instant, java.time.Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 1440 -> "${Math.round(minutes / 60.0)} 小时前"
        else -> "${Math.round(minutes / 1440.0)} 天前"
    }
}

private fun staticValue(profile: StaticProfile, key: String): String = when (key) {
    "gender" -> profile.gender
    "age" -> profile.age
    "race" -> profile.race
    "appearance" -> profile.appearance
    "personality" -> profile.personality
    "fears" -> profile.fears
    "background" -> profile.background
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
    "fears" -> profile.copy(fears = value)
    "background" -> profile.copy(background = value)
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
    "currentTone" -> state.currentTone
    else -> ""
}

private fun withDynamic(state: com.deeptalking.core.model.DynamicState, key: String, value: String): com.deeptalking.core.model.DynamicState = when (key) {
    "currentSituation" -> state.copy(currentSituation = value)
    "currentLocation" -> state.copy(currentLocation = value)
    "currentMood" -> state.copy(currentMood = value)
    "currentOccupation" -> state.copy(currentOccupation = value)
    "currentGoal" -> state.copy(currentGoal = value)
    "currentTone" -> state.copy(currentTone = value)
    else -> state
}
