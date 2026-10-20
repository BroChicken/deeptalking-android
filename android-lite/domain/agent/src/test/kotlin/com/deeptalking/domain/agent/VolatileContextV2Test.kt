package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.SceneSummary
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.model.Todo
import com.deeptalking.domain.agent.prompts.VOLATILE_HARD_CHARS
import com.deeptalking.domain.agent.prompts.buildVolatileContext
import com.deeptalking.domain.agent.prompts.buildVolatileContextForTurn
import com.deeptalking.domain.memory.MemoryServiceImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Volatile 上下文的 V2 结构测试（docs/OUTBOUND_CALLS_V2.md §一(二)4 +
 * docs/REPLY_PIPELINE_PLAN.md §11）：固定顺序、空段不出现、总长硬上限、召回条数与
 * 句子边界截断、pendingRecall 按实际渲染的 id 消费、同地点场景仍注入。
 */
class VolatileContextV2Test {

    private val memory = MemoryServiceImpl()
    private val now: Instant = Instant.parse("2026-10-14T04:00:00Z")

    private fun recall(id: String, key: String, value: String, importance: Int = 5) = LongTermMemory(
        id = id,
        category = MemoryCategory.Events,
        key = key,
        value = value,
        importance = importance,
    )

    /** One volatile section, up to the next section separator. */
    private fun section(text: String, header: String): String =
        text.substringAfter(header, "").substringBefore("\n\n【")

    private fun fullCharacter(): Character = Character(
        id = "c1",
        name = "小雨",
        staticProfile = StaticProfile(
            speakingStyle = "慵懒黏人，句尾常带“啦”；示例：好困哦 / 你回来啦",
            userAddress = "明明",
        ),
        dynamicState = DynamicState(
            currentSituation = "2026-10-14 夜里在等对方回家",
            currentLocation = "咖啡馆",
            currentMood = "有点困",
            currentOccupation = "自由插画师",
            currentGoal = "等对方回来",
            currentTone = "慵懒低语",
        ),
        todos = listOf(Todo(id = "t1", title = "交报告", detail = "发邮件", dueAt = "2026-10-20T10:00:00Z")),
        longTerm = listOf(
            LongTermMemory(
                id = "m1",
                category = MemoryCategory.Promises,
                key = "每晚的约定",
                value = "睡前说一句「鱼鱼爱你」",
                importance = 9,
            ),
        ),
        lorebook = listOf(
            LorebookEntry(
                id = "lore_1",
                name = "银月商会",
                keywords = emptyList(),
                content = "银月商会在西街。",
                alwaysActive = true,
            ),
        ),
        scenes = listOf(SceneSummary(id = "s1", key = "咖啡馆", content = "你们在咖啡馆坐到打烊。")),
        instant = listOf(
            ChatMessage(id = "msg_1", role = Role.User, content = "我回来了", timestamp = "2026-10-14T03:50:00Z"),
            ChatMessage(id = "msg_2", role = Role.Assistant, content = "你回来啦。", timestamp = "2026-10-14T03:52:00Z"),
            ChatMessage(id = "msg_3", role = Role.User, content = "好无聊", timestamp = "2026-10-14T03:59:00Z"),
        ),
        pendingRecall = listOf(recall("r1", "对方喜欢美式", "对方 2026-10-01 说过喜欢美式")),
    )

