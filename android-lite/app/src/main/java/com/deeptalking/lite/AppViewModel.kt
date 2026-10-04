package com.deeptalking.lite

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.RequestMetric
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.domain.agent.OrchestratorResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    fun createCharacter(name: String, emoji: String, personality: String = "", background: String = "") {
        viewModelScope.launch {
            val character = Character(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "新角色" },
                emoji = emoji.ifBlank { "🙂" },
                staticProfile = StaticProfile(personality = personality, background = background),
                createdAt = Instant.now().toString(),
            )
            core.data.characters.upsert(character)
            activeIdState.value = character.id
        }
    }

    fun createGroup(name: String, emoji: String, description: String, scene: String, rules: String, members: List<GroupMember>) {
        viewModelScope.launch {
            val group = Character(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "新群组" },
                emoji = emoji.ifBlank { "👥" },
                description = description,
                isGroup = true,
                interactionRules = rules,
                groupSharedDynamic = com.deeptalking.core.model.DynamicState(currentLocation = scene),
                members = members,
                createdAt = Instant.now().toString(),
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
            val prompt = buildString {
                append("把以下角色升级为一个群组，保留其身份作为群主，并新增 2-3 名与当前剧情相符的成员。")
                append("\n角色：").append(character.name)
                if (character.staticProfile.personality.isNotBlank()) {
                    append("（").append(character.staticProfile.personality).append("）")
                }
                val context = history.takeLast(6).joinToString("\n") { "${it.role}: ${it.content.take(200)}" }
                if (context.isNotBlank()) append("\n近期对话：\n").append(context)
            }
            val raw = runCatching { core.quickGenerate(cfg, prompt) }.getOrNull()
            val members = raw?.let { core.parseGroupMembers(it) }.orEmpty()
            if (members.isEmpty()) {
                eventsState.tryEmit(UiEvent("升级失败，请重试"))
                onDone(false)
                return@launch
            }
            val group = character.copy(
                isGroup = true,
                emoji = character.emoji.ifBlank { "👥" },
                description = character.staticProfile.personality.ifBlank { character.description },
                interactionRules = character.interactionRules,
                groupSharedDynamic = com.deeptalking.core.model.DynamicState(
                    currentSituation = character.dynamicState.currentSituation,
                    currentLocation = character.dynamicState.currentLocation,
                ),
                members = members,
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
            val sticker = com.deeptalking.core.model.Sticker(
                id = UUID.randomUUID().toString(),
                tag = "未分类",
                fileRef = path,
                createdAt = Instant.now().toString(),
            )
            core.data.characters.upsert(character.copy(stickers = character.stickers + sticker))
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

    /** Sends a sticker as a user image attachment. */
    fun sendSticker(sticker: com.deeptalking.core.model.Sticker) {
        val id = activeIdState.value ?: return
        viewModelScope.launch {
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
                            val probe = Character(
                                id = member.id,
                                name = member.name,
                                emoji = member.emoji,
                                staticProfile = member.staticProfile,
                            )
                            val emoji = runCatching { core.generateEmojiAvatar(cfg, probe) }.getOrNull()
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

    /** Legacy `isAvatarDamaged`: empty, placeholder, replacement char, or ASCII-only. */
    private fun needsRepair(emoji: String): Boolean {
        val v = emoji.trim()
        if (v.isEmpty()) return true
        if (v == "👤" || v == "👥") return true
        if (v.contains('?') || v.contains('\uFFFD')) return true
        return v.none { it.code > 127 }
    }

    // ---------------------------------------------------------------- messaging

    fun send(text: String) = submitTurn(text, proactive = false)

    fun promptProactive() = submitTurn("", proactive = true)

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

    private fun submitTurn(text: String, proactive: Boolean, skipUserAppend: Boolean = false) {
        val id = activeIdState.value ?: return
        val images = pendingImagesState.value
        if ((!proactive && text.isBlank() && images.isEmpty()) || sendingState.value) return
        viewModelScope.launch {
            sendingState.value = true
            statusState.value = "思考中…"
            lastUserActivityAt.value = System.currentTimeMillis()
            try {
                val character = core.data.characters.get(id) ?: return@launch
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
                val cfg = core.currentConfig()
                statusState.value = "生成中…"
                streamingState.value = ""

                val orchestrator = core.createOrchestrator(cfg) { updated ->
                    viewModelScope.launch { core.data.characters.upsert(updated) }
                }
                val result = orchestrator.run(
                    character = character,
                    history = history,
                    userText = text.trim(),
                    onDelta = { streamingState.value = it },
                    proactive = proactive,
                )
                streamingState.value = null
                core.data.chat.append(
                    id,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = Role.Assistant,
                        content = result.reply,
                        timestamp = Instant.now().toString(),
                        internalOnly = result.proactive,
                        staticChanges = result.staticChanges,
                        lorebookChanges = result.lorebookChanges,
                    ),
                )
                result.stickerFileRef?.let { ref ->
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
                core.data.characters.upsert(result.updatedCharacter)
                quickRepliesState.value = result.quickReplies
                statusState.value = ""
                recordMetrics(cfg, id, result)
            } catch (error: Exception) {
                streamingState.value = null
                statusState.value = ""
                eventsState.tryEmit(UiEvent("回复失败：${error.message ?: error}。你的消息已保留，可稍后重试。"))
            } finally {
                sendingState.value = false
            }
        }
    }

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
                pendingRecall = character.pendingRecall?.takeIf {
                    removedIds.isEmpty()
                },
            ),
        )
    }

    // ---------------------------------------------------------------- settings

    fun saveSettings(newConfig: AppConfig, apiKey: String?) {
        viewModelScope.launch {
            core.data.config.update(newConfig)
            if (!apiKey.isNullOrBlank()) {
                core.secrets.setApiKey(newConfig.apiPlatform, apiKey)
                core.secrets.setApiKey(apiKey)
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

    fun testApiConnection(config: AppConfig) {
        viewModelScope.launch {
            testResultState.value = "测试中…"
            val key = core.secrets.getApiKey(config.apiPlatform) ?: core.secrets.getApiKey()
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
            onReady(core.backup.exportJson())
        }
    }

    fun importJson(json: String) {
        viewModelScope.launch {
            val summary = runCatching { core.backup.importJson(json) }.getOrElse { error ->
                eventsState.tryEmit(UiEvent("导入失败：${error.message ?: error}"))
                return@launch
            }
            val all = core.data.characters.observeAll().first()
            activeIdState.value = all.firstOrNull()?.id
            eventsState.tryEmit(
                UiEvent("导入成功：${summary.characters} 个角色，${summary.messages} 条消息"),
            )
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
    }

    // ------------------------------------------------------------------- theme

    private val themeState = MutableStateFlow(com.deeptalking.core.designsystem.AppTheme.Qingqian)
    val theme: StateFlow<com.deeptalking.core.designsystem.AppTheme> = themeState

    private suspend fun themeLoop() {
        core.data.config.observe().collect { cfg ->
            themeState.value = com.deeptalking.core.designsystem.AppTheme.fromId(cfg.activeTheme)
        }
    }
}
