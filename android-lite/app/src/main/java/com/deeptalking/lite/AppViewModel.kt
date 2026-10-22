package com.deeptalking.lite

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.CacheStats
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.DynamicStateMeta
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.RequestMetric
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.model.TASK_CHAT_MAIN
import com.deeptalking.core.model.TASK_CHAT_WORKER
import com.deeptalking.core.model.TTS_BACKEND_REMOTE
import com.deeptalking.core.model.TtsPhase
import com.deeptalking.core.model.withStaticFieldTimestamps
import com.deeptalking.core.security.BackupCipher
import com.deeptalking.core.model.SpeechSegment
import com.deeptalking.core.model.extractSpeechSegments
import com.deeptalking.core.model.extractSpeechText
import com.deeptalking.core.model.extractSpeechTextFromSegment
import com.deeptalking.core.model.convergeLorebookCapacity
import com.deeptalking.core.model.changedStaticProfileKeys
import com.deeptalking.core.model.mergeCharacterChanges
import com.deeptalking.core.model.mergeMemberCardEdits
import com.deeptalking.core.model.mergeStaticProfile
import com.deeptalking.core.model.mergeTodos
import com.deeptalking.core.model.restoreArchivedLorebookEntry
import com.deeptalking.core.model.trimMetricsPerTaskType
import com.deeptalking.domain.agent.HotTopicCounters
import com.deeptalking.domain.agent.HotTopicPolicy
import com.deeptalking.domain.agent.OrchestratorResult
import com.deeptalking.domain.agent.background.MemoryTaskKind
import com.deeptalking.domain.agent.prompts.buildShortTermBlock
import com.deeptalking.domain.memory.TodoPolicy
import com.deeptalking.domain.memory.archiveToFit
import com.deeptalking.domain.memory.hasValidPushConfirmation
import com.deeptalking.domain.memory.lorebookOverflow
import com.deeptalking.domain.memory.promisePushDecision
import com.deeptalking.engine.ondevice.TokenUsage
import com.deeptalking.engine.ondevice.TtsSpeaker
import com.deeptalking.engine.ondevice.TtsTurn
import com.deeptalking.engine.remote.RemoteTtsProbe
import com.deeptalking.engine.remote.RemoteVoiceInfo
import com.deeptalking.engine.remote.RemoteVoiceUpload
import com.deeptalking.feature.characters.CharacterParity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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

    /**
     * Rolling per-request metrics, read straight from the independent metrics store
     * (`:core:data` `MetricsRepository`, its own DataStore file). `AppConfig.requestMetrics`
     * is only a legacy backup field now, so the panels must not read it.
     */
    val metrics: StateFlow<List<RequestMetric>> =
        core.data.metrics.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** A failed turn worker waiting for the user's red-bar retry / dialog decision. */
    private val workerFailureState = MutableStateFlow<WorkerFailureUi?>(null)
    val workerFailure: StateFlow<WorkerFailureUi?> = workerFailureState
    private val workerRetryState = MutableStateFlow(false)
    val workerRetryInProgress: StateFlow<Boolean> = workerRetryState
    private val workerRetryCharacterState = MutableStateFlow<String?>(null)
    val workerRetryCharacterId: StateFlow<String?> = workerRetryCharacterState
    private var workerRetryJob: Job? = null
    private var workerRetryToken = 0L
    /** Bumped whenever a character starts a new turn; stale retries must not write back. */
    private val turnGenerations = mutableMapOf<String, Long>()

    private fun bumpTurnGeneration(id: String): Long {
        val next = (turnGenerations[id] ?: 0L) + 1L
        turnGenerations[id] = next
        return next
    }

    private fun turnGeneration(id: String): Long = turnGenerations[id] ?: 0L

    private val memoryRetryPromptState = MutableStateFlow<MemoryRetryUi?>(null)
    val memoryRetryPrompt: StateFlow<MemoryRetryUi?> = memoryRetryPromptState
    private val memoryRetryQueue = ArrayDeque<MemoryRetryUi>()
    private var memoryPromptsRestored = false

    private fun enqueueMemoryRetry(prompt: MemoryRetryUi) {
        val current = memoryRetryPromptState.value
        if (current?.let { it.kind == prompt.kind && it.characterId == prompt.characterId } == true) return
        if (memoryRetryQueue.any { it.kind == prompt.kind && it.characterId == prompt.characterId }) return
        if (current == null) {
            memoryRetryPromptState.value = prompt
        } else {
            memoryRetryQueue.addLast(prompt)
        }
    }

    private fun showNextMemoryRetry() {
        if (memoryRetryPromptState.value != null) return
        memoryRetryPromptState.value = memoryRetryQueue.removeFirstOrNull()
    }

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

    /**
     * Non-null when a world-book pass failed repeatedly and the user must decide whether to
     * retry the held batch or drop it. The batch lives in memory in `NativeCore`, so this
     * dialog only ever appears for a failure that happened in this run.
     */
    private val lorebookRetryState = MutableStateFlow<LorebookRetryPrompt?>(null)
    val lorebookRetryPrompt: StateFlow<LorebookRetryPrompt?> = lorebookRetryState

    data class LorebookRetryPrompt(val characterId: String, val characterName: String, val reason: String)

    private val media = MediaStore(DeepTalkingApp.core.appContext)

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

    // --------------------------------------------------------- memory cleanup

    /** Non-null when the memory cleanup dialog should show. */
    private val memoryCleanupState = MutableStateFlow<NativeCore.MemoryCleanupTarget?>(null)
    val memoryCleanupPrompt: StateFlow<NativeCore.MemoryCleanupTarget?> = memoryCleanupState

    /** True while the short-term drain runs (progress dialog). */
    private val memoryCleanupProgressState = MutableStateFlow(false)
    val memoryCleanupProgress: StateFlow<Boolean> = memoryCleanupProgressState

    /** True once the cleanup dialog was shown in this run (one prompt at a time). */
    private var memoryCleanupPromptedThisRun = false

    /** Non-null when the oversized-window catch-up should be confirmed first. */
    private val memoryMigrationPromptState = MutableStateFlow<NativeCore.OversizedWindowTarget?>(null)
    val memoryMigrationPrompt: StateFlow<NativeCore.OversizedWindowTarget?> = memoryMigrationPromptState

    /** True once the window prompt was answered/dismissed in this app run ("稍后"). */
    private var memoryMigrationAskedThisRun = false

    /** Non-null while the one-time oversized-window catch-up runs (blocking overlay). */
    private val memoryMigrationState = MutableStateFlow<MemoryMigrationUi?>(null)
    val memoryMigration: StateFlow<MemoryMigrationUi?> = memoryMigrationState

    /**
     * Progress of the oversized-window catch-up. [error] non-blank means it stopped
     * early (already-folded batches are saved, so a retry resumes).
     */
    data class MemoryMigrationUi(
        val batches: Int = 0,
        val remaining: Int = 0,
        val running: Boolean = true,
        val error: String = "",
    )

    /** Non-null when the one-time lorebook rebuild should be confirmed first. */
    private val lorebookRebuildPromptState = MutableStateFlow<Int?>(null)
    val lorebookRebuildPrompt: StateFlow<Int?> = lorebookRebuildPromptState

    private var lorebookRebuildAskedThisRun = false

    /** Non-null while the one-time lorebook rebuild runs (progress dialog). */
    private val lorebookRebuildState = MutableStateFlow<LorebookRebuildUi?>(null)
    val lorebookRebuild: StateFlow<LorebookRebuildUi?> = lorebookRebuildState

    /** Progress of the lorebook rebuild ([error] non-blank = stopped, progress kept). */
    data class LorebookRebuildUi(
        val done: Int = 0,
        val remaining: Int = 0,
        val running: Boolean = true,
        val error: String = "",
    )

    /**
     * Asks once, when short-term memory is over its cap (the analysis task could not
     * drain it) or when images sit outside the retention window. Silent while a
     * confirmed cleanup is still running: one confirmation is enough, the app finishes
     * the job on its own and only asks again if it piles up later.
     */
    private suspend fun checkMemoryCleanupPrompt() {
        if (memoryCleanupPromptedThisRun) return
        if (core.data.settings.getBoolean(MEMORY_CLEANUP_SKIP_KEY)) return
        if (core.data.settings.getBoolean(MEMORY_CLEANUP_PENDING_KEY)) return
        val target = runCatching { core.memoryCleanupTarget() }.getOrNull() ?: return
        if (!target.overLimit && target.images <= 0) return
        memoryCleanupPromptedThisRun = true
        memoryCleanupState.value = target
    }

    fun dismissMemoryCleanup() {
        memoryCleanupState.value = null
        askLorebookRebuildOnce()
    }

    fun skipMemoryCleanupForever() {
        memoryCleanupState.value = null
        viewModelScope.launch { core.data.settings.putBoolean(MEMORY_CLEANUP_SKIP_KEY, true) }
        askLorebookRebuildOnce()
    }

    // ------------------------------------------------------ world-book retry

    /** "重试": re-runs the held world-book batch on the freshest row. */
    fun retryLorebookNow() {
        val target = lorebookRetryState.value ?: return
        lorebookRetryState.value = null
        viewModelScope.launch {
            val cfg = core.currentConfig()
            val failure = runCatching { core.retryPendingLorebook(cfg, target.characterId) }
                .getOrElse { it.message ?: "整理失败" }
            if (failure.isNotBlank()) {
                eventsState.tryEmit(UiEvent("世界书整理仍未成功：$failure"))
                // The batch is still held, so the dialog comes back with the new reason and
                // the user can either retry again or drop it — it must never sit in memory
                // with no way to resolve it.
                if (core.hasPendingLorebookRetry(target.characterId)) {
                    lorebookRetryState.value = target.copy(reason = failure)
                }
            } else {
                eventsState.tryEmit(UiEvent("世界书已整理完成"))
            }
        }
    }

    // ------------------------------------------------------- turn-worker retry

    /** "先这样": keep the red bar but stop asking. A new turn clears both. */
    fun dismissWorkerFailureDialog() {
        workerFailureState.value = workerFailureState.value?.copy(showDialog = false)
    }

    /** Red bar / "再试一次": rerun only the turn worker, never the assistant reply. */
    fun retryWorkerTurn() {
        val failure = workerFailureState.value ?: return
        if (workerRetryJob?.isActive == true) return
        val generation = turnGeneration(failure.characterId)
        val token = ++workerRetryToken
        workerRetryState.value = true
        workerRetryCharacterState.value = failure.characterId
        workerFailureState.value = failure.copy(showDialog = false)
        val job = viewModelScope.launch {
            try {
                val current = core.data.characters.get(failure.characterId) ?: return@launch
                if (!isSameWorkerTurn(failure, generation)) return@launch
                val cfg = core.currentConfig()
                val orchestrator = core.createOrchestrator(
                    cfg,
                    onStatus = { if (isActiveWorkerTurn(failure, generation)) statusState.value = it },
                )
                val result = orchestrator.retryWorker(
                    character = current,
                    userText = failure.userText,
                    reply = failure.reply,
                    replyId = failure.replyId,
                    proactive = failure.proactive,
                    onStatus = { if (isActiveWorkerTurn(failure, generation)) statusState.value = it },
                )
                if (!isSameWorkerTurn(failure, generation)) return@launch
                if (result.ok) {
                    writeCharacterChanges(current, result.character.copy(workerIssues = result.issues))
                    if (isActiveWorkerTurn(failure, generation) && result.quickReplies.isNotEmpty()) {
                        quickRepliesState.value = result.quickReplies
                    }
                    if (workerFailureState.value?.replyId == failure.replyId) {
                        workerFailureState.value = null
                    }
                    if (isActiveWorkerTurn(failure, generation)) {
                        statusState.value = ""
                        eventsState.tryEmit(UiEvent("整理已补上"))
                    }
                } else {
                    if (workerFailureState.value?.replyId == failure.replyId) {
                        workerFailureState.value = failure.copy(
                            reason = result.failure.ifBlank { "整理失败" },
                            showDialog = false,
                        )
                    }
                    if (isActiveWorkerTurn(failure, generation)) statusState.value = ""
                }
            } catch (error: Exception) {
                if (error !is CancellationException && isSameWorkerTurn(failure, generation)) {
                    if (workerFailureState.value?.replyId == failure.replyId) {
                        workerFailureState.value = failure.copy(reason = "请求失败", showDialog = false)
                    }
                    if (isActiveWorkerTurn(failure, generation)) statusState.value = ""
                }
            } finally {
                if (workerRetryToken == token) {
                    workerRetryState.value = false
                    workerRetryCharacterState.value = null
                    workerRetryJob = null
                }
            }
        }
        workerRetryJob = job
    }

    /** True while [failure] is still the same turn and its reply is still the latest answer. */
    private suspend fun isSameWorkerTurn(failure: WorkerFailureUi, generation: Long): Boolean {
        if (turnGeneration(failure.characterId) != generation) return false
        val messages = core.data.chat.getMessages(failure.characterId)
        val latestReply = messages.lastOrNull { it.role == Role.Assistant && it.content.isNotBlank() }
        return latestReply?.id == failure.assistantRowId
    }

    private fun isActiveWorkerTurn(failure: WorkerFailureUi, generation: Long): Boolean =
        activeIdState.value == failure.characterId && turnGeneration(failure.characterId) == generation

    /** "忽略" on extraction/analysis: stop prompting; the background backoff continues. */
    fun dismissMemoryRetry() {
        val prompt = memoryRetryPromptState.value ?: return
        memoryRetryPromptState.value = null
        viewModelScope.launch {
            runCatching { core.markMemoryRetryAcked(prompt.characterId, prompt.kind) }
        }
        showNextMemoryRetry()
    }

    /** "重试" on extraction/analysis: run one pass immediately, ignoring the backoff. */
    fun retryMemoryNow() {
        val prompt = memoryRetryPromptState.value ?: return
        memoryRetryPromptState.value = null
        viewModelScope.launch {
            // Answering "重试" also acknowledges this streak; a failed retry re-opens the
            // prompt once, but later background failures must not ask again.
            runCatching { core.markMemoryRetryAcked(prompt.characterId, prompt.kind) }
            val cfg = runCatching { core.currentConfig() }.getOrNull()
            if (cfg == null) {
                memoryRetryPromptState.value = prompt.copy(reason = "读取配置失败")
                return@launch
            }
            val outcome = runCatching {
                core.retryPendingMemory(cfg, prompt.characterId, prompt.kind)
            }.getOrElse { MemoryRetryOutcome(ok = false, reason = it.message ?: "请求失败") }
            if (outcome.ok) {
                eventsState.tryEmit(
                    UiEvent(
                        if (outcome.noop) outcome.reason else "${prompt.title}已恢复",
                    ),
                )
                showNextMemoryRetry()
            } else {
                // The batch is still there; keep the prompt open with the new short reason.
                memoryRetryPromptState.value = prompt.copy(reason = outcome.reason.take(80))
                eventsState.tryEmit(UiEvent("${prompt.title}仍未成功"))
            }
        }
    }

    /** "忽略": the user decided not to retry — drop the held batch (and record that). */
    fun dismissLorebookRetry() {
        val target = lorebookRetryState.value ?: return
        lorebookRetryState.value = null
        viewModelScope.launch {
            val cfg = core.currentConfig()
            runCatching { core.discardPendingLorebook(cfg, target.characterId) }
                .onFailure { DeepTalkingApp.recordError(it) }
        }
    }

    /**
     * App-open check: a window far above the normal size is a legacy backlog — ask first.
     *
     * Confirming folds the whole backlog into memory, oldest batch first, before the app
     * is usable (nothing is dropped; see `migrateOversizedWindow`). "稍后" means don't ask
     * again in this run — the next launch asks again. "以后不再询问" persists a key and
     * leaves the backlog to the per-turn catch-up path (which still runs, just silently).
     */
    private suspend fun checkOversizedWindowMigration() {
        if (memoryMigrationAskedThisRun) return
        memoryMigrationAskedThisRun = true
        if (core.data.settings.getBoolean(MEMORY_WINDOW_MIGRATION_SKIP_KEY)) return
        if (memoryMigrationState.value != null) return
        val target = runCatching { core.oversizedWindowTarget() }.getOrNull() ?: return
        if (target.characters <= 0) return
        memoryMigrationPromptState.value = target
    }

    fun confirmMemoryMigration() {
        memoryMigrationPromptState.value = null
        startMemoryMigration()
    }

    /** "稍后": don't ask again in this run; the next launch asks again. */
    fun dismissMemoryMigrationPrompt() {
        memoryMigrationPromptState.value = null
        continueStartupPromptsLater()
    }

    fun skipMemoryWindowMigrationForever() {
        memoryMigrationPromptState.value = null
        viewModelScope.launch { core.data.settings.putBoolean(MEMORY_WINDOW_MIGRATION_SKIP_KEY, true) }
        continueStartupPromptsLater()
    }

    private fun startMemoryMigration() {
        memoryMigrationState.value = MemoryMigrationUi()
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull()
            if (cfg == null) {
                memoryMigrationState.value = MemoryMigrationUi(running = false, error = "读取配置失败")
                return@launch
            }
            val result = runCatching {
                core.migrateOversizedWindows(cfg) { done, remaining ->
                    memoryMigrationState.value = MemoryMigrationUi(batches = done, remaining = remaining)
                }
            }.getOrElse { NativeCore.MigrationResult(0, it.message ?: "整理失败") }
            if (result.error.isBlank()) {
                memoryMigrationState.value = null
                continueStartupPromptsLater()
            } else {
                memoryMigrationState.value = MemoryMigrationUi(
                    batches = result.batches,
                    running = false,
                    error = result.error,
                )
            }
        }
    }

    /** Retries a catch-up that stopped early; saved progress is resumed, not repeated. */
    fun retryMemoryMigration() {
        memoryMigrationState.value = null
        startMemoryMigration()
    }

    fun dismissMemoryMigration() {
        memoryMigrationState.value = null
        continueStartupPromptsLater()
    }

    /**
     * Walks the one-time startup prompts one at a time: the cleanup dialog (when it has
     * something to do), then the world-book rebuild. Called at launch and again whenever
     * one of those prompts is resolved, so skipping one hands over to the next instead of
     * two dialogs stacking on top of each other.
     */
    private suspend fun continueStartupPrompts() {
        if (memoryCleanupState.value == null && !memoryCleanupProgressState.value) {
            runCatching { checkMemoryCleanupPrompt() }.onFailure { DeepTalkingApp.recordError(it) }
        }
        askLorebookRebuildOnce()
    }

    private fun continueStartupPromptsLater() {
        viewModelScope.launch { continueStartupPrompts() }
    }

    /**
     * App-open check: oversized AI lorebook entries (an older version grew them into
     * hundreds of characters) are rewritten once into one-concept entries, so the world
     * book stops eating the injection budget. Asked again next launch when dismissed.
     */
    private fun askLorebookRebuildOnce() {
        if (lorebookRebuildAskedThisRun) return
        // One dialog at a time: hand over only once every earlier prompt is gone. The flag
        // is set *after* these gates so a deferred ask still happens later (the paths that
        // can defer it all funnel back through `continueStartupPrompts`).
        if (memoryCleanupState.value != null || memoryCleanupProgressState.value) return
        if (memoryMigrationPromptState.value != null || memoryMigrationState.value != null) return
        if (fieldMigrationState.value != null || lorebookMigrationState.value != null) return
        if (avatarRepairPromptState.value != null || avatarRepairProgressState.value != null) return
        lorebookRebuildAskedThisRun = true
        viewModelScope.launch {
            if (lorebookRebuildState.value != null) return@launch
            val count = runCatching { core.oversizedLorebookCount() }.getOrNull() ?: return@launch
            if (count <= 0) return@launch
            val cfg = runCatching { core.currentConfig() }.getOrNull() ?: return@launch
            // Without a key the rewrite cannot run, so don't ask (the entries stay as they
            // are and simply eat the injection budget until a key is configured).
            if (!core.hasApiKey(cfg.apiPlatform)) return@launch
            lorebookRebuildPromptState.value = count
        }
    }

    fun confirmLorebookRebuild() {
        lorebookRebuildPromptState.value = null
        lorebookRebuildState.value = LorebookRebuildUi()
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull()
            if (cfg == null) {
                lorebookRebuildState.value = LorebookRebuildUi(running = false, error = "读取配置失败")
                return@launch
            }
            val result = runCatching {
                core.rebuildOversizedLorebooks(cfg) { done, remaining ->
                    lorebookRebuildState.value = LorebookRebuildUi(done = done, remaining = remaining)
                }
            }.getOrElse { NativeCore.MigrationResult(0, it.message ?: "整理失败") }
            lorebookRebuildState.value = if (result.error.isBlank()) {
                null
            } else {
                LorebookRebuildUi(done = result.batches, running = false, error = result.error)
            }
        }
    }

    /** "稍后": don't ask again in this run; the next launch asks again. */
    fun dismissLorebookRebuildPrompt() {
        lorebookRebuildPromptState.value = null
    }

    fun retryLorebookRebuild() {
        lorebookRebuildState.value = null
        confirmLorebookRebuild()
    }

    fun dismissLorebookRebuild() {
        lorebookRebuildState.value = null
    }

    /**
     * Confirms the cleanup: stale images are pruned immediately and short-term is
     * drained in bounded passes. Nothing is truncated — the backlog is *processed* into
     * long-term memory. If work is left over, the pending flag stays set and the app
     * keeps consuming it quietly on later launches and idle moments until short-term
     * settles at its low-water mark, so the dialog is never shown twice for one backlog.
     */
    fun runMemoryCleanup() {
        memoryCleanupState.value = null
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull()
            val canAnalyze = cfg != null && core.hasApiKey(cfg.apiPlatform)
            if (canAnalyze) core.data.settings.putBoolean(MEMORY_CLEANUP_PENDING_KEY, true)
            memoryCleanupProgressState.value = true
            val removedImages = runCatching { core.pruneOldMessageImages() }.getOrDefault(0)
            val drain = if (canAnalyze) {
                runCatching { core.drainShortTermBacklog(cfg!!) }
                    .getOrElse { NativeCore.MemoryDrainResult(0, it.message ?: "整理失败") }
            } else {
                NativeCore.MemoryDrainResult(0, "")
            }
            memoryCleanupProgressState.value = false
            val remaining = runCatching { core.memoryCleanupTarget() }.getOrNull()
            val stillPending = canAnalyze && remaining?.hasWork == true && drain.passes > 0
            if (canAnalyze) core.data.settings.putBoolean(MEMORY_CLEANUP_PENDING_KEY, stillPending)
            eventsState.tryEmit(
                UiEvent(cleanupSummary(removedImages, drain, remaining, canAnalyze, stillPending)),
            )
            // Hand over to the next startup prompt (world-book rewrite, …): the chain used
            // to dead-end here, so confirming the cleanup was the last thing that ever ran.
            continueStartupPrompts()
        }
    }

    /**
     * One quiet chunk of a confirmed cleanup. Returns true while the backlog is still
     * being consumed. Stops (and clears the flag) as soon as a chunk makes no progress,
     * so a broken API cannot spin — the reason is reported once.
     */
    private suspend fun runMemoryCleanupChunk(maxBatches: Int): Boolean {
        if (!core.data.settings.getBoolean(MEMORY_CLEANUP_PENDING_KEY)) return false
        if (sendingState.value) return false
        val cfg = runCatching { core.currentConfig() }.getOrNull()
        if (cfg == null || !core.hasApiKey(cfg.apiPlatform)) return false
        val drain = runCatching { core.drainShortTermBacklog(cfg, maxBatches) }
            .getOrElse { NativeCore.MemoryDrainResult(0, it.message ?: "整理失败") }
        val remaining = runCatching { core.memoryCleanupTarget() }.getOrNull()
        if (remaining?.hasWork == true && drain.passes > 0) {
            return true
        }
        core.data.settings.putBoolean(MEMORY_CLEANUP_PENDING_KEY, false)
        when {
            drain.lastAnalysisError.isNotBlank() ->
                eventsState.tryEmit(UiEvent("短期记忆整理失败：${drain.lastAnalysisError}"))
            drain.passes > 0 ->
                eventsState.tryEmit(UiEvent("短期记忆已整理到 ${remaining?.shortTermTotal ?: 0} 条"))
        }
        return false
    }

    /** Keeps a confirmed cleanup going in the background (one batch per minute while idle). */
    private suspend fun memoryCleanupLoop() {
        while (true) {
            delay(60_000)
            runCatching { runMemoryCleanupChunk(1) }.onFailure { DeepTalkingApp.recordError(it) }
        }
    }

    private fun cleanupSummary(
        removedImages: Int,
        drain: NativeCore.MemoryDrainResult,
        remaining: NativeCore.MemoryCleanupTarget?,
        canAnalyze: Boolean,
        stillPending: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        if (removedImages > 0) parts += "清理了 $removedImages 张旧图片"
        if (drain.passes > 0) parts += "整理了 ${drain.passes} 批短期记忆"
        if (!canAnalyze) {
            parts += "短期记忆需要 API Key 才能整理"
        } else if (drain.passes == 0 && drain.lastAnalysisError.isNotBlank()) {
            parts += "短期记忆整理失败：${drain.lastAnalysisError}"
        }
        if (parts.isEmpty()) parts += "没有需要清理的内容"
        val tail = when {
            stillPending -> "，还有 ${remaining?.shortTermPending ?: 0} 条会自动继续整理"
            canAnalyze && remaining != null -> "，短期记忆现有 ${remaining.shortTermTotal} 条"
            else -> ""
        }
        return parts.joinToString("，") + tail
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
        askLorebookRebuildOnce()
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
        askLorebookRebuildOnce()
    }

    // ---------------------------------------------------------------- selection

    fun select(id: String) {
        if (activeIdState.value != id) {
            activeIdState.value = id
            quickRepliesState.value = emptyList()
            // Keep worker failure/retry for the character it belongs to: the red bar and
            // dialog are filtered by `characterId`, so switching away must not lose it.
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
            // Legacy `createCharacter` cleans every generated field through
            // `cleanFieldValue(parseRelativeText(...))` (and `normalizeUserAddress`
            // for userAddress, `sanitizeDynamicStateField` for dynamic state).
            val cleaned = draft?.let {
                CharacterParity.normalizeEditedDraft(
                    Character(id = "", staticProfile = it.staticProfile, dynamicState = it.dynamicState),
                )
            }
            val cleanProfile = cleaned?.staticProfile ?: StaticProfile()
            val profile = cleanProfile.copy(
                personality = personality,
                background = background,
            )
            val character = Character(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "新角色" },
                emoji = emoji.ifBlank { "🙂" },
                staticProfile = profile,
                dynamicState = cleaned?.dynamicState ?: com.deeptalking.core.model.DynamicState(),
                lorebook = draft?.lorebook.orEmpty(),
                createdAt = now,
                // Fresh entities are already on the current schema and hold no
                // legacy world-layer prose, so they must not re-trigger migrations.
                fieldsMigrationVersion = "1.2.0",
                lorebookMigratedAt = now,
            )
            core.data.characters.upsert(character.convergeLorebookCapacity())
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
            core.data.characters.upsert(group.convergeLorebookCapacity())
            activeIdState.value = group.id
        }
    }

    fun updateCharacter(character: Character) {
        // Whole-object writes stay for legacy call sites; route them through the per-id lock
        // so they cannot interleave with a scoped patch.
        patchCharacter(character.id) { character }
    }

    /**
     * Applies [transform] to the **live** persisted row (not to a caller-held snapshot) and
     * writes it back. A card editor holds a copy taken when it opened; writing that copy
     * back would revert whatever a background task wrote in the meantime (analyzed memories,
     * dynamic state), so every partial edit from the UI goes through here instead.
     *
     * The per-id lock lives in [com.deeptalking.core.data.CharacterRepository.mutate].
     */
    private fun patchCharacter(characterId: String, transform: (Character) -> Character) {
        viewModelScope.launch { core.data.characters.mutate(characterId, transform) }
    }

    /**
     * Persists only what [after] changed relative to [before], merged onto the freshest
     * stored row (see `mergeCharacterChanges`). A whole-row write would roll back anything
     * a concurrent writer changed while this writer was working — a world-book entry, a
     * memory, the user's own edit — which is exactly the bug the scoped write fixes.
     *
     * Dynamic state is applied per key and the matching `dynamicStateMeta` entries get a
     * fresh `updatedAt`, so the next writer can tell *when* each field last moved.
     */
    private suspend fun writeCharacterChanges(before: Character, after: Character) {
        val now = Instant.now().toString()
        val keys = changedDynamicKeys(before.dynamicState, after.dynamicState)
        val sharedKeys = changedDynamicKeys(before.groupSharedDynamic, after.groupSharedDynamic)
        core.data.characters.mutate(after.id) { live ->
            var merged = mergeCharacterChanges(before, after, live)
            if (after.isGroup && sharedKeys.isNotEmpty()) {
                merged = merged.copy(
                    groupSharedDynamic = mergeDynamicState(merged.groupSharedDynamic, after.groupSharedDynamic, sharedKeys),
                )
            }
            merged = merged.copy(
                dynamicStateMeta = mergeDynamicStateMeta(
                    live = live.dynamicStateMeta,
                    source = merged.dynamicStateMeta,
                    keys = emptySet(),
                    now = now,
                ),
            )
            withDynamicStateMeta(merged, keys + sharedKeys, now)
        }
    }

    /** Stamps [keys] on a whole character; other keys keep their existing/turned value. */
    private fun withDynamicStateMeta(character: Character, keys: Set<String>, now: String): Character =
        character.copy(dynamicStateMeta = mergeDynamicStateMeta(character.dynamicStateMeta, emptyMap(), keys, now))

    /**
     * Saves the character-card editor. Only the fields the editor owns are copied onto the
     * live row: the draft's memory windows, stickers, scene state and world book may be
     * older than the store (background tasks and the per-entry world-book patches write
     * them), and a whole-row write silently rolled those back — including the user's own
     * world-book edits made in the same dialog.
     *
     * Dynamic state is merged **per changed key**; `dynamicStateMeta` is only ever added to
     * (never cleared), and each changed key is stamped with the save time.
     */
    fun saveCharacterEdit(draft: Character) {
        val now = Instant.now().toString()
        viewModelScope.launch {
            core.data.characters.mutate(draft.id) { live ->
                val next = live.copy(
                    name = draft.name,
                    emoji = draft.emoji,
                    description = draft.description,
                    interactionRules = draft.interactionRules,
                    staticProfile = draft.staticProfile,
                    // 待办是独立的 `Character.todos`：编辑页拥有它（用户可完成/取消/删除），
                    // 与记忆窗口无关，随卡片一起落盘。
                    todos = draft.todos,
                    // Background tasks own each member's world book; the card editor does not
                    // expose member books, so keep the live lists — a whole-list write would
                    // roll back an archive/consolidation that ran while the dialog was open.
                    // 成员按 **非空 id** 定位：id 为空（历史数据）时无法对应到实时行，原样保留
                    // 草稿——否则每个空 id 成员都会匹配到 `members[0]`，把第一个成员的世界书
                    // 复制到所有人身上。
                    members = draft.members.map { member ->
                        val liveMember = member.id.takeIf { it.isNotBlank() }
                            ?.let { id -> live.members.firstOrNull { it.id == id } }
                            ?: return@map member
                        val keys = changedDynamicKeys(liveMember.dynamicState, member.dynamicState)
                        member.copy(
                            lorebook = liveMember.lorebook,
                            lorebookArchive = liveMember.lorebookArchive,
                            dynamicState = mergeDynamicState(liveMember.dynamicState, member.dynamicState, keys),
                            dynamicStateMeta = mergeDynamicStateMeta(
                                live = liveMember.dynamicStateMeta,
                                source = member.dynamicStateMeta,
                                keys = keys,
                                now = now,
                            ),
                        )
                    },
                )
                if (draft.isGroup) {
                    val keys = changedDynamicKeys(live.groupSharedDynamic, draft.groupSharedDynamic)
                    next.copy(
                        groupSharedDynamic = mergeDynamicState(live.groupSharedDynamic, draft.groupSharedDynamic, keys),
                        dynamicStateMeta = mergeDynamicStateMeta(live.dynamicStateMeta, draft.dynamicStateMeta, keys, now),
                    )
                } else {
                    val keys = changedDynamicKeys(live.dynamicState, draft.dynamicState)
                    next.copy(
                        dynamicState = mergeDynamicState(live.dynamicState, draft.dynamicState, keys),
                        dynamicStateMeta = mergeDynamicStateMeta(live.dynamicStateMeta, draft.dynamicStateMeta, keys, now),
                    )
                }
            }
        }
    }

    /** Dynamic-state keys whose value differs between [before] and [after]. */
    private fun changedDynamicKeys(before: DynamicState, after: DynamicState): Set<String> = buildSet {
        if (before.currentSituation != after.currentSituation) add("currentSituation")
        if (before.currentLocation != after.currentLocation) add("currentLocation")
        if (before.currentMood != after.currentMood) add("currentMood")
        if (before.currentOccupation != after.currentOccupation) add("currentOccupation")
        if (before.currentGoal != after.currentGoal) add("currentGoal")
        if (before.currentTone != after.currentTone) add("currentTone")
    }

    /** Takes [source]'s values for [keys] only; every other field keeps [this]'s live value. */
    private fun mergeDynamicState(live: DynamicState, source: DynamicState, keys: Set<String>): DynamicState {
        if (keys.isEmpty()) return live
        return live.copy(
            currentSituation = if ("currentSituation" in keys) source.currentSituation else live.currentSituation,
            currentLocation = if ("currentLocation" in keys) source.currentLocation else live.currentLocation,
            currentMood = if ("currentMood" in keys) source.currentMood else live.currentMood,
            currentOccupation = if ("currentOccupation" in keys) source.currentOccupation else live.currentOccupation,
            currentGoal = if ("currentGoal" in keys) source.currentGoal else live.currentGoal,
            currentTone = if ("currentTone" in keys) source.currentTone else live.currentTone,
        )
    }

    /**
     * Add-only merge of the per-field bookkeeping: live entries are kept, the writer's
     * entries may fill gaps, and every key the writer changed is stamped with [now].
     */
    private fun mergeDynamicStateMeta(
        live: Map<String, DynamicStateMeta>,
        source: Map<String, DynamicStateMeta>,
        keys: Set<String>,
        now: String,
    ): Map<String, DynamicStateMeta> {
        if (keys.isEmpty() && source.isEmpty()) return live
        val merged = live.toMutableMap()
        merged.putAll(source)
        keys.forEach { merged[it] = DynamicStateMeta(updatedAt = now) }
        return merged
    }

    fun deleteCharacter(id: String) {
        if (workerFailureState.value?.characterId == id) {
            workerRetryJob?.cancel()
            workerRetryJob = null
            workerRetryToken++
            workerRetryState.value = false
            workerRetryCharacterState.value = null
            workerFailureState.value = null
        }
        turnGenerations.remove(id)
        viewModelScope.launch {
            core.data.characters.delete(id)
            core.data.chat.clear(id)
            if (activeIdState.value == id) activeIdState.value = null
        }
    }

    // ------------------------------------------------------------------ 待办

    /**
     * 自动清理超过保留期（[AppLimits.Todo.CLOSED_RETENTION_DAYS] 天）的已完成 / 已取消待办。
     * 待办与长期记忆无关（[Character.todos]）；列表本身也会防御性过滤，这里只是把数据收干净。
     */
    private suspend fun pruneClosedTodos() {
        core.data.characters.observeAll().first().forEach { character ->
            val pruned = TodoPolicy.pruneClosedTodos(character.todos)
            if (pruned.size != character.todos.size) {
                patchCharacter(character.id) { live -> live.copy(todos = TodoPolicy.pruneClosedTodos(live.todos)) }
            }
        }
    }

    /** Applies a transform to the current character and persists it. */
    fun editActiveCharacter(transform: (Character) -> Character) {
        val id = activeIdState.value ?: return
        viewModelScope.launch { core.data.characters.mutate(id, transform) }
    }

    /** Replaces a group member (by index) on [character]. */
    fun updateMember(character: Character, index: Int, member: GroupMember) {
        if (index !in character.members.indices) return
        patchCharacter(character.id) { live ->
            if (index !in live.members.indices) {
                live
            } else {
                live.copy(members = live.members.toMutableList().also { it[index] = member })
            }
        }
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
                put("fears", character.staticProfile.fears)
                put("background", character.staticProfile.background)
                put("speakingStyle", character.staticProfile.speakingStyle)
                put("language", character.staticProfile.language)
                put("userAddress", character.staticProfile.userAddress)
            }.toString()
            val prompt = buildString {
                append("将以下角色和近期对话升级为群组。原角色会由系统完整保留，因此绝不能在输出成员列表中再次生成原角色，也不要生成同名或明显重复的变体。")
                append("请列出所有近期对话中已出现、应成为固定成员的其他角色；若不足一人，再新增一名最适合当前剧情的成员。")
                append("返回JSON：{\"groupInfo\":{\"name\":\"群组名\",\"avatar\":\"emoji\",\"description\":\"群组前提（这群人是谁、为什么在一起，1-2 句；不要写世界观/地点/组织等世界层设定）\",\"scene\":\"场景\",\"interactionRules\":\"成员互动规则\"},")
                append("\"lorebook\":[{\"name\":\"条目名\",\"keywords\":[\"触发词\"],\"content\":\"命中后注入的世界层设定\",\"alwaysActive\":false}],")
                append("\"additionalMembers\":[{\"name\":\"\",\"avatar\":\"emoji\",\"gender\":\"\",\"age\":\"\",\"race\":\"\",\"appearance\":\"\",\"personality\":\"\",\"fears\":\"\",\"background\":\"\",\"speakingStyle\":\"\",\"language\":\"\",\"userAddress\":\"\",\"roleInGroup\":\"\",\"dynamicState\":{\"currentSituation\":\"\",\"currentLocation\":\"\",\"currentMood\":\"\",\"currentOccupation\":\"\",\"currentGoal\":\"\",\"currentTone\":\"\"}}]}。")
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
                // Legacy `generation.js:149`: the AI group avatar wins (`groupInfo.avatar ?? '👥'`).
                emoji = draft.emoji.ifBlank { "👥" },
                description = draft.description.ifBlank { character.description },
                interactionRules = draft.interactionRules.ifBlank { character.interactionRules },
                groupSharedDynamic = com.deeptalking.core.model.DynamicState(
                    currentSituation = character.dynamicState.currentSituation,
                    // Legacy `generation.js:152`: group scene wins, falling back to the
                    // original character's current location only when empty.
                    currentLocation = draft.scene.ifBlank { character.dynamicState.currentLocation },
                ),
                members = members,
                lorebook = mergedLorebook,
                fieldsMigrationVersion = "1.2.0",
                lorebookMigratedAt = now,
            )
            core.data.characters.upsert(group.convergeLorebookCapacity())
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

    /**
     * Regenerates exactly one field of the editor draft. Emoji keeps using the dedicated
     * avatar call; every other key is a static profile field. Nothing is persisted here.
     */
    fun regenerateCharacterField(
        character: Character,
        memberIndex: Int?,
        fieldKey: String,
        instruction: String,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull()
            if (cfg == null || core.secrets.getApiKey().isNullOrBlank()) {
                eventsState.tryEmit(UiEvent("请先在设置页配置 API Key"))
                onResult(null)
                return@launch
            }
            val member = memberIndex?.let { character.members.getOrNull(it) }
            val value = runCatching {
                if (fieldKey == "emoji") {
                    if (member != null) core.generateEmojiAvatar(cfg, member, character)
                    else core.generateEmojiAvatar(cfg, character)
                } else {
                    core.regenerateCharacterField(cfg, character, memberIndex, fieldKey, instruction)
                }
            }.getOrNull()
            if (value.isNullOrBlank()) {
                eventsState.tryEmit(UiEvent("字段生成失败，请重试"))
            }
            onResult(value)
        }
    }

    /**
     * World-book edits from the editor. Each one patches the live row: the entry the UI
     * shows is the entry that is stored (same id), consecutive edits cannot overwrite each
     * other, and a background consolidation that touched another entry survives.
     *
     * Capacity is judged **globally** (shared book + members): adding the entry archives the
     * oldest evictable entries when the book is full, and refuses (with a visible prompt)
     * when only manual/常驻 entries are left.
     */
    fun addLorebookEntry(characterId: String, entry: LorebookEntry) {
        patchCharacter(characterId) { live ->
            val withEntry = live.copy(lorebook = live.lorebook + entry)
            val overflow = lorebookOverflow(withEntry)
            if (overflow <= 0) {
                withEntry
            } else {
                val adjustment = archiveToFit(withEntry, overflow, excludeIds = setOf(entry.id))
                if (adjustment.unmet > 0) {
                    eventsState.tryEmit(UiEvent("世界书已满（其余条目为人工维护或常驻），无法新增；请先归档或删除一些条目"))
                    live
                } else {
                    emitArchiveNotice(adjustment.archived)
                    adjustment.character
                }
            }
        }
    }

    /** Soft-deletes a live entry into the archive; restorable from the editor. */
    fun archiveLorebookEntry(characterId: String, entryId: String) {
        patchCharacter(characterId) { live ->
            val entry = live.lorebook.firstOrNull { it.id == entryId } ?: return@patchCharacter live
            live.copy(
                lorebook = live.lorebook.filterNot { it.id == entryId },
                lorebookArchive = live.lorebookArchive + entry,
            )
        }
    }

    /** Restores an archived entry; if that overflows, the same capacity rule frees room. */
    fun restoreLorebookEntry(characterId: String, entryId: String) {
        patchCharacter(characterId) { live ->
            // 本体页签只恢复**共享归档**里的条目：成员归档归成员那条路径
            // （[restoreMemberLorebookEntry] 会先确认该成员确实拥有它），免得一个过期的
            // 编辑器列表把成员条目悄悄恢复到本体的世界书里。
            if (live.lorebookArchive.none { it.id == entryId }) return@patchCharacter live
            val restored = live.restoreArchivedLorebookEntry(entryId)
            if (restored == live) return@patchCharacter live
            val overflow = lorebookOverflow(restored)
            if (overflow <= 0) {
                restored
            } else {
                // Never re-archive the entry being restored: it is the oldest precisely
                // because it had been idle, and the user just asked for it back.
                val adjustment = archiveToFit(restored, overflow, excludeIds = setOf(entryId))
                if (adjustment.unmet > 0) {
                    eventsState.tryEmit(UiEvent("世界书已满（其余条目为人工维护或常驻），无法恢复该条目"))
                    live
                } else {
                    emitArchiveNotice(adjustment.archived)
                    adjustment.character
                }
            }
        }
    }

    private fun emitArchiveNotice(archived: List<LorebookEntry>, memberName: String? = null) {
        if (archived.isEmpty()) return
        val who = memberName?.takeIf { it.isNotBlank() }?.let { "（成员：$it）" }.orEmpty()
        eventsState.tryEmit(
            UiEvent("世界书已满，已归档最久未活跃的 ${archived.size} 条$who：${archived.joinToString("、") { it.name }}"),
        )
    }

    fun removeLorebookEntry(characterId: String, entryId: String) {
        patchCharacter(characterId) { live ->
            live.copy(
                lorebook = live.lorebook.filterNot { it.id == entryId },
                lorebookArchive = live.lorebookArchive.filterNot { it.id == entryId },
            )
        }
    }

    fun updateLorebookEntry(characterId: String, entry: LorebookEntry) {
        patchCharacter(characterId) { live ->
            live.copy(lorebook = live.lorebook.map { if (it.id == entry.id) entry else it })
        }
    }

    // -------------------------------------------------- member world books

    /**
     * The same five world-book edits, aimed at **one member's** book. The member is addressed
     * by **id**, not by its index: the card editor's member list is a snapshot and may have
     * changed while the dialog was open. Only that member's book/archive is touched — the
     * shared book and every other member are left alone ([CharacterParity.updateMemberLorebook]
     * is the single addressing point shared with the editor's draft mirror).
     */
    fun addMemberLorebookEntry(characterId: String, memberId: String, entry: LorebookEntry) {
        patchCharacter(characterId) { live ->
            val withEntry = CharacterParity.updateMemberLorebook(live, memberId) { it.copy(lorebook = it.lorebook + entry) }
            if (withEntry == live) return@patchCharacter live
            val overflow = lorebookOverflow(withEntry)
            if (overflow <= 0) {
                withEntry
            } else {
                val adjustment = archiveToFit(withEntry, overflow, excludeIds = setOf(entry.id))
                if (adjustment.unmet > 0) {
                    eventsState.tryEmit(UiEvent("世界书已满（其余条目为人工维护或常驻），无法新增；请先归档或删除一些条目"))
                    live
                } else {
                    emitArchiveNotice(adjustment.archived, memberNameOf(live, memberId))
                    adjustment.character
                }
            }
        }
    }

    fun updateMemberLorebookEntry(characterId: String, memberId: String, entry: LorebookEntry) {
        patchCharacter(characterId) { live ->
            CharacterParity.updateMemberLorebook(live, memberId) { member ->
                member.copy(lorebook = member.lorebook.map { if (it.id == entry.id) entry else it })
            }
        }
    }

    fun removeMemberLorebookEntry(characterId: String, memberId: String, entryId: String) {
        patchCharacter(characterId) { live ->
            CharacterParity.updateMemberLorebook(live, memberId) { member ->
                member.copy(
                    lorebook = member.lorebook.filterNot { it.id == entryId },
                    lorebookArchive = member.lorebookArchive.filterNot { it.id == entryId },
                )
            }
        }
    }

    /** Soft-deletes one member's entry into **that member's** archive; restorable from the editor. */
    fun archiveMemberLorebookEntry(characterId: String, memberId: String, entryId: String) {
        patchCharacter(characterId) { live ->
            CharacterParity.updateMemberLorebook(live, memberId) { member ->
                val entry = member.lorebook.firstOrNull { it.id == entryId } ?: return@updateMemberLorebook member
                member.copy(
                    lorebook = member.lorebook.filterNot { it.id == entryId },
                    lorebookArchive = member.lorebookArchive + entry,
                )
            }
        }
    }

    /**
     * Restores one member's archived entry back into **that member's** book, then fits the
     * global capacity again (`restoreArchivedLorebookEntry` reads the owning book off the
     * archive list itself). The member must actually own the archived id, so a stale editor
     * cannot pull another book's entry into this member. The restored entry is excluded from
     * eviction — it is the oldest precisely because it had been idle.
     */
    fun restoreMemberLorebookEntry(characterId: String, memberId: String, entryId: String) {
        patchCharacter(characterId) { live ->
            val owns = live.members.firstOrNull { it.id == memberId }
                ?.lorebookArchive?.any { it.id == entryId } == true
            if (!owns) return@patchCharacter live
            val restored = live.restoreArchivedLorebookEntry(entryId)
            if (restored == live) return@patchCharacter live
            val overflow = lorebookOverflow(restored)
            if (overflow <= 0) {
                restored
            } else {
                val adjustment = archiveToFit(restored, overflow, excludeIds = setOf(entryId))
                if (adjustment.unmet > 0) {
                    eventsState.tryEmit(UiEvent("世界书已满（其余条目为人工维护或常驻），无法恢复该条目"))
                    live
                } else {
                    emitArchiveNotice(adjustment.archived, memberNameOf(live, memberId))
                    adjustment.character
                }
            }
        }
    }

    private fun memberNameOf(character: Character, memberId: String): String? =
        character.members.firstOrNull { it.id == memberId }?.name

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
            patchCharacter(id) { live -> live.copy(stickers = live.stickers + sticker) }
            // Legacy `tagStickerInBackground`: vision-classify, fill only while untagged.
            viewModelScope.launch {
                val cfg = runCatching { core.currentConfig() }.getOrNull() ?: return@launch
                if (!core.hasApiKey(cfg.apiPlatform)) return@launch
                val tag = runCatching { core.tagSticker(cfg, path) }.getOrNull() ?: return@launch
                patchCharacter(id) { live ->
                    live.copy(
                        stickers = live.stickers.map {
                            if (it.fileRef == path && it.tag == "未分类") it.copy(tag = tag) else it
                        },
                    )
                }
            }
        }
    }

    fun deleteSticker(character: Character, stickerId: String) {
        patchCharacter(character.id) { live ->
            live.copy(stickers = live.stickers.filterNot { it.id == stickerId })
        }
    }

    fun setStickerTag(character: Character, stickerId: String, tag: String) {
        patchCharacter(character.id) { live ->
            live.copy(stickers = live.stickers.map { if (it.id == stickerId) it.copy(tag = tag) else it })
        }
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

    private data class AvatarTarget(val character: Character, val memberIndex: Int?)

    private data class RepairOutcome(val done: Int, val failed: Int, val failNames: List<String>)

    /** Non-null while the manual "补全头像" confirmation dialog should show (target count). */
    private val avatarRepairPromptState = MutableStateFlow<Int?>(null)
    val avatarRepairPrompt: StateFlow<Int?> = avatarRepairPromptState

    /** Non-null while a manual repair runs: (processed, total). */
    private val avatarRepairProgressState = MutableStateFlow<Pair<Int, Int>?>(null)
    val avatarRepairProgress: StateFlow<Pair<Int, Int>?> = avatarRepairProgressState

    /** Legacy `collectAvatarRepairJobs`: manual targets include 👤/👥 placeholders. */
    private suspend fun collectAvatarRepairTargets(includePlaceholders: Boolean): List<AvatarTarget> {
        val all = core.data.characters.observeAll().first()
        val targets = mutableListOf<AvatarTarget>()
        all.forEach { character ->
            val needs = if (includePlaceholders) {
                needsRepair(character.emoji)
            } else {
                character.avatarRepairPending || isAvatarDamaged(character.emoji)
            }
            if (needs) targets += AvatarTarget(character, null)
            character.members.forEachIndexed { index, member ->
                val memberNeeds = if (includePlaceholders) {
                    needsRepair(member.emoji)
                } else {
                    member.avatarRepairPending || isAvatarDamaged(member.emoji)
                }
                if (memberNeeds) targets += AvatarTarget(character, index)
            }
        }
        return targets
    }

    /** Legacy `repairAllAvatars` entry: confirm first, then run with progress. */
    fun requestAvatarRepair() {
        viewModelScope.launch {
            if (avatarRepairProgressState.value != null) {
                eventsState.tryEmit(UiEvent("头像补全正在进行中，请稍候…"))
                return@launch
            }
            val cfg = runCatching { core.currentConfig() }.getOrNull() ?: return@launch
            if (!core.hasApiKey(cfg.apiPlatform)) {
                eventsState.tryEmit(UiEvent("请先在设置页配置 API Key，再补全头像"))
                return@launch
            }
            val count = collectAvatarRepairTargets(includePlaceholders = true).size
            if (count == 0) {
                eventsState.tryEmit(UiEvent("所有角色与群组成员都已使用 emoji 头像，无需补全"))
                return@launch
            }
            avatarRepairPromptState.value = count
        }
    }

    fun dismissAvatarRepair() {
        avatarRepairPromptState.value = null
        askLorebookRebuildOnce()
    }

    fun confirmAvatarRepair() {
        avatarRepairPromptState.value = null
        viewModelScope.launch {
            val cfg = runCatching { core.currentConfig() }.getOrNull() ?: return@launch
            if (!core.hasApiKey(cfg.apiPlatform)) {
                eventsState.tryEmit(UiEvent("请先在设置页配置 API Key，再补全头像"))
                return@launch
            }
            val targets = collectAvatarRepairTargets(includePlaceholders = true)
            val result = repairAvatarTargets(cfg, targets, showProgress = true)
            val msg = "已生成 ${result.done} 个 emoji 头像"
            if (result.failed > 0) {
                eventsState.tryEmit(UiEvent("$msg，失败 ${result.failed} 个（${result.failNames.joinToString("、")}），请检查网络后重试"))
            } else {
                eventsState.tryEmit(UiEvent(msg))
            }
        }
    }

    /** Legacy `repairAvatarJobs`: serial AI emoji generation, flagging failures as pending. */
    private suspend fun repairAvatarTargets(
        cfg: AppConfig,
        targets: List<AvatarTarget>,
        showProgress: Boolean,
    ): RepairOutcome {
        var done = 0
        var failed = 0
        val failNames = mutableListOf<String>()
        try {
            targets.forEachIndexed { index, target ->
                if (showProgress) avatarRepairProgressState.value = (index + 1) to targets.size
                val member = target.memberIndex?.let { target.character.members.getOrNull(it) }
                val name = member?.name ?: target.character.name
                val emoji = runCatching {
                    if (member == null) core.generateEmojiAvatar(cfg, target.character)
                    else core.generateEmojiAvatar(cfg, member, target.character)
                }.getOrNull()
                val latest = core.data.characters.get(target.character.id)
                if (latest == null) return@forEachIndexed
                if (!emoji.isNullOrBlank()) {
                    val updated = if (member == null) {
                        latest.copy(emoji = emoji, avatarRepairPending = false)
                    } else {
                        latest.copy(
                            members = latest.members.mapIndexed { i, m ->
                                if (i == target.memberIndex) m.copy(emoji = emoji, avatarRepairPending = false) else m
                            },
                        )
                    }
                    core.data.characters.upsert(updated)
                    done++
                } else {
                    val updated = if (member == null) {
                        latest.copy(avatarRepairPending = true)
                    } else {
                        latest.copy(
                            members = latest.members.mapIndexed { i, m ->
                                if (i == target.memberIndex) m.copy(avatarRepairPending = true) else m
                            },
                        )
                    }
                    core.data.characters.upsert(updated)
                    failed++
                    failNames += name
                }
                if (index < targets.lastIndex) delay(300)
            }
        } finally {
            if (showProgress) avatarRepairProgressState.value = null
        }
        return RepairOutcome(done, failed, failNames)
    }

    /** Legacy `autoRepairAvatars`: silently repair damaged/pending avatars on startup/import. */
    fun autoRepairAvatars() {
        viewModelScope.launch {
            if (avatarRepairProgressState.value != null) return@launch
            val cfg = runCatching { core.currentConfig() }.getOrNull() ?: return@launch
            if (!core.hasApiKey(cfg.apiPlatform)) return@launch
            val targets = collectAvatarRepairTargets(includePlaceholders = false)
            if (targets.isEmpty()) return@launch
            val result = repairAvatarTargets(cfg, targets, showProgress = false)
            if (result.done > 0 || result.failed > 0) {
                val msg = "已自动生成 ${result.done} 个损坏头像"
                if (result.failed > 0) {
                    eventsState.tryEmit(UiEvent("$msg，失败 ${result.failed} 个（${result.failNames.joinToString("、")}），可稍后在侧边栏点击“补全头像”重试"))
                } else {
                    eventsState.tryEmit(UiEvent(msg))
                }
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
        // Legacy `handleImagePick`: only `image/*` files are accepted.
        val mime = runCatching { core.appContext.contentResolver.getType(uri) }.getOrNull()
        if (mime == null || !mime.startsWith("image/")) {
            eventsState.tryEmit(UiEvent("只能发送图片文件"))
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
        // A new turn supersedes any manual worker retry for the same character.
        if (workerFailureState.value?.characterId == id) {
            workerRetryJob?.cancel()
            workerRetryJob = null
            workerRetryToken++
            workerRetryState.value = false
            workerRetryCharacterState.value = null
            workerFailureState.value = null
        }
        bumpTurnGeneration(id)
        return viewModelScope.launch {
            sendingState.value = true
            statusState.value = "思考中…"
            quickRepliesState.value = emptyList()
            lastUserActivityAt.value = System.currentTimeMillis()
            // Captured before the request body is assembled so the failure metric can
            // still report a duration (its scope must outlive `cfg`/`requestStartedAt`).
            val turnStartedAt = System.currentTimeMillis()
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
                // Grow the persisted `character.instant` window with the messages it has
                // not seen yet (append-only): the fold pass drops it back to the low
                // watermark, and each turn only appends. Re-deriving "the newest N" every
                // turn instead would slide the window by one message per turn, which is
                // exactly what made the cached request prefix miss on every request.
                val stored = core.data.characters.get(id) ?: return@launch
                val grownInstant = growInstantWindow(stored.instant, history)
                val character = stored.copy(instant = grownInstant)
                // A collapsed window (e.g. one synthetic reply left by a failed turn)
                // cannot be repaired by the normal three-way merge: the merge never
                // resurrects ids the writer's `before` already had. Persist the rebuilt
                // window before the turn so the next write starts from a usable state.
                if (stored.instant.size < AppLimits.Memory.INSTANT_TRIM_FLOOR &&
                    grownInstant.size >= AppLimits.Memory.INSTANT_TRIM_FLOOR
                ) {
                    core.data.characters.mutate(id) { live -> live.copy(instant = grownInstant) }
                }
                statusState.value = "生成中…"
                streamingState.value = ""

                // 小概率「热榜开场」（HotTopicPolicy）：每个角色每个逻辑日只掷一次骰子，
                // 每天最多 1 次、每周最多 2 次；命中则本轮先查热榜再起话题。掷在真正发请求之前。
                val hotTopicTurn = armHotTopicTurn(id)

                val orchestrator = core.createOrchestrator(
                    cfg,
                    onCharacterUpdated = { before, updated ->
                        core.appScope.launch {
                            writeCharacterChanges(before, updated)
                        }
                    },
                    onStatus = { statusState.value = it },
                    onAuxiliaryUsage = { taskType, usage -> recordAuxiliaryUsage(taskType, usage) },
                )
                val priorCache = cfg.cacheStats
                val hadCacheMiss = priorCache != null && priorCache.hitTokens == 0 && (priorCache.promptTokens ?: 0) > 0
                val requestStartedAt = System.currentTimeMillis()
                // The empty-round retry now lives in AgentLoop's FINAL phase (2 retries).
                // A second retry loop here would multiply the request count (3 × 3), so the
                // app runs exactly one turn; a still-empty reply is surfaced as an error.
                var result: OrchestratorResult? = null
                val attemptResults = mutableListOf<OrchestratorResult>()
                var receiveLogged = false
                statusState.value = if (hadCacheMiss) "缓存未命中，正在更新队列…" else "正在请求 API…"
                val outcome = orchestrator.run(
                    character = character,
                    history = character.instant,
                    userText = text.trim(),
                    onDelta = { delta ->
                        if (!receiveLogged && delta.isNotBlank()) {
                            receiveLogged = true
                            statusState.value = "正在接收回复…"
                        }
                        streamingState.value = delta
                    },
                    onToolActivity = { statusState.value = it },
                    onQuickRepliesRepaired = { quickRepliesState.value = it },
                    proactive = proactive,
                    hotTopicTurn = hotTopicTurn,
                )
                result = outcome
                attemptResults += outcome
                streamingState.value = null
                val finalResult = result ?: return@launch
                if (finalResult.reply.isBlank()) {
                    statusState.value = ""
                    // The FINAL phase already retried the empty round twice; record the
                    // failed turn once so the per-request rows stay truthful.
                    runCatching {
                        appendMetric(
                            listOf(
                                RequestMetric(
                                    at = Instant.now().toString(),
                                    characterId = id,
                                    taskType = TASK_CHAT_MAIN,
                                    model = cfg.modelName,
                                    platform = cfg.apiPlatform,
                                    phase = "empty-reply",
                                    status = "failed",
                                    durationMs = System.currentTimeMillis() - requestStartedAt,
                                ),
                            ),
                        )
                    }
                    eventsState.tryEmit(UiEvent("收到空回复，请重试。你的消息已保留。"))
                    return@launch
                }
                val assistantRowId = UUID.randomUUID().toString()
                core.data.chat.append(
                    id,
                    ChatMessage(
                        id = assistantRowId,
                        role = Role.Assistant,
                        content = finalResult.reply,
                        timestamp = Instant.now().toString(),
                        internalOnly = false,
                        // The chat list is loaded from this row, not from the in-memory
                        // instant window: keep the per-member turns or the group reply
                        // collapses back to one bubble with raw speaker prefixes.
                        segments = finalResult.segments,
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
                val withConfirmations = recordPromiseConfirmations(finalResult.updatedCharacter)
                // Write only what this turn changed. The turn's result is built from the
                // snapshot taken when the turn started, so writing it whole would roll back
                // anything a background pass changed while the model was answering (and a
                // background world-book write would be rolled back likewise).
                writeCharacterChanges(character, withConfirmations)
                if (finalResult.quickReplies.isNotEmpty()) {
                    quickRepliesState.value = finalResult.quickReplies
                }
                if (finalResult.workerRan && !finalResult.workerOk) {
                    workerFailureState.value = WorkerFailureUi(
                        characterId = id,
                        userText = text.trim(),
                        reply = finalResult.reply,
                        replyId = finalResult.workerReplyId,
                        assistantRowId = assistantRowId,
                        proactive = proactive,
                        reason = finalResult.workerFailure.ifBlank { "整理失败" },
                    )
                } else if (workerFailureState.value?.characterId == id) {
                    workerFailureState.value = null
                }
                statusState.value = ""
                recordMetrics(
                    cfg,
                    id,
                    attemptResults,
                    character.instant,
                    buildShortTermBlock(character),
                    System.currentTimeMillis() - requestStartedAt,
                )
                if (cfg.ttsEnabled && cfg.ttsAutoRead) {
                    speakReply(finalResult.segments, finalResult.reply, finalResult.updatedCharacter)
                }
            } catch (error: Exception) {
                streamingState.value = null
                statusState.value = ""
                if (error !is CancellationException) {
                    // Legacy `recordCacheUsage` also logged failed chat requests: without
                    // this row the settings panel cannot explain "为什么这次没回".
                    val failedCfg = core.currentConfig()
                    runCatching {
                        appendMetric(
                            listOf(
                                RequestMetric(
                                    at = Instant.now().toString(),
                                    characterId = id,
                                    taskType = TASK_CHAT_MAIN,
                                    model = failedCfg.modelName,
                                    platform = failedCfg.apiPlatform,
                                    phase = (error.message ?: error.toString()).replace(Regex("\\s+"), " ").trim().take(160),
                                    status = "failed",
                                    durationMs = System.currentTimeMillis() - turnStartedAt,
                                ),
                            ),
                        )
                    }
                }
                eventsState.tryEmit(UiEvent("回复失败：${error.message ?: error}。你的消息已保留，可稍后重试。"))
            } finally {
                sendingState.value = false
            }
        }
    }


    /**
     * Records the **confirmation conclusion** for the active character's promises: a promise
     * the user themself agreed to (and whose due time their own words locate) gets
     * [LongTermMemory.pushConfirmedAt] / `pushConfirmedDueAt` stamped here, while the citing
     * sources are still live. Later, when those messages slide out of the window and the
     * sources no longer resolve, the promise must not drop to 「不提醒」. Idempotent: an
     * already-valid confirmation is never rewritten. Only the character that just took a turn
     * is scanned, never every character.
     *
     * The gate is [promisePushDecision]: a promise the **user** did not agree to
     * (`promisor=character`, or no user-verbatim evidence) is not stamped — its memory stays
     * stored and visible, it just never counts as a confirmed agreement. A due time the user's
     * own words do not locate is likewise never stamped (`dueAtNeedsConfirmation`); stamping it
     * would later promote the model's unconfirmed date into a confirmed one. A promise with no
     * due time at all **is** stamped — it is a real user-agreed agreement, it just has no date.
     *
     * Nothing is delivered to the user and nothing is displayed any more: this only stamps the
     * memory's own metadata (「用户本人确认过这条约定」), entirely independent of the 待办 list.
     */
    private fun recordPromiseConfirmations(character: Character): Character {
        var changed = false
        val longTerm = character.longTerm.map { memory ->
            val decision = promisePushDecision(character, memory)
            if (!decision.pushable) return@map memory
            if (decision.dueAtNeedsConfirmation || hasValidPushConfirmation(memory)) return@map memory
            changed = true
            memory.copy(
                pushConfirmedAt = Instant.now().toString(),
                pushConfirmedDueAt = decision.confirmedDueAt,
            )
        }
        return if (changed) character.copy(longTerm = longTerm) else character
    }

    /** Already-completed [Job] for the no-op branches of [submitTurn]. */
    private fun completedJob(): Job = Job().apply { complete() }

    /** Per-character prefix snapshots backing the request trace (legacy `requestPrefixSnapshots`). */
    private val requestPrefixSnapshots = mutableMapOf<String, PrefixSnapshot>()

    private data class PrefixSnapshot(
        val instructionsHash: String,
        val toolsHash: String,
        val inputs: List<String>,
    )

    private data class RequestTrace(
        val historyHash: String,
        val prefixChange: String,
        val commonHistoryMessages: Int,
        val inputChars: Int,
    )

    /**
     * App-side request trace; the instructions/tools hashes come from the
     * domain (`OrchestratorResult`) since only it sees the assembled request.
     *
     * The short-term block is injected *before* the history, so it is part of the
     * request prefix: it is tracked as the first pseudo-entry, otherwise a change there
     * would be reported as `history-extended` while the provider actually saw a miss.
     */
    private fun requestTrace(
        characterId: String,
        history: List<ChatMessage>,
        shortTermBlock: String,
        instructionsHash: String,
        toolsHash: String,
    ): RequestTrace {
        val inputs = buildList {
            if (shortTermBlock.isNotEmpty()) add("short_term|" + shortTermBlock)
            history.filter { !it.rejected }.forEach { message ->
                add(
                    message.role.name + "|" + message.content + "|" +
                        message.attachments.joinToString(",") { it.kind.name + ":" + it.uri },
                )
            }
        }
        val previous = requestPrefixSnapshots[characterId]
        var common = 0
        if (previous != null) {
            while (common < minOf(previous.inputs.size, inputs.size) && previous.inputs[common] == inputs[common]) common++
        }
        val change = when {
            previous == null -> "first-request"
            previous.instructionsHash != instructionsHash -> "instructions-changed"
            previous.toolsHash != toolsHash -> "tools-changed"
            common < previous.inputs.size -> "history-rebased"
            else -> "history-extended"
        }
        requestPrefixSnapshots[characterId] = PrefixSnapshot(instructionsHash, toolsHash, inputs)
        return RequestTrace(
            historyHash = fnv1a(inputs.joinToString("\n")),
            prefixChange = change,
            commonHistoryMessages = common,
            inputChars = inputs.sumOf { it.length },
        )
    }

    /** Port of the legacy `hashRequestPart` FNV-1a hash. */
    private fun fnv1a(text: String): String {
        var hash = 0x811c9dc5L
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return hash.toString(16)
    }

    /** Stores the last reply debug payload and appends a rolling usage/cache metric. */
    private suspend fun recordMetrics(
        cfg: AppConfig,
        characterId: String,
        results: List<OrchestratorResult>,
        requestHistory: List<ChatMessage>,
        shortTermBlock: String,
        durationMs: Long,
    ) {
        val first = results.firstOrNull() ?: return
        val last = results.last()
        val at = Instant.now().toString()
        // The prefix trace is a property of the *request*, so it is computed once and
        // attached to the first `chat.main` row (the request that carried the prefix).
        val trace = requestTrace(characterId, requestHistory, shortTermBlock, first.instructionsHash, first.toolsHash)

        // One row per model request: every tool round of the turn is its own row, and a
        // retried (empty-reply) attempt appends its own rows too.
        val rows = mutableListOf<RequestMetric>()
        var round = 0
        results.forEach { result ->
            val usages = result.roundUsages.ifEmpty {
                listOfNotNull(
                    if (result.inputTokens > 0) {
                        TokenUsage(result.inputTokens, result.outputTokens, result.cachedTokens)
                    } else {
                        null
                    },
                )
            }
            if (usages.isEmpty()) {
                // The request happened but no usage came back → "no data" row, never a 0 % hit.
                round++
                rows += mainMetric(
                    at = at, cfg = cfg, characterId = characterId, round = round,
                    usage = null, trace = if (round == 1) trace else null, result = result,
                )
            } else {
                usages.forEach { usage ->
                    round++
                    rows += mainMetric(
                        at = at, cfg = cfg, characterId = characterId, round = round,
                        usage = usage, trace = if (round == 1) trace else null, result = result,
                    )
                }
            }
            workerMetricRow(at, cfg, characterId, result)?.let { rows += it }
        }
        // The per-round durations are not reported by the backend; the turn total lands
        // on the last main row so the list stays honest without inventing per-round values.
        val lastMain = rows.indexOfLast { it.taskType == TASK_CHAT_MAIN }
        if (lastMain >= 0) rows[lastMain] = rows[lastMain].copy(durationMs = durationMs)
        if (rows.isEmpty()) return

        // Header snapshot: the **first** main request of the turn — it is the one that
        // carries the (possibly broken) cached prefix; tool rounds re-warm it and would
        // mask a real miss. `null` means "no usage reported".
        val firstUsage = first.roundUsages.firstOrNull()
            ?: if (first.inputTokens > 0) {
                TokenUsage(first.inputTokens, first.outputTokens, first.cachedTokens)
            } else {
                null
            }
        val usage = usageParts(firstUsage)
        // A completed chat turn always refreshes the header snapshot — including the
        // "no data" case (nulls → the pill shows 缓存未返回命中数据). Keeping the previous
        // numbers here is what made the header look "stuck" after a prefix change.
        val cacheStats = CacheStats(
            hitTokens = usage.hit,
            missTokens = usage.miss,
            promptTokens = firstUsage?.inputTokens?.takeIf { it > 0 },
            updatedAt = at,
        )
        // Legacy `DEBUG_REPLY_STORAGE_KEY`: a JSON object with the config snapshot,
        // display/full text and quick replies, rendered as two sections in Settings.
        val debug = buildJsonObject {
            put("at", at)
            putJsonObject("config") {
                put("stream", cfg.stream)
                put("reasoning", cfg.reasoningEffort)
                put("model", cfg.modelName)
            }
            put("durationMs", durationMs)
            put("displayText", last.reply.take(2000))
            put("fullText", last.rawReply.take(2000))
            putJsonArray("quickReplies") { last.quickReplies.forEach { add(it) } }
        }.toString()
        appendMetric(rows, cacheStats = cacheStats, lastReplyDebug = debug)
    }

    /** One `chat.main` row (one model request of the turn). */
    private fun mainMetric(
        at: String,
        cfg: AppConfig,
        characterId: String,
        round: Int,
        usage: TokenUsage?,
        trace: RequestTrace?,
        result: OrchestratorResult,
    ): RequestMetric {
        val parts = usageParts(usage)
        return RequestMetric(
            at = at,
            taskType = TASK_CHAT_MAIN,
            characterId = characterId,
            model = cfg.modelName,
            platform = cfg.apiPlatform,
            status = "completed",
            round = round,
            instructionsHash = result.instructionsHash,
            toolsHash = result.toolsHash,
            historyHash = trace?.historyHash,
            prefixChange = trace?.prefixChange,
            commonHistoryMessages = trace?.commonHistoryMessages,
            contextChars = result.contextChars,
            sectionChars = metricSectionChars(result),
            inputChars = trace?.inputChars,
            inputTokens = usage?.inputTokens?.takeIf { it > 0 },
            outputTokens = usage?.outputTokens?.takeIf { parts.hasUsage },
            hitTokens = parts.hit,
            missTokens = parts.miss,
            hitRate = parts.rate,
        )
    }

    private data class UsageParts(val hasUsage: Boolean, val hit: Int?, val miss: Int?, val rate: Double?)

    /** Splits a usage report into hit/miss/rate; no reported input tokens → "no data". */
    private fun usageParts(usage: TokenUsage?): UsageParts {
        val input = usage?.inputTokens ?: 0
        if (input <= 0) return UsageParts(hasUsage = false, hit = null, miss = null, rate = null)
        val hit = (usage?.cachedTokens ?: 0).coerceAtLeast(0)
        val miss = (input - hit).coerceAtLeast(0)
        return UsageParts(hasUsage = true, hit = hit, miss = miss, rate = hit.toDouble() / input)
    }

    /**
     * Appends [rows] to the independent metrics store and, when given, refreshes the
     * header's `cacheStats` / reply-debug columns.
     *
     * The metric rows go to `:core:data`'s `MetricsRepository`, whose append is one atomic
     * read-modify-write on its own DataStore file — so concurrent writers can no longer
     * clobber each other, and no in-process mutex is needed. The config columns are only
     * written when a chat turn actually produces them (one turn at a time), never by
     * auxiliary calls.
     */
    private suspend fun appendMetric(
        rows: List<RequestMetric> = emptyList(),
        cacheStats: CacheStats? = null,
        lastReplyDebug: String? = null,
    ) {
        if (rows.isNotEmpty()) {
            runCatching { core.data.metrics.appendAll(rows) }
        }
        if (cacheStats == null && lastReplyDebug == null) return
        runCatching {
            val current = core.data.config.current()
            core.data.config.update(
                current.copy(
                    cacheStats = cacheStats ?: current.cacheStats,
                    lastReplyDebug = lastReplyDebug ?: current.lastReplyDebug,
                ),
            )
        }
    }

    /** Records an auxiliary (background/helper) LLM call's token usage (legacy `recordCacheUsage`). */
    private fun recordAuxiliaryUsage(taskType: String, usage: TokenUsage) {
        viewModelScope.launch {
            val cfg = core.currentConfig()
            val at = Instant.now().toString()
            // No usage reported → record "no data" (nulls), never a 0 % cache hit: auxiliary
            // calls are excluded from the header average, but the debug list must not lie.
            val hasUsage = usage.inputTokens > 0
            val hit = if (hasUsage) (usage.cachedTokens ?: 0).coerceAtLeast(0) else null
            val miss = if (hasUsage) (usage.inputTokens - (cachedOrZero(usage))).coerceAtLeast(0) else null
            val metric = RequestMetric(
                at = at,
                taskType = taskType,
                model = cfg.modelName,
                platform = cfg.apiPlatform,
                status = "completed",
                inputTokens = if (hasUsage) usage.inputTokens else null,
                outputTokens = if (hasUsage) usage.outputTokens else null,
                hitTokens = hit,
                missTokens = miss,
                hitRate = if (hasUsage) (cachedOrZero(usage)).toDouble() / usage.inputTokens else null,
            )
            appendMetric(listOf(metric))
        }
    }

    private fun cachedOrZero(usage: com.deeptalking.engine.ondevice.TokenUsage): Int =
        usage.cachedTokens?.coerceAtLeast(0) ?: 0

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
        fun hasRemovedSource(ids: List<String>) = ids.any { it in removedIds }
        // Legacy `cleanDynamicStateSources` drops memory items whose source messages were
        // removed; the dynamic values themselves are left untouched. `dynamicStateMeta`
        // timestamps are **kept**: attribution to one field is not possible here, and
        // clearing them would let a subsequent write report an older update time.
        core.data.characters.mutate(characterId) { live ->
            live.copy(
                shortTerm = live.shortTerm.filterNot { hasRemovedSource(it.sourceMessageIds) },
                longTerm = live.longTerm.filterNot { hasRemovedSource(it.sourceMessageIds) },
                pendingRecall = live.pendingRecall.filterNot { hasRemovedSource(it.sourceMessageIds) },
                revision = live.revision + 1,
            )
        }
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

    fun hasApiKey(platform: String = ""): Boolean =
        !core.secrets.getApiKey(platform).isNullOrBlank() || !core.secrets.getApiKey().isNullOrBlank()

    /** Legacy key backfill: the platform's stored key, falling back to the legacy global slot. */
    fun storedApiKey(platform: String): String? =
        core.secrets.getApiKey(platform)?.takeIf { it.isNotBlank() }
            ?: core.secrets.getApiKey()?.takeIf { it.isNotBlank() }

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

    private val backupJson = Json { prettyPrint = true; encodeDefaults = true }

    /** True when the selected backup carries the encrypted secret bundle. */
    fun backupNeedsPassword(json: String): Boolean =
        runCatching { backupJson.parseToJsonElement(json).jsonObject.containsKey("secrets") }
            .getOrDefault(false)

    fun exportJson(password: String, onReady: (String) -> Unit) {
        if (password.isEmpty()) {
            eventsState.tryEmit(UiEvent("请设置备份密码"))
            return
        }
        viewModelScope.launch {
            val activeId = activeIdState.value
            val withSecrets = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val json = core.backup.exportJson(activeId)
                // Keep the backup shape unchanged and put the encrypted secret bundle at the
                // root. Only API keys and the remote-TTS token are inside the ciphertext.
                val root = backupJson.parseToJsonElement(json).jsonObject
                val encrypted = BackupCipher.encrypt(core.secrets.snapshot(), password)
                JsonObject(root + ("secrets" to backupJson.parseToJsonElement(encrypted)))
            }
            onReady(backupJson.encodeToString(JsonElement.serializer(), withSecrets))
        }
    }

    fun importJson(json: String, password: String) {
        viewModelScope.launch {
            val secretNode = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    backupJson.parseToJsonElement(json).jsonObject["secrets"]
                }.getOrNull()
            }
            val secrets = if (secretNode != null) {
                if (password.isEmpty()) {
                    eventsState.tryEmit(UiEvent("这个备份包含密钥，请输入备份密码"))
                    return@launch
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    BackupCipher.decrypt(secretNode.toString(), password)
                } ?: run {
                    eventsState.tryEmit(UiEvent("备份密码错误，或密钥数据已损坏"))
                    return@launch
                }
            } else {
                null
            }
            val summary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { core.backup.importJson(json) }
            }.getOrElse { error ->
                eventsState.tryEmit(UiEvent("导入失败：${error.message ?: error}"))
                return@launch
            }
            secrets?.let { core.secrets.restore(it) }
            normalizeImportedTtsVoice()
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
            refreshTtsState(core.currentConfig())
            // Legacy runs silent static-field completion + migration prompts after import.
            runCatching { core.runStartupMigrations() }.onFailure { DeepTalkingApp.recordError(it) }
            runCatching { core.runMemoryRepair() }.onFailure { DeepTalkingApp.recordError(it) }
            runCatching {
                val cfg = core.data.config.observe().first()
                core.runStartupMaintenance(cfg)
            }.onFailure { DeepTalkingApp.recordError(it) }
            runCatching { checkMigrationPrompts() }.onFailure { DeepTalkingApp.recordError(it) }
            // Oversized (legacy) window catch-up asks first and blocks while it runs, so the
            // cleanup prompt and the world-book rebuild wait for it.
            runCatching { checkOversizedWindowMigration() }.onFailure { DeepTalkingApp.recordError(it) }
            if (memoryMigrationPromptState.value == null && memoryMigrationState.value == null) {
                continueStartupPrompts()
            }
            runCatching { runMemoryCleanupChunk(AppLimits.Memory.CLEANUP_BATCHES_PER_RUN) }
                .onFailure { DeepTalkingApp.recordError(it) }
            autoRepairAvatars()
        }
    }

    /**
     * A backup stores the voice path, which is device-specific. Resolve it by file name
     * against the local catalog; a missing voice becomes an empty selection instead of a
     * dead absolute path from another phone.
     */
    private suspend fun normalizeImportedTtsVoice() {
        val cfg = core.data.config.current()
        val fileName = cfg.ttsVoiceFile.takeIf { it.isNotBlank() }?.let { java.io.File(it).name }
        val match = fileName?.let { name ->
            runCatching { core.cosyVoice.listVoices() }.getOrDefault(emptyList())
                .firstOrNull { it.file == name }
        }
        val resolved = match?.let { core.cosyVoice.voiceFileFor(it.file) }
        val path = resolved?.absolutePath.orEmpty()
        if (cfg.ttsVoiceFile != path) {
            core.data.config.update(cfg.copy(ttsVoiceFile = path))
        }
        // The config is the persisted selection, but the engine keeps its own active
        // voice field. Apply it now so read-aloud immediately after an import uses the
        // restored voice instead of the previous device's selection.
        core.cosyVoice.setVoiceFile(resolved)
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

    /**
     * 「热榜开场」小概率事件（[HotTopicPolicy]）：每个角色每个逻辑日只在当天第一个
     * 回合掷一次骰子（10%），命中冲 每天 1 次 / 每周 2 次 上限，每角色独立。计数存在
     * DataStore 的 [HOT_TOPIC_KEY]（不进 Room、不进备份），坏数据当成没记过。
     */
    private suspend fun armHotTopicTurn(characterId: String): Boolean {
        val settings = core.data.settings
        val store = HotTopicPolicy.decode(runCatching { settings.getString(HOT_TOPIC_KEY) }.getOrNull())
        val roll = HotTopicPolicy.roll(counters = store[characterId] ?: HotTopicCounters())
        if (!roll.rolled) return false
        runCatching {
            settings.putString(HOT_TOPIC_KEY, HotTopicPolicy.encode(store + (characterId to roll.counters)))
        }
        return roll.triggered
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

        /** Set when the user chose "以后不再询问" on the one-time memory cleanup. */
        const val MEMORY_CLEANUP_SKIP_KEY = "deeptalking_memory_cleanup_skip_v1"

        /** Set when the user chose "以后不再询问" on the oversized-window catch-up. */
        const val MEMORY_WINDOW_MIGRATION_SKIP_KEY = "deeptalking_memory_window_migration_skip_v1"

        /** 每个角色的「热榜开场」计数（JSON：角色id → HotTopicCounters）。 */
        const val HOT_TOPIC_KEY = "deeptalking_hot_topic_v1"

        /**
         * Set while a confirmed cleanup is still consuming the short-term backlog: the
         * app then keeps working on its own and never asks twice for one backlog.
         */
        const val MEMORY_CLEANUP_PENDING_KEY = "deeptalking_memory_cleanup_pending_v1"
        /** How long the bubble shows the "done" tick before reverting to idle. */
        const val TTS_DONE_LINGER_MS = 1_000L
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

    /** Read-aloud state (on-device CosyVoice3 or the remote HTTP server). */
    val tts: StateFlow<TtsState> = ttsState

    private var ttsJob: Job? = null

    /** Engine used by the running read-aloud, so a stop reaches the right one. */
    @Volatile
    private var activeSpeaker: TtsSpeaker? = null

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
            speed = cfg.ttsSpeed,
            backend = cfg.ttsBackend,
            remoteUrl = cfg.ttsRemoteUrl,
            remoteModel = cfg.ttsRemoteModel,
            remoteVoice = cfg.ttsRemoteVoice,
            remoteSampleRate = cfg.ttsRemoteSampleRate,
            remoteToken = core.secrets.getTtsToken().orEmpty(),
            remoteReady = cfg.ttsRemoteUrl.isNotBlank(),
            remoteChunkTokens = cfg.ttsRemoteChunkTokens,
            remoteTemperature = cfg.ttsRemoteTemperature,
            remoteTopK = cfg.ttsRemoteTopK,
            remoteTopP = cfg.ttsRemoteTopP,
            remoteWinSize = cfg.ttsWinSize,
            remoteTauR = cfg.ttsTauR,
            remoteMinTokenTextRatio = cfg.ttsMinTokenTextRatio,
            remoteMaxTokenTextRatio = cfg.ttsMaxTokenTextRatio,
        )
    }

    fun setTtsBackend(backend: String) {
        viewModelScope.launch {
            val cfg = core.currentConfig()
            core.data.config.update(cfg.copy(ttsBackend = backend))
            refreshTtsState(core.currentConfig())
            // Switching to the remote engine should be a one-tap affair: with USB
            // tethering there is no address to type, so look for the PC right away
            // when none is configured yet (the user can still override it by hand).
            if (backend == TTS_BACKEND_REMOTE && cfg.ttsRemoteUrl.isBlank()) {
                discoverTtsServer()
            }
        }
    }

    /** Persists the non-secret remote fields edited in one pass from the settings form. */
    fun updateRemoteTts(
        url: String? = null,
        model: String? = null,
        voice: String? = null,
        chunkTokens: Int? = null,
        temperature: Double? = null,
        topK: Int? = null,
        topP: Double? = null,
        winSize: Int? = null,
        tauR: Double? = null,
        minTokenTextRatio: Double? = null,
        maxTokenTextRatio: Double? = null,
    ) {
        viewModelScope.launch {
            val cfg = core.currentConfig()
            core.data.config.update(
                cfg.copy(
                    ttsRemoteUrl = url?.trim()?.trimEnd('/') ?: cfg.ttsRemoteUrl,
                    ttsRemoteModel = model ?: cfg.ttsRemoteModel,
                    ttsRemoteVoice = voice ?: cfg.ttsRemoteVoice,
                    ttsRemoteChunkTokens = chunkTokens ?: cfg.ttsRemoteChunkTokens,
                    ttsRemoteTemperature = temperature ?: cfg.ttsRemoteTemperature,
                    ttsRemoteTopK = topK ?: cfg.ttsRemoteTopK,
                    ttsRemoteTopP = topP ?: cfg.ttsRemoteTopP,
                    ttsWinSize = winSize ?: cfg.ttsWinSize,
                    ttsTauR = tauR ?: cfg.ttsTauR,
                    ttsMinTokenTextRatio = minTokenTextRatio ?: cfg.ttsMinTokenTextRatio,
                    ttsMaxTokenTextRatio = maxTokenTextRatio ?: cfg.ttsMaxTokenTextRatio,
                ),
            )
            refreshTtsState(core.currentConfig())
        }
    }

    /** Saves (or clears) the remote server's bearer token. Never written to `AppConfig`. */
    fun saveTtsToken(token: String?) {
        viewModelScope.launch {
            core.secrets.setTtsToken(token?.trim()?.takeIf { it.isNotEmpty() })
            refreshTtsState(core.currentConfig())
        }
    }

    /** "测试连接": probes the configured server and stores the discovered sample rate. */
    fun testTtsConnection() {
        if (ttsState.value.busy) return
        ttsState.value = ttsState.value.copy(busy = true, remoteStatus = "正在测试…")
        viewModelScope.launch {
            val cfg = core.currentConfig()
            val probe = runCatching { core.testTtsConnection(cfg) }
                .getOrElse { RemoteTtsProbe(ok = false, message = "测试失败：${it.message}") }
            val manifest = if (probe.ok) {
                runCatching { core.listTtsVoices(cfg) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            if (probe.sampleRate > 0) {
                core.data.config.update(core.currentConfig().copy(ttsRemoteSampleRate = probe.sampleRate))
            }
            ttsState.value = ttsState.value.copy(
                busy = false,
                remoteStatus = probe.message,
                remoteVoices = if (manifest.isNotEmpty()) manifest.map { it.id }
                else if (probe.speakers.isEmpty()) ttsState.value.remoteVoices else probe.speakers,
                remoteVoiceNames = if (manifest.isNotEmpty()) {
                    manifest.associate { it.id to it.name }
                } else {
                    ttsState.value.remoteVoiceNames
                },
            )
            refreshTtsState(core.currentConfig())
        }
    }

    /**
     * "上传音色到电脑": pushes every on-device voice (built-ins and user imports) to
     * the PC, which stores and registers each one under the same name. The PC then
     * reads with the exact voice the phone has, because it is the same prompt-speech
     * file the phone cloned locally (see docs/REMOTE_TTS.md §8).
     */
    fun uploadVoicesToRemote() {
        if (ttsState.value.busy) return
        val voices = runCatching { core.cosyVoice.listVoices() }.getOrDefault(emptyList())
        if (voices.isEmpty()) {
            ttsState.value = ttsState.value.copy(remoteStatus = "本机还没有音色可上传")
            return
        }
        ttsState.value = ttsState.value.copy(busy = true, remoteStatus = "正在上传 ${voices.size} 个音色…")
        viewModelScope.launch {
            val config = core.remoteTtsConfig(core.currentConfig())
            var uploaded = 0
            val failures = mutableListOf<String>()
            for (voice in voices) {
                val file = core.cosyVoice.voiceFileFor(voice.file)
                val bytes = file?.let { runCatching { it.readBytes() }.getOrNull() }
                if (bytes == null) {
                    failures += "${voice.name}（文件缺失）"
                    continue
                }
                val result = runCatching {
                    core.uploadTtsVoice(
                        config,
                        voice.file.removeSuffix(".gguf"),
                        bytes,
                        displayName = voice.name,
                    )
                }.getOrElse { RemoteVoiceUpload(voice.file, ok = false, message = it.message ?: "上传失败") }
                if (result.ok) uploaded++ else failures += "${voice.name}：${result.message}"
            }
            // Re-probe so the picker stops marking the uploaded voices as "服务端未注册".
            val probe = runCatching { core.testTtsConnection(core.currentConfig()) }.getOrNull()
            ttsState.value = ttsState.value.copy(
                busy = false,
                remoteStatus = buildString {
                    append("已上传 $uploaded/${voices.size} 个音色到电脑")
                    if (failures.isNotEmpty()) append("；未成功：").append(failures.joinToString("、"))
                },
                remoteVoices = probe?.speakers?.takeIf { it.isNotEmpty() } ?: ttsState.value.remoteVoices,
            )
            refreshTtsState(core.currentConfig())
        }
    }

    /**
     * One-tap voice sync. Stable file IDs identify a voice; display names are the
     * user-facing field and the phone always wins a name conflict. Same-ID files with
     * different content are reported, never overwritten. PC-only files are downloaded;
     * phone-only files are uploaded. A name collision gets `_1`/`_2` on the side that
     * loses the base name.
     */
    fun syncTtsVoices() {
        if (ttsState.value.busy) return
        if (ttsState.value.remoteUrl.isBlank()) {
            ttsState.value = ttsState.value.copy(remoteStatus = "请先配置电脑端服务器地址")
            return
        }
        ttsState.value = ttsState.value.copy(busy = true, remoteStatus = "正在同步音色和名称…")
        viewModelScope.launch {
            val cfg = core.currentConfig()
            val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { core.cosyVoice.listVoices() }.getOrDefault(emptyList())
            }
            val remote = runCatching { core.listTtsVoices(cfg) }.getOrDefault(emptyList())
            val remoteConfig = core.remoteTtsConfig(cfg)
            var uploaded = 0
            var downloaded = 0
            var renamed = 0
            val skipped = mutableListOf<String>()
            val usedRemoteNames = remote.map { it.name }.toMutableList()
            val remoteNamesById = remote.associate { it.id to it.name }.toMutableMap()

            suspend fun renameRemote(id: String, name: String): Boolean {
                val target = uniqueVoiceName(name, usedRemoteNames)
                val error = core.renameTtsVoice(cfg, id, target)
                if (error != null) {
                    skipped += "$id：重命名失败（$error）"
                    return false
                }
                val old = remoteNamesById[id]
                if (old != null) usedRemoteNames.remove(old)
                usedRemoteNames += target
                remoteNamesById[id] = target
                renamed++
                return true
            }

            if (remote.isEmpty()) {
                // Older PC server: no manifest, so only the existing upload direction works.
                for (voice in local) {
                    val file = core.cosyVoice.voiceFileFor(voice.file) ?: continue
                    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { file.readBytes() }.getOrNull()
                    } ?: continue
                    val result = runCatching {
                        core.uploadTtsVoice(
                            remoteConfig,
                            voice.file.removeSuffix(".gguf"),
                            bytes,
                            displayName = voice.name,
                        )
                    }.getOrElse { RemoteVoiceUpload(voice.file, false, it.message ?: "上传失败") }
                    if (result.ok) uploaded++ else skipped += "${voice.name}：${result.message}"
                }
                val refreshed = runCatching { core.listTtsVoices(core.currentConfig()) }.getOrDefault(emptyList())
                ttsState.value = ttsState.value.copy(
                    busy = false,
                    remoteVoices = refreshed.map { it.id },
                    remoteVoiceNames = refreshed.associate { it.id to it.name },
                    remoteStatus = "电脑端未提供音色清单，已上传 $uploaded/${local.size} 个；升级电脑端后可双向同步名称",
                )
                refreshTtsState(core.currentConfig())
                return@launch
            }

            // Phone-side voices: upload missing ones and let the phone's name win.
            for (voice in local) {
                val id = voice.file.removeSuffix(".gguf")
                val remoteVoice = remote.firstOrNull { it.id == id }
                val localFile = core.cosyVoice.voiceFileFor(voice.file)
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    localFile?.let { runCatching { it.readBytes() }.getOrNull() }
                }
                if (bytes == null) {
                    skipped += "${voice.name}：本机文件缺失"
                    continue
                }
                if (remoteVoice == null) {
                    // A different PC voice may already own this display name; the phone
                    // wins the base name, so move the PC one to _1/_2 first.
                    remote.filter { it.id != id && remoteNamesById[it.id] == voice.name }
                        .forEach { conflict -> renameRemote(conflict.id, voice.name) }
                    val result = runCatching {
                        core.uploadTtsVoice(remoteConfig, id, bytes, displayName = voice.name)
                    }.getOrElse { RemoteVoiceUpload(id, false, it.message ?: "上传失败") }
                    if (result.ok) {
                        uploaded++
                        usedRemoteNames += voice.name
                    } else {
                        skipped += "${voice.name}：${result.message}"
                    }
                    continue
                }
                val sameBytes = if (remoteVoice.hash.isNotEmpty()) {
                    remoteVoice.hash == fnv1aHex(bytes)
                } else {
                    remoteVoice.size == bytes.size.toLong()
                }
                if (!sameBytes) {
                    skipped += "${voice.name}：两端文件内容不同，已保留两端"
                    continue
                }
                if (remoteVoice.name != voice.name) {
                    // Free the phone's base name first: another PC voice may already own it.
                    remote.filter { it.id != id && remoteNamesById[it.id] == voice.name }
                        .forEach { conflict -> renameRemote(conflict.id, voice.name) }
                    renameRemote(id, voice.name)
                }
            }

            // The rename pass above may have changed several PC names. Refresh the manifest
            // before downloading so suffixes are computed from the actual current names.
            val remoteAfterLocal = runCatching { core.listTtsVoices(core.currentConfig()) }
                .getOrDefault(remote)
            usedRemoteNames.clear()
            usedRemoteNames.addAll(remoteAfterLocal.map { it.name })
            remoteNamesById.clear()
            remoteNamesById.putAll(remoteAfterLocal.associate { it.id to it.name })

            // PC-only voices: download them and suffix the display name if it collides.
            val localNames = local.map { it.name }.toMutableSet()
            val localIds = local.map { it.file.removeSuffix(".gguf") }.toSet()
            for (voice in remoteAfterLocal) {
                if (voice.id in localIds) continue
                val bytes = runCatching { core.downloadTtsVoice(cfg, voice.id) }.getOrNull()
                if (bytes == null) {
                    skipped += "${voice.name}：下载失败"
                    continue
                }
                val name = uniqueVoiceName(voice.name, localNames)
                if (name != voice.name) {
                    // Keep the PC side in sync with the suffixed name we are about to store
                    // locally; otherwise the two ends would show different names.
                    renameRemote(voice.id, name)
                }
                val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { core.cosyVoice.importDownloadedVoice(voice.id, bytes, name) }.getOrNull()
                }
                if (imported == null) {
                    skipped += "${voice.name}：写入本机失败"
                } else {
                    localNames += name
                    downloaded++
                }
            }

            val refreshed = runCatching { core.listTtsVoices(core.currentConfig()) }.getOrDefault(remote)
            ttsState.value = ttsState.value.copy(
                busy = false,
                remoteVoices = refreshed.map { it.id },
                remoteVoiceNames = refreshed.associate { it.id to it.name },
                remoteStatus = buildString {
                    append("同步完成：上传 $uploaded，下载 $downloaded，改名 $renamed")
                    if (skipped.isNotEmpty()) append("；未处理：").append(skipped.joinToString("、"))
                },
            )
            refreshTtsState(core.currentConfig())
        }
    }

    /** Renames a server-side display name without changing its speaker ID. */
    fun renameRemoteVoice(id: String, name: String) {
        val trimmed = name.trim()
        if (id.isBlank() || trimmed.isEmpty()) return
        viewModelScope.launch {
            val error = runCatching { core.renameTtsVoice(core.currentConfig(), id, trimmed) }
                .getOrElse { it.message ?: "重命名失败" }
            ttsState.value = if (error == null) {
                ttsState.value.copy(
                    remoteVoiceNames = ttsState.value.remoteVoiceNames + (id to trimmed),
                    remoteStatus = "已重命名电脑音色：$trimmed",
                )
            } else {
                ttsState.value.copy(remoteStatus = "重命名失败：$error")
            }
        }
    }

    private fun uniqueVoiceName(base: String, used: Collection<String>): String {
        if (base !in used) return base
        var index = 1
        while ("${base}_$index" in used) index++
        return "${base}_$index"
    }

    private fun fnv1aHex(bytes: ByteArray): String {
        var hash = 1469598103934665603uL
        for (byte in bytes) {
            hash = hash xor (byte.toInt() and 0xff).toULong()
            hash *= 1099511628211uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    /** "自动发现": sweeps the phone's own subnets for the server and stores the address. */
    fun discoverTtsServer() {        if (ttsState.value.busy) return
        ttsState.value = ttsState.value.copy(busy = true, remoteStatus = "正在扫描本机网段…")
        viewModelScope.launch {
            val cfg = core.currentConfig()
            val found = runCatching { core.findTtsServer(cfg) }.getOrNull()
            if (found != null) {
                core.data.config.update(core.currentConfig().copy(ttsRemoteUrl = found))
                val probe = runCatching { core.testTtsConnection(core.currentConfig()) }.getOrNull()
                if (probe != null && probe.sampleRate > 0) {
                    core.data.config.update(core.currentConfig().copy(ttsRemoteSampleRate = probe.sampleRate))
                }
                ttsState.value = ttsState.value.copy(
                    busy = false,
                    remoteStatus = "已找到服务器：$found（${probe?.message ?: "已连接"}）",
                    remoteVoices = probe?.speakers ?: ttsState.value.remoteVoices,
                )
            } else {
                ttsState.value = ttsState.value.copy(
                    busy = false,
                    remoteStatus = "没有找到服务器。请确认电脑已启动 cosyvoice-server，且手机已开启 USB 网络共享、蓝牙网络共享或手机热点",
                )
            }
            refreshTtsState(core.currentConfig())
        }
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

    // ------------------------------------------------------------- read-aloud

    private var ttsTimerJob: Job? = null
    private var ttsGeneration = 0

    /**
     * Reads an AI reply aloud. Stage directions are stripped and each turn's
     * tone instruction comes from the speaker's `currentTone` dynamic field (so
     * no extra API call is needed). Group replies are split per member, and each
     * member's line uses that member's own tone. Only one request runs at a
     * time; starting a new one preempts the previous.
     */
    fun speakMessage(message: ChatMessage, character: Character?) {
        val segments = buildSpeechSegments(message.content, character, message.segments)
        if (segments.isEmpty()) {
            eventsState.tryEmit(UiEvent("这条消息没有可朗读的内容"))
            return
        }
        startSpeak(message.id, segments, character)
    }

    /** Bubble tap: starts read-aloud, or stops it when this message is already active. */
    fun onBubbleReadAloud(message: ChatMessage, character: Character?) {
        val busy = ttsState.value.phase == TtsPhase.Synthesizing || ttsState.value.phase == TtsPhase.Playing
        if (busy && ttsState.value.activeMessageId == message.id) stopSpeaking() else speakMessage(message, character)
    }

    /** Settings voice preview: synthesizes the typed text with no tone instruction. */
    fun speakText(text: String) {
        val speech = text.trim()
        if (speech.isEmpty()) return
        startSpeak(null, listOf(SpeechSegment(speaker = "", text = speech)), null)
    }

    /** Auto-reads a freshly generated reply (no bubble highlight). */
    private fun speakReply(persisted: List<SpeechSegment>, reply: String, character: Character?) {
        val segments = buildSpeechSegments(reply, character, persisted)
        if (segments.isEmpty()) return
        startSpeak(null, segments, character)
    }

    /** Splits a reply into speakable turns: per member for groups, else one turn. */
    private fun buildSpeechSegments(
        content: String,
        character: Character?,
        persisted: List<SpeechSegment> = emptyList(),
    ): List<SpeechSegment> {
        if (persisted.isNotEmpty()) {
            return persisted.mapNotNull { segment ->
                val speech = extractSpeechTextFromSegment(segment.text)
                if (speech.isEmpty()) null else SpeechSegment(segment.speaker, speech)
            }
        }
        if (character != null && character.isGroup) {
            return extractSpeechSegments(content, character.members.map { it.name })
        }
        val speech = extractSpeechText(content)
        return if (speech.isEmpty()) emptyList() else listOf(SpeechSegment(speaker = "", text = speech))
    }

    /** Tone instruction for one spoken turn: the speaker's `currentTone`. */
    private fun toneFor(segment: SpeechSegment, character: Character?): String? {
        val raw = if (segment.speaker.isNotBlank()) {
            val memberTone = character?.members
                ?.firstOrNull { it.name == segment.speaker }
                ?.dynamicState?.currentTone
                .orEmpty().ifBlank { character?.dynamicState?.currentTone.orEmpty() }
            memberTone
        } else {
            character?.dynamicState?.currentTone
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun startSpeak(messageId: String?, segments: List<SpeechSegment>, character: Character?) {
        val generation = ++ttsGeneration
        val previous = ttsJob
        ttsJob = viewModelScope.launch {
            // Preempt: only cancel a genuinely running request. Cancelling an
            // already-finished job still stops the player (pause+flush), which
            // used to silence every read-aloud after the first one.
            if (previous?.isActive == true) {
                previous.cancel()
                withContext(Dispatchers.IO) { runCatching { activeSpeaker?.cancel() } }
            }
            val cfg = core.currentConfig()
            if (!cfg.ttsEnabled) {
                eventsState.tryEmit(UiEvent("请先在设置-语音朗读中开启"))
                return@launch
            }
            if (cfg.ttsBackend == TTS_BACKEND_REMOTE && cfg.ttsRemoteUrl.isBlank()) {
                eventsState.tryEmit(UiEvent("请先在设置-语音朗读中配置远程服务器地址，或点「查找并测试电脑」自动发现"))
                return@launch
            }
            if (generation != ttsGeneration) return@launch
            ttsState.value = ttsState.value.copy(
                speaking = true,
                activeMessageId = messageId,
                phase = TtsPhase.Synthesizing,
                elapsedMs = 0L,
                status = "正在合成…",
            )
            startTtsTimer()
            val speaker = core.ttsSpeaker(cfg)
            activeSpeaker = speaker
            val turns = segments.map { TtsTurn(it.text, toneFor(it, character)) }
            if (generation != ttsGeneration) return@launch
            val ok = runCatching {
                speaker.speak(turns) { phase ->
                    viewModelScope.launch {
                        if (generation == ttsGeneration) ttsState.value = ttsState.value.copy(phase = phase)
                    }
                }
            }.onFailure { t ->
                if (generation == ttsGeneration) eventsState.tryEmit(UiEvent("朗读失败：${t.message}"))
            }.isSuccess
            if (generation != ttsGeneration) return@launch
            stopTtsTimer()
            ttsState.value = ttsState.value.copy(phase = if (ok) TtsPhase.Done else TtsPhase.Error, status = "")
            delay(TTS_DONE_LINGER_MS)
            if (generation == ttsGeneration) {
                ttsState.value = ttsState.value.copy(
                    speaking = false, activeMessageId = null, phase = TtsPhase.Idle, elapsedMs = 0L, status = "",
                )
            }
        }
    }

    private fun startTtsTimer() {
        ttsTimerJob?.cancel()
        val startedAt = System.currentTimeMillis()
        ttsTimerJob = viewModelScope.launch {
            while (isActive) {
                ttsState.value = ttsState.value.copy(elapsedMs = System.currentTimeMillis() - startedAt)
                delay(200)
            }
        }
    }

    private fun stopTtsTimer() {
        ttsTimerJob?.cancel()
        ttsTimerJob = null
    }

    /** Stops any running read-aloud (playback + synthesis on the active engine). */
    fun stopSpeaking() {
        ++ttsGeneration
        ttsJob?.cancel()
        ttsJob = null
        stopTtsTimer()
        viewModelScope.launch { withContext(Dispatchers.IO) { runCatching { activeSpeaker?.cancel() } } }
        ttsState.value = ttsState.value.copy(
            speaking = false, activeMessageId = null, phase = TtsPhase.Idle, elapsedMs = 0L, status = "",
        )
    }

    // Startup side effects live in a TRAILING init block on purpose: Kotlin runs
    // initializer blocks and property initializers in textual order, and
    // viewModelScope uses Dispatchers.Main.immediate, so a launch here executes
    // synchronously until its first suspension. Running this from the top of the
    // class would touch state flows declared further down (avatarRepairProgress,
    // ttsState, lastUserActivityAt, appVisible, themeState, ...) before they are
    // initialized -> NullPointerException. Declared last, every property is ready.
    init {
        // A world-book batch that failed repeatedly is held in memory; ask the user whether
        // to retry it or drop it (dropping is their call and is recorded, not silent).
        core.onLorebookRetryNeeded = { characterId, reason ->
            viewModelScope.launch {
                if (lorebookRetryState.value?.characterId == characterId) return@launch
                val name = core.data.characters.get(characterId)?.name.orEmpty()
                lorebookRetryState.value = LorebookRetryPrompt(
                    characterId = characterId,
                    characterName = name.ifBlank { "该角色" },
                    reason = reason,
                )
            }
        }
        core.onMemoryRetryNeeded = { kind, characterId, reason ->
            viewModelScope.launch {
                val name = core.data.characters.get(characterId)?.name.orEmpty()
                enqueueMemoryRetry(
                    MemoryRetryUi(
                        kind = kind,
                        characterId = characterId,
                        characterName = name.ifBlank { "该角色" },
                        reason = reason.ifBlank { "请求失败" },
                    ),
                )
            }
        }
        viewModelScope.launch {
            core.data.characters.observeAll().collect { list ->
                if (activeIdState.value == null && list.isNotEmpty()) {
                    activeIdState.value = list.first().id
                }
                if (!memoryPromptsRestored && list.isNotEmpty()) {
                    memoryPromptsRestored = true
                    list.forEach { character ->
                        if (character.counters.extractionFailures >= 3 &&
                            !character.counters.extractionPromptAcked
                        ) {
                            enqueueMemoryRetry(
                                MemoryRetryUi(
                                    kind = MemoryTaskKind.Extraction,
                                    characterId = character.id,
                                    characterName = character.name.ifBlank { "该角色" },
                                    reason = "请求失败",
                                ),
                            )
                        }
                        if (character.counters.analysisFailures >= 3 &&
                            !character.counters.analysisPromptAcked
                        ) {
                            enqueueMemoryRetry(
                                MemoryRetryUi(
                                    kind = MemoryTaskKind.Analysis,
                                    characterId = character.id,
                                    characterName = character.name.ifBlank { "该角色" },
                                    reason = character.counters.analysisLastError
                                        .ifBlank { "请求失败" }
                                        .take(80),
                                ),
                            )
                        }
                    }
                }
            }
        }
        viewModelScope.launch { proactiveLoop() }
        viewModelScope.launch { themeLoop() }
        // Continue a confirmed memory cleanup quietly (one launch chunk, then idle chunks).
        viewModelScope.launch {
            runCatching { runMemoryCleanupChunk(AppLimits.Memory.CLEANUP_BATCHES_PER_RUN) }
                .onFailure { DeepTalkingApp.recordError(it) }
        }
        viewModelScope.launch { memoryCleanupLoop() }
        // 自动清理超过保留期的已完成 / 已取消待办（与长期记忆无关）。
        viewModelScope.launch {
            runCatching { pruneClosedTodos() }.onFailure { DeepTalkingApp.recordError(it) }
        }
        // Adopt the legacy `AppConfig.requestMetrics` into the independent metrics store
        // **once** (no-op once the store has been written). After this, the config field is
        // only ever read by backups/migration — never written at runtime.
        viewModelScope.launch {
            runCatching {
                val cfg = core.data.config.observe().first()
                core.data.metrics.migrateLegacy(cfg.requestMetrics)
            }.onFailure { DeepTalkingApp.recordError(it) }
        }
        // Legacy one-time relative-time → absolute migration (no key required).
        viewModelScope.launch {
            runCatching { core.runStartupMigrations() }.onFailure { DeepTalkingApp.recordError(it) }
            runCatching { core.runMemoryRepair() }.onFailure { DeepTalkingApp.recordError(it) }
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
        // One-time startup prompts, in order: oversized window (blocks while it runs) →
        // short-term/image cleanup → world-book rebuild. Each waits for the previous one.
        viewModelScope.launch {
            runCatching { checkOversizedWindowMigration() }.onFailure { DeepTalkingApp.recordError(it) }
            if (memoryMigrationPromptState.value == null && memoryMigrationState.value == null) {
                continueStartupPrompts()
            }
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
            if (core.cosyVoice.voiceFile == null) {
                core.cosyVoice.listVoices().firstOrNull()?.let { v ->
                    core.cosyVoice.selectVoice(v.file)?.let { resolved ->
                        core.data.config.update(core.currentConfig().copy(ttsVoiceFile = resolved.absolutePath))
                    }
                }
            }
            refreshTtsState(core.currentConfig())
        }
    }
}

/**
 * Phase label of the `chat.worker` metric row.
 *
 * A failed worker keeps the `worker-failed` marker; every note the worker produced (a todo
 * rejected by [TodoPolicy], a malformed `quickReplies` count, an unparseable forced call)
 * is appended, so an item the worker silently dropped is still traceable from the settings
 * debug log. A successful worker with no notes leaves the phase empty.
 */
internal fun workerPhase(result: OrchestratorResult): String {
    val notes = result.workerNotes.joinToString("；")
    return when {
        !result.workerOk -> if (notes.isEmpty()) "worker-failed" else "worker-failed：$notes"
        notes.isEmpty() -> ""
        else -> "worker-note：$notes"
    }
}

/**
 * The turn worker's `chat.worker` row.
 *
 * Written when the worker reported usage, failed, **or** left any note: a failed forced call
 * still costs a request and a dropped todo/quick-reply is a real outcome, so neither may
 * vanish. Nothing is written only when the worker did not run and reported nothing.
 */
internal fun workerMetricRow(
    at: String,
    cfg: AppConfig,
    characterId: String,
    result: OrchestratorResult,
): RequestMetric? {
    val usage = result.workerUsage
    if (usage == null && result.workerOk && result.workerNotes.isEmpty()) return null
    val input = usage?.inputTokens?.takeIf { it > 0 }
    val hit = if (input != null) (usage?.cachedTokens ?: 0).coerceAtLeast(0) else null
    val miss = if (input != null && hit != null) (input - hit).coerceAtLeast(0) else null
    return RequestMetric(
        at = at,
        taskType = TASK_CHAT_WORKER,
        characterId = characterId,
        model = cfg.modelName,
        platform = cfg.apiPlatform,
        status = if (result.workerOk) "completed" else "failed",
        phase = workerPhase(result),
        inputTokens = input,
        outputTokens = if (input != null) usage?.outputTokens else null,
        hitTokens = hit,
        missTokens = miss,
        hitRate = if (input != null && hit != null) hit.toDouble() / input else null,
    )
}

/**
 * `RequestMetric.sectionChars` of a turn: the volatile-context sections the request really
 * carried (`ChatOrchestrator` already omits dropped ones). Empty → `null`, so legacy rows
 * and turns without prompt assembly stay distinguishable from a genuinely measured turn.
 */
internal fun metricSectionChars(result: OrchestratorResult): Map<String, Int>? {
    val kept = result.sectionChars.filterValues { it > 0 }
    return kept.ifEmpty { null }
}
