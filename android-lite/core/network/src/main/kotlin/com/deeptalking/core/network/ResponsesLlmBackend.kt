package com.deeptalking.core.network

import com.deeptalking.core.model.BuiltinPlatforms
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmChunk
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.TokenUsage
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

/**
 * [LlmBackend] for the OpenAI-compatible "Responses" endpoint
 * (`"$baseUrl/responses"`), matching the legacy JS transport used by
 * DeepSeek / OpenCode Go.
 */
class ResponsesLlmBackend(
    private val apiKeyProvider: () -> String?,
    private val baseUrl: String = "https://api.deepseek.com/v1",
    override val id: String = "responses",
    /** Identifies this client (OpenCode Go rejects generic SDK/HTTP-library UAs). */
    private val userAgent: String = "DeepTalking-Lite",
) : LlmBackend {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    override fun supportsTools(): Boolean = true

    override suspend fun complete(request: LlmRequest): LlmResult = withContext(Dispatchers.IO) {
        val payload = buildBody(request, stream = false)
        client.newCall(newRequest(payload, request, stream = false)).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Responses API HTTP ${response.code}: ${body.take(500)}")
            }
            val parsed = json.decodeFromString(ResponsesResponse.serializer(), body)
            toResult(parsed, body, extractReasoningFromBody(body))
        }
    }

    override fun stream(request: LlmRequest): Flow<LlmChunk> = callbackFlow {
        val payload = buildBody(request, stream = true)
        val httpRequest = newRequest(payload, request, stream = true)
        val factory = EventSources.createFactory(client)

        var pendingCallId: String? = null
        var pendingName: String? = null
        val pendingArgs = StringBuilder()
        val reasoningItems = mutableListOf<String>()

        fun emitToolStart(callId: String?, name: String?, args: String) {
            if (name.isNullOrEmpty() && args.isEmpty()) return
            this@callbackFlow.trySend(
                LlmChunk.ToolCallStart(
                    ToolCall(id = callId.orEmpty(), name = name.orEmpty(), arguments = args),
                ),
            )
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return
                val eventType = root["type"].str() ?: type ?: return
                when (eventType) {
                    "response.output_text.delta" -> {
                        val delta = root["delta"].str()
                        if (!delta.isNullOrEmpty()) this@callbackFlow.trySend(LlmChunk.TextDelta(delta))
                    }

                    "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                        val delta = root["delta"].str()
                        if (!delta.isNullOrEmpty()) this@callbackFlow.trySend(LlmChunk.ReasoningDelta(delta))
                    }

                    "response.output_item.added" -> {
                        val item = root["item"].obj()
                        if (item != null && item["type"].str() == "function_call") {
                            pendingCallId = item["call_id"].str() ?: item["id"].str()
                            pendingName = item["name"].str()
                            pendingArgs.setLength(0)
                        }
                    }

                    "response.function_call_arguments.delta" -> {
                        val delta = root["delta"].str().orEmpty()
                        if (delta.isNotEmpty()) pendingArgs.append(delta)
                        emitToolStart(pendingCallId, pendingName, pendingArgs.toString())
                    }

                    "response.output_item.done" -> {
                        val item = root["item"].obj()
                        if (item != null) {
                            when (item["type"].str()) {
                                "function_call" -> {
                                    val callId = item["call_id"].str() ?: item["id"].str() ?: pendingCallId
                                    val name = item["name"].str() ?: pendingName
                                    val args = item["arguments"].str() ?: pendingArgs.toString()
                                    emitToolStart(callId, name, args)
                                }
                                "reasoning" -> normalizeReasoningItem(item)?.let { reasoningItems += it }
                                else -> Unit
                            }
                        }
                    }

                    "response.completed", "response.incomplete" -> {
                        val responseElement = root["response"]
                        if (responseElement != null) {
                            val parsed = runCatching {
                                json.decodeFromJsonElement(ResponsesResponse.serializer(), responseElement)
                            }.getOrNull()
                            if (parsed != null) {
                                this@callbackFlow.trySend(
                                    LlmChunk.Completed(toResult(parsed, data, reasoningItems.toList())),
                                )
                            }
                        }
                        this@callbackFlow.close()
                    }

                    "response.failed", "error" -> {
                        val message = root["error"].obj()?.get("message").str()
                            ?: root["message"].str()
                            ?: "流式响应错误"
                        this@callbackFlow.close(IllegalStateException(message))
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val error = t ?: IllegalStateException(
                    "Responses SSE HTTP ${response?.code}: ${response?.body?.string()?.take(500).orEmpty()}",
                )
                this@callbackFlow.close(error)
            }

            override fun onClosed(eventSource: EventSource) {
                this@callbackFlow.close()
            }
        }

        val eventSource = factory.newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun newRequest(payload: String, request: LlmRequest, stream: Boolean): Request {
        val builder = Request.Builder()
            .url(endpointFor(request.apiPlatform))
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", userAgent)
        if (stream) builder.addHeader("Accept", "text/event-stream")
        apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { builder.addHeader("Authorization", "Bearer $it") }
        // OpenCode Go gateway mandates a stable per-session header; missing it
        // returns 400 MissingSessionID (legacy `buildApiHeaders`).
        opencodeSessionHeader(request.apiPlatform, request.sessionId)?.let { (name, value) ->
            builder.addHeader(name, value)
        }
        return builder.post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    /**
     * Mirrors the legacy `getResponsesEndpoint`: DeepSeek's `/responses` lives at
     * the host root (strip a trailing `/v1`), while the OpenCode Go gateway keeps
     * `/v1` as part of its path (`keepV1InResponses`).
     */
    private fun endpointFor(platform: String?): String = responsesEndpoint(baseUrl, platform)

    private fun buildBody(request: LlmRequest, stream: Boolean): String {
        val input = buildResponsesInput(request.input)

        val tools = if (request.tools.isNotEmpty()) {
            request.tools.map { definition ->
                ToolDto(
                    name = definition.name,
                    description = definition.description,
                    parameters = json.parseToJsonElement(definition.parametersJson),
                    strict = definition.strict,
                )
            }
        } else {
            null
        }

        val toolChoice: JsonElement = when (val choice = request.toolChoice) {
            ToolChoice.Auto -> JsonPrimitive("auto")
            ToolChoice.None -> JsonPrimitive("none")
            is ToolChoice.Function -> buildJsonObject {
                put("type", "function")
                put("name", choice.name)
            }
        }

        val dto = ResponsesRequest(
            model = request.model,
            input = input,
            instructions = request.instructions,
            tools = tools,
            toolChoice = toolChoice,
            temperature = request.temperature,
            maxOutputTokens = request.maxOutputTokens ?: 8192,
            stream = stream,
            reasoning = ReasoningDto(request.reasoningEffort ?: "medium"),
        )
        return json.encodeToString(ResponsesRequest.serializer(), dto)
    }

    private fun toResult(
        response: ResponsesResponse,
        raw: String?,
        reasoning: List<String> = emptyList(),
    ): LlmResult {
        val text = response.output
            .filter { it.type == "message" }
            .flatMap { it.content.orEmpty() }
            .filter { it.type == "output_text" }
            .joinToString(separator = "") { it.text }

        val toolCalls = response.output
            .filter { it.type == "function_call" }
            .map { item ->
                ToolCall(
                    id = item.callId ?: item.name.orEmpty(),
                    name = item.name.orEmpty(),
                    arguments = item.arguments.orEmpty(),
                )
            }

        val usage = response.usage?.let {
            TokenUsage(
                inputTokens = it.inputTokens,
                outputTokens = it.outputTokens,
                cachedTokens = it.inputTokensDetails?.cachedTokens,
            )
        }

        return LlmResult(text = text, toolCalls = toolCalls, usage = usage, raw = raw, reasoning = reasoning)
    }

    /** Non-streaming reasoning items from the raw body's `output[]` (type `reasoning`). */
    private fun extractReasoningFromBody(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val output = root["output"] as? JsonArray ?: return emptyList()
        return output.mapNotNull { element ->
            (element as? JsonObject)?.takeIf { it["type"].str() == "reasoning" }
                ?.let { normalizeReasoningItem(it) }
        }
    }

    /**
     * Rebuilds a minimal Responses `reasoning` item (`{type,id,status,content,summary}`)
     * from a streamed/done item, dropping transport-only fields (legacy keeps the same
     * shape when replaying reasoning before `function_call`).
     */
    private fun normalizeReasoningItem(item: JsonObject): String? {
        val id = item["id"].str() ?: return null
        val status = item["status"].str() ?: "completed"
        val content = (item["content"] as? JsonArray).orEmpty().mapNotNull { part ->
            val text = (part as? JsonObject)?.get("text").str().orEmpty()
            if (text.isEmpty()) null else buildJsonObject {
                put("type", "reasoning_text")
                put("text", text)
            }
        }
        val summary = (item["summary"] as? JsonArray).orEmpty().mapNotNull { part ->
            val text = (part as? JsonObject)?.get("text").str().orEmpty()
            if (text.isEmpty()) null else buildJsonObject {
                put("type", "reasoning_summary")
                put("text", text)
            }
        }
        if (content.isEmpty() && summary.isEmpty()) return null
        return buildJsonObject {
            put("type", "reasoning")
            put("id", id)
            put("status", status)
            put("content", buildJsonArray { content.forEach { add(it) } })
            put("summary", buildJsonArray { summary.forEach { add(it) } })
        }.toString()
    }
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

/**
 * Resolves the final Responses endpoint for a platform, mirroring the legacy
 * `getResponsesEndpoint` (`src/js/prompts/request.js`): a trailing
 * `/chat/completions` is dropped, then `/v1` is stripped unless the platform
 * keeps it (`opencode` Go gateway), and `/responses` is appended.
 */
internal fun responsesEndpoint(baseUrl: String, platform: String?): String {
    var base = baseUrl.trim().trimEnd('/')
    base = base.replace(Regex("/chat/completions$", RegexOption.IGNORE_CASE), "").trimEnd('/')
    val keepV1 = platform != null && BuiltinPlatforms.firstOrNull { it.id == platform }?.keepV1InResponses == true
    if (!keepV1) base = base.replace(Regex("/v1$", RegexOption.IGNORE_CASE), "")
    return base + "/responses"
}

/**
 * The OpenCode Go gateway requires a stable `x-opencode-session` header; a
 * missing value returns `400 MissingSessionID`. Returns null for other
 * platforms (legacy `buildApiHeaders`).
 */
internal fun opencodeSessionHeader(platform: String?, sessionId: String?): Pair<String, String>? =
    if (platform == "opencode") "x-opencode-session" to (sessionId ?: "deeptalking-general") else null

/**
 * Builds the heterogeneous Responses `input` array from chat messages: plain
 * `{role, content}` items plus structured `function_call` / `function_call_output`
 * items and replayed `reasoning` items, mirroring the legacy `toolState.items`
 * push order (`src/js/chat/conversation.js:204-207`, `src/js/api/responses.js:72-74`).
 * `Role.Tool` must never be emitted as a plain `{role:"tool"}` message.
 */
internal fun buildResponsesInput(messages: List<ChatMessage>): List<JsonElement> {
    val json = Json { isLenient = true; ignoreUnknownKeys = true }
    val input = mutableListOf<JsonElement>()
    messages.forEach { message ->
        val reasoning = message.reasoningJson
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
        when {
            message.role == Role.Tool -> input += buildJsonObject {
                put("type", "function_call_output")
                put("call_id", message.toolCallId.orEmpty())
                put("output", message.content)
            }

            message.role == Role.Assistant && !message.toolName.isNullOrBlank() -> {
                reasoning?.forEach { input += it }
                input += buildJsonObject {
                    put("type", "function_call")
                    put("call_id", message.toolCallId.orEmpty())
                    put("name", message.toolName)
                    put("arguments", message.toolArguments.orEmpty())
                }
            }

            else -> {
                reasoning?.forEach { input += it }
                if (message.content.isNotEmpty() || message.role != Role.Assistant) {
                    input += buildJsonObject {
                        put("role", message.role.name.lowercase())
                        put("content", message.content)
                    }
                }
            }
        }
    }
    return input
}
