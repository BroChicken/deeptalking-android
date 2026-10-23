package com.deeptalking.domain.agent

import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.DynamicStateMeta
import com.deeptalking.core.model.FieldEditRegistry
import com.deeptalking.core.model.FieldSection
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.SpeechSegment
import com.deeptalking.core.model.Todo
import com.deeptalking.core.model.TodoProposer
import com.deeptalking.core.model.TodoStatus
import com.deeptalking.core.model.parseGroupSpeechSegments
import com.deeptalking.domain.agent.prompts.DYNAMIC_STATE_FIELDS
import com.deeptalking.domain.agent.prompts.PersonaInputs
import com.deeptalking.domain.agent.prompts.RequestBuilder
import com.deeptalking.domain.agent.prompts.buildShortTermBlock
import com.deeptalking.domain.agent.prompts.buildSystemPrompt
import com.deeptalking.domain.agent.prompts.withDynamicField
import com.deeptalking.domain.agent.background.BackgroundTasks
import com.deeptalking.domain.agent.background.STYLE_GUARD
import com.deeptalking.domain.agent.background.dedupeRepeatedEnding
import com.deeptalking.domain.agent.background.detectStyleViolations
import com.deeptalking.domain.agent.background.recentReplyTexts
import com.deeptalking.domain.agent.tools.MemoryToolSupport
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.domain.memory.ShortTermDraft
import com.deeptalking.domain.memory.TimeRef
import com.deeptalking.domain.memory.addShortTermMemory
import com.deeptalking.domain.memory.consumeInjectedRecalls
import com.deeptalking.domain.memory.SourceRef
import com.deeptalking.domain.memory.TodoPolicy
import com.deeptalking.domain.memory.dueAtBackedByEvidence
import com.deeptalking.domain.memory.hasValidUserEvidence
import com.deeptalking.domain.memory.knownSources
import com.deeptalking.domain.memory.parseRelativeText
import com.deeptalking.domain.memory.parseZoned
import com.deeptalking.domain.memory.resolveDynamicStateSources
import com.deeptalking.domain.memory.resolveMemoryConflicts
import com.deeptalking.domain.memory.resolveTimeRef
import com.deeptalking.domain.memory.upsertLongTermMemory
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.TokenUsage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

/** Automatic worker retries after the first failed forced call. */
private const val WORKER_MAX_RETRIES = 2

/** Result of one user turn. */
data class OrchestratorResult(
    val reply: String,
    val quickReplies: List<String>,
    val updatedCharacter: Character,
    /** True when this turn was a proactive (unprompted) opening. */
    val proactive: Boolean = false,
    /** Lorebook entry names created/updated this turn (legacy `lorebookChanges`). */
    val lorebookChanges: List<String> = emptyList(),
    /** File ref of a sticker the character chose to send this turn, if any. */
    val stickerFileRef: String? = null,
    /** Raw (pre-processing) model reply, for the settings debug panel. */
    val rawReply: String = "",
    /**
     * Token usage totalled over **every** model request this turn made, or all-zero
     * when no request reported usage (the caller then records "no data" rather than a
     * 0 % cache hit).
     */
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    /**
     * Usage of **each** main-chat request this turn made, in request order (tool rounds
     * included). One entry per `chat.main` metric row; [inputTokens]/[outputTokens]/
     * [cachedTokens] above stay the turn totals for callers that want the aggregate.
     */
    val roundUsages: List<TokenUsage> = emptyList(),
    /** Usage reported by the turn-worker (`submit_state`) call, if it reported any. */
    val workerUsage: TokenUsage? = null,
    /** True when the turn worker's forced call succeeded and its state was applied. */
    val workerOk: Boolean = false,
    /** True when this turn actually attempted the worker (not a proactive/no-background turn). */
    val workerRan: Boolean = false,
    /** One-word failure label when [workerOk] is false. */
    val workerFailure: String = "",
    /** Persisted short rule-rejection hints after this turn. */
    val workerIssues: List<String> = emptyList(),
    /** Synthetic reply id the worker used; the app reuses it for a manual retry. */
    val workerReplyId: String = "",
    /** User text of the turn, kept only for a manual worker retry. */
    val workerUserText: String = "",
    /**
     * Problems the worker pass hit without failing the turn: a `todos` entry rejected by
     * [TodoPolicy] (with the reason), a malformed `quickReplies` count, a request that
     * returned nothing parseable. The reply is unaffected; the metrics pass records these
     * so a silently dropped item can still be traced.
     */
    val workerNotes: List<String> = emptyList(),
    /**
     * Group replies only: the per-member turns of this single assistant message
     * (speaker + display text). The caller persists them **with** the message so the chat
     * can draw one bubble per member and read-aloud gets real speakers; history and the
     * `instant` window still count the reply as one message. Empty for single characters.
     */
    val segments: List<SpeechSegment> = emptyList(),
    /** FNV-1a hash of the system instructions (legacy `instructionsHash`). */
    val instructionsHash: String = "",
    /** FNV-1a hash of this request's tool definitions (legacy `toolsHash`). */
    val toolsHash: String = "",
    /** Character count of the per-turn volatile context message (legacy `contextChars`). */
    val contextChars: Int = 0,
    /**
     * Characters per volatile-context section of this request (section key → chars), for
     * `RequestMetric.sectionChars`. Only sections the request actually carried are listed:
     * a section dropped to fit the hard cap is omitted rather than reported as 0 chars.
     */
    val sectionChars: Map<String, Int> = emptyMap(),
)

/**
 * High-level turn orchestrator: assemble prompts, run the tool loop, parse the
 * structured turn, and fold memory updates onto the character.
 *
 * Dynamic/member state is applied through the shared evidence gates
 * ([hasValidUserEvidence], [resolveDynamicStateSources]) and long-term writes through
 * [upsertLongTermMemory], mirroring the legacy `applyMemoryUpdate`; promise updates go
 * through [MemoryService.updateMemory]. Static profile fields are never written from a
 * chat turn. The legacy background tasks (prose memory extraction, quick-reply repair,
 * style critique) are dispatched through [BackgroundTasks] when one is supplied.
 */
