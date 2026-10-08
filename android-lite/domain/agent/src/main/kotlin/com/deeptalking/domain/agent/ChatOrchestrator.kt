package com.deeptalking.domain.agent

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.domain.agent.prompts.DYNAMIC_STATE_FIELDS
import com.deeptalking.domain.agent.prompts.PersonaInputs
import com.deeptalking.domain.agent.prompts.RequestBuilder
import com.deeptalking.domain.agent.prompts.STATIC_PROFILE_FIELDS
import com.deeptalking.domain.agent.prompts.buildSystemPrompt
import com.deeptalking.domain.agent.prompts.buildVolatileContext
import com.deeptalking.domain.agent.prompts.withDynamicField
import com.deeptalking.domain.agent.prompts.withStaticField
import com.deeptalking.domain.agent.background.BackgroundTasks
import com.deeptalking.domain.agent.background.STYLE_GUARD
import com.deeptalking.domain.agent.background.dedupeRepeatedEnding
import com.deeptalking.domain.agent.background.detectStyleViolations
import com.deeptalking.domain.agent.background.recentReplyTexts
import com.deeptalking.domain.agent.tools.MemoryToolSupport
import com.deeptalking.domain.agent.tools.SubmitResponseTool
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.domain.memory.ShortTermDraft
import com.deeptalking.domain.memory.TimeRef
import com.deeptalking.domain.memory.addShortTermMemory
import com.deeptalking.domain.memory.consumeInjectedRecalls
import com.deeptalking.domain.memory.SourceRef
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZonedDateTime

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
    /** Token usage of the last model request, when reported. */
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
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
    private val onCharacterUpdated: (Character) -> Unit = {},
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
        val volatileContext = buildVolatileContext(
            character = character,
            query = userText,
            memory = memory,
            proactiveTurn = proactive,
            recentAssistantReplies = recentAssistantReplies,
        )
        val contextMessage = ChatMessage(
            role = Role.User,
            content = "【系统提供的本轮上下文，仅供角色理解，不代表用户陈述】\n" + volatileContext,
        )
        val input = mapped + contextMessage

        val autoTools = tools.definitions(context)
        val submitTool = SubmitResponseTool().definition
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
            submitTool = submitTool,
            builder = builder,
            model = config.modelName,
            onDelta = onDelta,
            onToolActivity = onToolActivity,
        )

        val parsedBase = when {
            outcome.submitCall != null ->
                ResponseParser.parseSubmitResponse(outcome.submitCall.arguments)
                    ?: ResponseParser.parseTurnFromText(outcome.text)
            else -> ResponseParser.parseTurnFromText(outcome.text)
        }
        // Prose/salvage turns may still carry a legacy `<MEM_UPDATE>` block; parse and
        // fold it in before applying memory (legacy `parseMemoryFromText`).
        val memUpdate = if (parsedBase.raw == null) ResponseParser.parseMemoryFromText(outcome.text) else null
        val parsed = if (memUpdate != null) {
            parsedBase.copy(
                shortTerm = memUpdate.shortTerm,
                shortTermTimeRefs = memUpdate.shortTermTimeRefs,
                longTerm = memUpdate.longTerm,
                longTermTimeRefs = memUpdate.longTermTimeRefs,
                dynamicState = memUpdate.dynamicState,
                staticFields = memUpdate.staticFields,
                memberDynamicState = memUpdate.memberDynamicState,
                promiseUpdates = memUpdate.promiseUpdates,
                recall = memUpdate.recall,
            )
        } else {
            parsedBase
        }

        val quickReplies = if (parsed.quickReplies.size >= 2) {
            parsed.quickReplies
        } else {
            val fromText = ResponseParser.parseQuickRepliesFromText(outcome.text)
            if (fromText.size > parsed.quickReplies.size) fromText else parsed.quickReplies
        }

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
        // Legacy `consumeInjectedRecalls`: the volatile context injects the
        // persisted pending-recall items first, so only those ids are consumed.
        val injectedRecallIds = recalled.pendingRecall.map { it.id }
            .take(AppLimits.Agent.TOOL_MEMORY_INJECT_LIMIT)
        val base = consumeInjectedRecalls(
            bumpLorebookMentions(
                recalled,
                memory.selectLorebook(character, lorebookQuery),
            ).copy(instant = memoryInstant),
            injectedRecallIds,
        )
        val updated = applyMemory(base, parsed, memoryAssistant)

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
        // `memory.instant`): tools (set_reminder), the volatile context and the
        // background tasks all read user/reply IDs and recent replies from here.
        val assistantMessage = ChatMessage(
            id = "assistant_" + character.id,
            role = Role.Assistant,
            content = displayReply,
            styleViolations = styleViolations,
        )
        val instantWindow = (history.filter { !it.rejected } + assistantMessage).let {
            if (it.size > AppLimits.Memory.INSTANT) it.takeLast(AppLimits.Memory.INSTANT) else it
        }
        val characterWithInstant = updated.copy(instant = instantWindow, revision = character.revision + 1)

        if (backgroundTasks != null && runBackgroundTasks) {
            backgroundTasks.scheduleTurn(
                character = characterWithInstant,
                reply = displayReply,
                userText = userText,
                currentQuickReplies = quickReplies,
                messages = history.filter { !it.isLoading } + assistantMessage,
                critiqueEnabled = config.styleCritique,
                quickReplyRepairEnabled = config.quickReplyRepair,
                onCharacterUpdated = onCharacterUpdated,
                onQuickRepliesRepaired = onQuickRepliesRepaired,
                styleViolations = styleViolations,
                proseFallback = parsed.raw == null && memUpdate == null,
            )
        }

        return OrchestratorResult(
            reply = displayReply,
            quickReplies = quickReplies,
            updatedCharacter = characterWithInstant,
            proactive = proactive,
            staticChanges = diffStaticChanges(character, characterWithInstant),
            lorebookChanges = diffLorebookChanges(character, characterWithInstant),
            stickerFileRef = outcome.stickerFileRef,
            rawReply = outcome.text,
            inputTokens = outcome.usage?.inputTokens ?: 0,
            outputTokens = outcome.usage?.outputTokens ?: 0,
            cachedTokens = outcome.usage?.cachedTokens ?: 0,
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
        "values" -> profile.values
        "fears" -> profile.fears
        "background" -> profile.background
        "keyEvents" -> profile.keyEvents
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
        var updated = applyShortTerm(character, parsed, nowBase)
        val sources = knownSources(updated)

        updated = applyLongTerm(updated, parsed, sources, assistantMessage, nowBase)

        parsed.promiseUpdates.forEach { update ->
            updated = applyPromiseUpdate(updated, update, sources)
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

    /**
     * Validated short-term writes through [addShortTermMemory] (legacy `addShortTermMemory`
     * from the main turn): every cited source must resolve to a real message, relative
     * time wording is normalized, and `timeRef` is resolved to an absolute event time.
     */
    private fun applyShortTerm(character: Character, parsed: ParsedTurn, nowBase: ZonedDateTime): Character {
        if (parsed.shortTerm.isEmpty()) return character
        val nowIso = nowBase.toInstant().toString()
        var working = character
        parsed.shortTerm.forEachIndexed { index, item ->
            val eventTime = resolveEventTime(item.eventTime, parsed.shortTermTimeRefs.getOrNull(index), nowBase, nowIso)
            val result = addShortTermMemory(
                list = working.shortTerm,
                draft = ShortTermDraft(
                    content = item.content,
                    sourceMessageIds = item.sourceMessageIds,
                    eventTime = eventTime,
                    participants = item.participants,
                    location = item.location,
                ),
                timestamp = nowIso,
                sources = knownSources(working),
            )
            if (result.accepted) working = working.copy(shortTerm = result.list)
        }
        return working
    }

    /** Resolves a short-term `timeRef` against its declared/now event time (legacy write order). */
    private fun resolveEventTime(
        eventTime: String?,
        timeRef: TimeRef?,
        nowBase: ZonedDateTime,
        nowIso: String,
    ): String {
        val base = parseZoned(eventTime) ?: nowBase
        resolveTimeRef(timeRef, base)?.iso?.let { return it }
        return parseZoned(eventTime)?.toInstant()?.toString() ?: nowIso
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
            if (resolveDynamicStateSources(host, fieldSourceIds(obj), fieldEvidence(obj), assistantMessage, sources) == null) {
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
        return memory.updateMemory(character, update.promiseId, member?.name) { promise ->
            promise.copy(
                status = status,
                sourceMessageIds = resolved.map { it.id }.take(8),
                sourceRoles = resolved.map { it.role }.distinct(),
                evidence = update.evidence.trimTo(300),
                updatedAt = now,
            )
        }
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
}
