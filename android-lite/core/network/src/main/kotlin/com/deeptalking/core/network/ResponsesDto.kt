package com.deeptalking.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DTOs for the OpenAI-compatible "Responses" API used by DeepSeek / OpenCode Go.
 *
 * [ResponsesRequest.input] stays a list of raw [JsonElement] because the payload is
 * heterogeneous: plain `{role, content}` messages plus structured
 * `function_call` / `function_call_output` / `reasoning` items (legacy
 * `toolState.items`, `src/js/api/responses.js:72-74`).
 */
@Serializable
data class ResponsesRequest(
    val model: String,
    val input: List<JsonElement> = emptyList(),
    val instructions: String? = null,
    val tools: List<ToolDto>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val temperature: Double? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val stream: Boolean = false,
    val reasoning: ReasoningDto? = null,
)

@Serializable
data class ToolDto(
    val type: String = "function",
    val name: String,
    val description: String? = null,
    val parameters: JsonElement,
    val strict: Boolean = false,
)

@Serializable
data class ReasoningDto(
    val effort: String,
)

@Serializable
data class ResponsesResponse(
    val id: String? = null,
    val output: List<OutputItem> = emptyList(),
    val usage: UsageDto? = null,
    val status: String? = null,
    val error: JsonElement? = null,
    @SerialName("incomplete_details") val incompleteDetails: JsonElement? = null,
)

@Serializable
data class OutputItem(
    val type: String = "",
    val name: String? = null,
    val arguments: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val content: List<OutputContent>? = null,
    val role: String? = null,
    val text: String? = null,
)

@Serializable
data class OutputContent(
    val type: String = "",
    val text: String = "",
)

@Serializable
data class UsageDto(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("input_tokens_details") val inputTokensDetails: InputTokensDetails? = null,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int? = null,
)

@Serializable
data class InputTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
)
