package com.deeptalking.domain.agent

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.ShortTermMemory
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end orchestrator tests for the plain-text protocol: the main call returns prose
 * plus an optional trailing `【语气】` line, and no longer carries structured fields.
 */
class ChatOrchestratorTest {

    /** Fake LLM that streams one canned plain-text reply. */
    private class ScriptedLlm(private val text: String) : LlmBackend {
        override val id = "scripted"

        override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
            if (text.isNotEmpty()) emit(LlmChunk.TextDelta(text))
            emit(LlmChunk.Completed(LlmResult(text = text)))
        }

        override suspend fun complete(request: LlmRequest): LlmResult = LlmResult(text = text)
    }

    private class NoTools : AgentTool {
        override val definition = ToolDefinition(name = "noop", description = "", parametersJson = "{}")
        override suspend fun execute(call: ToolCall, context: AgentContext) = AgentToolResult("{}")
    }

    private fun character() = Character(id = "c1", name = "Alice", emoji = "🙂")

    private fun orchestrator(llm: LlmBackend, config: AppConfig = AppConfig()) = ChatOrchestrator(
        llm = llm,
        tools = ToolRegistry(listOf(NoTools())),
        memory = MemoryServiceImpl(),
        config = config,
    )

    @Test
    fun `plain prose reply is returned and the tone line is stripped`() = runBlocking {
        val result = orchestrator(ScriptedLlm("你好呀，今天过得怎么样？\n\n【语气】温柔含笑，语速偏慢"))
            .run(
                character = character(),
                history = listOf(ChatMessage(id = "m1", role = Role.User, content = "在吗")),
                userText = "在吗",
            )

        assertEquals("你好呀，今天过得怎么样？", result.reply)
        assertEquals("温柔含笑，语速偏慢", result.updatedCharacter.dynamicState.currentTone)
        assertTrue(
            "the tone timestamp is written with the value",
            result.updatedCharacter.dynamicStateMeta["currentTone"]?.updatedAt != null,
        )
        assertTrue("quick replies come from the worker", result.quickReplies.isEmpty())
    }

    @Test
    fun `missing tone keeps the previous tone`() = runBlocking {
        val before = character().copy(
            dynamicState = com.deeptalking.core.model.DynamicState(currentTone = "上一轮的语气"),
        )
        val result = orchestrator(ScriptedLlm("（笑了笑）今天天气真好。")).run(
            character = before,
            history = listOf(ChatMessage(id = "m1", role = Role.User, content = "天气如何")),
            userText = "天气如何",
        )

        assertEquals("（笑了笑）今天天气真好。", result.reply)
        assertEquals("上一轮的语气", result.updatedCharacter.dynamicState.currentTone)
    }

    @Test
    fun `main call no longer writes structured memory fields`() = runBlocking {
        val json = """
            {"reply":"你好呀","quickReplies":["还不错","有点累"],
             "longTerm":[{"category":"userProfile","subject":"user","key":"用户姓名","value":"用户叫小明",
                          "sourceMessageIds":["m1"],"evidence":"我叫小明"}]}
        """.trimIndent()
        val result = orchestrator(ScriptedLlm(json)).run(
            character = character(),
            history = listOf(ChatMessage(id = "m1", role = Role.User, content = "我叫小明")),
            userText = "我叫小明",
        )

        // Whole-object JSON is still understood as a legacy fallback, but the length of the
        // main turn no longer writes the short-term window.
        assertEquals("你好呀", result.reply)
        assertTrue(result.updatedCharacter.shortTerm.isEmpty())
    }

    @Test
    fun `streams deltas through the callback`() = runBlocking {
        val deltas = StringBuilder()
        orchestrator(ScriptedLlm("流式回复正文")).run(
            character(),
            emptyList(),
            "hi",
            onDelta = { deltas.append(it) },
        )
        assertTrue("onDelta should receive streamed text", deltas.isNotEmpty())
    }

    @Test
    fun `mirrors the turn into character instant`() = runBlocking {
        val orchestrator = orchestrator(ScriptedLlm("记住了"))
        val history = listOf(ChatMessage(id = "m1", role = Role.User, content = "在吗"))
        val result = orchestrator.run(character(), history, "在吗")
        assertTrue(
            "user message should be mirrored into instant",
            result.updatedCharacter.instant.any { it.role == Role.User && it.id == "m1" },
        )
        assertTrue(
            "assistant reply should be mirrored into instant",
            result.updatedCharacter.instant.any { it.role == Role.Assistant && it.content == "记住了" },
        )
    }

    @Test
    fun `legacy MEM_UPDATE block is stripped and never applied`() = runBlocking {
        val text = "正文\n<MEM_UPDATE>{\"shortTerm\":[{\"content\":\"用户喜欢咖啡\",\"sourceMessageIds\":[\"m1\"]}]," +
            "\"longTerm\":[{\"category\":\"userProfile\",\"subject\":\"user\",\"key\":\"用户饮品偏好\"," +
            "\"value\":\"用户喜欢咖啡\",\"sourceMessageIds\":[\"m1\"],\"evidence\":\"我喜欢咖啡\"}]}</MEM_UPDATE>"
        val result = orchestrator(ScriptedLlm(text)).run(
            character = character(),
            history = listOf(ChatMessage(id = "m1", role = Role.User, content = "我喜欢咖啡")),
            userText = "我喜欢咖啡",
        )

        assertEquals("正文", result.reply)
        assertFalse(result.reply.contains("MEM_UPDATE"))
        assertTrue("legacy block must not write long-term memory", result.updatedCharacter.longTerm.isEmpty())
        assertTrue(result.updatedCharacter.shortTerm.isEmpty())
    }

    @Test
    fun `non-stream config drives complete instead of stream`() = runBlocking {
        var completeCalled = false
        val llm = object : LlmBackend {
            override val id = "nonstream"
            override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
                throw AssertionError("stream() must not be used when config.stream=false")
            }
            override suspend fun complete(request: LlmRequest): LlmResult {
                completeCalled = true
                return LlmResult(text = "非流式")
            }
        }
        val result = orchestrator(llm, AppConfig(stream = false)).run(character(), emptyList(), "hi")
        assertTrue("complete() should be used for non-stream config", completeCalled)
        assertEquals("非流式", result.reply)
    }

    @Test
    fun `short-term window is injected before the history, volatile last`() = runBlocking {
        val requests = mutableListOf<LlmRequest>()
        val llm = object : LlmBackend {
            override val id = "recording"
            override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
                requests += request
                emit(LlmChunk.Completed(LlmResult(text = "好")))
            }
            override suspend fun complete(request: LlmRequest): LlmResult {
                requests += request
                return LlmResult(text = "好")
            }
        }
        val character = character().copy(
            shortTerm = listOf(
                ShortTermMemory(
                    id = "s1",
                    content = "用户2026-10-01上午说了要紧事",
                    sourceMessageIds = listOf("m1"),
                    eventTime = "2026-10-01T10:00:00Z",
                ),
            ),
        )
        orchestrator(llm).run(
            character = character,
            history = listOf(ChatMessage(id = "m1", role = Role.User, content = "我叫小明")),
            userText = "我叫小明",
        )

        val input = requests.first().input
        // The window rides *before* the history so it stays inside the cached prefix
        // (it only changes when the fold pass rebases the history anyway).
        assertEquals(Role.User, input.first().role)
        assertTrue(
            "the short-term block must come first",
            input.first().content.startsWith("【近期摘要记忆"),
        )
        assertTrue("the window header states the count", input.first().content.contains("共 1 条"))
        assertTrue("the whole window is injected", input.first().content.contains("要紧事"))
        assertTrue("the history follows the window", input[1].content.contains("我叫小明"))
        assertTrue(
            "the volatile context stays last",
            input.last().content.startsWith("【系统提供的本轮上下文"),
        )
    }
}
