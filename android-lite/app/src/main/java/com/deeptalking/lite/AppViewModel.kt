package com.deeptalking.lite

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.RequestMetric
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.domain.agent.OrchestratorResult
import com.deeptalking.feature.characters.CharacterParity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** One-shot user-facing message (alert/toast) surfaced from the ViewModel. */
data class UiEvent(val message: String)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(private val core: NativeCore) : ViewModel() {

    val characters: StateFlow<List<Character>> =
        core.data.characters.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val config: StateFlow<AppConfig> =
        core.data.config.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppConfig())

    private val activeIdState = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = activeIdState

    private val storedMessages: StateFlow<List<ChatMessage>> =
        activeIdState.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else core.data.chat.observeMessages(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live assistant text while a turn streams; null when idle. */
    private val streamingState = MutableStateFlow<String?>(null)

    val messages: StateFlow<List<ChatMessage>> = combine(storedMessages, streamingState) { stored, streaming ->
        if (streaming == null) stored
        else stored + ChatMessage(
            id = "streaming",
            role = Role.Assistant,
            content = streaming,
            timestamp = null,
            isLoading = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val quickRepliesState = MutableStateFlow<List<String>>(emptyList())
    val quickReplies: StateFlow<List<String>> = quickRepliesState

    private val sendingState = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = sendingState

    private val statusState = MutableStateFlow("")
    val status: StateFlow<String> = statusState

    /** Pending image Uris selected in the composer; cleared on send. */
    private val pendingImagesState = MutableStateFlow<List<Uri>>(emptyList())
    val pendingImages: StateFlow<List<Uri>> = pendingImagesState

    private val eventsState = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = eventsState.asSharedFlow()

    /** Non-null when the one-time field-structure migration dialog should show (target count). */
    private val fieldMigrationState = MutableStateFlow<Int?>(null)
    val fieldMigrationPrompt: StateFlow<Int?> = fieldMigrationState

    /** Non-null when the one-time world-book migration dialog should show (target count). */
    private val lorebookMigrationState = MutableStateFlow<Int?>(null)
    val lorebookMigrationPrompt: StateFlow<Int?> = lorebookMigrationState

    private val media = MediaStore(DeepTalkingApp.core.appContext)

    init {
        viewModelScope.launch {
            core.data.characters.observeAll().collect { list ->
                if (activeIdState.value == null && list.isNotEmpty()) {
                    activeIdState.value = list.first().id
                }
            }
        }
        viewModelScope.launch { proactiveLoop() }
        viewModelScope.launch { themeLoop() }
        // Legacy one-time relative-time → absolute migration (no key required).
        viewModelScope.launch {
            runCatching { core.runStartupMigrations() }.onFailure { DeepTalkingApp.recordError(it) }
        }
        // Legacy on-load silent static-field completion (only with an API key).
        viewModelScope.launch {
            runCatching {
                val cfg = core.data.config.observe().first()
                core.runStartupMaintenance(cfg)
            }.onFailure { DeepTalkingApp.recordError(it) }
        }
        // Legacy one-time migration prompts (field schema, then world book).
        viewModelScope.launch {
            runCatching { checkMigrationPrompts() }.onFailure { DeepTalkingApp.recordError(it) }
        }
        // Legacy silent avatar auto-repair for damaged placeholders.
        autoRepairAvatars()
        // Restore the on-device read-aloud voice selection and ready the built-in voices.
        viewModelScope.launch {
            val cfg = core.currentConfig()
            cfg.ttsVoiceFile.takeIf { it.isNotBlank() }?.let { p ->
                java.io.File(p).takeIf { it.exists() }?.let { core.cosyVoice.setVoiceFile(it) }
            }
            runCatching { core.cosyVoice.ensureDefaultVoices() }.onFailure { DeepTalkingApp.recordError(it) }
            refreshTtsState(core.currentConfig())
        }
    }

    // --------------------------------------------------------- migrations

    private suspend fun checkMigrationPrompts() {
        val cfg = core.data.config.observe().first()
        if (!core.hasApiKey(cfg.apiPlatform)) return
        val settings = core.data.settings
        if (!settings.getBoolean(FIELD_MIGRATION_SKIP_KEY)) {
            val count = core.fieldMigrationTargetCount()
            if (count > 0) fieldMigrationState.value = count
        }
        if (!settings.getBoolean(LOREBOOK_MIGRATION_SKIP_KEY)) {
            val count = core.lorebookMigrationTargetCount()
            if (count > 0) lorebookMigrationState.value = count
        }
    }

    fun runFieldMigration() {
        fieldMigrationState.value = null
        viewModelScope.launch {
            val cfg = core.currentConfig()
            if (!core.hasApiKey(cfg.apiPlatform)) {
                eventsState.tryEmit(UiEvent("未配置 API Key，无法迁移"))
                return@launch
            }
            val count = core.remapCharacterFields(cfg)
            val remaining = runCatching { core.fieldMigrationTargetCount() }.getOrDefault(0)
            val suffix = if (remaining > 0) "，仍有 $remaining 个待处理" else ""
            eventsState.tryEmit(UiEvent("字段结构迁移完成（$count 个角色$suffix）"))
        }
    }

    fun skipFieldMigrationForever() {
        fieldMigrationState.value = null
        viewModelScope.launch {
            core.data.settings.putBoolean(FIELD_MIGRATION_SKIP_KEY, true)
            val characters = core.data.characters.all()
            characters.forEach { character ->
                if (character.fieldsMigrationVersion != "1.2.0") {
                    core.data.characters.upsert(character.copy(fieldsMigrationVersion = "1.2.0"))
                }
            }
        }
    }

    /** Dismiss without marking, so it is asked again on the next launch (legacy "跳过"). */
    fun dismissFieldMigration() {
        fieldMigrationState.value = null
    }

    fun runLorebookMigration(trimSource: Boolean) {
        lorebookMigrationState.value = null
        viewModelScope.launch {
            val cfg = core.currentConfig()
            if (!core.hasApiKey(cfg.apiPlatform)) {
                eventsState.tryEmit(UiEvent("未配置 API Key，无法整理"))
                return@launch
            }
            val outcome = core.runWorldLoreMigration(cfg, trimSource)
            val remaining = runCatching { core.lorebookMigrationTargetCount() }.getOrDefault(0)
            val parts = mutableListOf("${outcome.success} 个角色已整理")
            if (outcome.failed > 0) parts += "${outcome.failed} 个失败（稍后会再提醒）"
            if (remaining > 0) parts += "仍有 $remaining 个待处理"
            eventsState.tryEmit(UiEvent("世界书整理完成：${parts.joinToString("，")}"))
        }
    }

    fun skipLorebookMigrationForever() {
        lorebookMigrationState.value = null
        viewModelScope.launch {
            core.data.settings.putBoolean(LOREBOOK_MIGRATION_SKIP_KEY, true)
            val now = Instant.now().toString()
            core.data.characters.all().forEach { character ->
                if (character.lorebookMigratedAt == null) {
                    core.data.characters.upsert(character.copy(lorebookMigratedAt = now))
                }
            }
        }
    }

    fun dismissLorebookMigration() {
        lorebookMigrationState.value = null
    }

    // ---------------------------------------------------------------- selection

    fun select(id: String) {
        if (activeIdState.value != id) {
            activeIdState.value = id
            quickRepliesState.value = emptyList()
            streamingState.value = null
            lastUserActivityAt.value = System.currentTimeMillis()
        }
    }

    // --------------------------------------------------------------- characters

    fun createCharacter(
        name: String,
        emoji: String,
        personality: String = "",
        background: String = "",
        draft: com.deeptalking.feature.characters.CharacterParity.GeneratedDraft? = null,
    ) {
        viewModelScope.launch {
            val now = Instant.now().toString()
            val profile = (draft?.staticProfile ?: StaticProfile()).copy(
                personality = personality.ifBlank { draft?.staticProfile?.personality.orEmpty() },
                background = background.ifBlank { draft?.staticProfile?.background.orEmpty() },
            )
            val character = Character(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "新角色" },
                emoji = emoji.ifBlank { "🙂" },
                staticProfile = profile,
                dynamicState = draft?.dynamicState ?: com.deeptalking.core.model.DynamicState(),
                lorebook = draft?.lorebook.orEmpty(),
                createdAt = now,
                // Fresh entities are already on the current schema and hold no
                // legacy world-layer prose, so they must not re-trigger migrations.
                fieldsMigrationVersion = "1.2.0",
                lorebookMigratedAt = now,
            )
            core.data.characters.upsert(character)
            activeIdState.value = character.id
        }
    }

    fun createGroup(
        name: String,
        emoji: String,
        description: String,
        scene: String,
        rules: String,
        members: List<GroupMember>,
        lorebook: List<LorebookEntry> = emptyList(),
    ) {
        viewModelScope.launch {
            val now = Instant.now().toString()
            val group = Character(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "新群组" },
                emoji = emoji.ifBlank { "👥" },
                description = description,
                isGroup = true,
                interactionRules = rules,
                groupSharedDynamic = com.deeptalking.core.model.DynamicState(currentLocation = scene),
                members = members,
                lorebook = lorebook,
                createdAt = now,
                fieldsMigrationVersion = "1.2.0",
                lorebookMigratedAt = now,
            )
            core.data.characters.upsert(group)
            activeIdState.value = group.id
        }
    }

    fun updateCharacter(character: Character) {
        viewModelScope.launch { core.data.characters.upsert(character.copy(updatedAt = Instant.now().toString())) }
    }

    fun deleteCharacter(id: String) {
        viewModelScope.launch {
            core.data.characters.delete(id)
            core.data.chat.clear(id)
            if (activeIdState.value == id) activeIdState.value = null
        }
    }

    /** Applies a transform to the current character and persists it. */
    fun editActiveCharacter(transform: (Character) -> Character) {
        val id = activeIdState.value ?: return
        viewModelScope.launch {
            val current = core.data.characters.get(id) ?: return@launch
            core.data.characters.upsert(transform(current))
        }
    }

    /** Replaces a group member (by index) on [character]. */
    fun updateMember(character: Character, index: Int, member: GroupMember) {
        if (index !in character.members.indices) return
        val members = character.members.toMutableList().also { it[index] = member }
        updateCharacter(character.copy(members = members))
    }

    /**
     * Legacy "升级为群组": expands a single character into a group using the
     * current conversation as seed material (AI-generated members).
     */
    fun upgradeToGroup(character: Character, onDone: (Boolean) -> Unit = {}) {
        if (character.isGroup) { onDone(false); return }
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull()
            if (cfg == null || core.secrets.getApiKey().isNullOrBlank()) {
                eventsState.tryEmit(UiEvent("请先在设置页配置 API Key"))
                onDone(false)
                return@launch
            }
            val history = core.data.chat.getMessages(character.id)
            val transcript = history.filter { !it.isLoading }.takeLast(20).joinToString("\n") { message ->
                val label = if (message.role == Role.User) "用户" else character.name
                "$label: " + message.content.take(1000)
            }
            val originalCharJson = buildJsonObject {
                put("name", character.name)
                put("avatar", character.emoji)
                put("gender", character.staticProfile.gender)
                put("age", character.staticProfile.age)
                put("race", character.staticProfile.race)
                put("appearance", character.staticProfile.appearance)
                put("personality", character.staticProfile.personality)
                put("values", character.staticProfile.values)
                put("fears", character.staticProfile.fears)
                put("background", character.staticProfile.background)
                put("keyEvents", character.staticProfile.keyEvents)
                put("speakingStyle", character.staticProfile.speakingStyle)
                put("language", character.staticProfile.language)
                put("userAddress", character.staticProfile.userAddress)
            }.toString()
            val prompt = buildString {
                append("将以下角色和近期对话升级为群组。原角色会由系统完整保留，因此绝不能在输出成员列表中再次生成原角色，也不要生成同名或明显重复的变体。")
                append("请列出所有近期对话中已出现、应成为固定成员的其他角色；若不足一人，再新增一名最适合当前剧情的成员。")
                append("返回JSON：{\"groupInfo\":{\"name\":\"群组名\",\"avatar\":\"emoji\",\"description\":\"群组前提（这群人是谁、为什么在一起，1-2 句；不要写世界观/地点/组织等世界层设定）\",\"scene\":\"场景\",\"interactionRules\":\"成员互动规则\"},")
                append("\"lorebook\":[{\"name\":\"条目名\",\"keywords\":[\"触发词\"],\"content\":\"命中后注入的世界层设定\",\"alwaysActive\":false}],")
                append("\"additionalMembers\":[{\"name\":\"\",\"avatar\":\"emoji\",\"gender\":\"\",\"age\":\"\",\"race\":\"\",\"appearance\":\"\",\"personality\":\"\",\"values\":\"\",\"fears\":\"\",\"background\":\"\",\"keyEvents\":\"\",\"speakingStyle\":\"\",\"language\":\"\",\"userAddress\":\"\",\"roleInGroup\":\"\",\"dynamicState\":{\"currentSituation\":\"\",\"currentLocation\":\"\",\"currentMood\":\"\",\"currentOccupation\":\"\",\"currentGoal\":\"\",\"currentRelationship\":\"\",\"currentImportantOthers\":\"\"}}]}。")
                append("lorebook 只需补上近期对话中出现、值得日后复用的世界层设定（没有就返回空数组）；additionalMembers只能包含新增成员，至少一名，且姓名必须互不重复；每名成员必须尽可能填满所有字段，不能只返回名称和性格；成员之间的说话方式必须显著不同（看台词就能分辨是谁）。")
                append(CharacterParity.CHARACTER_QUALITY_RULE)
                append(CharacterParity.SPEAKING_STYLE_SAMPLES_RULE)
                append("\n原角色（禁止重复输出）:\n").append(originalCharJson)
                if (transcript.isNotBlank()) append("\n近期对话:\n").append(transcript)
            }
            val raw = runCatching { core.quickGenerate(cfg, prompt) }.getOrNull()
            val draft = raw?.let { com.deeptalking.feature.characters.CharacterParity.parseGeneratedDraft(it) }
            if (draft == null) {
                eventsState.tryEmit(UiEvent("升级失败，请重试"))
                onDone(false)
                return@launch
            }
            val originalName = character.name.trim().lowercase()
            val original = GroupMember(
                id = character.id,
                name = character.name,
                emoji = character.emoji,
                roleInGroup = "原有成员",
                staticProfile = character.staticProfile,
                dynamicState = character.dynamicState,
            )
            val members = (listOf(original) + draft.members.filter { it.name.trim().lowercase() != originalName })
                .filter { it.name.isNotBlank() }
                .distinctBy { it.name.trim().lowercase() }
            if (members.size < 2) {
                eventsState.tryEmit(UiEvent("升级失败：成员不足，请重试"))
                onDone(false)
                return@launch
            }
            val mergedLorebook = (character.lorebook + draft.lorebook)
                .distinctBy { it.name.trim().lowercase().ifBlank { it.id } }
            val now = Instant.now().toString()
            val group = character.copy(
                isGroup = true,
                name = draft.name.ifBlank { character.name + "的群组" },
                emoji = character.emoji.ifBlank { draft.emoji.ifBlank { "👥" } },
                description = draft.description.ifBlank { character.description },
                interactionRules = draft.interactionRules.ifBlank { character.interactionRules },
                groupSharedDynamic = com.deeptalking.core.model.DynamicState(
                    currentSituation = character.dynamicState.currentSituation,
                    currentLocation = character.dynamicState.currentLocation.ifBlank { draft.scene },
                ),
                members = members,
                lorebook = mergedLorebook,
                fieldsMigrationVersion = "1.2.0",
                lorebookMigratedAt = now,
            )
            core.data.characters.upsert(group)
            onDone(true)
        }
    }

    /** Legacy "一句话补全空字段" for a group member: fills only empty fields. */
    fun fillGroupMember(character: Character, index: Int, hint: String) {
        val member = character.members.getOrNull(index) ?: return
        if (hint.isBlank()) {
            eventsState.tryEmit(UiEvent("请输入一句话描述"))
            return
        }
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull()
            if (cfg == null || core.secrets.getApiKey().isNullOrBlank()) {
                eventsState.tryEmit(UiEvent("请先在设置页配置 API Key"))
                return@launch
            }
            val raw = runCatching { core.fillMemberFields(cfg, character, member, hint) }.getOrNull()
            if (raw == null) {
                eventsState.tryEmit(UiEvent("补全失败，请重试"))
                return@launch
            }
            val filled = core.applyMemberFill(member, raw)
            updateMember(character, index, filled)
        }
    }

    /** Legacy "AI 生成 emoji 头像" for the draft character. */
    fun generateEmojiAvatar(character: Character, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull()
            if (cfg == null || core.secrets.getApiKey().isNullOrBlank()) {
                eventsState.tryEmit(UiEvent("请先在设置页配置 API Key"))
                onResult(null)
                return@launch
            }
            val emoji = runCatching { core.generateEmojiAvatar(cfg, character) }.getOrNull()
            if (emoji.isNullOrBlank()) eventsState.tryEmit(UiEvent("emoji 头像生成失败"))
            onResult(emoji)
        }
    }

    fun addLorebookEntry(character: Character) {
        if (character.lorebook.size >= com.deeptalking.core.common.AppLimits.Lorebook.ENTRIES) {
            eventsState.tryEmit(UiEvent("世界书条目已达上限（200 条）"))
            return
        }
        val entry = LorebookEntry(
            id = UUID.randomUUID().toString(),
            name = "",
            content = "",
            alwaysActive = true,
            origin = LorebookOrigin.User,
            createdAt = Instant.now().toString(),
        )
        updateCharacter(character.copy(lorebook = character.lorebook + entry))
    }

    fun removeLorebookEntry(character: Character, entryId: String) {
        updateCharacter(character.copy(lorebook = character.lorebook.filterNot { it.id == entryId }))
    }

    fun updateLorebookEntry(character: Character, entry: LorebookEntry) {
        updateCharacter(
            character.copy(
                lorebook = character.lorebook.map { if (it.id == entry.id) entry else it },
            ),
        )
    }

    // ----------------------------------------------------------------- stickers

    fun addSticker(uri: Uri) {
        val id = activeIdState.value ?: return
        viewModelScope.launch {
            val path = media.saveSticker(uri) ?: run {
                eventsState.tryEmit(UiEvent("图片处理失败，请换一张试试"))
                return@launch
            }
            val character = core.data.characters.get(id) ?: return@launch
            // Skip duplicates (same compressed payload → same file key).
            if (character.stickers.any { it.fileRef == path }) return@launch
            val sticker = com.deeptalking.core.model.Sticker(
                id = UUID.randomUUID().toString(),
                tag = "未分类",
                fileRef = path,
                createdAt = Instant.now().toString(),
            )
            core.data.characters.upsert(character.copy(stickers = character.stickers + sticker))
            // Legacy `tagStickerInBackground`: vision-classify, fill only while untagged.
            viewModelScope.launch {
                val cfg = runCatching { core.currentConfig() }.getOrNull() ?: return@launch
                if (!core.hasApiKey(cfg.apiPlatform)) return@launch
                val tag = runCatching { core.tagSticker(cfg, path) }.getOrNull() ?: return@launch
                val latest = core.data.characters.get(id) ?: return@launch
                core.data.characters.upsert(
                    latest.copy(
                        stickers = latest.stickers.map {
                            if (it.fileRef == path && it.tag == "未分类") it.copy(tag = tag) else it
                        },
                    ),
                )
            }
        }
    }

    fun deleteSticker(character: Character, stickerId: String) {
        updateCharacter(character.copy(stickers = character.stickers.filterNot { it.id == stickerId }))
    }

    fun setStickerTag(character: Character, stickerId: String, tag: String) {
        updateCharacter(
            character.copy(
                stickers = character.stickers.map { if (it.id == stickerId) it.copy(tag = tag) else it },
            ),
        )
    }

    /**
     * Sends a sticker as the user. Mirrors legacy `sendStickerAsUser`: any typed
     * text is sent first as its own message, then the sticker becomes a separate
     * user bubble and triggers its own reply (two turns, not a shared bubble).
     */
    fun sendSticker(sticker: com.deeptalking.core.model.Sticker, text: String = "") {
        val id = activeIdState.value ?: return
        if (sendingState.value) return
        viewModelScope.launch {
            if (text.isNotBlank()) submitTurn(text.trim(), proactive = false).join()
            if (sendingState.value || activeIdState.value != id) return@launch
            core.data.chat.append(
                id,
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = Role.User,
                    content = "",
                    timestamp = Instant.now().toString(),
                    attachments = listOf(MessageAttachment(MessageAttachment.Kind.Sticker, sticker.fileRef)),
                ),
            )
            submitTurn("", proactive = false, skipUserAppend = true).join()
        }
    }

    // ----------------------------------------------------------- AI generators

    private val generatingState = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = generatingState

    /** One-line character/group generation; returns the raw model JSON or null. */
    fun quickGenerate(prompt: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull()
            if (cfg == null || core.secrets.getApiKey().isNullOrBlank()) {
                eventsState.tryEmit(UiEvent("请先在设置页配置 API Key"))
                onResult(null)
                return@launch
            }
            generatingState.value = true
            val result = runCatching { core.quickGenerate(cfg, prompt) }.getOrNull()
            generatingState.value = false
            if (result == null) eventsState.tryEmit(UiEvent("生成失败，请重试"))
            onResult(result)
        }
    }

    /** Repairs default/damaged emoji avatars (characters + group members) via the LLM. */
    fun repairAvatars() {
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull() ?: return@launch
            if (core.secrets.getApiKey(cfg.apiPlatform).isNullOrBlank() && core.secrets.getApiKey().isNullOrBlank()) {
                eventsState.tryEmit(UiEvent("请先在设置页配置 API Key，再补全头像"))
                return@launch
            }
            val all = core.data.characters.observeAll().first()
            var done = 0
            for (character in all) {
                var updated = character
                var changed = false
                if (needsRepair(character.emoji)) {
                    val emoji = runCatching { core.generateEmojiAvatar(cfg, character) }.getOrNull()
                    if (!emoji.isNullOrBlank()) {
                        updated = updated.copy(emoji = emoji)
                        changed = true
                        done++
                    }
                }
                if (character.members.isNotEmpty()) {
                    val members = updated.members.toMutableList()
                    var memberChanged = false
                    members.forEachIndexed { index, member ->
                        if (needsRepair(member.emoji)) {
                            val emoji = runCatching { core.generateEmojiAvatar(cfg, member, character) }.getOrNull()
                            if (!emoji.isNullOrBlank()) {
                                members[index] = member.copy(emoji = emoji)
                                memberChanged = true
                                done++
                            }
                        }
                    }
                    if (memberChanged) {
                        updated = updated.copy(members = members)
                        changed = true
                    }
                }
                if (changed) core.data.characters.upsert(updated)
            }
            if (done == 0) {
                eventsState.tryEmit(UiEvent("所有角色都已使用 emoji 头像，无需补全"))
            } else {
                eventsState.tryEmit(UiEvent("已生成 $done 个 emoji 头像"))
            }
        }
    }

    /** Legacy `isAvatarDamaged`: empty, replacement char, or ASCII-only. */
    private fun isAvatarDamaged(emoji: String): Boolean {
        val v = emoji.trim()
        if (v.isEmpty()) return true
        if (v.contains('?') || v.contains('\uFFFD')) return true
        return v.none { it.code > 127 }
    }

    /** Legacy `isAvatarDamaged` plus 👤/👥 placeholders (manual repair only). */
    private fun needsRepair(emoji: String): Boolean {
        val v = emoji.trim()
        if (v == "👤" || v == "👥") return true
        return isAvatarDamaged(v)
    }

    /** Legacy `autoRepairAvatars`: silently repair damaged avatars on startup/import. */
    fun autoRepairAvatars() {
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull() ?: return@launch
            if (!core.hasApiKey(cfg.apiPlatform)) return@launch
            val all = core.data.characters.observeAll().first()
            for (character in all) {
                var updated = character
                var changed = false
                if (isAvatarDamaged(character.emoji)) {
                    val emoji = runCatching { core.generateEmojiAvatar(cfg, character) }.getOrNull()
                    if (!emoji.isNullOrBlank()) {
                        updated = updated.copy(emoji = emoji)
                        changed = true
                    }
                }
                if (character.members.isNotEmpty()) {
                    val members = updated.members.toMutableList()
                    updated.members.forEachIndexed { index, member ->
                        if (isAvatarDamaged(member.emoji)) {
                            val emoji = runCatching { core.generateEmojiAvatar(cfg, member, character) }.getOrNull()
                            if (!emoji.isNullOrBlank()) {
                                members[index] = member.copy(emoji = emoji)
                                changed = true
                            }
                        }
                    }
                    updated = updated.copy(members = members)
                }
                if (changed) core.data.characters.upsert(updated)
            }
        }
    }

    // ---------------------------------------------------------------- messaging

    fun send(text: String) {
        submitTurn(text, proactive = false)
    }

    fun promptProactive() {
        submitTurn("", proactive = true)
    }

    fun addPendingImage(uri: Uri) {
        if (pendingImagesState.value.size >= 4) {
            eventsState.tryEmit(UiEvent("一次最多发送 4 张图片"))
            return
        }
        pendingImagesState.value = pendingImagesState.value + uri
    }

    fun removePendingImage(uri: Uri) {
        pendingImagesState.value = pendingImagesState.value - uri
    }

    fun clearPendingImages() {
        pendingImagesState.value = emptyList()
    }

    private fun submitTurn(text: String, proactive: Boolean, skipUserAppend: Boolean = false): Job {
        val id = activeIdState.value ?: return completedJob()
        val images = pendingImagesState.value
        if (sendingState.value) return completedJob()
        if (!proactive && !skipUserAppend && text.isBlank() && images.isEmpty()) return completedJob()
        return viewModelScope.launch {
            sendingState.value = true
            statusState.value = "思考中…"
            lastUserActivityAt.value = System.currentTimeMillis()
            try {
                // Legacy blocks before mutating the conversation when no key is set.
                val cfg = core.currentConfig()
                if (!core.hasApiKey(cfg.apiPlatform)) {
                    eventsState.tryEmit(UiEvent("未配置 API Key，请在设置中填写"))
                    return@launch
                }
                val savedImages = images.mapNotNull { uri -> media.saveImage(uri) }
                pendingImagesState.value = emptyList()
                if (!proactive && !skipUserAppend) {
                    core.data.chat.append(
                        id,
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = Role.User,
                            content = text.trim(),
                            timestamp = Instant.now().toString(),
                            attachments = savedImages.map { MessageAttachment(MessageAttachment.Kind.Image, it) },
                        ),
                    )
                }
                val history = core.data.chat.getMessages(id)
                // Mirror the live chat into `character.instant` (incl. the just-appended
                // user message) so set_reminder validation + volatile context see it.
                val character = (core.data.characters.get(id) ?: return@launch)
                    .copy(instant = windowInstant(history))
                statusState.value = "生成中…"
                streamingState.value = ""

                val orchestrator = core.createOrchestrator(cfg) { updated ->
                    core.appScope.launch { core.data.characters.upsert(updated) }
                }
                // Legacy `chatStageDecision('empty')`: an empty turn is retried twice
                // (attemptsLeft=2 → up to 3 requests) before failing.
                var result: OrchestratorResult? = null
                var attempt = 0
                while (attempt < 3) {
                    if (attempt > 0) {
                        statusState.value = "正在重新生成…"
                        streamingState.value = ""
                    }
                    val outcome = orchestrator.run(
                        character = character,
                        history = history,
                        userText = text.trim(),
                        onDelta = { streamingState.value = it },
                        onToolActivity = { statusState.value = it },
                        onQuickRepliesRepaired = { quickRepliesState.value = it },
                        proactive = proactive,
                    )
                    result = outcome
                    if (outcome.reply.isNotBlank()) break
                    attempt++
                }
                streamingState.value = null
                val finalResult = result ?: return@launch
                if (finalResult.reply.isBlank()) {
                    statusState.value = ""
                    eventsState.tryEmit(UiEvent("收到空回复，请重试。你的消息已保留。"))
                    return@launch
                }
                core.data.chat.append(
                    id,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = Role.Assistant,
                        content = finalResult.reply,
                        timestamp = Instant.now().toString(),
                        internalOnly = false,
                        staticChanges = finalResult.staticChanges,
                        lorebookChanges = finalResult.lorebookChanges,
                    ),
                )
                finalResult.stickerFileRef?.let { ref ->
                    core.data.chat.append(
                        id,
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = Role.Assistant,
                            content = "",
                            timestamp = Instant.now().toString(),
                            attachments = listOf(MessageAttachment(MessageAttachment.Kind.Sticker, ref)),
                        ),
                    )
                }
                val withReminders = scheduleDueReminders(finalResult.updatedCharacter)
                core.data.characters.upsert(withReminders)
                quickRepliesState.value = finalResult.quickReplies
                statusState.value = ""
                recordMetrics(cfg, id, finalResult)
                if (cfg.ttsEnabled && cfg.ttsAutoRead) speakText(finalResult.reply)
            } catch (error: Exception) {
                streamingState.value = null
                statusState.value = ""
                eventsState.tryEmit(UiEvent("回复失败：${error.message ?: error}。你的消息已保留，可稍后重试。"))
            } finally {
                sendingState.value = false
            }
        }
    }

    /** Trims a message list to the persisted `character.instant` window (legacy `memory.instant`). */
    private fun windowInstant(messages: List<ChatMessage>): List<ChatMessage> =
        if (messages.size > AppLimits.Memory.INSTANT) messages.takeLast(AppLimits.Memory.INSTANT) else messages

    /**
     * Native equivalent of a due reminder: for the active character's active
     * promises with a `dueAt`, post a notification once when due (marking
     * [LongTermMemory.notifiedAt]) or schedule it for the future. Only the
     * character that just took a turn is scanned, never every character.
     */
    private fun scheduleDueReminders(character: Character): Character {
        val now = System.currentTimeMillis()
        var changed = false
        val longTerm = character.longTerm.map { memory ->
            if (memory.category != MemoryCategory.Promises || memory.status != PromiseStatus.Active) return@map memory
            val due = memory.dueAt?.let(::parseDueAt) ?: return@map memory
            val text = "${memory.key}：${memory.value}"
            when {
                memory.notifiedAt != null -> {
                    if (due > now) core.scheduleReminder(memory.id, due, text)
                    memory
                }
                due <= now -> {
                    core.postReminder(memory.id, text)
                    changed = true
                    memory.copy(notifiedAt = Instant.now().toString())
                }
                else -> {
                    core.scheduleReminder(memory.id, due, text)
                    memory
                }
            }
        }
        return if (changed) character.copy(longTerm = longTerm) else character
    }

    private fun parseDueAt(raw: String): Long? {
        val text = raw.trim()
        return runCatching {
            java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
        }.recoverCatching {
            java.time.LocalDateTime.parse(text, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.recoverCatching {
            java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** Already-completed [Job] for the no-op branches of [submitTurn]. */
    private fun completedJob(): Job = Job().apply { complete() }

    /** Stores the last reply debug payload and appends a rolling usage/cache metric. */
    private suspend fun recordMetrics(cfg: AppConfig, characterId: String, result: OrchestratorResult) {
        val hitTokens = result.cachedTokens
        val missTokens = (result.inputTokens - hitTokens).coerceAtLeast(0)
        val hitRate = if (result.inputTokens > 0) hitTokens.toDouble() / result.inputTokens else 0.0
        val metric = RequestMetric(
            at = Instant.now().toString(),
            taskType = "chat",
            characterId = characterId,
            inputTokens = result.inputTokens,
            hitTokens = hitTokens,
            missTokens = missTokens,
            hitRate = hitRate,
        )
        val metrics = (cfg.requestMetrics + metric).takeLast(60)
        val debug = buildString {
            append("reply: ").append(result.reply.take(500))
            if (result.rawReply.isNotBlank() && result.rawReply != result.reply) {
                append("\n\n原始回应（未处理）:\n").append(result.rawReply.take(1500))
            }
        }
        runCatching { core.data.config.update(cfg.copy(requestMetrics = metrics, lastReplyDebug = debug)) }
    }

    /** Removes [message] and everything after it, then re-sends [message]'s text. */
    fun editAndResend(messageId: String, newText: String) {
        val id = activeIdState.value ?: return
        if (sendingState.value) return
        viewModelScope.launch {
            val history = core.data.chat.getMessages(id)
            val userIndex = history.indexOfFirst { it.id == messageId && it.role == Role.User }
            if (userIndex < 0) return@launch
            discardBranch(id, userIndex)
            send(newText)
        }
    }

    /** Removes the assistant [messageId] and everything after, then regenerates. */
    fun regenerate(messageId: String) {
        val id = activeIdState.value ?: return
        if (sendingState.value) return
        viewModelScope.launch {
            val history = core.data.chat.getMessages(id)
            val replyIndex = history.indexOfFirst { it.id == messageId && it.role == Role.Assistant }
            if (replyIndex < 0) return@launch
            var userIndex = replyIndex - 1
            while (userIndex >= 0 && history[userIndex].role != Role.User) userIndex--
            if (userIndex < 0) return@launch
            val userText = history[userIndex].content
            discardBranch(id, userIndex + 1)
            submitTurn(userText, proactive = false, skipUserAppend = true)
        }
    }

    /**
     * Legacy `discardConversationBranch`: truncates the persisted history from
     * [fromIndex] and prunes short-term / long-term / pendingRecall memories whose
     * source messages were removed.
     */
    private suspend fun discardBranch(characterId: String, fromIndex: Int) {
        val history = core.data.chat.getMessages(characterId)
        if (fromIndex !in 0..history.size) return
        val removedIds = history.drop(fromIndex).mapNotNull { it.id.ifBlank { null } }.toSet()
        core.data.chat.replace(characterId, history.take(fromIndex))
        val character = core.data.characters.get(characterId) ?: return
        fun hasRemovedSource(ids: List<String>) = ids.any { it in removedIds }
        core.data.characters.upsert(
            character.copy(
                shortTerm = character.shortTerm.filterNot { hasRemovedSource(it.sourceMessageIds) },
                longTerm = character.longTerm.filterNot { hasRemovedSource(it.sourceMessageIds) },
                pendingRecall = character.pendingRecall.filterNot { hasRemovedSource(it.sourceMessageIds) },
                revision = character.revision + 1,
            ),
        )
    }

    // ---------------------------------------------------------------- settings

    fun saveSettings(newConfig: AppConfig, apiKey: String?) {
        viewModelScope.launch {
            core.data.config.update(newConfig)
            val trimmedKey = apiKey?.trim()
            if (!trimmedKey.isNullOrEmpty()) {
                core.secrets.setApiKey(newConfig.apiPlatform, trimmedKey)
                core.secrets.setApiKey(trimmedKey)
            }
        }
    }

    fun setTheme(themeId: String) {
        viewModelScope.launch { core.data.config.update(core.currentConfig().copy(activeTheme = themeId)) }
    }

    fun testReminder() = core.scheduleTestReminder()

    fun hasApiKey(platform: String = ""): Boolean =
        !core.secrets.getApiKey(platform).isNullOrBlank() || !core.secrets.getApiKey().isNullOrBlank()

    private val testResultState = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = testResultState

    fun testApiConnection(config: AppConfig, typedKey: String? = null) {
        viewModelScope.launch {
            testResultState.value = "测试中…"
            val key = typedKey?.trim()?.takeIf { it.isNotEmpty() }
                ?: core.secrets.getApiKey(config.apiPlatform)?.trim()?.takeIf { it.isNotEmpty() }
                ?: core.secrets.getApiKey()?.trim()?.takeIf { it.isNotEmpty() }
            testResultState.value = if (key.isNullOrBlank()) {
                "未配置 API Key"
            } else {
                core.testApiConnection(config, key)
            }
        }
    }

    fun clearTestResult() {
        testResultState.value = null
    }

    // ----------------------------------------------------------- import/export

    fun exportJson(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val activeId = activeIdState.value
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                core.backup.exportJson(activeId)
            }
            onReady(json)
        }
    }

    fun importJson(json: String) {
        viewModelScope.launch {
            val summary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { core.backup.importJson(json) }
            }.getOrElse { error ->
                eventsState.tryEmit(UiEvent("导入失败：${error.message ?: error}"))
                return@launch
            }
            val all = core.data.characters.observeAll().first()
            val restored = summary.activeCharacterId?.takeIf { id -> all.any { it.id == id } }
            activeIdState.value = restored ?: all.firstOrNull()?.id
            eventsState.tryEmit(
                UiEvent(
                    if (summary.skipped > 0) {
                        "导入成功：${summary.characters} 个角色，${summary.messages} 条消息（${summary.skipped} 个角色无法解析已跳过）"
                    } else {
                        "导入成功：${summary.characters} 个角色，${summary.messages} 条消息"
                    },
                ),
            )
            // Legacy runs silent static-field completion + migration prompts after import.
            runCatching { core.runStartupMigrations() }.onFailure { DeepTalkingApp.recordError(it) }
            runCatching {
                val cfg = core.data.config.observe().first()
                core.runStartupMaintenance(cfg)
            }.onFailure { DeepTalkingApp.recordError(it) }
            runCatching { checkMigrationPrompts() }.onFailure { DeepTalkingApp.recordError(it) }
            autoRepairAvatars()
        }
    }

    // -------------------------------------------------------------- proactive

    /**
     * Legacy proactive opening: after [PROACTIVE_IDLE_MS] of no user activity
     * (and only when the app is visible, a key is set, and a character is
     * active), the character opens unprompted. Any user input or send resets the
     * timer. No cooldown, not restricted to empty conversations.
     */
    private val lastUserActivityAt = MutableStateFlow(System.currentTimeMillis())

    private suspend fun proactiveLoop() {
        while (true) {
            delay(5_000)
            val cfg = runCatching { core.currentConfig() }.getOrNull() ?: continue
            if (!cfg.proactiveEnabled) {
                lastUserActivityAt.value = System.currentTimeMillis()
                continue
            }
            if (sendingState.value) continue
            if (core.secrets.getApiKey().isNullOrBlank()) continue
            if (activeIdState.value == null) continue
            if (!appVisible.value) {
                lastUserActivityAt.value = System.currentTimeMillis()
                continue
            }
            if (System.currentTimeMillis() - lastUserActivityAt.value < PROACTIVE_IDLE_MS) continue
            submitTurn("", proactive = true)
            lastUserActivityAt.value = System.currentTimeMillis()
        }
    }

    /** Keeps the proactive idle timer fresh on any user interaction / send. */
    fun noteUserActivity() {
        lastUserActivityAt.value = System.currentTimeMillis()
    }

    /** Called by the Activity on visibility changes so proactive pauses when hidden. */
    fun setAppVisible(visible: Boolean) {
        appVisible.value = visible
        if (visible) lastUserActivityAt.value = System.currentTimeMillis()
    }

    private val appVisible = MutableStateFlow(true)

    private companion object {
        const val PROACTIVE_IDLE_MS = 60_000L
        const val FIELD_MIGRATION_SKIP_KEY = "deeptalking_field_migration_skip_v1"
        const val LOREBOOK_MIGRATION_SKIP_KEY = "deeptalking_lorebook_migration_skip_v1"
    }

    // ------------------------------------------------------------------- theme

    private val themeState = MutableStateFlow(com.deeptalking.core.designsystem.AppTheme.Qingqian)
    val theme: StateFlow<com.deeptalking.core.designsystem.AppTheme> = themeState

    private suspend fun themeLoop() {
        core.data.config.observe().collect { cfg ->
            themeState.value = com.deeptalking.core.designsystem.AppTheme.fromId(cfg.activeTheme)
        }
    }

    // -------------------------------------------------------------------- tts

    private val ttsState = MutableStateFlow(TtsState())

    /** On-device read-aloud state (CosyVoice3). */
    val tts: StateFlow<TtsState> = ttsState

    private var ttsJob: Job? = null

    private fun refreshTtsState(cfg: AppConfig) {
        val voices = runCatching { core.cosyVoice.listVoices() }.getOrDefault(emptyList())
        val activeFile = cfg.ttsVoiceFile.takeIf { it.isNotBlank() }?.let { java.io.File(it).name }
            ?.takeIf { name -> voices.any { it.file == name } }
            ?: voices.firstOrNull()?.file
            ?: ""
        ttsState.value = ttsState.value.copy(
            enabled = cfg.ttsEnabled,
            autoRead = cfg.ttsAutoRead,
            modelReady = runCatching { core.cosyVoice.isModelReady }.getOrDefault(false),
            voices = voices,
            activeVoice = activeFile,
            style = cfg.ttsStyle,
            speed = cfg.ttsSpeed,
        )
    }

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val cfg = core.currentConfig()
            core.data.config.update(cfg.copy(ttsEnabled = enabled))
            refreshTtsState(core.currentConfig())
        }
    }

    fun setTtsAutoRead(autoRead: Boolean) {
        viewModelScope.launch {
            core.data.config.update(core.currentConfig().copy(ttsAutoRead = autoRead))
            refreshTtsState(core.currentConfig())
        }
    }

    fun setTtsStyle(style: String) {
        ttsState.value = ttsState.value.copy(style = style)
        viewModelScope.launch { core.data.config.update(core.currentConfig().copy(ttsStyle = style)) }
    }

    fun setTtsSpeed(speed: Float) {
        ttsState.value = ttsState.value.copy(speed = speed)
        viewModelScope.launch { core.data.config.update(core.currentConfig().copy(ttsSpeed = speed)) }
    }

    fun downloadVoiceModel() {
        if (ttsState.value.downloading) return
        ttsState.value = ttsState.value.copy(downloading = true, status = "正在下载语音模型…")
        ttsJob = viewModelScope.launch {
            runCatching {
                core.cosyVoice.download { done, total, label ->
                    val pct = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    ttsState.value = ttsState.value.copy(progress = pct, progressLabel = label)
                }
            }.onSuccess { ok ->
                ttsState.value = ttsState.value.copy(downloading = false, modelReady = ok, status = if (ok) "模型已就绪" else "下载未完成")
                if (ok) {
                    ttsState.value = ttsState.value.copy(status = "正在准备默认音色…")
                    runCatching { core.cosyVoice.ensureDefaultVoices() }.onFailure { DeepTalkingApp.recordError(it) }
                }
            }.onFailure { t ->
                ttsState.value = ttsState.value.copy(downloading = false, status = "下载失败：${t.message}")
            }
            refreshTtsState(core.currentConfig())
        }
    }

    /** Imports any audio file; the engine auto-picks a few seconds of clear speech. */
    fun importVoice(uri: android.net.Uri, name: String, promptText: String?) {
        if (ttsState.value.busy) return
        ttsState.value = ttsState.value.copy(busy = true, status = "正在解析音频并挑选人声片段…")
        ttsJob = viewModelScope.launch {
            runCatching { core.cosyVoice.importVoice(uri, name, promptText?.trim()?.ifBlank { null }) }
                .onSuccess { info ->
                    core.data.config.update(core.currentConfig().copy(ttsVoiceFile = core.cosyVoice.voiceFile?.absolutePath ?: ""))
                    ttsState.value = ttsState.value.copy(busy = false, status = "音色已导入：${info.name}")
                }
                .onFailure { t -> ttsState.value = ttsState.value.copy(busy = false, status = "导入失败：${t.message}") }
            refreshTtsState(core.currentConfig())
        }
    }

    fun selectVoice(file: String) {
        viewModelScope.launch {
            val resolved = core.cosyVoice.selectVoice(file) ?: return@launch
            core.data.config.update(core.currentConfig().copy(ttsVoiceFile = resolved.absolutePath))
            refreshTtsState(core.currentConfig())
        }
    }

    fun renameVoice(file: String, name: String) {
        viewModelScope.launch {
            val ok = runCatching { core.cosyVoice.renameVoice(file, name) }.getOrDefault(false)
            ttsState.value = ttsState.value.copy(status = if (ok) "已重命名为：${name.trim()}" else "重命名失败")
            refreshTtsState(core.currentConfig())
        }
    }

    fun deleteVoice(file: String) {
        viewModelScope.launch {
            val ok = runCatching { core.cosyVoice.deleteVoice(file) }.getOrDefault(false)
            if (ok) {
                val cfg = core.currentConfig()
                if (cfg.ttsVoiceFile.endsWith(file)) {
                    core.data.config.update(cfg.copy(ttsVoiceFile = ""))
                }
                ttsState.value = ttsState.value.copy(status = "音色已删除")
            } else {
                ttsState.value = ttsState.value.copy(status = "内置音色不可删除")
            }
            refreshTtsState(core.currentConfig())
        }
    }

    /** Reads the currently visible AI reply aloud. */
    fun speakText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val cfg = core.currentConfig()
            if (!cfg.ttsEnabled) {
                eventsState.tryEmit(UiEvent("请先在设置-语音朗读中开启"))
                return@launch
            }
            ttsState.value = ttsState.value.copy(speaking = true, status = "正在合成…")
            runCatching { core.cosyVoice.speak(clean, cfg.ttsStyle.ifBlank { null }) }
                .onFailure { t -> ttsState.value = ttsState.value.copy(status = "朗读失败：${t.message}") }
            ttsState.value = ttsState.value.copy(speaking = false)
        }
    }

    fun stopSpeaking() {
        runCatching { core.cosyVoice.stop() }
        ttsState.value = ttsState.value.copy(speaking = false)
    }
}
