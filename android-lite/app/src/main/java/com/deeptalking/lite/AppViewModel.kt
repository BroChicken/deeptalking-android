package com.deeptalking.lite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

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

    val messages: StateFlow<List<ChatMessage>> = combineMessages()

    private fun combineMessages(): StateFlow<List<ChatMessage>> {
        val flow = kotlinx.coroutines.flow.combine(storedMessages, streamingState) { stored, streaming ->
            if (streaming == null) stored
            else stored + ChatMessage(
                id = "streaming",
                role = Role.Assistant,
                content = streaming,
                timestamp = null,
                isLoading = true,
            )
        }
        return flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    private val quickRepliesState = MutableStateFlow<List<String>>(emptyList())
    val quickReplies: StateFlow<List<String>> = quickRepliesState

    private val sendingState = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = sendingState

    private val statusState = MutableStateFlow("")
    val status: StateFlow<String> = statusState

    init {
        viewModelScope.launch {
            core.data.characters.observeAll().collect { list ->
                if (activeIdState.value == null && list.isNotEmpty()) {
                    activeIdState.value = list.first().id
                }
            }
        }
    }

    fun select(id: String) {
        if (activeIdState.value != id) {
            activeIdState.value = id
            quickRepliesState.value = emptyList()
            streamingState.value = null
        }
    }

    fun createCharacter(name: String, emoji: String) {
        viewModelScope.launch {
            val character = Character(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "新角色" },
                emoji = emoji.ifBlank { "🙂" },
                createdAt = Instant.now().toString(),
            )
            core.data.characters.upsert(character)
            activeIdState.value = character.id
        }
    }

    fun deleteCharacter(id: String) {
        viewModelScope.launch {
            core.data.characters.delete(id)
            core.data.chat.clear(id)
            if (activeIdState.value == id) activeIdState.value = null
        }
    }

    fun send(text: String) = submitTurn(text, proactive = false)

    /** Manual "主动开口": generate an unprompted turn for the active character. */
    fun promptProactive() = submitTurn("", proactive = true)

    private fun submitTurn(text: String, proactive: Boolean) {
        val id = activeIdState.value ?: return
        if ((!proactive && text.isBlank()) || sendingState.value) return
        viewModelScope.launch {
            sendingState.value = true
            statusState.value = "思考中…"
            try {
                val character = core.data.characters.get(id) ?: return@launch
                if (!proactive) {
                    core.data.chat.append(
                        id,
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = Role.User,
                            content = text.trim(),
                            timestamp = Instant.now().toString(),
                        ),
                    )
                }
                // Re-read persisted history AFTER the user append so the model sees it.
                val history = core.data.chat.getMessages(id)
                val cfg = core.currentConfig()
                statusState.value = "生成中…"
                streamingState.value = ""

                val orchestrator = core.createOrchestrator(cfg) { updated ->
                    // Background tasks finished and mutated the character; persist it.
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
                statusState.value = "失败：" + (error.message ?: error.toString())
            } finally {
                sendingState.value = false
            }
        }
    }

    fun saveSettings(newConfig: AppConfig, apiKey: String?) {
        viewModelScope.launch {
            core.data.config.update(newConfig)
            if (!apiKey.isNullOrBlank()) core.secrets.setApiKey(apiKey)
        }
    }

    fun testReminder() = core.scheduleTestReminder()

    fun hasApiKey(): Boolean = !core.secrets.getApiKey().isNullOrBlank()
}
