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
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
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
            explicitIdle.value = System.currentTimeMillis()
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

    fun createGroup(name: String, emoji: String, description: String, scene: String, members: List<GroupMember>) {
        viewModelScope.launch {
            val group = Character(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "新群组" },
                emoji = emoji.ifBlank { "👥" },
                description = description,
                isGroup = true,
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

    fun deleteLongTerm(character: Character, memoryId: String) {
        updateCharacter(character.copy(longTerm = character.longTerm.filterNot { it.id == memoryId }))
    }

    fun deleteShortTerm(character: Character, memoryId: String) {
        updateCharacter(character.copy(shortTerm = character.shortTerm.filterNot { it.id == memoryId }))
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

    /** Repairs default/damaged emoji avatars via the LLM. */
    fun repairAvatars() {
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull() ?: return@launch
            if (core.secrets.getApiKey().isNullOrBlank()) {
                eventsState.tryEmit(UiEvent("请先在设置页配置 API Key，再补全头像"))
                return@launch
            }
            val all = core.data.characters.observeAll().first()
            val jobs = all.filter { needsRepair(it.emoji) }
            if (jobs.isEmpty()) {
                eventsState.tryEmit(UiEvent("所有角色都已使用 emoji 头像，无需补全"))
                return@launch
            }
            var done = 0
            for (character in jobs) {
                val emoji = runCatching { core.generateEmojiAvatar(cfg, character) }.getOrNull() ?: continue
                if (emoji.isNotBlank()) {
                    core.data.characters.upsert(character.copy(emoji = emoji))
                    done++
                }
            }
            eventsState.tryEmit(UiEvent("已生成 $done 个 emoji 头像"))
        }
    }

    private fun needsRepair(emoji: String): Boolean =
        emoji.isBlank() || emoji == "👤" || emoji == "👥" || emoji == "🙂" || emoji.all { it.code < 128 }

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

    private fun submitTurn(text: String, proactive: Boolean) {
        val id = activeIdState.value ?: return
        val images = pendingImagesState.value
        if ((!proactive && text.isBlank() && images.isEmpty()) || sendingState.value) return
        viewModelScope.launch {
            sendingState.value = true
            statusState.value = "思考中…"
            explicitIdle.value = System.currentTimeMillis()
            try {
                val character = core.data.characters.get(id) ?: return@launch
                val savedImages = images.mapNotNull { uri -> media.saveImage(uri) }
                pendingImagesState.value = emptyList()
                if (!proactive) {
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
                    ),
                )
                core.data.characters.upsert(result.updatedCharacter)
                quickRepliesState.value = result.quickReplies
                statusState.value = ""
            } catch (error: Exception) {
                streamingState.value = null
                statusState.value = ""
                eventsState.tryEmit(UiEvent("回复失败：${error.message ?: error}。你的消息已保留，可稍后重试。"))
            } finally {
                sendingState.value = false
            }
        }
    }

    /** Removes [message] and everything after it, then re-sends [message]'s text. */
    fun editAndResend(messageId: String, newText: String) {
        val id = activeIdState.value ?: return
        viewModelScope.launch {
            val history = core.data.chat.getMessages(id)
            val index = history.indexOfFirst { it.id == messageId }
            if (index < 0) return@launch
            core.data.chat.replace(id, history.take(index))
            send(newText)
        }
    }

    /** Removes the assistant [messageId] and after, then regenerates. */
    fun regenerate(messageId: String) {
        val id = activeIdState.value ?: return
        viewModelScope.launch {
            val history = core.data.chat.getMessages(id)
            val index = history.indexOfFirst { it.id == messageId }
            if (index < 0) return@launch
            val kept = history.take(index)
            core.data.chat.replace(id, kept)
            val lastUser = kept.lastOrNull { it.role == Role.User } ?: return@launch
            core.data.chat.replace(id, kept.dropLast(1))
            send(lastUser.content)
        }
    }

    // ---------------------------------------------------------------- settings

    fun saveSettings(newConfig: AppConfig, apiKey: String?) {
        viewModelScope.launch {
            core.data.config.update(newConfig)
            if (!apiKey.isNullOrBlank()) core.secrets.setApiKey(apiKey)
        }
    }

    fun setTheme(themeId: String) {
        viewModelScope.launch { core.data.config.update(core.currentConfig().copy(activeTheme = themeId)) }
    }

    fun testReminder() = core.scheduleTestReminder()

    fun hasApiKey(): Boolean = !core.secrets.getApiKey().isNullOrBlank()

    private val testResultState = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = testResultState

    fun testApiConnection(config: AppConfig) {
        viewModelScope.launch {
            testResultState.value = "测试中…"
            val key = core.secrets.getApiKey()
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

    private val explicitIdle = MutableStateFlow(System.currentTimeMillis())

    private suspend fun proactiveLoop() {
        while (true) {
            delay(15_000)
            val cfg = runCatching { core.currentConfig() }.getOrNull() ?: continue
            if (!cfg.proactiveEnabled) continue
            if (sendingState.value) continue
            if (core.secrets.getApiKey().isNullOrBlank()) continue
            val id = activeIdState.value ?: continue
            if (System.currentTimeMillis() - explicitIdle.value < 60_000) continue
            val character = core.data.characters.get(id) ?: continue
            val messages = core.data.chat.getMessages(id)
            if (messages.isEmpty()) {
                submitTurn("", proactive = true)
                explicitIdle.value = System.currentTimeMillis()
            }
        }
    }

    /** Keeps [explicitIdle] fresh when the user interacts with the composer. */
    fun noteUserActivity() {
        explicitIdle.value = System.currentTimeMillis()
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
