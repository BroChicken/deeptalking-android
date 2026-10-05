package com.deeptalking.core.network

import com.deeptalking.engine.ondevice.LlmChunk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the Responses SSE event handling ported from legacy `consumeStreamData`
 * (`src/js/chat/conversation.js:541-678`): text/reasoning deltas, streamed
 * function-call assembly, `web_search_call` status hints and terminal events.
 */
class ResponsesSseInterpreterTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun interpreter() = ResponsesSseInterpreter(json)

    @Test
    fun emitsTextDelta() {
        val outcome = interpreter().onEvent(
            "response.output_text.delta",
            """{"type":"response.output_text.delta","delta":"你好"}""",
        )
        assertEquals(listOf(LlmChunk.TextDelta("你好")), outcome.chunks)
    }

    @Test
    fun outputTextDoneBackfillsWhenNoDeltaSeen() {
        val outcome = interpreter().onEvent(
            "response.output_text.done",
            """{"type":"response.output_text.done","text":"整段正文"}""",
        )
        assertEquals(listOf(LlmChunk.TextDelta("整段正文")), outcome.chunks)
    }

    @Test
    fun webSearchSearchingSurfacesStatusHint() {
        val outcome = interpreter().onEvent(
            "response.web_search_call.searching",
            """{"type":"response.web_search_call.searching"}""",
        )
        val call = (outcome.chunks.single() as LlmChunk.ToolCallStart).call
        assertEquals(LLM_STATUS_CHUNK_NAME, call.name)
        assertEquals("正在联网搜索…", call.arguments)
    }

    @Test
    fun webSearchCompletedSurfacesStatusHint() {
        val outcome = interpreter().onEvent(
            "response.web_search_call.completed",
            """{"type":"response.web_search_call.completed"}""",
        )
        val call = (outcome.chunks.single() as LlmChunk.ToolCallStart).call
        assertEquals(LLM_STATUS_CHUNK_NAME, call.name)
        assertEquals("正在接收回复…", call.arguments)
    }

    @Test
    fun webSearchFailedSurfacesStatusHint() {
        val outcome = interpreter().onEvent(
            "response.web_search_call.failed",
            """{"type":"response.web_search_call.failed"}""",
        )
        assertEquals(
            "搜索未能完成，角色将自行应对…",
            (outcome.chunks.single() as LlmChunk.ToolCallStart).call.arguments,
        )
    }

    @Test
    fun failedWebSearchOutputItemSurfacesStatusHint() {
        val outcome = interpreter().onEvent(
            "response.output_item.done",
            """{"type":"response.output_item.done","item":{"type":"web_search_call","status":"failed"}}""",
        )
        assertEquals(
            "搜索未能完成，角色将自行应对…",
            (outcome.chunks.single() as LlmChunk.ToolCallStart).call.arguments,
        )
    }

    @Test
    fun assemblesStreamedFunctionCall() {
        val interpreter = interpreter()
        interpreter.onEvent(
            "response.output_item.added",
            """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"c1","name":"submit_response"}}""",
        )
        interpreter.onEvent(
            "response.function_call_arguments.delta",
            """{"type":"response.function_call_arguments.delta","delta":"{\"reply\":\"hi\"}"}""",
        )
        val outcome = interpreter.onEvent(
            "response.output_item.done",
            """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"c1","name":"submit_response","arguments":"{\"reply\":\"hi\"}"}}""",
        )
        val call = (outcome.chunks.single() as LlmChunk.ToolCallStart).call
        assertEquals("submit_response", call.name)
        assertEquals("""{"reply":"hi"}""", call.arguments)
    }

    @Test
    fun completedCarriesResultAndCloses() {
        val outcome = interpreter().onEvent(
            "response.completed",
            """{"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"正文"}]}]}}""",
        )
        assertTrue(outcome.close)
        val result = (outcome.chunks.single() as LlmChunk.Completed).result
        assertEquals("正文", result.text)
    }

    @Test
    fun failedEventYieldsError() {
        val outcome = interpreter().onEvent("error", """{"type":"error","error":{"message":"boom"}}""")
        assertNotNull(outcome.error)
        assertEquals("boom", outcome.error?.message)
    }
}
