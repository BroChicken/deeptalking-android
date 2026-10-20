package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnWorkerTest {

    private val args = """
        {"dynamicState":{"currentMood":{"value":"有点累","sourceMessageIds":["m1"],"evidence":"有点累"}},
         "memberDynamicState":[{"memberName":"张三","dynamicState":{"currentMood":{"value":"担心","sourceMessageIds":["m1"],"evidence":"有点累"}}}],
         "quickReplies":["还不睡吗","早点休息"],
         "todos":[{"op":"create","title":"周末看展","detail":"一起去美术馆","proposer":"user",
                   "dueAt":"2026-10-24T14:00:00+08:00","sourceMessageIds":["m1"],"evidence":"周六下午去看展"}],
         "promiseUpdates":[{"promiseId":"p1","status":"resolved","sourceMessageIds":["m1"],"evidence":"已经做到了"}]}
    """.trimIndent()

    @Test
    fun `parses the forced submit_state call`() {
        val output = TurnWorker.parse(
            LlmResult(
                toolCalls = listOf(ToolCall(id = "c1", name = TurnWorker.TOOL_NAME, arguments = args)),
            ),
        )
        assertTrue(output.ok)
        assertEquals(listOf("还不睡吗", "早点休息"), output.quickReplies)
        assertNotNull(output.dynamicState)
        assertNotNull(output.memberDynamicState)
        assertEquals(1, output.todos?.size)
        assertEquals(1, output.promiseUpdates?.size)
    }

    @Test
    fun `falls back to a whole JSON text when no call is emitted`() {
        assertTrue(TurnWorker.parse(LlmResult(text = args)).ok)
    }

    @Test
    fun `missing call and malformed text are a non-fatal failure`() {
        assertFalse(TurnWorker.parse(LlmResult(text = "没有结构化内容")).ok)
        assertFalse(TurnWorker.parse(LlmResult(text = "{oops")).ok)
    }

    @Test
    fun `tool schema forbids currentTone and requires quickReplies`() {
        val schema = TurnWorker.tool.parametersJson
        assertFalse("currentTone belongs to the main call", schema.contains("currentTone"))
        assertFalse("the \$field shorthand must be expanded", schema.contains("\$field"))
        assertTrue(schema.contains("\"quickReplies\""))
        assertTrue(schema.contains("\"memberDynamicState\""))
        // A literal `$field` would not be valid JSON Schema for the provider.
        kotlinx.serialization.json.Json.parseToJsonElement(schema)
    }

    @Test
    fun `worker request is a forced function call with no streaming`() {
        val request = TurnWorker.buildRequest(
            config = com.deeptalking.core.model.AppConfig(modelName = "m"),
            character = Character(id = "c1", name = "Alice"),
            userText = "在吗",
            reply = "在的",
            replyId = "a1",
            proactive = false,
            now = java.time.Instant.parse("2026-10-20T12:00:00Z"),
        )
        assertEquals(TurnWorker.TOOL_NAME, (request.toolChoice as com.deeptalking.engine.ondevice.ToolChoice.Function).name)
        assertEquals("none", request.reasoningEffort)
        assertFalse(request.stream)
        assertTrue(request.input.single().content.contains("【角色回复（id: a1）】"))
    }
}
