package com.deeptalking.domain.agent

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmChunk
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WorkerRetryTest {

    private class QueueLlm(private val results: MutableList<Result<LlmResult>>) : LlmBackend {
        var calls = 0
        override val id = "queue"
        override fun stream(request: LlmRequest): Flow<LlmChunk> = flow { error("unused") }
        override suspend fun complete(request: LlmRequest): LlmResult {
            calls++
            val result = results.removeFirst()
            return result.getOrThrow()
        }
    }

    private fun workerReply(args: String): LlmResult = LlmResult(
        toolCalls = listOf(ToolCall(id = "w1", name = TurnWorker.TOOL_NAME, arguments = args)),
    )

    private fun orchestrator(llm: LlmBackend) = ChatOrchestrator(
        llm = llm,
        tools = ToolRegistry(emptyList()),
        memory = MemoryServiceImpl(),
        config = AppConfig(modelName = "m"),
    )

    @Test
    fun `worker retries two transient failures before succeeding`() = runTest {
        val llm = QueueLlm(
            mutableListOf(
                Result.failure(IOException("network")),
                Result.failure(IOException("network")),
                Result.success(workerReply("""{"quickReplies":["好","继续"]}""")),
            ),
        )

        val result = orchestrator(llm).retryWorker(
            character = Character(id = "c1", name = "Alice"),
            userText = "在吗",
            reply = "在的",
            replyId = "a1",
            proactive = false,
        )

        assertTrue(result.ok)
        assertEquals(3, llm.calls)
        assertEquals(listOf("好", "继续"), result.quickReplies)
    }

    @Test
    fun `worker gives up after the initial call plus two retries`() = runTest {
        val llm = QueueLlm(
            mutableListOf(
                Result.failure(IOException("network")),
                Result.failure(IOException("network")),
                Result.failure(IOException("network")),
            ),
        )

        val result = orchestrator(llm).retryWorker(
            character = Character(id = "c1", name = "Alice"),
            userText = "在吗",
            reply = "在的",
            replyId = "a1",
            proactive = false,
        )

        assertFalse(result.ok)
        assertEquals("请求失败", result.failure)
        assertEquals(3, llm.calls)
    }

    @Test
    fun `rule rejections are returned as short labels`() = runTest {
        val llm = QueueLlm(
            mutableListOf(
                Result.success(
                    workerReply(
                        """
                        {
                          "dynamicState": {
                            "currentMood": {"value":"开心","sourceMessageIds":[],"evidence":""}
                          },
                          "todos": [
                            {"op":"create","title":"","evidence":"","sourceMessageIds":[]}
                          ],
                          "quickReplies":["好","继续"]
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val result = orchestrator(llm).retryWorker(
            character = Character(id = "c1", name = "Alice"),
            userText = "在吗",
            reply = "在的",
            replyId = "a1",
            proactive = false,
        )

        assertTrue(result.ok)
        assertTrue(result.issues.any { it.startsWith("当前情绪：") })
        assertTrue(result.issues.contains("待办：缺标题"))
        assertTrue(result.issues.all { it.length < 30 })
    }

    @Test
    fun `previous worker issues are replayed in the next worker request`() {
        val request = TurnWorker.buildRequest(
            config = AppConfig(modelName = "m"),
            character = Character(
                id = "c1",
                name = "Alice",
                workerIssues = listOf("待办：期限不明", "情绪：证据不符"),
            ),
            userText = "在吗",
            reply = "在的",
            replyId = "a1",
            proactive = false,
            now = java.time.Instant.parse("2026-10-20T12:00:00Z"),
        )

        val content = request.input.single().content
        assertTrue(content.contains("上轮整理未落地"))
        assertTrue(content.contains("待办：期限不明"))
        assertTrue(content.contains("不要编造证据"))
    }
}
