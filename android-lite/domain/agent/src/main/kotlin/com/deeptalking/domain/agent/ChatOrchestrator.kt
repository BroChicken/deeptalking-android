package com.deeptalking.domain.agent

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.DynamicStateMeta
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.model.Todo
import com.deeptalking.core.model.TodoProposer
import com.deeptalking.core.model.TodoStatus
import com.deeptalking.domain.agent.prompts.DYNAMIC_STATE_FIELDS
import com.deeptalking.domain.agent.prompts.PersonaInputs
import com.deeptalking.domain.agent.prompts.RequestBuilder
import com.deeptalking.domain.agent.prompts.STATIC_PROFILE_FIELDS
import com.deeptalking.domain.agent.prompts.buildShortTermBlock
import com.deeptalking.domain.agent.prompts.buildSystemPrompt
import com.deeptalking.domain.agent.prompts.withDynamicField
import com.deeptalking.domain.agent.prompts.withStaticField
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
import com.deeptalking.domain.memory.hasStaticEditIntent
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

/** Result of one user turn. */
data class OrchestratorResult(
    val reply: String,
    val quickReplies: List<String>,
    val updatedCharacter: Character,
    /** True when this turn was a proactive (unprompted) opening. */
    val proactive: Boolean = false,
    /** Static profile field labels auto-adjusted this turn (legacy `staticChanges`). */
    val staticChanges: List<String> = emptyList(),
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
    /** FNV-1a hash of the system instructions (legacy `instructionsHash`). */
    val instructionsHash: String = "",
    /** FNV-1a hash of this request's tool definitions (legacy `toolsHash`). */
    val toolsHash: String = "",
    /** Character count of the per-turn volatile context message (legacy `contextChars`). */
    val contextChars: Int = 0,
)

