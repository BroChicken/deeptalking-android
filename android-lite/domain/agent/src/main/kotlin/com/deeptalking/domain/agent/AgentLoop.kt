package com.deeptalking.domain.agent

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import com.deeptalking.domain.agent.prompts.RequestBuilder
import com.deeptalking.domain.agent.prompts.RequestPhase
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmChunk
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Result of one full agent/tool loop. */
data class LoopOutcome(
    val text: String,
    val submitCall: ToolCall?,
    val rounds: Int,
    val executedCalls: Int,
    /** Latest character returned by a tool's [AgentToolResult.updatedCharacter], if any. */
    val updatedCharacter: Character? = null,
)

/**
 * Runs the model/tool rounds, mirroring the auto/submit phase loop in
 * `chat/conversation.js`.
 *
 * The model is consumed through [LlmBackend.stream]; growing assistant text is
 * reported through `onDelta` and the final [LlmResult] is taken from
 * `LlmChunk.Completed` (falling back to the accumulated deltas when a stream
 * ends without one). Tool results are fed back as [Role.Tool] messages (the
 * transport in :core:network maps them to plain `tool` input items, which is
 * the closest available representation).
 */
class AgentLoop(
    private val llm: LlmBackend,
    private val registry: ToolRegistry,
) {

    suspend fun run(
        character: Character,
        instructions: String,
        baseInput: List<ChatMessage>,
        autoTools: List<ToolDefinition>,
        submitTool: ToolDefinition,
        builder: RequestBuilder,
        model: String,
        onDelta: (String) -> Unit = {},
    ): LoopOutcome {
        var workingCharacter = character
        var context = AgentContext(character = workingCharacter, activeCharacterId = workingCharacter.id)
        val messages = baseInput.toMutableList()
        var rounds = 0
        var executedCalls = 0
        var lastText = ""
        var latestCharacter: Character? = null
        var phase = RequestPhase.AUTO
        var attempts = 0

        while (attempts < 3) {
            attempts++
            val tools = if (phase == RequestPhase.AUTO) autoTools else listOf(submitTool)
            val request = builder.build(phase, instructions, messages, tools, model)
            val result = collectStream(request, onDelta)
            if (result.text.isNotBlank()) lastText = result.text

            val submit = result.toolCalls.firstOrNull { it.name == "submit_response" }
            if (submit != null) return LoopOutcome(result.text, submit, rounds, executedCalls, latestCharacter)

            val infoCalls = result.toolCalls.filter { it.name != "submit_response" }
            if (phase == RequestPhase.AUTO) {
                // Special case: a lone ask_user becomes this turn's reply.
                if (infoCalls.size == 1 && infoCalls[0].name == "ask_user") {
                    val question = ToolJson.question(infoCalls[0].arguments)
                    if (question.isNotBlank()) {
                        return LoopOutcome(
                            text = result.text,
                            submitCall = syntheticSubmitReply(question),
                            rounds = rounds,
                            executedCalls = executedCalls,
                            updatedCharacter = latestCharacter,
                        )
                    }
                }

                val withinLimits = rounds < AppLimits.Agent.MAX_TOOL_ROUNDS &&
                    executedCalls < AppLimits.Agent.MAX_TOOL_CALLS
                if (infoCalls.isNotEmpty() && withinLimits) {
                    rounds++
                    executedCalls++
                    val call = infoCalls.first()
                    val toolResult = registry.execute(call, context)
                    toolResult.updatedCharacter?.let { updated ->
                        latestCharacter = updated
                        workingCharacter = updated
                        context = AgentContext(character = workingCharacter, activeCharacterId = workingCharacter.id)
                    }
                    messages += ChatMessage(
                        id = "assistant_${rounds}",
                        role = Role.Assistant,
                        content = "",
                        internalOnly = false,
                        isLoading = false,
                    )
                    messages += ChatMessage(
                        id = call.id.ifBlank { "tool_${rounds}" },
                        role = Role.Tool,
                        content = toolResult.contentJson,
                    )
                    continue
                }
                if (lastText.isNotBlank()) {
                    return LoopOutcome(lastText, null, rounds, executedCalls, latestCharacter)
                }
                phase = RequestPhase.SUBMIT
                attempts = 0
                continue
            }

            // Submit phase: no submit_response call came back.
            if (lastText.isNotBlank()) return LoopOutcome(lastText, null, rounds, executedCalls, latestCharacter)
            if (attempts >= 2) break
        }
        return LoopOutcome(lastText, null, rounds, executedCalls, latestCharacter)
    }

    /**
     * Consumes [LlmBackend.stream]. [onDelta] receives the growing assistant
     * reply text; partial `submit_response` arguments are salvaged when the
     * model answers through the terminal tool instead of plain text.
     */
    private suspend fun collectStream(
        request: com.deeptalking.engine.ondevice.LlmRequest,
        onDelta: (String) -> Unit,
    ): LlmResult {
        val text = StringBuilder()
        var completed: LlmResult? = null
        val pending = LinkedHashMap<String, ToolCall>()
        try {
            llm.stream(request).collect { chunk ->
                when (chunk) {
                    is LlmChunk.TextDelta -> {
                        text.append(chunk.text)
                        onDelta(text.toString())
                    }
                    is LlmChunk.ToolCallStart -> {
                        val call = chunk.call
                        pending[call.id.ifBlank { call.name }] = call
                        if (call.name == "submit_response" && text.isEmpty()) {
                            salvagePartialReply(call.arguments)?.let(onDelta)
                        }
                    }
                    is LlmChunk.Completed -> completed = chunk.result
                    is LlmChunk.ReasoningDelta -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Fall through with whatever was accumulated before the failure.
        }
        val finished = completed
        if (finished != null && (finished.text.isNotBlank() || finished.toolCalls.isNotEmpty())) {
            return finished
        }
        return LlmResult(text = text.toString(), toolCalls = pending.values.toList())
    }

    /** Best-effort partial extraction of the `reply` string from a streamed tool call. */
    private fun salvagePartialReply(arguments: String): String? {
        val keyIdx = arguments.indexOf("\"reply\"")
        if (keyIdx < 0) return null
        var i = arguments.indexOf(':', keyIdx)
        if (i < 0) return null
        i++
        while (i < arguments.length && arguments[i].isWhitespace()) i++
        if (i >= arguments.length || arguments[i] != '"') return null
        val start = i
        var j = i + 1
        var escaped = false
        while (j < arguments.length) {
            val c = arguments[j]
            if (escaped) {
                escaped = false
                j++
                continue
            }
            if (c == '\\') {
                escaped = true
                j++
                continue
            }
            if (c == '"') break
            j++
        }
        val token = if (j < arguments.length) arguments.substring(start, j + 1) else arguments.substring(start) + "\""
        val wrapped = "{\"reply\":$token}"
        return (ResponseParser.parseJsonLenient(wrapped) as? JsonObject)
            ?.let { (it["reply"] as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
    }

    private fun syntheticSubmitReply(question: String): ToolCall {
        val args = buildJsonObject {
            put("reply", question)
            put(
                "quickReplies",
                buildJsonArray {
                    add("嗯，我明白")
                    add("稍等，我想一下")
                },
            )
        }
        return ToolCall(id = "ask_user", name = "submit_response", arguments = args.toString())
    }
}

private object ToolJson {
    fun question(arguments: String): String =
        (ResponseParser.parseJsonLenient(arguments) as? JsonObject)
            ?.let { (it["question"] as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
            .trim()
}
