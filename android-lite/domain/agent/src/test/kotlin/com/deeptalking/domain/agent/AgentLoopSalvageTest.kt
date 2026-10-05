package com.deeptalking.domain.agent

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.domain.agent.prompts.RequestBuilder
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmChunk
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end salvage order: when a turn yields no streamed/`message` text, the
 * loop must fall back to `extractResponsesText`, then an embedded `reply` JSON in
 * a reasoning item, then `extractAnyResponseText` (`conversation.js:249-254`).
 */
class AgentLoopSalvageTest {

    private class RawLlm(private val raw: String) : LlmBackend {
        override val id = "raw"
        override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
            emit(LlmChunk.Completed(LlmResult(raw = raw)))
        }
        override suspend fun complete(request: LlmRequest): LlmResult = LlmResult(raw = raw)
    }

    private fun runLoop(llm: LlmBackend): LoopOutcome = runBlocking {
        AgentLoop(llm, ToolRegistry(emptyList())).run(
            character = Character(id = "c1", name = "Alice"),
            instructions = "sys",
            baseInput = emptyList(),
            autoTools = emptyList(),
            submitTool = ToolDefinition(name = "submit_response", description = "", parametersJson = "{}"),
            builder = RequestBuilder(AppConfig(stream = false)),
            model = "m",
        )
    }

    @Test
    fun salvagesTopLevelOutputText() {
        val outcome = runLoop(RawLlm("""{"output_text":"兜底正文"}"""))
        assertEquals("兜底正文", outcome.text)
    }

    @Test
    fun salvagesReplyEmbeddedInReasoning() {
        val raw = """{"output":[{"type":"reasoning","content":[{"type":"reasoning_text","text":"thinking {\"reply\":\"思考里的回复\"}"}]}]}"""
        val outcome = runLoop(RawLlm(raw))
        assertTrue(outcome.text.contains("思考里的回复"), outcome.text)
    }

    @Test
    fun salvagesAnyOutputTextAsLastResort() {
        val raw = """{"output":[{"type":"custom","content":[{"type":"output_text","text":"任意正文"}]}]}"""
        val outcome = runLoop(RawLlm(raw))
        assertEquals("任意正文", outcome.text)
    }
}
