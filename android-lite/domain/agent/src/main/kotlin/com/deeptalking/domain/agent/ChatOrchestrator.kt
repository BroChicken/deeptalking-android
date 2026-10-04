package com.deeptalking.domain.agent

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
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
import com.deeptalking.domain.agent.tools.SubmitResponseTool
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.engine.ondevice.LlmBackend
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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
    /** Raw (pre-processing) model reply, for the settings debug panel. */
    val rawReply: String = "",
    /** Token usage of the last model request, when reported. */
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
)

/**
 * High-level turn orchestrator: assemble prompts, run the tool loop, parse the
 * structured turn, and fold memory updates onto the character.
 *
 * SIMPLIFIED: static/dynamic/member state are applied directly to the
 * [Character] copy here because [MemoryService.applyTurn] only covers memory
 * stores. Promise updates go through [MemoryService.updateMemory]. The legacy
 * background tasks (prose memory extraction, quick-reply repair, style
 * critique) are dispatched through [BackgroundTasks] when one is supplied.
 */
class ChatOrchestrator(
    private val llm: LlmBackend,
    private val tools: ToolRegistry,
    private val memory: MemoryService,
    private val config: AppConfig = AppConfig(),
    private val backgroundTasks: BackgroundTasks? = null,
    private val runBackgroundTasks: Boolean = true,
    private val onCharacterUpdated: (Character) -> Unit = {},
) {

    suspend fun run(
        character: Character,
        history: List<ChatMessage>,
        userText: String,
        onDelta: (String) -> Unit = {},
        proactive: Boolean = false,
    ): OrchestratorResult {
        val builder = RequestBuilder(config)
        val instructions = buildSystemPrompt(PersonaInputs(character, config, proactive))
        val context = AgentContext(character = character, activeCharacterId = character.id)

        val mapped = builder.mapHistory(history).toMutableList()
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
        val input = mapped + ChatMessage(
            role = Role.User,
            content = "【系统提供的本轮上下文，仅供角色理解，不代表用户陈述】\n" + volatileContext,
        )

        val autoTools = tools.definitions(context)
        val submitTool = SubmitResponseTool().definition
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
        )

        val parsed = when {
            outcome.submitCall != null ->
                ResponseParser.parseSubmitResponse(outcome.submitCall.arguments)
                    ?: ResponseParser.parseTurnFromText(outcome.text)
            else -> ResponseParser.parseTurnFromText(outcome.text)
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
        val base = (outcome.updatedCharacter ?: character).copy(pendingRecall = null)
        val updated = applyMemory(base, parsed)

        // The visible reply may be replaced by the style critique before it is shown,
        // matching the legacy flow (critique runs inline; repair + extraction run after).
        val displayReply = if (backgroundTasks != null && runBackgroundTasks && config.styleCritique) {
            backgroundTasks.critiqueStyle(updated, parsed.reply)
        } else {
            parsed.reply
        }

        if (backgroundTasks != null && runBackgroundTasks) {
            val assistant = ChatMessage(
                id = "assistant_" + character.id,
                role = Role.Assistant,
                content = displayReply,
            )
            backgroundTasks.scheduleTurn(
                character = updated,
                reply = displayReply,
                userText = userText,
                currentQuickReplies = quickReplies,
                messages = history.filter { !it.isLoading } + assistant,
                critiqueEnabled = config.styleCritique,
                quickReplyRepairEnabled = config.quickReplyRepair,
                onCharacterUpdated = onCharacterUpdated,
            )
        }

        return OrchestratorResult(
            reply = displayReply,
            quickReplies = quickReplies,
            updatedCharacter = updated,
            proactive = proactive,
            staticChanges = diffStaticChanges(character, updated),
            lorebookChanges = diffLorebookChanges(character, updated),
            rawReply = outcome.text,
            inputTokens = outcome.usage?.inputTokens ?: 0,
            outputTokens = outcome.usage?.outputTokens ?: 0,
            cachedTokens = outcome.usage?.cachedTokens ?: 0,
        )
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

    private fun applyMemory(character: Character, parsed: ParsedTurn): Character {
        val shared = parsed.longTerm.filter { it.memberName.isNullOrBlank() }
        val memberLongTerm = parsed.longTerm
            .filter { !it.memberName.isNullOrBlank() }
            .groupBy { it.memberName!! }
            .mapValues { (_, entries) -> entries.map { it.copy(memberName = null) } }

        var updated = memory.applyTurn(character, parsed.shortTerm, shared, memberLongTerm)

        parsed.promiseUpdates.forEach { update ->
            val status = when (update.status.lowercase()) {
                "resolved" -> PromiseStatus.Resolved
                "cancelled" -> PromiseStatus.Cancelled
                else -> null
            }
            if (status != null) {
                updated = memory.updateMemory(updated, update.promiseId) { it.copy(status = status) }
            }
        }

        parsed.recall?.let { recall ->
            updated = memory.setPendingRecall(updated, recall.category, recall.tags)
        }

        parsed.staticFields?.let { fields ->
            var profile = updated.staticProfile
            fields.forEach { (key, element) ->
                if (STATIC_PROFILE_FIELDS.any { it.first == key }) {
                    val value = fieldValue(element)
                    if (value.isNotEmpty()) profile = withStaticField(profile, key, value)
                }
            }
            updated = updated.copy(staticProfile = profile)
        }

        parsed.dynamicState?.let { state ->
            var dynamic = updated.dynamicState
            state.forEach { (key, element) ->
                if (DYNAMIC_STATE_FIELDS.any { it.first == key }) {
                    val value = fieldValue(element)
                    if (value.isNotEmpty()) dynamic = withDynamicField(dynamic, key, value)
                }
            }
            updated = updated.copy(dynamicState = dynamic)
        }

        if (parsed.memberDynamicState.isNotEmpty() && updated.members.isNotEmpty()) {
            val members = updated.members.map { member ->
                val update = parsed.memberDynamicState.firstOrNull {
                    it.memberName.equals(member.name, ignoreCase = true)
                } ?: return@map member
                var dynamic = member.dynamicState
                update.dynamicState.forEach { (key, element) ->
                    if (DYNAMIC_STATE_FIELDS.any { it.first == key }) {
                        val value = fieldValue(element)
                        if (value.isNotEmpty()) dynamic = withDynamicField(dynamic, key, value)
                    }
                }
                member.copy(dynamicState = dynamic)
            }
            updated = updated.copy(members = members)
        }

        return updated
    }

    /** Reads `{ "value": "..." }` (or a bare string) from a state-field element. */
    private fun fieldValue(element: kotlinx.serialization.json.JsonElement): String {
        val obj = runCatching { element.jsonObject }.getOrNull()
        val raw = when {
            obj != null -> (obj["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            element is JsonPrimitive -> element.contentOrNull.orEmpty()
            else -> ""
        }
        return raw.trim()
    }
}
