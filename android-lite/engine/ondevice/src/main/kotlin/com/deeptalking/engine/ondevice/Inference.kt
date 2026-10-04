package com.deeptalking.engine.ondevice

import com.deeptalking.core.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/** Common marker for every pluggable inference backend. */
interface InferenceBackend {
    val id: String
}

/* ---------------------------------- LLM ---------------------------------- */

data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema for the parameters object, serialized as a JSON string. */
    val parametersJson: String,
    val strict: Boolean = false,
)

sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data class Function(val name: String) : ToolChoice
}

data class LlmRequest(
    val model: String,
    val instructions: String? = null,
    val input: List<ChatMessage> = emptyList(),
    val tools: List<ToolDefinition> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val stream: Boolean = false,
    val reasoningEffort: String? = null,
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int? = null,
)

data class LlmResult(
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: TokenUsage? = null,
    val raw: String? = null,
)

sealed interface LlmChunk {
    data class TextDelta(val text: String) : LlmChunk
    data class ReasoningDelta(val text: String) : LlmChunk
    data class ToolCallStart(val call: ToolCall) : LlmChunk
    data class Completed(val result: LlmResult) : LlmChunk
}

interface LlmBackend : InferenceBackend {
    fun supportsTools(): Boolean = true
    fun stream(request: LlmRequest): Flow<LlmChunk>
    suspend fun complete(request: LlmRequest): LlmResult
}

/* ----------------------- Embedding / ASR / TTS --------------------------- */

interface EmbeddingBackend : InferenceBackend {
    suspend fun embed(texts: List<String>): List<FloatArray>
}

interface AsrBackend : InferenceBackend {
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String
}

data class AudioChunk(
    val pcm: ShortArray,
    val sampleRate: Int,
    val isLast: Boolean = false,
)

interface TtsBackend : InferenceBackend {
    /**
     * Synthesizes speech. The on-device implementation is added later; the
     * interface exists now so callers never depend on a concrete model.
     */
    suspend fun synthesize(text: String, voice: String? = null): Flow<AudioChunk>
}

/* ------------------------------ Registry --------------------------------- */

/**
 * Single place the app resolves inference capabilities from. Only [llm] is
 * required today; the rest stay nullable until their milestones land.
 */
class InferenceRegistry(
    val llm: LlmBackend,
    val embedding: EmbeddingBackend? = null,
    val asr: AsrBackend? = null,
    val tts: TtsBackend? = null,
)
