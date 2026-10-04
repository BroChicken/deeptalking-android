package com.deeptalking.domain.agent

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmChunk
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end orchestrator test with a scripted fake LLM: proves streaming deltas
 * are reported, a submit_response turn is parsed, and memory deltas are folded
 * onto the character.
 */
class ChatOrchestratorTest {

    /** Fake LLM that streams a canned submit_response tool call. */
    private class ScriptedLlm(private val submitArgs: String) : LlmBackend {
        override val id = "scripted"

        override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
            val call = ToolCall(id = "call-1", name = "submit_response", arguments = submitArgs)
            emit(LlmChunk.ToolCallStart(call))
            emit(LlmChunk.Completed(LlmResult(toolCalls = listOf(call))))
        }

        override suspend fun complete(request: LlmRequest): LlmResult = LlmResult(text = "")
    }

    private class NoTools : AgentTool {
        override val definition = ToolDefinition(name = "noop", description = "", parametersJson = "{}")
        override suspend fun execute(call: ToolCall, context: AgentContext) = AgentToolResult("{}")
    }

    private fun character() = Character(id = "c1", name = "Alice", emoji = "🙂")

    @Test
    fun `parses submit_response and folds memory onto the character`() = runBlocking {
        val args = """
            {"reply":"你好呀，今天过得怎么样？","quickReplies":["还不错","有点累"],
             "shortTerm":[{"content":"用户2026-10-04告诉角色今天心情不错","sourceMessageIds":["m1"]}],
             "longTerm":[{"category":"userProfile","key":"用户姓名","value":"用户叫小明",
                          "sourceMessageIds":["m1"],"evidence":"我叫小明"}]}
        """.trimIndent()
        val orchestrator = ChatOrchestrator(
            llm = ScriptedLlm(args),
            tools = ToolRegistry(listOf(NoTools())),
            memory = MemoryServiceImpl(),
            config = AppConfig(),
        )

        val deltas = StringBuilder()
        val result = orchestrator.run(
            character = character(),
            history = listOf(ChatMessage(id = "m1", role = Role.User, content = "我叫小明")),
            userText = "我叫小明",
            onDelta = { deltas.append(it) },
        )

        assertEquals("你好呀，今天过得怎么样？", result.reply)
        assertEquals(listOf("还不错", "有点累"), result.quickReplies)
        assertTrue("long-term memory should be folded", result.updatedCharacter.longTerm.any { it.value.contains("小明") })
        assertTrue("short-term memory should be folded", result.updatedCharacter.shortTerm.isNotEmpty())
    }

    @Test
    fun `streams deltas through the callback`() = runBlocking {
        val args = """{"reply":"流式回复正文","quickReplies":["一","二"]}"""
        val streamingLlm = object : LlmBackend {
            override val id = "streaming"
            override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
                emit(LlmChunk.TextDelta("流式回复"))
                emit(LlmChunk.TextDelta("正文"))
                emit(LlmChunk.Completed(LlmResult(text = "流式回复正文")))
            }
            override suspend fun complete(request: LlmRequest) = LlmResult()
        }
        val orchestrator = ChatOrchestrator(
            llm = streamingLlm,
            tools = ToolRegistry(listOf(NoTools())),
            memory = MemoryServiceImpl(),
            config = AppConfig(),
        )
        val deltas = StringBuilder()
        orchestrator.run(character(), emptyList(), "hi", onDelta = { deltas.append(it) })
        assertTrue("onDelta should receive streamed text", deltas.isNotEmpty())
    }
}
