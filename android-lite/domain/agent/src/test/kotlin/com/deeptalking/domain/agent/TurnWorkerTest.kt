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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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
    fun `tool schema allows currentTone for members only and requires quickReplies`() {
        val schema = TurnWorker.tool.parametersJson
        assertFalse("the \$field shorthand must be expanded", schema.contains("\$field"))
        assertTrue(schema.contains("\"quickReplies\""))
        assertTrue(schema.contains("\"memberDynamicState\""))
        // A literal `$field` would not be valid JSON Schema for the provider.
        val root = kotlinx.serialization.json.Json.parseToJsonElement(schema).jsonObject
        val properties = root["properties"]!!.jsonObject
        // The character-level state never carries the read-aloud tone.
        val stateProps = properties["dynamicState"]!!.jsonObject["properties"]!!.jsonObject
        assertFalse("character currentTone belongs to the main call", "currentTone" in stateProps)
        // Member state does: each member reads their own lines with their own delivery.
        val memberState = properties["memberDynamicState"]!!
            .jsonObject["items"]!!.jsonObject["properties"]!!
            .jsonObject["dynamicState"]!!.jsonObject["properties"]!!.jsonObject
        assertTrue("members need their own currentTone", "currentTone" in memberState)
    }

    @Test
    fun `todos fields document proposer and evidence`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(TurnWorker.tool.parametersJson).jsonObject
        val todoProps = root["properties"]!!.jsonObject["todos"]!!
            .jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        listOf("proposer", "evidence").forEach { key ->
            val description = (todoProps[key]!!.jsonObject["description"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull
            assertNotNull("$key needs a description", description)
            assertTrue("$key description is empty", description!!.isNotBlank())
        }
    }

    @Test
    fun `quick replies keep exactly two or are rejected with a warning`() {
        fun parse(vararg replies: String) = TurnWorker.parse(
            LlmResult(
                toolCalls = listOf(
                    ToolCall(
                        id = "c1",
                        name = TurnWorker.TOOL_NAME,
                        arguments = """{"quickReplies":[${replies.joinToString(",") { "\"$it\"" }}]}""",
                    ),
                ),
            ),
        )

        val exact = parse("还不睡吗", "早点休息")
        assertEquals(listOf("还不睡吗", "早点休息"), exact.quickReplies)
        assertTrue(exact.warnings.isEmpty())

        // Over-delivery: the first two stay usable, the extra one is reported, not hidden.
        val extra = parse("一", "二", "三")
        assertEquals(listOf("一", "二"), extra.quickReplies)
        assertEquals(1, extra.warnings.size)
        assertTrue(extra.warnings.single().contains("超过"))

        // Under-delivery: the whole list is unusable and the repair path regenerates it.
        val missing = parse("只有一条")
        assertTrue(missing.quickReplies.isEmpty())
        assertEquals(1, missing.warnings.size)
        assertTrue(missing.warnings.single().contains("只有 1 条"))

        // Duplicates collapse first, so two identical lines are one line — still invalid.
        val duplicate = parse("嗯", "嗯")
        assertTrue(duplicate.quickReplies.isEmpty())
    }

    @Test
    fun `member states keep their own currentTone`() {
        val output = TurnWorker.parse(
            LlmResult(
                toolCalls = listOf(
                    ToolCall(
                        id = "c1",
                        name = TurnWorker.TOOL_NAME,
                        arguments = """
                            {"quickReplies":["一","二"],
                             "memberDynamicState":[
                               {"memberName":"张三","dynamicState":{
                                 "currentMood":{"value":"担心","sourceMessageIds":["m1"],"evidence":"担心"},
                                 "currentTone":{"value":"压低声音"}}},
                               {"memberName":"","dynamicState":{}}
                             ]}
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val members = TurnWorker.memberStates(output)
        assertEquals(1, members.size)
        val (name, state) = members.single()
        assertEquals("张三", name)
        assertTrue("member tone must survive parsing", "currentTone" in state)
    }

    @Test
    fun `worker request uses the shared output budget`() {
        val request = TurnWorker.buildRequest(
            config = com.deeptalking.core.model.AppConfig(modelName = "m"),
            character = Character(id = "c1", name = "Alice"),
            userText = "在吗",
            reply = "在的",
            replyId = "a1",
            proactive = false,
            now = java.time.Instant.parse("2026-10-20T12:00:00Z"),
        )
        assertEquals(TurnWorker.MAX_OUTPUT_TOKENS, request.maxOutputTokens)
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
