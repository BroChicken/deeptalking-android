package com.deeptalking.domain.agent

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.domain.agent.prompts.RequestBuilder
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmChunk
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolChoice
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FINAL phase: tool-less terminal round, empty rounds retried at most twice. */
class AgentLoopFinalPhaseTest {

    private class ScriptedLlm(private val results: List<LlmResult>) : LlmBackend {
        override val id = "scripted"
        val requests = mutableListOf<LlmRequest>()
        private var index = 0

        override suspend fun complete(request: LlmRequest): LlmResult {
            requests += request
            val i = index++
            return results[i.coerceAtMost(results.lastIndex)]
        }

        override fun stream(request: LlmRequest): kotlinx.coroutines.flow.Flow<LlmChunk> =
            kotlinx.coroutines.flow.flowOf(LlmChunk.Completed(LlmResult()))
    }

    private class ProbeTool : AgentTool {
        override val definition = ToolDefinition(name = "probe", description = "", parametersJson = "{}")
        override suspend fun execute(call: ToolCall, context: AgentContext) = AgentToolResult("""{"ok":true}""")
    }

    private fun run(llm: LlmBackend, tools: List<AgentTool> = emptyList()) = runBlocking {
        AgentLoop(llm, ToolRegistry(tools)).run(
            character = Character(id = "c1", name = "Alice"),
            instructions = "sys",
            baseInput = emptyList(),
            autoTools = tools.map { it.definition },
            builder = RequestBuilder(AppConfig(stream = false)),
            model = "m",
        )
    }

    @Test
    fun `empty auto round falls through to a tool-less final round`() {
        val llm = ScriptedLlm(listOf(LlmResult(text = ""), LlmResult(text = "终稿")))
        val outcome = run(llm)

        assertEquals("终稿", outcome.text)
        assertEquals(2, llm.requests.size)
        assertEquals(ToolChoice.Auto, llm.requests[1].toolChoice)
        assertTrue("FINAL must not carry tools", llm.requests[1].tools.isEmpty())
    }

    @Test
    fun `empty final rounds are retried at most twice then return empty`() {
        val llm = ScriptedLlm(listOf(LlmResult(text = ""), LlmResult(text = ""), LlmResult(text = "")))
        val outcome = run(llm)

        assertEquals("", outcome.text)
        // AUTO(1) + FINAL + 2 retries
        assertEquals(4, llm.requests.size)
        assertTrue("every FINAL round is tool-less", llm.requests.drop(1).all { it.tools.isEmpty() })
    }

    @Test
    fun `tool round is followed by a prose reply and no submit call`() {
        val call = ToolCall(id = "call-1", name = "probe", arguments = "{}")
        val llm = ScriptedLlm(
            listOf(
                LlmResult(toolCalls = listOf(call)),
                LlmResult(text = "看过工具结果后的回复"),
            ),
        )
        val outcome = run(llm, listOf(ProbeTool()))

        assertEquals("看过工具结果后的回复", outcome.text)
        assertEquals(1, outcome.executedCalls)
        assertNull(outcome.submitCall)
        assertEquals(2, llm.requests.size)
        assertFalse(llm.requests[1].input.none { it.role == com.deeptalking.core.model.Role.Tool })
    }

    @Test
    fun `tool-only response never becomes the reply`() {
        val call = ToolCall(id = "call-1", name = "probe", arguments = "{}")
        // The tool round carries prose as well; the contract says the final reply must be a
        // separate tool-less round, so the accumulated prose must not be returned early.
        val llm = ScriptedLlm(
            listOf(
                LlmResult(text = "工具轮里的旁白", toolCalls = listOf(call)),
                LlmResult(text = "真正的回复"),
            ),
        )
        val outcome = run(llm, listOf(ProbeTool()))
        assertEquals("真正的回复", outcome.text)
    }

    @Test
    fun `all calls of one response are executed and answered`() {
        val calls = (1..3).map { ToolCall(id = "call-$it", name = "probe", arguments = "{}") }
        val llm = ScriptedLlm(listOf(LlmResult(toolCalls = calls), LlmResult(text = "三件事都查过了")))

        val outcome = run(llm, listOf(ProbeTool()))

        assertEquals("三件事都查过了", outcome.text)
        assertEquals(3, outcome.executedCalls)
        // Three calls in one model response are still one tool round.
        assertEquals(1, outcome.rounds)
        val followUp = llm.requests[1].input
        val assistantCalls = followUp.filter { it.role == com.deeptalking.core.model.Role.Assistant && it.toolCallId != null }
        val outputs = followUp.filter { it.role == com.deeptalking.core.model.Role.Tool }
        assertEquals(3, assistantCalls.size)
        assertEquals(3, outputs.size)
        // Every function_call has exactly one function_call_output with the same call id.
        assertEquals(
            assistantCalls.mapNotNull { it.toolCallId }.toSet(),
            outputs.mapNotNull { it.toolCallId }.toSet(),
        )
        // The replay shape is: all calls of the response, then all of their outputs.
        val firstOutput = followUp.indexOfFirst { it.role == com.deeptalking.core.model.Role.Tool }
        assertTrue(
            "function_call items must precede their outputs",
            assistantCalls.all { followUp.indexOf(it) < firstOutput },
        )
    }

    @Test
    fun `calls without ids still get distinct call ids and outputs`() {
        val calls = listOf(
            ToolCall(id = "", name = "probe", arguments = "{}"),
            ToolCall(id = "", name = "probe", arguments = "{}"),
        )
        val llm = ScriptedLlm(listOf(LlmResult(toolCalls = calls), LlmResult(text = "好")))

        val outcome = run(llm, listOf(ProbeTool()))

        assertEquals(2, outcome.executedCalls)
        val followUp = llm.requests[1].input
        val assistantIds = followUp
            .filter { it.role == com.deeptalking.core.model.Role.Assistant && it.toolCallId != null }
            .mapNotNull { it.toolCallId }
        val outputIds = followUp
            .filter { it.role == com.deeptalking.core.model.Role.Tool }
            .mapNotNull { it.toolCallId }
        assertEquals(assistantIds.size, assistantIds.distinct().size)
        assertEquals(assistantIds.toSet(), outputIds.toSet())
    }

    @Test
    fun `round cap counts rounds and never truncates a response`() {
        // Two calls per response, one response more than the cap allows.
        val calls = listOf(
            ToolCall(id = "left", name = "probe", arguments = "{}"),
            ToolCall(id = "right", name = "probe", arguments = "{}"),
        )
        val results = (1..com.deeptalking.core.common.AppLimits.Agent.MAX_TOOL_ROUNDS + 1)
            .map { LlmResult(toolCalls = calls) } + LlmResult(text = "收尾")
        val llm = ScriptedLlm(results)

        val outcome = run(llm, listOf(ProbeTool()))

        assertEquals("收尾", outcome.text)
        assertEquals(com.deeptalking.core.common.AppLimits.Agent.MAX_TOOL_ROUNDS, outcome.rounds)
        // Two calls ran in every admitted round — the cap never cuts a response in half.
        assertEquals(com.deeptalking.core.common.AppLimits.Agent.MAX_TOOL_ROUNDS * 2, outcome.executedCalls)
        assertEquals(com.deeptalking.core.common.AppLimits.Agent.MAX_TOOL_ROUNDS + 2, llm.requests.size)
        assertTrue("the last request must be the tool-less FINAL", llm.requests.last().tools.isEmpty())
    }
}
