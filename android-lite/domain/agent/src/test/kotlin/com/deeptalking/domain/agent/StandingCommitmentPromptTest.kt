package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Todo
import com.deeptalking.core.model.TodoStatus
import com.deeptalking.domain.agent.prompts.STANDING_COMMITMENT_LIMIT
import com.deeptalking.domain.agent.prompts.UNFINISHED_BLOCK_CHARS
import com.deeptalking.domain.agent.prompts.buildUnfinishedBlock
import com.deeptalking.domain.agent.prompts.buildVolatileContext
import com.deeptalking.domain.agent.prompts.selectStandingCommitments
import com.deeptalking.domain.memory.MemoryServiceImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 「每晚/每天」这类周期性约定本来只是一条普通长期记忆，只有被检索命中才进上下文。
 * V2 把它并进【未完成事项】（`- [周期] key：value`），不再单独占【长期约定提醒】段；
 * 待办同样并入这段，同一条记忆不得以两种身份重复出现。
 */
class StandingCommitmentPromptTest {

    private val memory = MemoryServiceImpl()

    /** 固定"现在"，否则【时间】里的分钟会随时间跳到下一格。 */
    private val now: Instant = Instant.parse("2026-10-14T04:00:00Z")

    private fun mem(
        id: String,
        key: String,
        value: String = "",
        category: MemoryCategory = MemoryCategory.Promises,
        importance: Int = 5,
        status: PromiseStatus = PromiseStatus.Active,
        dueAt: String? = null,
        updatedAt: String? = null,
    ) = LongTermMemory(
        id = id,
        category = category,
        key = key,
        value = value,
        importance = importance,
        status = status,
        dueAt = dueAt,
        updatedAt = updatedAt,
    )

    @Test
    fun `周期性约定进入未完成事项`() = runBlocking {
        val character = Character(
            id = "c1",
            name = "小雨",
            longTerm = listOf(
                mem(
                    id = "m1",
                    key = "每晚的约定",
                    value = "晚安吻后说一次「鱼鱼爱你」，不许重样",
                    importance = 9,
                ),
            ),
        )
        val block = buildUnfinishedBlock(character, now)
        assertTrue(block.startsWith("【未完成事项】"))
        assertTrue(block.contains("- [周期] 每晚的约定：晚安吻后说一次「鱼鱼爱你」，不许重样"))
        assertFalse(block.contains("[待办]"))

        val context = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        assertTrue(context.contains("【未完成事项】"))
        assertTrue(context.contains("[周期] 每晚的约定"))
        // 旧段整段消失。
        assertFalse(context.contains("【长期约定提醒"))
        assertFalse(context.contains("【待跟进承诺】"))
        assertFalse(context.contains("【待办约定】"))
    }

    @Test
    fun `非周期性约定与已结束或非约定类记忆都不注入`() = runBlocking {
        val character = Character(
            id = "c1",
            name = "小雨",
            longTerm = listOf(
                // promises，但没有周期性词
                mem(id = "m1", key = "周末去看展", value = "说好一起去看展", importance = 10),
                // 有周期性词，但已结束
                mem(id = "m2", key = "每晚道晚安", value = "已经说好了", importance = 10, status = PromiseStatus.Resolved),
                // 有周期性词，但不是 promises/habits
                mem(
                    id = "m3",
                    key = "每天喝咖啡",
                    value = "对方每天都喝",
                    category = MemoryCategory.UserProfile,
                    importance = 10,
                ),
                mem(
                    id = "m4",
                    key = "每天写日记",
                    value = "对方每天都写",
                    category = MemoryCategory.Events,
                    importance = 10,
                ),
            ),
        )
        assertTrue(selectStandingCommitments(character).isEmpty())
        assertEquals("", buildUnfinishedBlock(character, now))
        val context = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        assertFalse(context.contains("【未完成事项】"))
        assertFalse(context.contains("【长期约定提醒"))
    }