class ChatOrchestrator(
    private val llm: LlmBackend,
    private val tools: ToolRegistry,
    private val memory: MemoryService,
    private val config: AppConfig = AppConfig(),
    private val backgroundTasks: BackgroundTasks? = null,
    private val runBackgroundTasks: Boolean = true,
    /**
     * Background-pass result: `(before, after)`, where `before` is the character the pass
     * started from. The app writes **only** the difference between the two onto the
     * freshest stored row (see `mergeCharacterChanges`), so a pass that took seconds can no
     * longer roll back a world-book entry or a memory the user/chat added meanwhile.
     */
    private val onCharacterUpdated: (Character, Character) -> Unit = { _, _ -> },
    private val imageResolver: ((String) -> String?)? = null,
) {

    suspend fun run(
        character: Character,
        history: List<ChatMessage>,
        userText: String,
        onDelta: (String) -> Unit = {},
        onToolActivity: (String) -> Unit = {},
        onQuickRepliesRepaired: (List<String>) -> Unit = {},
        proactive: Boolean = false,
        /**
         * 小概率的「热榜开场」轮（见 [HotTopicPolicy]）：本轮先查热榜再起话题。只影响
         * 本轮 volatile 上下文里那条指令，不进 system prompt，所以不会打乱缓存前缀。
         */
        hotTopicTurn: Boolean = false,
    ): OrchestratorResult {
        val builder = RequestBuilder(config, imageResolver)
        val instructions = buildSystemPrompt(PersonaInputs(character, config, proactive))
        val context = AgentContext(character = character, activeCharacterId = character.id)

        // Append-only: the fold pass is the only place that trims the window. Taking
        // `takeLast(INSTANT)` here would slide the prefix by the new messages every turn.
        val mapped = builder.mapHistory(history).toMutableList()
        // The history must end with the user's turn. A proactive opening has **no** user
        // message at all — appending an empty placeholder would tell the model the user
        // just said nothing, so the request keeps the real (user-less) shape instead.
        if (userText.isNotBlank() && mapped.lastOrNull()?.role != Role.User) {
            mapped += ChatMessage(role = Role.User, content = userText, internalOnly = proactive)
        }
        val recentAssistantReplies = history
            .filter { it.role == Role.Assistant && !it.isLoading && it.content.isNotBlank() }
            .map { it.content }
        val volatile = com.deeptalking.domain.agent.prompts.buildVolatileContextForTurn(
            character = character,
            query = userText,
            memory = memory,
            proactiveTurn = proactive,
            hotTopicTurn = hotTopicTurn,
            recentAssistantReplies = recentAssistantReplies,
        )
        val contextMessage = ChatMessage(
            role = Role.User,
            content = "【系统提供的本轮上下文，仅供角色理解，不代表用户陈述】\n" + volatile.text,
        )
        // The short-term window is prepended, *before* the history. The window is only
        // appended to by the fold pass, so between folds the block is byte-identical and
        // the cached request prefix covers it; when it does change the history was
        // rebased by that same fold, so the prefix was already broken (see
        // `buildShortTermBlock`).
        val shortTermBlock = buildShortTermBlock(character)
        val input = if (shortTermBlock.isNotEmpty()) {
            listOf(ChatMessage(role = Role.User, content = shortTermBlock)) + mapped + contextMessage
        } else {
            mapped + contextMessage
        }

        val autoTools = tools.definitions(context)
        val instructionsHash = fnv1a(instructions)
        val toolsHash = fnv1a(
            autoTools.joinToString("\n") { "${it.name}|${it.description}|${it.parametersJson}|${it.strict}" },
        )
        val loop = AgentLoop(llm, tools)
        val outcome = loop.run(
            character = character,
            instructions = instructions,
            baseInput = input,
            autoTools = autoTools,
            builder = builder,
            model = config.modelName,
            onDelta = onDelta,
            onToolActivity = onToolActivity,
        )

        // Plain-text protocol: the assistant text is the reply. JSON output is only
        // accepted as a whole object (legacy/cached-prompt compatibility).
        val parsed = ResponseParser.parseTurnFromText(outcome.text)

        // Quick replies are produced by the turn worker (decision 2/7).

        // Tool-driven edits win first; memory deltas fold onto that copy.
        // The pending recall surfaced in this turn's volatile context is consumed here;
        // applyMemory may set a fresh one from this turn's `recall`.
        // Legacy scans the recent instant window (not just the current query) for
        // lorebook keyword hits.
        val lorebookQuery = buildString {
            val recent = character.instant
                .filter { !it.isLoading && it.content.isNotBlank() }
                .takeLast(6)
                .joinToString("\n") { it.content }
            if (recent.isNotEmpty()) append(recent).append('\n')
            append(userText)
        }
        val memoryAssistant = ChatMessage(
            id = "assistant_" + character.id,
            role = Role.Assistant,
            content = parsed.reply,
        )
        val memoryInstant = (character.instant + history.filter { !it.rejected } + memoryAssistant)
            .distinctBy { it.id }
        val recalled = outcome.updatedCharacter ?: character
        // Consume exactly the recall ids rendered in this turn's volatile context (the
        // rendered set is capped by the recall budget, not by the whole pending list).
        val injectedRecallIds = volatile.recallIds
        val base = consumeInjectedRecalls(
            bumpLorebookMentions(
                recalled,
                memory.selectLorebook(character, lorebookQuery),
            ).copy(instant = memoryInstant),
            injectedRecallIds,
        )
        val updated = applyTone(base, parsed.tone)

        // The visible reply may be replaced by the style critique before it is shown,
        // matching the legacy flow (critique runs inline; repair + extraction run after).
        val previousReply = recentReplyTexts(character, 1).firstOrNull()
        val critiqued = if (backgroundTasks != null && runBackgroundTasks && config.styleCritique) {
            backgroundTasks.critiqueStyle(updated, parsed.reply)
        } else {
            parsed.reply
        }
        val displayReply = dedupeRepeatedEnding(critiqued, previousReply)
        // Client-side style violation detection (legacy `detectStyleViolations`), stored
        // on the assistant turn so the review pass can report "最近几轮问题".
        val styleViolations = detectStyleViolations(
            displayReply,
            updated,
            recentReplyTexts(character, STYLE_GUARD.lookbackReplies),
        )

        // Mirror the live conversation window into `character.instant` (legacy
        // `memory.instant`): tools (add_todo), the volatile context and the
        // background tasks all read user/reply IDs and recent replies from here.
        // Group replies stay one assistant message: the per-member turns ride along as
        // `segments` (display text, quotes and stage directions kept verbatim) so the UI can
        // draw one bubble per member and read-aloud gets real speakers. Single characters
        // keep an empty list, and the streaming path never writes segments (the reply is
        // only final here, after critique/dedupe).
        val replySegments = groupSpeechSegments(character, displayReply)
        val assistantMessage = ChatMessage(
            id = "assistant_" + character.id,
            role = Role.Assistant,
            content = displayReply,
            styleViolations = styleViolations,
            segments = replySegments,
        )
        // Append-only: the window grows with the turn and is only compacted by the fold
        // pass (which consumes exactly the batch it read). Clamping here would drop the oldest
        // message outside a fold — before it was ever folded (silent loss) and in the
        // middle of a stable period (a cache miss the request prefix cannot predict).
        val instantWindow = history.filter { !it.rejected } + assistantMessage
        val characterWithInstant = updated.copy(instant = instantWindow, revision = character.revision + 1)

        // Turn worker: produces dynamicState (without currentTone), quickReplies, todos and
        // promise status changes through one forced `submit_state` call. Failure is not
        // fatal: the reply is already final, so we keep the previous state (decision 6).
        val workerRan = backgroundTasks != null && runBackgroundTasks
        val worker = if (workerRan) {
            runTurnWorker(
                character = characterWithInstant,
                userText = userText,
                reply = displayReply,
                replyId = assistantMessage.id,
                proactive = proactive,
                onStatus = onToolActivity,
            )
        } else {
            WorkerResult(character = characterWithInstant)
        }
        // A successful worker replaces the previous turn's hints; a failed one keeps
        // them (the next attempt still needs the same reminders). Static profile fields
        // are deliberately not written from a chat turn: they change only through the
        // character editor (including its manual per-field regenerate button).
        val finalCharacter = worker.character.copy(
            workerIssues = if (worker.ok) worker.issues else worker.character.workerIssues,
        )

        if (backgroundTasks != null && runBackgroundTasks) {
            backgroundTasks.scheduleTurn(
                character = finalCharacter,
                reply = displayReply,
                userText = userText,
                currentQuickReplies = worker.quickReplies,
                messages = history.filter { !it.isLoading } + assistantMessage,
                critiqueEnabled = config.styleCritique,
                // The worker is the only producer of quick replies. The legacy
                // "as the user" repair round may only step in when the worker did **not**
                // succeed (or did not run at all); a successful worker already handed back
                // its two lines, and a repair would just race it (and re-open the
                // `issues`-non-empty case on every turn).
                quickReplyRepairEnabled = quickReplyRepairEnabled(config, worker.ok, worker.quickReplies.size),
                onCharacterUpdated = { bg -> onCharacterUpdated(characterWithInstant, bg) },
                onQuickRepliesRepaired = onQuickRepliesRepaired,
                styleViolations = styleViolations,
            )
        }

        return OrchestratorResult(
            reply = displayReply,
            quickReplies = worker.quickReplies,
            updatedCharacter = finalCharacter,
            proactive = proactive,
            lorebookChanges = diffLorebookChanges(character, finalCharacter),
            stickerFileRef = outcome.stickerFileRef,
            rawReply = outcome.text,
            // Turn totals across **every** request the turn made (tool rounds + the
            // forced submit round). The first request is the one that carries a
            // cache-breaking prefix, so reporting only the last one (which is usually
            // re-warmed by the same turn) hid every real cache miss. A round that
            // reported usage without cache detail counts as uncached, matching the
            // provider's own "no cache detail = no hit reported" semantics.
            inputTokens = outcome.roundUsages.sumOf { it.inputTokens },
            outputTokens = outcome.roundUsages.sumOf { it.outputTokens },
            cachedTokens = outcome.roundUsages.sumOf { it.cachedTokens ?: 0 },
            roundUsages = outcome.roundUsages,
            workerUsage = worker.usage,
            workerOk = worker.ok,
            workerRan = workerRan,
            workerFailure = worker.failure,
            workerIssues = finalCharacter.workerIssues,
            workerReplyId = assistantMessage.id,
            workerUserText = userText,
            workerNotes = worker.notes,
            segments = replySegments,
            instructionsHash = instructionsHash,
            toolsHash = toolsHash,
            contextChars = contextMessage.content.length,
            // Only the sections actually kept in the text: `dropped` ones are 0 chars and
            // the model never saw them, so they must not show up as a (tiny) section.
            sectionChars = volatile.sections
                .filter { !it.dropped && it.chars > 0 }
                .associate { it.name to it.chars },
        )
    }

    /**
     * Re-runs only the turn worker for a previous turn (the red failed-state action).
     * The assistant reply is not regenerated; the caller merges the returned character
     * onto the live row and replaces the quick replies when [WorkerResult.ok].
     */
    suspend fun retryWorker(
        character: Character,
        userText: String,
        reply: String,
        replyId: String,
        proactive: Boolean,
        onStatus: (String) -> Unit = {},
    ): WorkerResult = runTurnWorker(
        character = character,
        userText = userText,
        reply = reply,
        replyId = replyId,
        proactive = proactive,
        onStatus = onStatus,
    )

    /** Port of the legacy `hashRequestPart` (FNV-1a over the FNV offset base). */
    private fun fnv1a(text: String): String {
        var hash = 0x811c9dc5L
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return hash.toString(16)
    }

    /** Names of lorebook entries added or whose content/keywords changed. */
    private fun diffLorebookChanges(before: Character, after: Character): List<String> {
        val beforeById = before.lorebook.associateBy { it.id }
        return after.lorebook
            .filter { entry ->
                val old = beforeById[entry.id]
                old == null || old.content != entry.content || old.keywords != entry.keywords || old.name != entry.name
            }
            .map { it.name.ifBlank { "未命名条目" } }
            .distinct()
    }

    /** Bumps `mentions`/`lastMentionedAt` on entries injected this turn (legacy lorebook hit tracking). */
    private fun bumpLorebookMentions(character: Character, injected: List<LorebookEntry>): Character {
        if (injected.isEmpty()) return character
        val ids = injected.map { it.id }.toSet()
        val now = java.time.Instant.now().toString()
        fun bump(list: List<LorebookEntry>) = list.map {
            if (it.id in ids) it.copy(mentions = it.mentions + 1, lastMentionedAt = now, misses = 0) else it
        }
        return character.copy(
            lorebook = bump(character.lorebook),
            members = character.members.map { it.copy(lorebook = bump(it.lorebook)) },
        )
    }

    private fun applyMemory(
        character: Character,
        parsed: ParsedTurn,
        assistantMessage: ChatMessage,
    ): Character {
        val nowBase = ZonedDateTime.now()
        // Short-term memory is *not* written from the turn: `parsed.shortTerm` is
        // ignored so the window is only appended to by the whole-window extraction
        // pass (which is what keeps the injected block stable between folds).
        var updated = character
        val sources = knownSources(updated)

        updated = applyLongTerm(updated, parsed, sources, assistantMessage, nowBase)

        parsed.promiseUpdates.forEach { update ->
            updated = applyPromiseUpdate(updated, update, sources)
        }

        parsed.todoUpdates.forEach { update ->
            updated = applyTodoUpdate(updated, update, sources)
        }

        parsed.recall?.let { recall ->
            updated = memory.setPendingRecall(updated, recall.category, recall.tags)
        }

        // Static profile fields are intentionally ignored here. Legacy JSON replies may
        // still carry `staticFields`, but they must not change the card behind the user's
        // back; the card editor (and its manual regenerate button) is the only write path.

        parsed.dynamicState?.let { state ->
            val dynamic = applyDynamicStateFields(
                updated.dynamicState,
                state,
                updated,
                assistantMessage,
                sources,
                nowBase,
                isFocused = focusedDynamicFields(updated, null),
            )
            updated = updated.copy(dynamicState = dynamic)
        }

        if (parsed.memberDynamicState.isNotEmpty() && updated.members.isNotEmpty()) {
            val members = updated.members.map { member ->
                val update = parsed.memberDynamicState.firstOrNull {
                    it.memberName.equals(member.name, ignoreCase = true)
                } ?: return@map member
                val dynamic = applyDynamicStateFields(
                    member.dynamicState,
                    update.dynamicState,
                    updated,
                    assistantMessage,
                    sources,
                    nowBase,
                    isFocused = focusedDynamicFields(updated, member.id),
                )
                member.copy(dynamicState = dynamic)
            }
            updated = updated.copy(members = members)
        }

        return resolveMemoryConflicts(updated)
    }

    /** Applies one `dynamicState` object through the field cleaning + evidence gate. */
    private fun applyDynamicStateFields(
        current: DynamicState,
        state: JsonObject,
        host: Character,
        assistantMessage: ChatMessage,
        sources: Map<String, SourceRef>,
        nowBase: ZonedDateTime,
        /** Fields the user is typing right now are skipped (the editor's draft is the truth). */
        isFocused: (String) -> Boolean = { false },
        /** Worker-only collector; the plain turn path leaves this null. */
        issues: MutableList<WorkerIssue>? = null,
    ): DynamicState {
        var dynamic = current
        state.forEach { (key, element) ->
            val label = DYNAMIC_STATE_FIELDS.firstOrNull { it.first == key }?.second ?: key
            if (DYNAMIC_STATE_FIELDS.none { it.first == key }) {
                issues?.add(WorkerIssue(label, "未知字段"))
                return@forEach
            }
            if (isFocused(key)) return@forEach
            val obj = fieldObject(element)
            val raw = fieldValue(element)
            if (raw.isEmpty()) return@forEach
            val value = MemoryToolSupport.cleanFieldValue(key, parseRelativeText(raw, resolveFieldBase(obj, nowBase)))
            if (value.isEmpty()) return@forEach
            // `currentTone` is the read-aloud delivery instruction, not a factual
            // state: it is self-referential (derived from how the character just
            // spoke), so it is accepted without the user-evidence gate.
            if (key != "currentTone" &&
                resolveDynamicStateSources(host, fieldSourceIds(obj), fieldEvidence(obj), assistantMessage, sources) == null
            ) {
                issues?.add(WorkerIssue(label, if (fieldSourceIds(obj).isEmpty()) "无证据" else "证据不符"))
                return@forEach
            }
            dynamic = withDynamicField(dynamic, key, value)
        }
        return dynamic
    }

    /** Base instant for a state/field update: its `timeRef` resolved against now, else now. */
    private fun resolveFieldBase(obj: JsonObject?, nowBase: ZonedDateTime): ZonedDateTime {
        val ref = ResponseParser.parseTimeRef(obj) ?: return nowBase
        val resolved = resolveTimeRef(ref, nowBase) ?: return nowBase
        return resolved.iso?.let { parseZoned(it) } ?: nowBase
    }

    /** Validated long-term writes routed through `upsertLongTermMemory` (legacy `applyMemoryUpdate`). */
    private fun applyLongTerm(
        character: Character,
        parsed: ParsedTurn,
        sources: Map<String, SourceRef>,
        assistantMessage: ChatMessage,
        nowBase: ZonedDateTime,
    ): Character {
        var working = character
        parsed.longTerm.forEachIndexed { index, rawEntry ->
            val entry = withResolvedEventTime(rawEntry, parsed.longTermTimeRefs.getOrNull(index), nowBase)
            if (entry.memberName.isNullOrBlank()) {
                val store = working.longTerm.filter { it.category == entry.category }
                val (newStore, ok) = upsertLongTermMemory(
                    character = working,
                    item = entry.copy(memberName = null),
                    store = store,
                    sources = sources,
                    assistantMessage = assistantMessage,
                )
                if (ok) {
                    working = working.copy(
                        longTerm = working.longTerm.filter { it.category != entry.category } + newStore,
                    )
                }
            } else {
                val memberIndex = working.members.indexOfFirst {
                    it.name.equals(entry.memberName, ignoreCase = true)
                }
                if (memberIndex < 0) return@forEachIndexed
                val member = working.members[memberIndex]
                val store = member.longTerm.filter { it.category == entry.category }
                val (newStore, ok) = upsertLongTermMemory(
                    character = working,
                    item = entry.copy(memberName = null),
                    store = store,
                    sources = sources,
                    assistantMessage = assistantMessage,
                )
                if (ok) {
                    val members = working.members.toMutableList()
                    members[memberIndex] = member.copy(
                        longTerm = member.longTerm.filter { it.category != entry.category } + newStore,
                    )
                    working = working.copy(members = members)
                }
            }
        }
        return working
    }

    /** Resolves a long-term `timeRef` to an absolute `eventTime` before the evidence-gated upsert. */
    private fun withResolvedEventTime(item: LongTermMemory, timeRef: TimeRef?, nowBase: ZonedDateTime): LongTermMemory {
        if (timeRef == null) return item
        val base = parseZoned(item.eventTime) ?: nowBase
        val iso = resolveTimeRef(timeRef, base)?.iso ?: return item
        return item.copy(eventTime = iso)
    }

    /** Validated promise resolution/cancellation (legacy `consumePromiseUpdates`). */
    private fun applyPromiseUpdate(
        character: Character,
        update: PromiseUpdate,
        sources: Map<String, SourceRef>,
        issues: MutableList<WorkerIssue>? = null,
    ): Character {
        val status = when (update.status.lowercase()) {
            "resolved" -> PromiseStatus.Resolved
            "cancelled" -> PromiseStatus.Cancelled
            else -> {
                issues?.add(WorkerIssue("约定", "状态无效"))
                return character
            }
        }
        val resolved = hasValidUserEvidence(character, update.sourceMessageIds, update.evidence, sources)
            ?: run {
                issues?.add(WorkerIssue("约定", "证据不符"))
                return character
            }
        val shared = character.longTerm.firstOrNull {
            it.id == update.promiseId && it.status == PromiseStatus.Active
        }
        val member = if (shared == null) {
            character.members.firstOrNull { candidate ->
                candidate.longTerm.any { it.id == update.promiseId && it.status == PromiseStatus.Active }
            }
        } else {
            null
        }
        if (shared == null && member == null) {
            issues?.add(WorkerIssue("约定", "不存在"))
            return character
        }
        val now = Instant.now().toString()
        // `sourceRoles` is index-aligned with `sourceMessageIds`; both must be cut
        // the same way (a `.distinct()` on roles would leave later ids role-less).
        val limitedSources = resolved.take(8)
        return memory.updateMemory(character, update.promiseId, member?.name) { promise ->
            promise.copy(
                status = status,
                sourceMessageIds = limitedSources.map { it.id },
                sourceRoles = limitedSources.map { it.role },
                evidence = update.evidence.trimTo(300),
                updatedAt = now,
            )
        }
    }

    /**
     * Validated todo reschedule / complete / cancel (`submit_response.todoUpdates`).
     *
     * Every change must cite the **user's own words** ([hasValidUserEvidence]); a due-time change
     * must additionally be locatable in those words ([dueAtBackedByEvidence]). A todo is never
     * completed just because its due time passed — completion and cancellation are explicit.
     */
    private fun applyTodoUpdate(
        character: Character,
        update: TodoUpdate,
        sources: Map<String, SourceRef>,
        notes: MutableList<String>? = null,
        issues: MutableList<WorkerIssue>? = null,
    ): Character {
        fun rejected(reason: String, short: String): Character {
            notes?.add("todo.${update.status.ifBlank { "update" }} 未应用：$reason（todoId: ${update.todoId}）")
            issues?.add(WorkerIssue("待办", short))
            return character
        }
        val target = character.todos.firstOrNull {
            it.id == update.todoId && it.status == TodoStatus.Active
        } ?: return rejected("找不到进行中的待办", "不存在")
        val resolved = hasValidUserEvidence(character, update.sourceMessageIds, update.evidence, sources)
            ?: return rejected("用户原话证据校验失败", "证据不符")
        val userSources = resolved.filter { it.role == "user" }
        val status = TodoPolicy.todoStatusOf(update.status)
        val newDue = update.dueAt?.let { MemoryToolSupport.normalizeTimestamp(it, null) }
        if (status == null && newDue == null) return rejected("状态或期限都无效", "状态无效")
        if (newDue != null && !dueAtBackedByEvidence(userSources, newDue)) {
            return rejected("期限无法从用户原话定位", "改期无据")
        }
        val now = Instant.now().toString()
        val closing = status != null && status != TodoStatus.Active
        return character.copy(
            todos = character.todos.map { todo ->
                if (todo.id != target.id) {
                    todo
                } else {
                    todo.copy(
                        status = status ?: todo.status,
                        dueAt = newDue ?: todo.dueAt,
                        sourceMessageIds = resolved.take(8).map { it.id },
                        evidence = update.evidence.trimTo(300),
                        updatedAt = now,
                        closedAt = if (closing) now else todo.closedAt,
                    )
                }
            },
        )
    }

    private fun fieldObject(element: JsonElement): JsonObject? =
        runCatching { element.jsonObject }.getOrNull()

    private fun fieldSourceIds(obj: JsonObject?): List<String> =
        (obj?.get("sourceMessageIds") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

    private fun fieldEvidence(obj: JsonObject?): String =
        (obj?.get("evidence") as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** Reads `{ "value": "..." }` (or a bare string) from a state-field element. */
    private fun fieldValue(element: JsonElement): String {
        val obj = fieldObject(element)
        val raw = when {
            obj != null -> (obj["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            element is JsonPrimitive -> element.contentOrNull.orEmpty()
            else -> ""
        }
        return raw.trim()
    }

    // ------------------------------------------------------------------ turn worker

    /**
     * Applies the main call's `【语气】` line: value + per-field timestamp, never blanking it.
     *
     * The timestamp is refreshed **even when the text did not change**: `updatedAt` means
     * "the tone was re-derived from this turn's delivery", not "the string changed", so a
     * long stretch of identical tone still shows a fresh confirmation time.
     */
    private fun applyTone(character: Character, tone: String?): Character {
        val value = tone?.trim()?.takeIf { it.isNotEmpty() } ?: return character
        // The editor may be typing the read-aloud tone right now: its draft is the truth.
        if (FieldEditRegistry.isFocused(character.id, null, FieldSection.Dynamic, "currentTone")) return character
        return character.copy(
            dynamicState = character.dynamicState.copy(currentTone = value),
            dynamicStateMeta = character.dynamicStateMeta +
                ("currentTone" to DynamicStateMeta(updatedAt = Instant.now().toString())),
        )
    }

    /** True while the user is typing one of [character]'s (or [memberId]'s) dynamic fields. */
    private fun focusedDynamicFields(character: Character, memberId: String?): (String) -> Boolean =
        { key -> FieldEditRegistry.isFocused(character.id, memberId, FieldSection.Dynamic, key) }

    /**
     * Runs the forced `submit_state` call and folds its fields onto the character.
     * Any failure (transport, no call, unparseable arguments, rejected evidence) keeps the
     * previous state; the reply has already been produced and must not be affected.
     */
    private suspend fun runTurnWorker(
        character: Character,
        userText: String,
        reply: String,
        replyId: String,
        proactive: Boolean,
        onStatus: (String) -> Unit = {},
    ): WorkerResult {
        val request = TurnWorker.buildRequest(
            config = config,
            character = character,
            userText = userText,
            reply = reply,
            replyId = replyId,
            proactive = proactive,
            now = Instant.now(),
        )

        var lastFailure = "请求失败"
        var lastNotes = listOf("worker 请求失败：没有拿到结果")
        var lastUsage: com.deeptalking.engine.ondevice.TokenUsage? = null
        var attempt = 0
        while (true) {
            var result: com.deeptalking.engine.ondevice.LlmResult? = null
            var transient = true
            try {
                result = llm.complete(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastFailure = "请求失败"
                lastNotes = listOf("worker 请求失败：${error.message ?: error}")
                transient = isTransientApiError(error)
            }

            if (result != null) {
                val output = TurnWorker.parse(result)
                lastUsage = output.usage
                if (output.ok) {
                    val applied = runCatching {
                        applyWorkerOutput(
                            character = character,
                            reply = reply,
                            replyId = replyId,
                            output = output,
                        )
                    }
                    applied.getOrNull()?.let { return it }
                    lastFailure = "解析失败"
                    lastNotes = listOf("worker 结果无法应用：${applied.exceptionOrNull()?.message ?: "格式错误"}")
                } else {
                    lastFailure = if (result.toolCalls.any { it.name == TurnWorker.TOOL_NAME }) "解析失败" else "无结果"
                    lastNotes = listOf("worker 未返回可解析的 submit_state")
                }
            }

            // A non-transient HTTP error (auth/bad request) will not improve by asking
            // again; parse/no-result failures are worth the two retries.
            if (!transient || attempt >= WORKER_MAX_RETRIES) break
            attempt++
            onStatus("整理失败，正在重试（$attempt/$WORKER_MAX_RETRIES）…")
            kotlinx.coroutines.delay(if (attempt == 1) 1_000L else 2_000L)
        }

        return WorkerResult(
            character = character,
            ok = false,
            usage = lastUsage,
            failure = lastFailure,
            notes = lastNotes,
        )
    }

    /** Applies one successfully parsed worker result, collecting short retry hints. */
    private fun applyWorkerOutput(
        character: Character,
        reply: String,
        replyId: String,
        output: TurnWorker.Output,
    ): WorkerResult {
        val now = Instant.now()
        val nowZoned = ZonedDateTime.now()
        val assistant = character.instant.lastOrNull { it.id == replyId }
            ?: ChatMessage(id = replyId, role = Role.Assistant, content = reply)
        var working = character
        val sources = knownSources(working)
        // Long diagnostics go to metrics; short labels go to the next worker prompt.
        val notes = mutableListOf<String>()
        val issues = mutableListOf<WorkerIssue>()
        notes += output.warnings
        if (output.quickReplies.size < 2) issues += WorkerIssue("快捷回复", "条数不足")
        if (output.warnings.any { it.contains("超过") }) issues += WorkerIssue("快捷回复", "条数过多")

        output.dynamicState?.let { state ->
            val fields = JsonObject(state.filterKeys { it != "currentTone" })
            if (working.isGroup) {
                // Groups keep their shared state in `groupSharedDynamic` (the volatile
                // context reads that field); only situation/location are shared.
                val sharedKeys = setOf("currentSituation", "currentLocation")
                val shared = JsonObject(fields.filterKeys { it in sharedKeys })
                val before = working.groupSharedDynamic
                val after = applyDynamicStateFields(
                    before,
                    shared,
                    working,
                    assistant,
                    sources,
                    nowZoned,
                    isFocused = focusedDynamicFields(working, null),
                    issues = issues,
                )
                // The shared state is stored in `groupSharedDynamic`, but its per-field
                // timestamps live in the same `dynamicStateMeta` map: stamp the keys this
                // pass actually moved, exactly like the single-character branch.
                working = working.copy(
                    groupSharedDynamic = after,
                    dynamicStateMeta = stampChangedDynamicFields(
                        working.dynamicStateMeta,
                        before,
                        after,
                        now.toString(),
                    ),
                )
            } else {
                val before = working.dynamicState
                val after = applyDynamicStateFields(
                    before,
                    fields,
                    working,
                    assistant,
                    sources,
                    nowZoned,
                    isFocused = focusedDynamicFields(working, null),
                    issues = issues,
                )
                working = working.copy(
                    dynamicState = after,
                    dynamicStateMeta = stampChangedDynamicFields(
                        working.dynamicStateMeta,
                        before,
                        after,
                        now.toString(),
                    ),
                )
            }
        }

        output.memberDynamicState?.let { elements ->
            working = applyMemberDynamicState(
                character = working,
                elements = elements,
                assistant = assistant,
                sources = sources,
                nowZoned = nowZoned,
                nowIso = now.toString(),
                issues = issues,
            )
        }

        output.todos?.forEach { element ->
            val obj = element as? JsonObject ?: run {
                issues += WorkerIssue("待办", "格式错误")
                return@forEach
            }
            val outcome = applyWorkerTodo(working, obj, assistant, sources, nowZoned)
            working = outcome.character
            notes += outcome.notes
            issues += outcome.issues
        }

        output.promiseUpdates?.forEach { element ->
            val obj = element as? JsonObject ?: run {
                issues += WorkerIssue("约定", "格式错误")
                return@forEach
            }
            val id = (obj["promiseId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val status = (obj["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (id.isBlank() || status.isBlank()) {
                issues += WorkerIssue("约定", "状态无效")
                return@forEach
            }
            val update = PromiseUpdate(
                promiseId = id,
                status = status,
                sourceMessageIds = (obj["sourceMessageIds"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                evidence = (obj["evidence"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
            working = applyPromiseUpdate(working, update, knownSources(working), issues)
        }

        return WorkerResult(
            character = working,
            quickReplies = output.quickReplies,
            ok = true,
            usage = output.usage,
            notes = notes,
            issues = workerIssueLines(issues),
        )
    }

    /**
     * Applies the worker's `memberDynamicState` (groups). Each member's own state is patched
     * onto that member — **`currentTone` included**, because a member reads their own lines
     * with their own delivery (unlike the group-wide shared state, which never carries a tone).
     * Malformed elements and unknown member names are skipped, exactly like the parse layer.
     */
    internal fun applyMemberDynamicState(
        character: Character,
        elements: JsonArray,
        assistant: ChatMessage,
        sources: Map<String, SourceRef>,
        nowZoned: ZonedDateTime,
        nowIso: String,
        issues: MutableList<WorkerIssue>? = null,
    ): Character {
        var working = character
        elements.forEach { element ->
            val obj = element as? JsonObject ?: run {
                issues?.add(WorkerIssue("成员", "格式错误"))
                return@forEach
            }
            val name = (obj["memberName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val state = obj["dynamicState"] as? JsonObject ?: run {
                issues?.add(WorkerIssue(name.ifBlank { "成员" }, "格式错误"))
                return@forEach
            }
            val index = working.members.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (index < 0) {
                issues?.add(WorkerIssue(name.ifBlank { "成员" }, "不存在"))
                return@forEach
            }
            val member = working.members[index]
            val before = member.dynamicState
            val after = applyDynamicStateFields(
                before,
                state,
                working,
                assistant,
                sources,
                nowZoned,
                isFocused = focusedDynamicFields(working, member.id),
                issues = issues,
            )
            val members = working.members.toMutableList()
            members[index] = member.copy(
                dynamicState = after,
                dynamicStateMeta = stampChangedDynamicFields(
                    member.dynamicStateMeta,
                    before,
                    after,
                    nowIso,
                ),
            )
            working = working.copy(members = members)
        }
        return working
    }

    /**
     * `submit_state.todos`: create goes through [TodoPolicy.validateTodoCreation]; the rest
     * reuses [applyTodoUpdate].
     *
     * A rejected entry is **not** silently dropped: the reason rides back in [WorkerTodoOutcome.notes]
     * so the metrics pass can record it.
     */
    internal fun applyWorkerTodo(
        character: Character,
        obj: JsonObject,
        assistant: ChatMessage,
        sources: Map<String, SourceRef>,
        nowZoned: ZonedDateTime,
    ): WorkerTodoOutcome {
        fun str(key: String): String = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun ids(): List<String> = (obj["sourceMessageIds"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val op = str("op").trim().lowercase()
        if (op != "create") {
            val update = TodoUpdate(
                todoId = str("todoId"),
                status = when (op) {
                    "done" -> "done"
                    "cancel" -> "cancelled"
                    else -> ""
                },
                dueAt = str("dueAt").takeIf { it.isNotBlank() },
                sourceMessageIds = ids(),
                evidence = str("evidence"),
            )
            val notes = mutableListOf<String>()
            val issues = mutableListOf<WorkerIssue>()
            val changed = applyTodoUpdate(character, update, sources, notes, issues)
            return WorkerTodoOutcome(changed, notes = notes, issues = issues)
        }

        // `title` is the only required text. `detail` is optional — a missing detail used to
        // drop the whole todo, which lost the entry (and its due time) entirely.
        val text = workerTodoText(str("title"), str("detail"))
            ?: return WorkerTodoOutcome(
                character = character,
                notes = listOf("todo.create 缺少 title，已忽略（evidence: ${str("evidence").trimTo(80)}）"),
                issues = listOf(WorkerIssue("待办", "缺标题")),
            )
        val title = text.title
        val detail = text.detail
        val proposer = if (str("proposer").trim().lowercase() == "character") {
            TodoProposer.Character
        } else {
            TodoProposer.User
        }
        val evidence = str("evidence").trimTo(300)
        val sourceIds = ids().map { it.trim() }.filter { it.isNotEmpty() }
        val timeRef = ResponseParser.parseTimeRef(obj)
        val resolved = resolveTimeRef(timeRef, nowZoned)
        val dueAt = MemoryToolSupport.normalizeTimestamp(str("dueAt"), null) ?: resolved?.iso
        val accepted = when (val decision = TodoPolicy.validateTodoCreation(
            character = character,
            proposer = proposer,
            sourceIds = sourceIds,
            evidence = evidence,
            dueAtIso = dueAt,
            sources = sources,
        )) {
            is TodoPolicy.Creation.Accepted -> decision
            is TodoPolicy.Creation.Rejected -> return WorkerTodoOutcome(
                character = character,
                notes = listOf("todo.create 未通过校验：${decision.reason}（title: $title）"),
                issues = listOf(WorkerIssue("待办", todoRejectLabel(decision.reason))),
            )
        }
        val base = parseZoned(dueAt) ?: nowZoned
        val parsedTitle = parseRelativeText(title, base)
        val parsedDetail = parseRelativeText(detail, base)
        val sourceIdList = accepted.sources.map { it.id }.distinct().take(8)
        val now = Instant.now().toString()
        val existing = character.todos.indexOfFirst {
            it.status == TodoStatus.Active && it.title.equals(parsedTitle, ignoreCase = true)
        }
        val todos = if (existing >= 0) {
            val old = character.todos[existing]
            character.todos.toMutableList().also {
                it[existing] = old.copy(
                    detail = parsedDetail,
                    dueAt = dueAt.orEmpty(),
                    proposer = proposer,
                    sourceMessageIds = sourceIdList,
                    evidence = evidence,
                    updatedAt = now,
                )
            }
        } else {
            character.todos + Todo(
                id = UUID.randomUUID().toString(),
                title = parsedTitle,
                detail = parsedDetail,
                dueAt = dueAt.orEmpty(),
                status = TodoStatus.Active,
                proposer = proposer,
                sourceMessageIds = sourceIdList,
                evidence = evidence,
                createdAt = now,
                updatedAt = now,
            )
        }
        return WorkerTodoOutcome(character.copy(todos = todos))
    }
}

/** Outcome of one `submit_state.todos` entry: the patched character plus any drop reason. */
internal data class WorkerTodoOutcome(
    val character: Character,
    val notes: List<String> = emptyList(),
    val issues: List<WorkerIssue> = emptyList(),
)

/** One-word label for a `TodoPolicy` rejection, shown in the next worker prompt. */
private fun todoRejectLabel(reason: String): String = when {
    reason.contains("明确期限") || reason.contains("没有期限") -> "无期限"
    reason.contains("拒绝") || reason.contains("取消") -> "用户拒绝"
    reason.contains("答应") || reason.contains("同意") -> "未确认"
    reason.contains("期限") || reason.contains("日期") || reason.contains("时间") -> "期限不明"
    else -> "证据不符"
}

/**
 * `submit_state.todos` create text: `title` is the only required part, a blank `detail`
 * mirrors it (the entry itself must survive). Null when there is no usable title.
 */
internal data class WorkerTodoText(val title: String, val detail: String)

internal fun workerTodoText(rawTitle: String, rawDetail: String): WorkerTodoText? {
    val title = rawTitle.trimTo(100)
    if (title.isEmpty()) return null
    return WorkerTodoText(title, rawDetail.trimTo(900))
}

/**
 * Per-member turns of a final assistant reply: groups get [SpeechSegment]s parsed from the
 * `成员名：内容` format (display text, not TTS text), single characters get none.
 */
internal fun groupSpeechSegments(character: Character, reply: String): List<SpeechSegment> =
    if (!character.isGroup) {
        emptyList()
    } else {
        parseGroupSpeechSegments(reply, character.members.map { it.name })
    }

/**
 * May the legacy "as the user" quick-reply repair round run?
 *
 * The turn worker is the only producer of quick replies. A successful worker already handed
 * back its two lines — re-running the repair would race it (and fire on every turn whose
 * lines merely trip the style checker). Only a worker that did not run or did not succeed
 * leaves room for the fallback.
 */
internal fun quickReplyRepairEnabled(config: AppConfig, workerOk: Boolean, quickReplyCount: Int): Boolean =
    config.quickReplyRepair && (!workerOk || quickReplyCount < 2)

/** Result of one turn-worker pass: the patched character plus its quick replies. */
data class WorkerResult(
    val character: Character,
    val quickReplies: List<String> = emptyList(),
    val ok: Boolean = false,
    val usage: com.deeptalking.engine.ondevice.TokenUsage? = null,
    /** Non-fatal contract violations, surfaced to the caller for the metrics pass. */
    val notes: List<String> = emptyList(),
    /** Short label of an unresolvable failure (`请求失败`/`无结果`/`解析失败`). */
    val failure: String = "",
    /** Compact rule-rejection hints for the next worker turn. */
    val issues: List<String> = emptyList(),
)

/** Reads one dynamic-state field by its wire key (empty for an unknown key). */
internal fun dynamicStateFieldValue(state: DynamicState, key: String): String = when (key) {
    "currentSituation" -> state.currentSituation
    "currentLocation" -> state.currentLocation
    "currentMood" -> state.currentMood
    "currentOccupation" -> state.currentOccupation
    "currentGoal" -> state.currentGoal
    "currentTone" -> state.currentTone
    else -> ""
}

/**
 * Writes `updatedAt` only for the dynamic keys that actually changed between [before] and
 * [after]; every other entry keeps its value. Used by the turn worker for both the single
 * character's `dynamicState` and a group's `groupSharedDynamic` — the shared state was
 * written without timestamps, so nothing downstream could tell when a group's scene moved.
 */
internal fun stampChangedDynamicFields(
    current: Map<String, DynamicStateMeta>,
    before: DynamicState,
    after: DynamicState,
    now: String,
): Map<String, DynamicStateMeta> {
    var meta = current
    DYNAMIC_STATE_FIELDS.forEach { (key, _) ->
        if (dynamicStateFieldValue(before, key) != dynamicStateFieldValue(after, key)) {
            meta = meta + (key to DynamicStateMeta(updatedAt = now))
        }
    }
    return meta
}
