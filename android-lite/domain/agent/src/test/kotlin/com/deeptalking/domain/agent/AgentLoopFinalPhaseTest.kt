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
}
