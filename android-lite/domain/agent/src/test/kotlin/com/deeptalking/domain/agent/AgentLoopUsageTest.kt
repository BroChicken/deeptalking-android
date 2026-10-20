package com.deeptalking.domain.agent

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.domain.agent.prompts.RequestBuilder
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmChunk
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.TokenUsage
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A chat turn can issue several model requests (tool rounds + the forced submit round), and
 * only the first of them carries a cache-breaking prefix. The loop must therefore report
 * **every** round's usage — reporting only the last one (which the same turn just re-warmed)
 * is what made the header cache hit rate look permanently healthy.
 */
class AgentLoopUsageTest {

    private class ProbeTool : AgentTool {
        override val definition = ToolDefinition(name = "probe", description = "", parametersJson = "{}")
        override suspend fun execute(call: ToolCall, context: AgentContext) = AgentToolResult("""{"ok":true}""")
    }

    /** Round 1 asks for a tool, round 2 answers — each with its own usage. */
    private class TwoRoundLlm : LlmBackend {
        override val id = "two-round"
        var calls = 0

        override suspend fun complete(request: LlmRequest): LlmResult = error("complete() unused")

        override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
            calls++
            if (calls == 1) {
                emit(
                    LlmChunk.Completed(
                        LlmResult(
                            toolCalls = listOf(ToolCall(id = "call_1", name = "probe", arguments = "{}")),
                            usage = TokenUsage(inputTokens = 100, outputTokens = 10, cachedTokens = 20),
                        ),
                    ),
                )
            } else {
                emit(
                    LlmChunk.Completed(
                        LlmResult(
                            text = """{"reply":"好","quickReplies":["嗯","好"]}""",
                            usage = TokenUsage(inputTokens = 200, outputTokens = 20, cachedTokens = 150),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun everyRoundOfTheTurnIsReported() = runBlocking {
        val outcome = AgentLoop(TwoRoundLlm(), ToolRegistry(listOf(ProbeTool()))).run(
            character = Character(id = "c1", name = "Alice"),
            instructions = "sys",
            baseInput = emptyList(),
            autoTools = listOf(ProbeTool().definition),
            builder = RequestBuilder(AppConfig(stream = true)),
            model = "m",
        )

        assertEquals(2, outcome.roundUsages.size)
        // The turn's totals: the cache-breaking first round is included, not hidden.
        assertEquals(300, outcome.roundUsages.sumOf { it.inputTokens })
        assertEquals(170, outcome.roundUsages.sumOf { it.cachedTokens ?: 0 })
        assertEquals(30, outcome.roundUsages.sumOf { it.outputTokens })
        assertEquals(outcome.roundUsages.last(), outcome.usage)
    }
}
