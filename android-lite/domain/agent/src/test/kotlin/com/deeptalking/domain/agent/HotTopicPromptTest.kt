package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import com.deeptalking.domain.agent.prompts.buildVolatileContext
import com.deeptalking.domain.memory.MemoryServiceImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 「热榜」轮的指令只加在本轮 volatile 上下文末尾段（在【消息时间元数据】之前）：平常轮
 * 完全看不到，也不会进 system prompt（缓存前缀不受影响）。工具已合并为
 * `hot_board(platform=...)`，提示词里不能再出现 `zhihu_hot` 这类旧工具名。
 */
class HotTopicPromptTest {

    private val memory = MemoryServiceImpl()

    /** 固定"现在"，否则【时间】里的分钟会随时间跳到下一格。 */
    private val now: Instant = Instant.parse("2026-10-14T04:00:00Z")

    private fun characterWithOneMessage(): Character = Character(
        id = "c1",
        name = "小雨",
        instant = listOf(
            ChatMessage(id = "msg_1", role = Role.User, content = "在吗", timestamp = "2026-10-14T03:58:00Z"),
        ),
    )

    @Test
    fun `热榜指令只在命中轮出现`() = runBlocking {
        val character = Character(id = "c1", name = "小雨")

        val plain = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        assertFalse(plain.contains("【热榜】"))
        assertFalse(plain.contains("hot_board"))

        val hot = buildVolatileContext(
            character = character,
            query = "在吗",
            memory = memory,
            now = now,
            hotTopicTurn = true,
        )
        assertTrue(hot.contains("【热榜】"))
        assertTrue(hot.contains("hot_board"))
        assertTrue(hot.contains("platform=zhihu"))
        assertTrue(hot.contains("只挑一条"))
        assertTrue(hot.contains("不编造话题"))
        assertFalse(hot.contains("zhihu_hot"))
        assertFalse(hot.contains("baidu_hot"))
        // 指令加在【时间】之后；平常轮完全没有这条。
        assertTrue(hot.indexOf("【热榜】") > hot.indexOf("【时间】"))
        assertTrue(hot.trimEnd().endsWith("不编造话题。"))
    }

    @Test
    fun `主动开场与热榜可以叠加，热榜指令在消息元数据之前`() = runBlocking {
        val context = buildVolatileContext(
            character = characterWithOneMessage(),
            query = "",
            memory = memory,
            now = now,
            proactiveTurn = true,
            hotTopicTurn = true,
        )
        val proactiveAt = context.indexOf("【主动开场】")
        val hotTopicAt = context.indexOf("【热榜】")
        assertTrue(proactiveAt >= 0)
        assertTrue(hotTopicAt > proactiveAt)
        // 10 本轮指令 → 11 消息时间元数据：元数据永远是最后一段。
        assertTrue(context.indexOf("【消息时间元数据，仅供内部推理】") > hotTopicAt)
    }
}