    @Test
    fun `十一段按固定顺序出现且每段只出现一次`() = runBlocking {
        val text = buildVolatileContext(
            character = fullCharacter(),
            query = "好无聊",
            memory = memory,
            now = now,
            proactiveTurn = true,
        )
        val headers = listOf(
            "【时间】",
            "【当前状态】",
            "【未完成事项】",
            "【世界书】",
            "【场景概要】",
            "【记忆召回】",
            "【本轮写法】",
            "【可选话题】",
            "【主动开场】",
            "【消息时间元数据，仅供内部推理】",
        )
        val positions = headers.map { header ->
            val at = text.indexOf(header)
            assertTrue("缺少段落：$header", at >= 0)
            assertEquals("段落重复：$header", 1, Regex(Regex.escape(header)).findAll(text).count())
            at
        }
        positions.zipWithNext().forEach { (left, right) ->
            assertTrue("段落顺序错误：$left -> $right", left < right)
        }
        // 时间行格式。
        assertTrue(Regex("【时间】\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}（.*；逻辑日 \\d{4}-\\d{2}-\\d{2}）").containsMatchIn(text))
        // 当前状态不写 currentTone。
        assertFalse(text.contains("慵懒低语"))
        assertTrue(text.contains("处境: 2026-10-14 夜里在等对方回家"))
    }

    @Test
    fun `空段整段不出现`() = runBlocking {
        val text = buildVolatileContext(character = Character(id = "c1", name = "小雨"), query = "", memory = memory, now = now)
        listOf("【当前状态】", "【未完成事项】", "【世界书】", "【场景概要】", "【成员私人记忆】", "【可选话题】", "【主动开场】", "【热榜】")
            .forEach { header -> assertFalse("空段不应出现：$header", text.contains(header)) }
        assertTrue(text.contains("【时间】"))
        assertTrue(text.contains("【记忆召回】\n无相关长期记忆"))
        assertFalse(text.contains("(未设置)"))
        assertFalse(text.contains("\n\n\n"))
    }

    @Test
    fun `召回最多三条且只消费实际渲染的 id`() = runBlocking {
        val pending = (1..5).map { recall("p$it", "记忆$it", "内容$it") }
        val character = Character(id = "c1", name = "小雨", pendingRecall = pending)
        val result = buildVolatileContextForTurn(character = character, query = "在吗", memory = memory, now = now)
        assertEquals(listOf("p1", "p2", "p3"), result.recallIds)
        assertTrue(result.text.contains("记忆1"))
        assertTrue(result.text.contains("记忆3"))
        assertFalse(result.text.contains("记忆4"))
        assertEquals(3, Regex("\\[[^]]+\\]\\[[^]]+] 记忆").findAll(result.text).count())
    }

    @Test
    fun `单条超长记忆按句子边界截断`() = runBlocking {
        val sentence = "这是一句很长的话。"
        val long = sentence.repeat(300) // 2700 chars
        val character = Character(
            id = "c1",
            name = "小雨",
            pendingRecall = listOf(recall("p1", "超长记忆", long)),
        )
        val text = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        val line = text.lines().first { it.contains("超长记忆") }
        assertTrue("应截断并补省略号：$line", line.endsWith("…"))
        val before = line.removeSuffix("…")
        assertTrue("截断点不在句子边界：${before.takeLast(8)}", before.last() in "。！？；")
        val rendered = line.substringAfter("超长记忆: ")
        assertTrue("单条超过 1200：${rendered.length}", rendered.length <= 1200)
    }

    @Test
    fun `同地点场景概要仍然注入`() = runBlocking {
        val character = Character(
            id = "c1",
            name = "小雨",
            dynamicState = DynamicState(currentLocation = "咖啡馆"),
            scenes = listOf(SceneSummary(id = "s1", key = "咖啡馆", content = "你们在咖啡馆坐到打烊。")),
        )
        val text = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        assertTrue(text.contains("【场景概要】\n你们在咖啡馆坐到打烊。"))
    }

    @Test
    fun `场景概要只取最近一条且不超过三百字`() = runBlocking {
        val character = Character(
            id = "c1",
            name = "小雨",
            scenes = listOf(
                SceneSummary(id = "s1", key = "a", content = "旧场景"),
                SceneSummary(id = "s2", key = "b", content = "新场景" + "啊".repeat(500)),
            ),
        )
        val text = buildVolatileContext(character = character, query = "在吗", memory = memory, now = now)
        assertFalse(text.contains("旧场景"))
        val sceneLine = text.lines().first { it.startsWith("新场景") }
        assertTrue("场景概要过长：${sceneLine.length}", sceneLine.length <= 301)
    }

