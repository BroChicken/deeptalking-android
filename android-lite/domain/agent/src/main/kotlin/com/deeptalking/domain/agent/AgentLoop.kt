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
import kotlinx.serialization.json.Json
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
    /** File ref of a sticker the character chose to send this turn, if any. */
    val stickerFileRef: String? = null,
    /** Token usage of the last model request, when the backend reported it. */
    val usage: com.deeptalking.engine.ondevice.TokenUsage? = null,
)

/**
 * Runs the model/tool rounds, mirroring the auto/submit phase loop in
 * `chat/conversation.js`.
 *
 * The model is consumed through [LlmBackend.stream]; growing assistant text is
 * reported through `onDelta` (already sanitized for display) and the final
 * [LlmResult] is taken from `LlmChunk.Completed` (falling back to the accumulated
 * deltas when a stream ends without one). Tool interactions are fed back as a
 * structured Responses `function_call` + `function_call_output` pair (with the
 * round's `reasoning` items replayed before the call), matching the legacy
 * `toolState.items` push order.
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
        onToolActivity: (String) -> Unit = {},
    ): LoopOutcome {
        var workingCharacter = character
        var context = AgentContext(character = workingCharacter, activeCharacterId = workingCharacter.id)
        val messages = baseInput.toMutableList()
        var rounds = 0
        var executedCalls = 0
        var lastText = ""
        var latestCharacter: Character? = null
        var latestSticker: String? = null
        var lastUsage: com.deeptalking.engine.ondevice.TokenUsage? = null
        var lastReasoning: List<String> = emptyList()
        var phase = RequestPhase.AUTO
        var attempts = 0

        while (attempts < 3) {
            attempts++
            val tools = if (phase == RequestPhase.AUTO) autoTools else listOf(submitTool)
            val request = builder.build(phase, instructions, messages, tools, model, sessionIdFor(workingCharacter))
            val result = collectResultWithRetry(request, onDelta)
            if (result.text.isNotBlank()) lastText = result.text
            result.usage?.let { lastUsage = it }
            lastReasoning = result.reasoning

            val submit = result.toolCalls.firstOrNull { it.name == "submit_response" }
            if (submit != null) return LoopOutcome(result.text, submit, rounds, executedCalls, latestCharacter, latestSticker, lastUsage)

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
                            stickerFileRef = latestSticker,
                        )
                    }
                }

                val withinLimits = rounds < AppLimits.Agent.MAX_TOOL_ROUNDS &&
                    executedCalls < AppLimits.Agent.MAX_TOOL_CALLS
                if (infoCalls.isNotEmpty() && withinLimits) {
                    rounds++
                    executedCalls++
                    val call = infoCalls.first()
                    val callId = call.id.ifBlank { "call_$rounds" }
                    onToolActivity(toolActivityHint(call.name))
                    val toolResult = registry.execute(call, context)
                    toolResult.stickerFileRef?.let { latestSticker = it }
                    toolResult.updatedCharacter?.let { updated ->
                        latestCharacter = updated
                        workingCharacter = updated
                        context = AgentContext(character = workingCharacter, activeCharacterId = workingCharacter.id)
                    }
                    messages += ChatMessage(
                        id = "assistant_${rounds}",
                        role = Role.Assistant,
                        content = "",
                        toolCallId = callId,
                        toolName = call.name,
                        toolArguments = call.arguments,
                        reasoningJson = reasoningArray(lastReasoning),
                        internalOnly = false,
                        isLoading = false,
                    )
                    messages += ChatMessage(
                        id = callId,
                        role = Role.Tool,
                        content = toolResult.contentJson,
                        toolCallId = callId,
                    )
                    onToolActivity("正在继续推理…")
                    continue
                }
                if (lastText.isNotBlank()) {
                    return LoopOutcome(lastText, null, rounds, executedCalls, latestCharacter, latestSticker, lastUsage)
                }
                reasoningArray(lastReasoning)?.let { reasoning ->
                    messages += ChatMessage(
                        id = "reasoning_${rounds}",
                        role = Role.Assistant,
                        content = "",
                        reasoningJson = reasoning,
                    )
                }
                phase = RequestPhase.SUBMIT
                attempts = 0
                continue
            }

            // Submit phase: no submit_response call came back.
            if (lastText.isNotBlank()) return LoopOutcome(lastText, null, rounds, executedCalls, latestCharacter, latestSticker, lastUsage)
            if (attempts >= 2) break
        }
        return LoopOutcome(lastText, null, rounds, executedCalls, latestCharacter, latestSticker, lastUsage)
    }

    /**
     * Consumes [LlmBackend.stream]. [onDelta] receives the growing assistant
     * reply text; partial `submit_response` arguments are salvaged when the
     * model answers through the terminal tool instead of plain text.
     */
    /**
     * Wraps the model request with the legacy request-level retry policy
     * (`src/js/api/retry.js`): transient transport failures are retried up to
     * [ApiRetry.MAX_API_RETRIES] times with 1s/2s/4s backoff; non-transient
     * errors propagate immediately. Honors [LlmRequest.stream]: streaming uses
     * [collectStream] (progressive `onDelta`), non-streaming uses
     * [LlmBackend.complete] in one shot.
     */
    private suspend fun collectResultWithRetry(
        request: com.deeptalking.engine.ondevice.LlmRequest,
        onDelta: (String) -> Unit,
    ): LlmResult {
        var attempt = 0
        while (true) {
            try {
                return if (request.stream) collectStream(request, onDelta) else llm.complete(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (attempt >= ApiRetry.MAX_API_RETRIES || !isTransientApiError(error)) throw error
                attempt++
                kotlinx.coroutines.delay(ApiRetry.delayFor(attempt))
            }
        }
    }

    private suspend fun collectStream(
        request: com.deeptalking.engine.ondevice.LlmRequest,
        onDelta: (String) -> Unit,
    ): LlmResult {
        val text = StringBuilder()
        var completed: LlmResult? = null
        val pending = LinkedHashMap<String, ToolCall>()
        var failure: Throwable? = null
        var salvaged: String? = null
        try {
            llm.stream(request).collect { chunk ->
                when (chunk) {
                    is LlmChunk.TextDelta -> {
                        text.append(chunk.text)
                        StreamText.displayFor(text.toString(), salvaged)?.let(onDelta)
                    }
                    is LlmChunk.ToolCallStart -> {
                        val call = chunk.call
                        pending[call.id.ifBlank { call.name }] = call
                        if (call.name == "submit_response" && text.isEmpty()) {
                            StreamText.extractReplyFromJson(call.arguments)?.let { reply ->
                                salvaged = reply
                                StreamText.displayFor(text.toString(), reply)?.let(onDelta)
                            }
                        }
                    }
                    is LlmChunk.Completed -> completed = chunk.result
                    is LlmChunk.ReasoningDelta -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure = error
        }
        val finished = completed
        if (finished != null && (finished.text.isNotBlank() || finished.toolCalls.isNotEmpty())) {
            return finished
        }
        // Nothing usable accumulated: surface transient transport failures so the
        // caller can retry (legacy `performChatRequestWithRetry`). When partial
        // content exists we keep it instead of throwing it away.
        if (failure != null && text.isEmpty() && pending.isEmpty()) {
            throw failure
        }
        return LlmResult(text = text.toString(), toolCalls = pending.values.toList())
    }

    /** Serializes captured reasoning items into a JSON array for [ChatMessage.reasoningJson]. */
    private fun reasoningArray(items: List<String>): String? {
        if (items.isEmpty()) return null
        return buildJsonArray {
            items.forEach { item -> runCatching { add(Json.parseToJsonElement(item)) } }
        }.toString()
    }

    private fun syntheticSubmitReply(question: String): ToolCall {
        val args = buildJsonObject {
            put("reply", question)
            put(
                "quickReplies",
                buildJsonArray {
                    add("嗯，我明白")
                    add("稍等，我想一想")
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

/** Top-right activity hint per tool, mirroring legacy `toolActivityHint` (`status-settings.js:1-15`). */
internal fun toolActivityHint(name: String): String = when (name) {
    "get_current_time" -> "正在确认时间…"
    "search_memory" -> "正在回忆…"
    "list_memories" -> "正在整理记忆…"
    "delete_memory" -> "正在清理记忆…"
    "set_reminder" -> "已记下，正在回应…"
    "update_character_field" -> "已按你的要求调整设定…"
    "upsert_lorebook_entry" -> "已更新世界书…"
    "web_search" -> "正在搜索…"
    "web_fetch" -> "正在读取网页…"
    "send_sticker" -> "正在挑表情…"
    else -> "正在处理…"
}
