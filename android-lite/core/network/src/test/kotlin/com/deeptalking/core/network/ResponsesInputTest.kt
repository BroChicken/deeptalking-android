package com.deeptalking.core.network

import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the Responses `input` shaping for tool rounds against the legacy
 * `toolState.items` contract (`src/js/chat/conversation.js:204-207`): the model
 * must receive structured `function_call` / `function_call_output` items, never a
 * plain `{"role":"tool"}` message (which the gateway rejects with 422).
 */
class ResponsesInputTest {

    @Test
    fun toolRoundEmitsStructuredItemsWithMatchingCallId() {
        val input = buildResponsesInput(
            listOf(
                ChatMessage(role = Role.User, content = "hi"),
                ChatMessage(
                    role = Role.Assistant,
                    content = "",
                    toolCallId = "call_1",
                    toolName = "web_search",
                    toolArguments = """{"query":"奥本海默"}""",
                ),
                ChatMessage(role = Role.Tool, content = "{\"ok\":true}", toolCallId = "call_1"),
            ),
        )

        assertEquals(3, input.size)
        val call = input[1] as JsonObject
        val output = input[2] as JsonObject
        assertEquals("function_call", (call["type"] as JsonPrimitive).content)
        assertEquals("web_search", (call["name"] as JsonPrimitive).content)
        assertEquals("call_1", (call["call_id"] as JsonPrimitive).content)
        assertEquals("function_call_output", (output["type"] as JsonPrimitive).content)
        assertEquals("call_1", (output["call_id"] as JsonPrimitive).content)
        assertEquals("{\"ok\":true}", (output["output"] as JsonPrimitive).content)
        // The old, gateway-rejected representation must never appear.
        assertTrue(input.none { (it as? JsonObject)?.get("role")?.toString() == "\"tool\"" })
    }

    @Test
    fun reasoningItemsPrecedeFunctionCall() {
        val reasoning = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_1")
                    put("status", "completed")
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "reasoning_text"); put("text", "思考") }) })
                    put("summary", buildJsonArray { })
                },
            )
        }.toString()

        val input = buildResponsesInput(
            listOf(
                ChatMessage(
                    role = Role.Assistant,
                    content = "",
                    toolCallId = "call_9",
                    toolName = "get_current_time",
                    toolArguments = "{}",
                    reasoningJson = reasoning,
                ),
            ),
        )

        assertEquals(2, input.size)
        assertEquals("reasoning", ((input[0] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("function_call", ((input[1] as JsonObject)["type"] as JsonPrimitive).content)
    }

    @Test
    fun plainMessagesStayRoleBased() {
        val input = buildResponsesInput(
            listOf(
                ChatMessage(role = Role.User, content = "你好"),
                ChatMessage(role = Role.Assistant, content = "你也好"),
            ),
        )
        assertEquals(2, input.size)
        assertEquals("\"user\"", (input[0] as JsonObject)["role"].toString())
        assertEquals("\"assistant\"", (input[1] as JsonObject)["role"].toString())
        assertFalse(input.any { (it as JsonObject).containsKey("type") })
    }

    @Test
    fun blankAssistantWithoutReasoningIsDropped() {
        val input = buildResponsesInput(listOf(ChatMessage(role = Role.Assistant, content = "")))
        assertTrue(input.isEmpty())
        assertNull(input.firstOrNull())
    }

    @Test
    fun imageToolOutputIsPassedThroughAsInputPartsArray() {
        val content =
            """[{"type":"input_text","text":"封面"},{"type":"input_image","image_url":"https://x/cover.jpg","detail":"low"}]"""
        val input = buildResponsesInput(
            listOf(ChatMessage(role = Role.Tool, content = content, toolCallId = "call_img")),
        )
        val output = (input.single() as JsonObject)["output"]
        assertTrue(output is kotlinx.serialization.json.JsonArray)
        val parts = output as kotlinx.serialization.json.JsonArray
        assertEquals(2, parts.size)
        assertEquals("input_image", ((parts[1] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("https://x/cover.jpg", ((parts[1] as JsonObject)["image_url"] as JsonPrimitive).content)
    }

    @Test
    fun plainToolOutputStaysString() {
        val input = buildResponsesInput(
            listOf(ChatMessage(role = Role.Tool, content = "{\"ok\":true}", toolCallId = "call_1")),
        )
        val output = (input.single() as JsonObject)["output"]
        assertEquals("{\"ok\":true}", (output as JsonPrimitive).content)
    }
}
