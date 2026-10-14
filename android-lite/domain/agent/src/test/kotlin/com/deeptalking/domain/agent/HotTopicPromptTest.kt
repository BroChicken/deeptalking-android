package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.domain.agent.prompts.buildVolatileContext
import com.deeptalking.domain.memory.MemoryServiceImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「热榜开场」轮的指令只加在本轮 volatile 上下文尾部：平常轮完全看不到，
 * 也不会进 system prompt（缓存前缀不受影响）。
 */
class HotTopicPromptTest {

    private val memory = MemoryServiceImpl()

    /** 固定"现在"，否则【时间基准】里的秒会随时间跳到下一格。 */
    private val now = java.time.Instant.parse("2026-10-14T04:00:00Z")

    @Test
    fun `热榜指令只在命中轮出现`() = runBlocking {
        val character = Character(id = "c1", name = "小雨")

        val plain = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        assertFalse(plain.contains("hot_topics"))
        assertFalse(plain.contains("本轮热榜话题"))

        val hot = buildVolatileContext(
            character = character,
            query = "在吗",
            memory = memory,
            now = now,
            hotTopicTurn = true,
        )
        assertTrue(hot.contains("【本轮热榜话题"))
        assertTrue(hot.contains("hot_topics"))
        assertTrue(hot.contains("只挑一条"))
        assertTrue(hot.contains("不得编造话题"))
        // 指令加在整段上下文的最后（时间基准/节奏骨架之后），平常轮完全没有这条。
        // 不能用 startsWith 比对两次调用：节奏骨架每轮随机，本来就不可比。
        assertTrue(hot.indexOf("【本轮热榜话题") > hot.indexOf("【时间基准】"))
        assertTrue(hot.trimEnd().endsWith("不得编造话题。"))
    }

    @Test
    fun `主动开场与热榜可以叠加，热榜指令在最后`() = runBlocking {
        val character = Character(id = "c1", name = "小雨")
        val context = buildVolatileContext(
            character = character,
            query = "",
            memory = memory,
            now = now,
            proactiveTurn = true,
            hotTopicTurn = true,
        )
        val proactiveAt = context.indexOf("【角色主动开场")
        val hotTopicAt = context.indexOf("【本轮热榜话题")
        assertTrue(proactiveAt >= 0)
        assertTrue(hotTopicAt > proactiveAt)
    }
}