    @Test
    fun `超过条数上限时按重要性再按最近更新时间取舍`() {
        val character = Character(
            id = "c1",
            name = "小雨",
            longTerm = listOf(
                mem(id = "a", key = "每晚说晚安", importance = 3, updatedAt = "2026-10-01T00:00:00Z"),
                mem(id = "b", key = "每天早安吻", importance = 9, updatedAt = "2026-10-01T00:00:00Z"),
                mem(id = "c", key = "每周写一封信", importance = 7, updatedAt = "2026-10-02T00:00:00Z"),
                mem(id = "d", key = "每个月看一次电影", importance = 7, updatedAt = "2026-10-05T00:00:00Z"),
                mem(id = "e", key = "一直戴着手链", importance = 5, updatedAt = "2026-10-09T00:00:00Z"),
                mem(id = "f", key = "每年过纪念日", importance = 1, updatedAt = "2026-10-09T00:00:00Z"),
            ),
        )
        // 重要性降序；同为 7 时按最近更新降序（d 先于 c）；上限 4，第 5 名起截掉。
        assertEquals(listOf("b", "d", "c", "e"), selectStandingCommitments(character).map { it.id })
        assertEquals(STANDING_COMMITMENT_LIMIT, selectStandingCommitments(character).size)

        val block = buildUnfinishedBlock(character, now)
        assertTrue(block.contains("每天早安吻"))
        assertTrue(block.contains("每个月看一次电影"))
        assertTrue(block.contains("每周写一封信"))
        assertTrue(block.contains("一直戴着手链"))
        assertFalse(block.contains("每晚说晚安"))
        assertFalse(block.contains("每年过纪念日"))
        // 注入顺序就是取舍顺序：b(9) → d(7,更新) → c(7) → e(5)。
        assertTrue(block.indexOf("每天早安吻") < block.indexOf("每个月看一次电影"))
        assertTrue(block.indexOf("每个月看一次电影") < block.indexOf("每周写一封信"))
        assertTrue(block.indexOf("每周写一封信") < block.indexOf("一直戴着手链"))
    }

    @Test
    fun `待办按期限升序且整段有字符上限`() {
        val character = Character(
            id = "c1",
            name = "小雨",
            todos = listOf(
                Todo(id = "t2", title = "交报告", detail = "下班前发邮件", dueAt = "2026-10-21T10:00:00Z"),
                Todo(id = "t1", title = "买牛奶", detail = "顺路", dueAt = "2026-10-13T10:00:00Z"),
                Todo(id = "t3", title = "已完成的", status = TodoStatus.Done, dueAt = "2026-10-01T10:00:00Z"),
            ),
            longTerm = listOf(
                mem(id = "m1", key = "每晚的约定", value = "晚安吻后说一句「鱼鱼爱你」", importance = 9),
            ),
        )
        val block = buildUnfinishedBlock(character, now)
        assertTrue(block.contains("- [待办] 买牛奶：顺路（"))
        assertTrue(block.contains("，已过期）"))
        assertTrue(block.indexOf("买牛奶") < block.indexOf("交报告"))
        assertFalse(block.contains("已完成的"))
        // 待办在前，周期性约定在后。
        assertTrue(block.indexOf("[待办]") < block.indexOf("[周期]"))
        assertTrue("block too long: ${block.length}", block.length <= UNFINISHED_BLOCK_CHARS)
    }

    @Test
    fun `同一条记忆不会同时以待办与周期身份出现`() {
        val character = Character(
            id = "c1",
            name = "小雨",
            todos = listOf(
                Todo(id = "t1", title = "每晚说晚安", detail = "", dueAt = "2026-10-14T22:00:00Z"),
            ),
            longTerm = listOf(
                mem(id = "m1", key = "每晚说晚安", value = "睡前一通电话", importance = 9),
            ),
        )
        val block = buildUnfinishedBlock(character, now)
        assertEquals(1, Regex("- \\[(待办|周期)] 每晚说晚安").findAll(block).count())
        assertTrue(block.contains("[待办]"))
        assertFalse(block.contains("[周期]"))
    }

    @Test
    fun `每行与整段的字符上限生效`() {
        val long = "啊".repeat(400)
        val character = Character(
            id = "c1",
            name = "小雨",
            longTerm = listOf(
                mem(id = "m1", key = "每晚背诵$long", value = long, importance = 9),
                mem(id = "m2", key = "每天默写$long", value = long, importance = 8),
                mem(id = "m3", key = "每周朗诵$long", value = long, importance = 7),
                mem(id = "m4", key = "每月抄写$long", value = long, importance = 6),
            ),
        )
        val block = buildUnfinishedBlock(character, now)
        assertTrue(block.isNotEmpty())
        block.lines().drop(1).forEach { line ->
            assertTrue("line too long: ${line.length}", line.length <= 100)
        }
        assertTrue("block too long: ${block.length}", block.length <= UNFINISHED_BLOCK_CHARS)
    }

    @Test
    fun `没有未完成事项时不产生任何空段`() = runBlocking {
        val character = Character(
            id = "c1",
            name = "小雨",
            longTerm = listOf(
                mem(id = "m1", key = "周末去看展", value = "说好一起去看展", importance = 10),
            ),
        )
        assertEquals("", buildUnfinishedBlock(character, now))
        val context = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        assertFalse(context.contains("【未完成事项】"))
        // 不凭空多出空行/标题：状态段之后紧接着就是下一个真实段落，中间只有固定分隔。
        assertTrue(context.contains("【记忆召回】"))
        assertFalse(context.contains("\n\n\n"))
    }
}
