package com.deeptalking.core.network

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
) : LlmBackend {

    private val endpoint: String = baseUrl.trimEnd('/') + "/responses"

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
        client.newCall(newRequest(payload)).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Responses API HTTP ${response.code}: ${body.take(500)}")
            }
            val parsed = json.decodeFromString(ResponsesResponse.serializer(), body)
            toResult(parsed, body)
        }
    }

    override fun stream(request: LlmRequest): Flow<LlmChunk> = callbackFlow {
        val payload = buildBody(request, stream = true)
        val httpRequest = newRequest(payload)
        val factory = EventSources.createFactory(client)

        var pendingCallId: String? = null
        var pendingName: String? = null
        val pendingArgs = StringBuilder()

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
                        if (item != null && item["type"].str() == "function_call") {
                            val callId = item["call_id"].str() ?: item["id"].str() ?: pendingCallId
                            val name = item["name"].str() ?: pendingName
                            val args = item["arguments"].str() ?: pendingArgs.toString()
                            emitToolStart(callId, name, args)
                        }
                    }

                    "response.completed", "response.incomplete" -> {
                        val responseElement = root["response"]
                        if (responseElement != null) {
                            val parsed = runCatching {
                                json.decodeFromJsonElement(ResponsesResponse.serializer(), responseElement)
                            }.getOrNull()
                            if (parsed != null) {
                                this@callbackFlow.trySend(LlmChunk.Completed(toResult(parsed, data)))
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

    private fun newRequest(payload: String): Request {
        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
        apiKeyProvider()?.let { builder.addHeader("Authorization", "Bearer $it") }
        return builder.post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    private fun buildBody(request: LlmRequest, stream: Boolean): String {
        val input = request.input.map { message ->
            InputItem(
                role = message.role.name.lowercase(),
                content = JsonPrimitive(message.content),
            )
        }

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

    private fun toResult(response: ResponsesResponse, raw: String?): LlmResult {
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

        return LlmResult(text = text, toolCalls = toolCalls, usage = usage, raw = raw)
    }
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