    @Test
    fun `整段不超过硬上限`() = runBlocking {
        val character = fullCharacter().copy(
            pendingRecall = (1..3).map { recall("p$it", "记忆$it", "内容".repeat(1200)) },
            lorebook = (1..6).map { index ->
                LorebookEntry(
                    id = "lore_$index",
                    name = "条目$index",
                    content = "设定".repeat(400),
                    alwaysActive = true,
                )
            },
            todos = (1..4).map {
                Todo(id = "t$it", title = "待办$it", detail = "详情".repeat(40), dueAt = "2026-10-2${it}T10:00:00Z")
            },
        )
        val text = buildVolatileContext(character = character, query = "好无聊", memory = memory, now = now)
        assertTrue("volatile 过长：${text.length}", text.length <= VOLATILE_HARD_CHARS)
    }

    @Test
    fun `群组只注入被点名成员的私人记忆`() = runBlocking {
        fun member(id: String, name: String, memoryKey: String) = GroupMember(
            id = id,
            name = name,
            staticProfile = StaticProfile(userAddress = "明明"),
            dynamicState = DynamicState(currentMood = "有点困"),
            pendingRecall = listOf(recall("pm_$id", memoryKey, "$name 的私事")),
        )
        val group = Character(
            id = "g1",
            name = "合租小屋",
            isGroup = true,
            members = listOf(member("m_a", "小美", "喜欢樱花"), member("m_b", "小刚", "怕黑")),
        )
        val text = buildVolatileContext(character = group, query = "小美你在吗", memory = memory, now = now)
        val privateSection = section(text, "【成员私人记忆】")
        assertTrue(privateSection.startsWith("\n[小美] "))
        assertFalse(privateSection.contains("[小刚]"))
        // 群组状态按成员分行。
        assertTrue(text.contains("成员·小美: "))
    }

    @Test
    fun `群组没有点名时用上一轮发言的成员`() = runBlocking {
        fun member(id: String, name: String, memoryKey: String) = GroupMember(
            id = id,
            name = name,
            staticProfile = StaticProfile(userAddress = "明明"),
            pendingRecall = listOf(recall("pm_$id", memoryKey, "$name 的私事")),
        )
        val group = Character(
            id = "g1",
            name = "合租小屋",
            isGroup = true,
            members = listOf(member("m_a", "小美", "喜欢樱花"), member("m_b", "小刚", "怕黑")),
            instant = listOf(
                ChatMessage(id = "msg_1", role = Role.Assistant, content = "小刚：我先去睡了。", timestamp = "2026-10-14T03:50:00Z"),
                ChatMessage(id = "msg_2", role = Role.User, content = "晚安", timestamp = "2026-10-14T03:59:00Z"),
            ),
        )
        val text = buildVolatileContext(character = group, query = "晚安", memory = memory, now = now)
        val privateSection = section(text, "【成员私人记忆】")
        assertTrue(privateSection.contains("[小刚]"))
        assertFalse(privateSection.contains("[小美]"))
    }

    @Test
    fun `本轮写法的口吻含风格摘要示例与称呼`() = runBlocking {
        val text = buildVolatileContext(character = fullCharacter(), query = "在吗", memory = memory, now = now)
        assertTrue(text.contains("【本轮写法】"))
        assertTrue(text.contains("口吻: 慵懒黏人，句尾常带“啦”"))
        assertTrue(text.contains("示例: 好困哦 / 你回来啦"))
        assertTrue(text.contains("称呼: 明明"))
        assertTrue(text.contains("骨架: "))
        assertFalse(text.contains("【语气回顾】"))
        assertFalse(text.contains("【结尾多样性】"))
    }
}
