package com.deeptalking.domain.agent

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import com.deeptalking.core.network.LLM_STATUS_CHUNK_NAME
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
        var latestCharacter: Character? = null
        var latestSticker: String? = null
        var lastUsage: com.deeptalking.engine.ondevice.TokenUsage? = null
        var lastReasoning: List<String> = emptyList()
        var phase = RequestPhase.AUTO
        var submitAttempted = false

        while (true) {
            val tools = if (phase == RequestPhase.AUTO) autoTools else listOf(submitTool)
            val request = builder.build(phase, instructions, messages, tools, model, sessionIdFor(workingCharacter))
            val turn = collectResultWithRetry(request, onDelta, onToolActivity)
            val result = turn.result
            result.usage?.let { lastUsage = it }
            lastReasoning = result.reasoning
            val responseText = salvageText(result)
            val salvaged = turn.salvaged

            val submitCall = result.toolCalls.firstOrNull { it.name == "submit_response" }
            val parsedSubmit = submitCall?.let { ResponseParser.parseSubmitResponse(it.arguments) }
            val infoCalls = result.toolCalls.filter { it.name != "submit_response" }

            // Special case: a lone ask_user becomes this turn's reply.
            if (phase == RequestPhase.AUTO && infoCalls.size == 1 && infoCalls[0].name == "ask_user") {
                val question = ToolJson.question(infoCalls[0].arguments)
                if (question.isNotBlank()) {
                    return LoopOutcome(
                        text = responseText,
                        submitCall = syntheticSubmitReply(question),
                        rounds = rounds,
                        executedCalls = executedCalls,
                        updatedCharacter = latestCharacter,
                        stickerFileRef = latestSticker,
                        usage = lastUsage,
                    )
                }
            }

            var toolFailed = false
            if (phase == RequestPhase.AUTO && infoCalls.isNotEmpty()) {
                val atLimit = rounds >= AppLimits.Agent.MAX_TOOL_ROUNDS ||
                    executedCalls >= AppLimits.Agent.MAX_TOOL_CALLS
                if (atLimit) {
                    // chatStageDecision('auto-tool-limit')
                    val accumulated = responseText.ifBlank { salvaged.orEmpty() }
                    if (accumulated.isNotBlank()) {
                        onToolActivity("工具调用已达上限，直接收尾…")
                        return LoopOutcome(accumulated, null, rounds, executedCalls, latestCharacter, latestSticker, lastUsage)
                    }
                    onToolActivity("正在整理回复…")
                    phase = RequestPhase.SUBMIT
                    submitAttempted = false
                    continue
                }
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
                toolFailed = isToolFailure(toolResult.contentJson)
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
                onToolActivity("工具结果已返回，正在继续推理…")
                if (parsedSubmit == null || toolFailed) continue
            }

            if (parsedSubmit != null) {
                return LoopOutcome(responseText, submitCall, rounds, executedCalls, latestCharacter, latestSticker, lastUsage)
            }

            if (phase == RequestPhase.AUTO) {
                // chatStageDecision('auto-final'): prose or valid JSON text is final.
                if (responseText.isNotBlank()) {
                    return LoopOutcome(responseText, null, rounds, executedCalls, latestCharacter, latestSticker, lastUsage)
                }
                onToolActivity("正在整理回复…")
                appendReasoning(messages, result.reasoning, rounds)
                phase = RequestPhase.SUBMIT
                submitAttempted = false
                continue
            }

            // Submit phase: give the forced submit_response exactly one explicit re-attempt.
            if (!submitAttempted) {
                // chatStageDecision('submit-retry')
                submitAttempted = true
                onToolActivity("正在重新整理回复…")
                continue
            }
            val usable = responseText.ifBlank { salvaged.orEmpty() }
            if (usable.isNotBlank()) {
                return LoopOutcome(usable, null, rounds, executedCalls, latestCharacter, latestSticker, lastUsage)
            }
            break
        }
        return LoopOutcome("", null, rounds, executedCalls, latestCharacter, latestSticker, lastUsage)
    }

    /**
     * Applies the legacy fallback order for a turn whose streamed/returned text is
     * empty: `extractResponsesText` -> embedded `reply` JSON in any reasoning item
     * (`extractReplyJsonFromAnyOutput`) -> `extractAnyResponseText`.
     */
    private fun salvageText(result: LlmResult): String {
        if (result.text.isNotBlank()) return result.text
        val response = result.raw
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        ResponseParser.extractResponsesText(response).takeIf { it.isNotBlank() }?.let { return it }
        ResponseParser.extractReplyJsonFromAnyOutput(response, result.reasoning)
            .takeIf { it.isNotBlank() }?.let { return it }
        return ResponseParser.extractAnyResponseText(response)
    }

    private fun isToolFailure(contentJson: String): Boolean {
        val element = runCatching { Json.parseToJsonElement(contentJson) }.getOrNull() ?: return true
        val obj = element as? JsonObject ?: return false
        return obj["ok"]?.let { (it as? JsonPrimitive)?.contentOrNull } == "false"
    }

    private fun appendReasoning(messages: MutableList<ChatMessage>, reasoning: List<String>, round: Int) {
        reasoningArray(reasoning)?.let { json ->
            messages += ChatMessage(
                id = "reasoning_$round",
                role = Role.Assistant,
                content = "",
                reasoningJson = json,
            )
        }
    }

    /**
     * Consumes [LlmBackend.stream]. [onDelta] receives the growing assistant
     * reply text; partial `submit_response` arguments are salvaged when the
     * model answers through the terminal tool instead of plain text.
     */
    private data class TurnResult(val result: LlmResult, val salvaged: String?)

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
        onToolActivity: (String) -> Unit,
    ): TurnResult {
        var attempt = 0
        while (true) {
            try {
                return if (request.stream) {
                    collectStream(request, onDelta, onToolActivity)
                } else {
                    TurnResult(llm.complete(request), null)
                }
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
        onToolActivity: (String) -> Unit,
    ): TurnResult {
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
                        StreamText.extractReplyFromJson(text.toString())?.let { salvaged = it }
                        StreamText.displayFor(text.toString(), salvaged)?.let(onDelta)
                    }
                    is LlmChunk.ToolCallStart -> {
                        val call = chunk.call
                        if (call.name == LLM_STATUS_CHUNK_NAME) {
                            if (call.arguments.isNotBlank()) onToolActivity(call.arguments)
                        } else {
                            pending[call.id.ifBlank { call.name }] = call
                            if (call.name == "submit_response" && call.arguments.trimStart().startsWith("{")) {
                                StreamText.extractReplyFromJson(call.arguments)?.let { reply ->
                                    salvaged = reply
                                    StreamText.displayFor(text.toString(), reply)?.let(onDelta)
                                }
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
            return TurnResult(finished, salvaged)
        }
        // Nothing usable accumulated: surface transient transport failures so the
        // caller can retry (legacy `performChatRequestWithRetry`). When partial
        // content exists we keep it instead of throwing it away.
        if (failure != null && text.isEmpty() && pending.isEmpty()) {
            throw failure
        }
        return TurnResult(LlmResult(text = text.toString(), toolCalls = pending.values.toList()), salvaged)
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