/**
 * High-level turn orchestrator: assemble prompts, run the tool loop, parse the
 * structured turn, and fold memory updates onto the character.
 *
 * SIMPLIFIED: static/dynamic/member state are applied through the shared
 * evidence gates ([hasValidUserEvidence], [resolveDynamicStateSources],
 * [hasStaticEditIntent]) and long-term writes through [upsertLongTermMemory],
 * mirroring the legacy `applyMemoryUpdate`; promise updates go through
 * [MemoryService.updateMemory]. The legacy background tasks (prose memory
 * extraction, quick-reply repair, style critique) are dispatched through
 * [BackgroundTasks] when one is supplied.
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

        val mapped = builder.mapHistory(history).let { mapped ->
            if (mapped.size > AppLimits.Memory.INSTANT) mapped.takeLast(AppLimits.Memory.INSTANT) else mapped
        }.toMutableList()
        if (mapped.lastOrNull()?.role != Role.User) {
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
        val updated = applyMemory(base, parsed, memoryAssistant)
            .let { applyTone(it, parsed.tone) }

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
        val assistantMessage = ChatMessage(
            id = "assistant_" + character.id,
            role = Role.Assistant,
            content = displayReply,
            styleViolations = styleViolations,
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
        val worker = if (backgroundTasks != null && runBackgroundTasks) {
            runTurnWorker(
                character = characterWithInstant,
                userText = userText,
                reply = displayReply,
                replyId = assistantMessage.id,
                proactive = proactive,
            )
        } else {
            WorkerResult(character = characterWithInstant)
        }
        val finalCharacter = run {
            var current = worker.character
            // Static-field revision call (replaces the deleted update_character_field tool).
            if (!character.isGroup) {
                FieldRevision.detect(userText)?.let { key ->
                    onToolActivity("正在调整设定…")
                    FieldRevision.revise(llm, config, current, key, userText)?.let { value ->
                        current = FieldRevision.apply(current, key, value, Instant.now().toString())
                    }
                }
            }
            current
        }

        if (backgroundTasks != null && runBackgroundTasks) {
            backgroundTasks.scheduleTurn(
                character = finalCharacter,
                reply = displayReply,
                userText = userText,
                currentQuickReplies = worker.quickReplies,
                messages = history.filter { !it.isLoading } + assistantMessage,
                critiqueEnabled = config.styleCritique,
                quickReplyRepairEnabled = config.quickReplyRepair,
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
            staticChanges = diffStaticChanges(character, finalCharacter),
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
            instructionsHash = instructionsHash,
            toolsHash = toolsHash,
            contextChars = contextMessage.content.length,
        )
    }

    /** Port of the legacy `hashRequestPart` (FNV-1a over the FNV offset base). */
    private fun fnv1a(text: String): String {
        var hash = 0x811c9dc5L
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return hash.toString(16)
    }

    /** Labels of static profile fields whose value changed during the turn. */
    private fun diffStaticChanges(before: Character, after: Character): List<String> {
        if (after.isGroup) return emptyList()
        return STATIC_PROFILE_FIELDS.filter { (key, _) ->
            staticValue(before.staticProfile, key) != staticValue(after.staticProfile, key)
        }.map { it.second }
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

        parsed.staticFields?.let { fields ->
            var profile = updated.staticProfile
            fields.forEach { (key, element) ->
                if (STATIC_PROFILE_FIELDS.none { it.first == key }) return@forEach
                val obj = fieldObject(element)
                val raw = fieldValue(element)
                if (raw.isEmpty()) return@forEach
                val value = MemoryToolSupport.cleanFieldValue(key, parseRelativeText(raw, resolveFieldBase(obj, nowBase)))
                if (value.isEmpty()) return@forEach
                val resolved = hasValidUserEvidence(updated, fieldSourceIds(obj), fieldEvidence(obj), sources)
                    ?: return@forEach
                if (!hasStaticEditIntent(key, resolved)) return@forEach
                profile = withStaticField(profile, key, value)
            }
            updated = updated.copy(staticProfile = profile)
        }

        parsed.dynamicState?.let { state ->
            val dynamic = applyDynamicStateFields(updated.dynamicState, state, updated, assistantMessage, sources, nowBase)
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
    ): DynamicState {
        var dynamic = current
        state.forEach { (key, element) ->
            if (DYNAMIC_STATE_FIELDS.none { it.first == key }) return@forEach
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
    ): Character {
        val status = when (update.status.lowercase()) {
            "resolved" -> PromiseStatus.Resolved
            "cancelled" -> PromiseStatus.Cancelled
            else -> return character
        }
        val resolved = hasValidUserEvidence(character, update.sourceMessageIds, update.evidence, sources)
            ?: return character
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
        if (shared == null && member == null) return character
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
    ): Character {
        val target = character.todos.firstOrNull {
            it.id == update.todoId && it.status == TodoStatus.Active
        } ?: return character
        val resolved = hasValidUserEvidence(character, update.sourceMessageIds, update.evidence, sources)
            ?: return character
        val userSources = resolved.filter { it.role == "user" }
        val status = TodoPolicy.todoStatusOf(update.status)
        val newDue = update.dueAt?.let { MemoryToolSupport.normalizeTimestamp(it, null) }
        if (status == null && newDue == null) return character
        if (newDue != null && !dueAtBackedByEvidence(userSources, newDue)) return character
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

    /** Applies the main call's `【语气】` line: value + per-field timestamp, never blanking it. */
    private fun applyTone(character: Character, tone: String?): Character {
        val value = tone?.trim()?.takeIf { it.isNotEmpty() } ?: return character
        if (value == character.dynamicState.currentTone) return character
        return character.copy(
            dynamicState = character.dynamicState.copy(currentTone = value),
            dynamicStateMeta = character.dynamicStateMeta +
                ("currentTone" to DynamicStateMeta(updatedAt = Instant.now().toString())),
        )
    }

    private fun dynamicValue(state: DynamicState, key: String): String = when (key) {
        "currentSituation" -> state.currentSituation
        "currentLocation" -> state.currentLocation
        "currentMood" -> state.currentMood
        "currentOccupation" -> state.currentOccupation
        "currentGoal" -> state.currentGoal
        "currentTone" -> state.currentTone
        else -> ""
    }

    /** Writes `updatedAt` only for the dynamic keys the worker actually changed. */
    private fun mergeDynamicMeta(
        current: Map<String, DynamicStateMeta>,
        before: DynamicState,
        after: DynamicState,
        now: String,
    ): Map<String, DynamicStateMeta> {
        var meta = current
        DYNAMIC_STATE_FIELDS.forEach { (key, _) ->
            if (dynamicValue(before, key) != dynamicValue(after, key)) {
                meta = meta + (key to DynamicStateMeta(updatedAt = now))
            }
        }
        return meta
    }

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
    ): WorkerResult {
        val result = runCatching {
            llm.complete(
                TurnWorker.buildRequest(
                    config = config,
                    character = character,
                    userText = userText,
                    reply = reply,
                    replyId = replyId,
                    proactive = proactive,
                    now = Instant.now(),
                ),
            )
        }.getOrNull() ?: return WorkerResult(character = character)

        val output = TurnWorker.parse(result)
        if (!output.ok) return WorkerResult(character = character, usage = output.usage)

        val now = Instant.now()
        val nowZoned = ZonedDateTime.now()
        val assistant = character.instant.lastOrNull { it.id == replyId }
            ?: ChatMessage(id = replyId, role = Role.Assistant, content = reply)
        var working = character
        val sources = knownSources(working)

        output.dynamicState?.let { state ->
            val fields = JsonObject(state.filterKeys { it != "currentTone" })
            if (working.isGroup) {
                // Groups keep their shared state in `groupSharedDynamic` (the volatile
                // context reads that field); only situation/location are shared.
                val sharedKeys = setOf("currentSituation", "currentLocation")
                val shared = JsonObject(fields.filterKeys { it in sharedKeys })
                val before = working.groupSharedDynamic
                val after = applyDynamicStateFields(before, shared, working, assistant, sources, nowZoned)
                working = working.copy(groupSharedDynamic = after)
            } else {
                val before = working.dynamicState
                val after = applyDynamicStateFields(before, fields, working, assistant, sources, nowZoned)
                working = working.copy(
                    dynamicState = after,
                    dynamicStateMeta = mergeDynamicMeta(working.dynamicStateMeta, before, after, now.toString()),
                )
            }
        }

        output.memberDynamicState?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val name = (obj["memberName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val state = obj["dynamicState"] as? JsonObject ?: return@forEach
            val index = working.members.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (index < 0) return@forEach
            val member = working.members[index]
            val before = member.dynamicState
            val after = applyDynamicStateFields(
                before,
                JsonObject(state.filterKeys { it != "currentTone" }),
                working,
                assistant,
                sources,
                nowZoned,
            )
            val members = working.members.toMutableList()
            members[index] = member.copy(
                dynamicState = after,
                dynamicStateMeta = mergeDynamicMeta(member.dynamicStateMeta, before, after, now.toString()),
            )
            working = working.copy(members = members)
        }

        output.todos?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            working = applyWorkerTodo(working, obj, assistant, sources, nowZoned)
        }

        output.promiseUpdates?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val id = (obj["promiseId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val status = (obj["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (id.isBlank() || status.isBlank()) return@forEach
            val update = PromiseUpdate(
                promiseId = id,
                status = status,
                sourceMessageIds = (obj["sourceMessageIds"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                evidence = (obj["evidence"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
            working = applyPromiseUpdate(working, update, knownSources(working))
        }

        return WorkerResult(
            character = working,
            quickReplies = output.quickReplies,
            ok = true,
            usage = output.usage,
        )
    }

    /** `submit_state.todos`: create goes through [TodoPolicy.validateTodoCreation]; the rest reuses [applyTodoUpdate]. */
    private fun applyWorkerTodo(
        character: Character,
        obj: JsonObject,
        assistant: ChatMessage,
        sources: Map<String, SourceRef>,
        nowZoned: ZonedDateTime,
    ): Character {
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
            return applyTodoUpdate(character, update, sources)
        }

        val title = str("title").trimTo(100)
        val detail = str("detail").trimTo(900)
        if (title.isEmpty() || detail.isEmpty()) return character
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
        val decision = TodoPolicy.validateTodoCreation(
            character = character,
            proposer = proposer,
            sourceIds = sourceIds,
            evidence = evidence,
            dueAtIso = dueAt,
            sources = sources,
        )
        val accepted = decision as? TodoPolicy.Creation.Accepted ?: return character
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
        return character.copy(todos = todos)
    }
}

/** Result of one turn-worker pass: the patched character plus its quick replies. */
data class WorkerResult(
    val character: Character,
    val quickReplies: List<String> = emptyList(),
    val ok: Boolean = false,
    val usage: com.deeptalking.engine.ondevice.TokenUsage? = null,
)
