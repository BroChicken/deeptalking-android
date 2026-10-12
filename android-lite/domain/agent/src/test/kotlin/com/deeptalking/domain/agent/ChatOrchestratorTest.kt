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
             "longTerm":[{"category":"userProfile","subject":"user","key":"用户姓名","value":"用户叫小明",
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
        // The main turn no longer writes short-term memory: the window is only appended
        // to by the whole-window extraction pass (see BackgroundTasks), so a `shortTerm`
        // field in a turn reply is ignored on purpose.
        assertTrue("turn shortTerm must be ignored", result.updatedCharacter.shortTerm.isEmpty())
    }

    @Test
    fun `currentTone is applied without the evidence gate`() = runBlocking {
        val args = """
            {"reply":"（笑了笑）今天天气真好。","quickReplies":["是啊","出去走走"],
             "dynamicState":{"currentTone":{"value":"温柔含笑，语速偏慢"},
                             "currentMood":{"value":"愉快","sourceMessageIds":["ghost"],"evidence":"不存在的原话"}}}
        """.trimIndent()
        val orchestrator = ChatOrchestrator(
            llm = ScriptedLlm(args),
            tools = ToolRegistry(listOf(NoTools())),
            memory = MemoryServiceImpl(),
            config = AppConfig(),
        )

        val result = orchestrator.run(
            character = character(),
            history = listOf(ChatMessage(id = "m1", role = Role.User, content = "今天天气如何")),
            userText = "今天天气如何",
        )

        assertEquals(
            "currentTone (delivery instruction) must be accepted without evidence",
            "温柔含笑，语速偏慢",
            result.updatedCharacter.dynamicState.currentTone,
        )
        assertEquals(
            "a non-tone field with an unresolvable source must still be rejected",
            "",
            result.updatedCharacter.dynamicState.currentMood,
        )
    }

    @Test
    fun `rejects long-term memory without valid subject and evidence`() = runBlocking {
        val args = """
            {"reply":"你好呀","quickReplies":["还不错","有点累"],
             "longTerm":[{"category":"userProfile","key":"用户姓名","value":"用户叫小明",
                          "sourceMessageIds":["m1"],"evidence":"我叫小明"}]}
        """.trimIndent()
        val orchestrator = ChatOrchestrator(
            llm = ScriptedLlm(args),
            tools = ToolRegistry(listOf(NoTools())),
            memory = MemoryServiceImpl(),
            config = AppConfig(),
        )

        val result = orchestrator.run(
            character = character(),
            history = listOf(ChatMessage(id = "m1", role = Role.User, content = "我叫小明")),
            userText = "我叫小明",
        )

        assertTrue(
            "a legacy-subject long-term entry must not be folded",
            result.updatedCharacter.longTerm.isEmpty(),
        )
    }

    @Test
    fun `streams deltas through the callback`() = runBlocking {
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

    @Test
    fun `mirrors the turn into character instant`() = runBlocking {
        val args = """{"reply":"记住了","quickReplies":["好","嗯"]}"""
        val orchestrator = ChatOrchestrator(
            llm = ScriptedLlm(args),
            tools = ToolRegistry(listOf(NoTools())),
            memory = MemoryServiceImpl(),
            config = AppConfig(),
        )
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
    fun `main-turn short-term memory goes through source validation`() = runBlocking {
        val args = """
            {"reply":"好","quickReplies":["一","二"],
             "shortTerm":[{"content":"用户2026-10-04说喜欢咖啡","sourceMessageIds":["ghost"]}]}
        """.trimIndent()
        val orchestrator = ChatOrchestrator(
            llm = ScriptedLlm(args),
            tools = ToolRegistry(listOf(NoTools())),
            memory = MemoryServiceImpl(),
            config = AppConfig(),
        )
        val result = orchestrator.run(
            character = character(),
            history = listOf(ChatMessage(id = "m1", role = Role.User, content = "我喜欢咖啡")),
            userText = "我喜欢咖啡",
        )
        assertTrue(
            "an unresolvable source id must reject the short-term write",
            result.updatedCharacter.shortTerm.isEmpty(),
        )
    }

    @Test
    fun `main-turn shortTerm is ignored now that the fold pass owns the window`() = runBlocking {
        val args = """
            {"reply":"好","quickReplies":["一","二"],
             "shortTerm":[{"content":"用户要去面试","sourceMessageIds":["m1"],
                           "timeRef":{"anchor":"day_after_tomorrow","slot":"下午"}}]}
        """.trimIndent()
        val orchestrator = ChatOrchestrator(
            llm = ScriptedLlm(args),
            tools = ToolRegistry(listOf(NoTools())),
            memory = MemoryServiceImpl(),
            config = AppConfig(),
        )
        val result = orchestrator.run(
            character = character(),
            history = listOf(ChatMessage(id = "m1", role = Role.User, content = "我后天下午面试")),
            userText = "我后天下午面试",
        )
        // Short-term entries are only produced by the whole-window extraction pass, so
        // writing them from a turn would land them mid-window (and duplicate the fold).
        assertTrue(result.updatedCharacter.shortTerm.isEmpty())
    }

    @Test
    fun `prose turn folds a legacy MEM_UPDATE block's long-term part`() = runBlocking {
        val llm = object : LlmBackend {
            override val id = "prose"
            override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
                emit(
                    LlmChunk.Completed(
                        LlmResult(
                            text = "正文\n<MEM_UPDATE>{\"shortTerm\":[{\"content\":\"用户喜欢咖啡\",\"sourceMessageIds\":[\"m1\"]}]," +
                                "\"longTerm\":[{\"category\":\"userProfile\",\"subject\":\"user\",\"key\":\"用户饮品偏好\"," +
                                "\"value\":\"用户喜欢咖啡\",\"sourceMessageIds\":[\"m1\"],\"evidence\":\"我喜欢咖啡\"}]}</MEM_UPDATE>",
                        ),
                    ),
                )
            }
            override suspend fun complete(request: LlmRequest) = LlmResult()
        }
        val orchestrator = ChatOrchestrator(
            llm = llm,
            tools = ToolRegistry(listOf(NoTools())),
            memory = MemoryServiceImpl(),
            config = AppConfig(),
        )
        val result = orchestrator.run(
            character = character(),
            history = listOf(ChatMessage(id = "m1", role = Role.User, content = "我喜欢咖啡")),
            userText = "我喜欢咖啡",
        )
        assertEquals("正文", result.reply)
        assertTrue("long-term part of the legacy block should still fold", result.updatedCharacter.longTerm.any { it.value.contains("咖啡") })
        // The block's shortTerm part is ignored (the fold pass owns the window).
        assertTrue(result.updatedCharacter.shortTerm.isEmpty())
    }

    @Test
    fun `non-stream config drives complete instead of stream`() = runBlocking {
        val args = """{"reply":"非流式","quickReplies":["一","二"]}"""
        var completeCalled = false
        val llm = object : LlmBackend {
            override val id = "nonstream"
            override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
                throw AssertionError("stream() must not be used when config.stream=false")
            }
            override suspend fun complete(request: LlmRequest): LlmResult {
                completeCalled = true
                val call = ToolCall(id = "c1", name = "submit_response", arguments = args)
                return LlmResult(toolCalls = listOf(call))
            }
        }
        val orchestrator = ChatOrchestrator(
            llm = llm,
            tools = ToolRegistry(listOf(NoTools())),
            memory = MemoryServiceImpl(),
            config = AppConfig(stream = false),
        )
        val result = orchestrator.run(character(), emptyList(), "hi")
        assertTrue("complete() should be used for non-stream config", completeCalled)
        assertEquals("非流式", result.reply)
    }

    @Test
    fun `short-term window is injected before the history, volatile last`() = runBlocking {
        val requests = mutableListOf<LlmRequest>()
        val args = """{"reply":"好","quickReplies":["一","二"]}"""
        val llm = object : LlmBackend {
            override val id = "recording"
            override fun stream(request: LlmRequest): Flow<LlmChunk> = flow {
                requests += request
                val call = ToolCall(id = "call-1", name = "submit_response", arguments = args)
                emit(LlmChunk.ToolCallStart(call))
                emit(LlmChunk.Completed(LlmResult(toolCalls = listOf(call))))
            }
            override suspend fun complete(request: LlmRequest): LlmResult = LlmResult()
        }
        val orchestrator = ChatOrchestrator(
            llm = llm,
            tools = ToolRegistry(listOf(NoTools())),
            memory = MemoryServiceImpl(),
            config = AppConfig(),
        )
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
        orchestrator.run(
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
