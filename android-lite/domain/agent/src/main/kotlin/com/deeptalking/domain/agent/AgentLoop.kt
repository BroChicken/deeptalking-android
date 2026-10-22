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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/** Result of one full agent/tool loop. */
data class LoopOutcome(
    val text: String,
    val submitCall: ToolCall?,
    /** Number of **tool rounds** (one per model response that carried tool calls), not calls. */
    val rounds: Int,
    /** Total tool calls actually executed — a single round may execute several. */
    val executedCalls: Int,
    /** Latest character returned by a tool's [AgentToolResult.updatedCharacter], if any. */
    val updatedCharacter: Character? = null,
    /** File ref of a sticker the character chose to send this turn, if any. */
    val stickerFileRef: String? = null,
    /** Token usage of the last model request, when the backend reported it. */
    val usage: com.deeptalking.engine.ondevice.TokenUsage? = null,
    /**
     * Usage of **every** model request this turn made, in order, for requests whose
     * backend reported one. One chat turn can issue several requests (tool rounds plus
     * the forced submit round), and only the first of them can carry the cache-breaking
     * prefix — keeping them all is what lets the caller report the turn's real cache
     * behaviour instead of just its last (usually re-warmed) request.
     */
    val roundUsages: List<com.deeptalking.engine.ondevice.TokenUsage> = emptyList(),
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

    /**
     * Runs the turn in two phases (see docs/AGENT_ARCHITECTURE.md, 对话循环):
     *
     * - `AUTO`: the model may call information tools. Tool rounds are tool-only; any prose
     *   that arrives alongside a call is discarded (the contract says "tool or prose, not
     *   both"), and the final prose arrives in a later round. A response may carry several
     *   calls: **all** of them run in the same round (each gets its own
     *   `function_call_output`), and the hard cap [AppLimits.Agent.MAX_TOOL_ROUNDS] counts
     *   responses, not calls.
     * - `FINAL`: no tools at all. This is the only phase that can terminate the turn; an
     *   empty FINAL round is retried at most twice and then returns an empty outcome so the
     *   caller (single outer retry loop) owns the "empty reply" UX.
     *
     * There is no terminal `submit_response` call any more: the main call is plain text.
     */
    suspend fun run(
        character: Character,
        instructions: String,
        baseInput: List<ChatMessage>,
        autoTools: List<ToolDefinition>,
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
        val roundUsages = mutableListOf<com.deeptalking.engine.ondevice.TokenUsage>()
        var lastReasoning: List<String> = emptyList()
        var phase = RequestPhase.AUTO
        var emptyAttempts = 0

        while (true) {
            val tools = if (phase == RequestPhase.AUTO) autoTools else emptyList()
            val request = builder.build(phase, instructions, messages, tools, model, sessionIdFor(workingCharacter))
            val turn = collectResultWithRetry(request, onDelta, onToolActivity)
            val result = turn.result
            result.usage?.let {
                lastUsage = it
                roundUsages += it
            }
            lastReasoning = result.reasoning
            val responseText = salvageText(result)
            val salvaged = turn.salvaged
            val infoCalls = result.toolCalls.filter { it.name != LLM_STATUS_CHUNK_NAME }

            if (phase == RequestPhase.AUTO && infoCalls.isNotEmpty()) {
                // `rounds` counts tool **rounds** (model responses), never individual calls:
                // one response may carry several parallel calls and every one of them must
                // be executed, otherwise its `function_call` would be replayed on the next
                // request without the matching `function_call_output`.
                if (rounds >= AppLimits.Agent.MAX_TOOL_ROUNDS) {
                    onToolActivity("工具调用已达上限，正在整理回复…")
                    appendReasoning(messages, result.reasoning, rounds)
                    phase = RequestPhase.FINAL
                    continue
                }
                rounds++
                val outputs = mutableListOf<Pair<String, String>>()
                infoCalls.forEachIndexed { index, call ->
                    executedCalls++
                    val callId = call.id.ifBlank { "call_${rounds}_${index + 1}" }
                    onToolActivity(toolActivityHint(call.name))
                    val toolResult = registry.execute(call, context)
                    toolResult.stickerFileRef?.let { latestSticker = it }
                    toolResult.updatedCharacter?.let { updated ->
                        latestCharacter = updated
                        workingCharacter = updated
                        context = AgentContext(character = workingCharacter, activeCharacterId = workingCharacter.id)
                    }
                    messages += ChatMessage(
                        id = "assistant_${rounds}_${index + 1}",
                        role = Role.Assistant,
                        content = "",
                        toolCallId = callId,
                        toolName = call.name,
                        toolArguments = call.arguments,
                        // The round's reasoning items precede its calls; attaching the same
                        // JSON to every call would replay it once per call.
                        reasoningJson = if (index == 0) reasoningArray(lastReasoning) else null,
                        internalOnly = false,
                        isLoading = false,
                    )
                    outputs += callId to toolResult.contentJson
                }
                // Matches the provider's own output shape: every `function_call` of the
                // response comes first, then one `function_call_output` per call.
                outputs.forEach { (callId, content) ->
                    messages += ChatMessage(
                        id = callId,
                        role = Role.Tool,
                        content = content,
                        toolCallId = callId,
                    )
                }
                onToolActivity("工具结果已返回，正在继续推理…")
                continue
            }

            val usable = responseText.ifBlank { salvaged.orEmpty() }
            if (usable.isNotBlank()) {
                return LoopOutcome(usable, null, rounds, executedCalls, latestCharacter, latestSticker, lastUsage, roundUsages)
            }
            if (phase == RequestPhase.AUTO) {
                onToolActivity("正在整理回复…")
                appendReasoning(messages, result.reasoning, rounds)
                phase = RequestPhase.FINAL
                continue
            }
            // FINAL: an empty round is retried at most twice (decision 14).
            if (emptyAttempts < 2) {
                emptyAttempts++
                onToolActivity("正在重新生成…")
                continue
            }
            return LoopOutcome("", null, rounds, executedCalls, latestCharacter, latestSticker, lastUsage, roundUsages)
        }
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
                onToolActivity("连接中断，正在重试（$attempt/${ApiRetry.MAX_API_RETRIES}）…")
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
        if (finished != null) {
            // Legacy uses the assembled function calls regardless of any trailing
            // text; never drop tool calls assembled from streaming deltas.
            val calls = if (finished.toolCalls.isNotEmpty()) finished.toolCalls else pending.values.toList()
            if (finished.text.isNotBlank() || calls.isNotEmpty()) {
                return TurnResult(finished.copy(toolCalls = calls), salvaged)
            }
        }
        // A stream that failed mid-way is retried as a whole request (legacy
        // `performChatRequestWithRetry` + retry.js), so a half-finished partial
        // must not be accepted as the turn's output.
        if (failure != null) throw failure
        return TurnResult(LlmResult(text = text.toString(), toolCalls = pending.values.toList()), salvaged)
    }

    /** Serializes captured reasoning items into a JSON array for [ChatMessage.reasoningJson]. */
    private fun reasoningArray(items: List<String>): String? {
        if (items.isEmpty()) return null
        return buildJsonArray {
            items.forEach { item -> runCatching { add(Json.parseToJsonElement(item)) } }
        }.toString()
    }
}

/** Top-right activity hint per tool. */
internal fun toolActivityHint(name: String): String = when (name) {
    "search_memory" -> "正在回忆…"
    "web_search" -> "正在搜索…"
    "web_fetch" -> "正在读取网页…"
    "search" -> "正在搜索…"
    "hot_board" -> "正在看热榜…"
    "memory_write" -> "正在更新记忆…"
    "upsert_lorebook_entry" -> "已更新世界书…"
    "send_sticker" -> "正在挑表情…"
    else -> "正在处理…"
}
