package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.domain.agent.prompts.STANDING_COMMITMENT_BLOCK_CHARS
import com.deeptalking.domain.agent.prompts.STANDING_COMMITMENT_LIMIT
import com.deeptalking.domain.agent.prompts.STANDING_COMMITMENT_LINE_CHARS
import com.deeptalking.domain.agent.prompts.buildStandingCommitmentBlock
import com.deeptalking.domain.agent.prompts.buildVolatileContext
import com.deeptalking.domain.agent.prompts.selectStandingCommitments
import com.deeptalking.domain.memory.MemoryServiceImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 「每晚/每天」这类周期性约定本来只是一条普通长期记忆，只有被检索命中才进上下文，
 * 于是经常整晚都没被执行。现在每轮从长期记忆里用确定性判据挑出周期性/仪式类约定，
 * 只注入「一行摘要 +（若有）期限」，并有条数上限与字符上限；其余记忆仍按需检索。
 */
class StandingCommitmentPromptTest {

    private val memory = MemoryServiceImpl()

    /** 固定"现在"，否则【时间基准】里的秒会随时间跳到下一格。 */
    private val now = Instant.parse("2026-10-14T04:00:00Z")

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
    fun `周期性约定每轮注入一行摘要与期限`() = runBlocking {
        val character = Character(
            id = "c1",
            name = "小雨",
            longTerm = listOf(
                mem(
                    id = "m1",
                    key = "每晚的约定",
                    value = "晚安吻后说一次「鱼鱼爱你」，不许重样",
                    importance = 9,
                    dueAt = "2026-10-20T12:00:00Z",
                ),
            ),
        )
        val context = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        assertTrue(context.contains("【长期约定提醒"))
        assertTrue(context.contains("长期约定"))
        assertTrue(context.contains("不要在 reply 里复述"))
        assertTrue(context.contains("- 每晚的约定: 晚安吻后说一次「鱼鱼爱你」，不许重样"))

        // 期限按本地时间渲染（不写死时区，避免测试机时区不同而失败）。
        val expectedDue = ZonedDateTime.ofInstant(Instant.parse("2026-10-20T12:00:00Z"), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        assertTrue(context.contains("截止/约定时间：$expectedDue"))
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
        assertEquals("", buildStandingCommitmentBlock(character))
        val context = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        assertFalse(context.contains("长期约定提醒"))
    }

    @Test
    fun `超过条数上限时按重要性再按最近更新时间取舍`() = runBlocking {
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

        val block = buildStandingCommitmentBlock(character)
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
        val block = buildStandingCommitmentBlock(character)
        assertTrue(block.isNotEmpty())
        val lines = block.lines().drop(1)
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertTrue("line too long: ${line.length}", line.length <= STANDING_COMMITMENT_LINE_CHARS)
        }
        // 整段硬上限：4 条都取满时会被截掉末尾（取舍确定：先丢最不重要的）。
        assertTrue("block too long: ${block.length}", block.length <= STANDING_COMMITMENT_BLOCK_CHARS)
        assertTrue("block cap did not bind: ${lines.size} lines", lines.size < STANDING_COMMITMENT_LIMIT)
    }

    @Test
    fun `没有周期性约定时不产生任何空段`() = runBlocking {
        val character = Character(
            id = "c1",
            name = "小雨",
            longTerm = listOf(
                mem(id = "m1", key = "周末去看展", value = "说好一起去看展", importance = 10),
            ),
        )
        assertEquals("", buildStandingCommitmentBlock(character))
        val context = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        assertFalse(context.contains("【长期约定提醒"))
        // 不凭空多出空行/标题：状态段之后紧接着就是下一个真实的段落，
        // 中间只有 block() 固定的一个分隔，没有多出来的空段。
        assertTrue(context.contains("当前语气: (未设置)\n\n【本轮主动召回的相关长期记忆】"))
    }
}
