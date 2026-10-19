package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.model.Sticker
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.ToolRegistry
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MemoryToolsTest {

    private val memory = com.deeptalking.domain.memory.MemoryServiceImpl()

    private fun context(character: Character) = AgentContext(character = character, activeCharacterId = character.id)

    private fun call(name: String, args: String) = ToolCall(id = "call-1", name = name, arguments = args)

    private fun mem(
        id: String,
        category: MemoryCategory = MemoryCategory.UserProfile,
        subject: MemorySubject = MemorySubject.User,
        key: String = "标识",
        value: String = "内容",
        importance: Int = 7,
        updatedAt: String = Instant.now().toString(),
        createdAt: String = Instant.now().toString(),
    ) = LongTermMemory(
        id = id,
        category = category,
        subject = subject,
        key = key,
        value = value,
        importance = importance,
        updatedAt = updatedAt,
        createdAt = createdAt,
    )

    private fun userTurn(id: String, content: String) =
        ChatMessage(id = id, role = Role.User, content = content)

    @Test
    fun `delete_memory reports not found instead of ok`() = runBlocking {
        val character = Character(id = "c1", longTerm = listOf(mem("m1")))
        val result = DeleteMemoryTool(memory).execute(call("delete_memory", """{"id":"nope"}"""), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("未找到该记忆ID"))
    }

    @Test
    fun `delete_memory removes the target and returns the updated character`() = runBlocking {
        val character = Character(id = "c1", longTerm = listOf(mem("m1")))
        val result = DeleteMemoryTool(memory).execute(call("delete_memory", """{"id":"m1"}"""), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertEquals(0, result.updatedCharacter!!.longTerm.size)
    }

    @Test
    fun `update_memory resolves timeRef against eventTime and preserves dueAt for non-promises`() = runBlocking {
        val target = mem("m1", key = "名字", value = "用户叫小明")
        val character = Character(
            id = "c1",
            instant = listOf(userTurn("m1", "我叫阿哲")),
            longTerm = listOf(target),
        )
        val args = """
            {"id":"m1","value":"明天见","timeRef":{"explicit":"2026-08-10T09:00:00"},
             "sourceMessageIds":["m1"],"evidence":"我叫阿哲"}
        """.trimIndent()
        val result = UpdateMemoryTool(memory).execute(call("update_memory", args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
        val updated = result.updatedCharacter!!.longTerm.first { it.id == "m1" }
        assertEquals("2026-08-11见", updated.value)
        assertEquals(null, updated.dueAt)
        assertNotNull(updated.updatedAt)
    }

    @Test
    fun `update_memory records the previous value text as the correction before`() = runBlocking {
        val character = Character(
            id = "c1",
            instant = listOf(userTurn("m1", "我叫阿哲")),
            longTerm = listOf(mem("m1", key = "名字", value = "用户叫小明")),
        )
        val args = """{"id":"m1","value":"用户叫阿哲","sourceMessageIds":["m1"],"evidence":"我叫阿哲"}"""
        val result = UpdateMemoryTool(memory).execute(call("update_memory", args), context(character))

        assertTrue(result.contentJson.contains("\"ok\":true"))
        val correction = result.updatedCharacter!!.longTerm.first { it.id == "m1" }.corrections.single()
        // `before` is the old *text*, not a JSON blob.
        assertEquals("用户叫小明", correction.before)
        assertEquals("我叫阿哲", correction.evidence)
        assertTrue("不能再写整条 JSON blob", !correction.before!!.contains("{"))
    }

    @Test
    fun `update_memory keeps only the last three corrections`() = runBlocking {
        var current = Character(
            id = "c1",
            instant = listOf(userTurn("m1", "我叫阿哲")),
            longTerm = listOf(mem("m1", key = "名字", value = "用户叫小明")),
        )
        val tool = UpdateMemoryTool(memory)
        val args = """{"id":"m1","value":"用户叫阿哲","sourceMessageIds":["m1"],"evidence":"我叫阿哲"}"""
        repeat(4) {
            current = tool.execute(call("update_memory", args), context(current)).updatedCharacter!!
            assertTrue(current.longTerm.first { it.id == "m1" }.corrections.size <= 3)
        }
        val corrections = current.longTerm.first { it.id == "m1" }.corrections
        assertEquals(3, corrections.size)
        // The oldest correction (the original 用户叫小明) fell off; the last three kept the
        // value that was current when each of them was written.
        assertEquals("用户叫阿哲", corrections.first().before)
    }

    @Test
    fun `update_memory rejects evidence that is not verbatim in the user message`() = runBlocking {
        val character = Character(
            id = "c1",
            instant = listOf(userTurn("m1", "我叫阿哲")),
            longTerm = listOf(mem("m1")),
        )
        val args = """{"id":"m1","value":"明天见","sourceMessageIds":["m1"],"evidence":"我叫小明"}"""
        val result = UpdateMemoryTool(memory).execute(call("update_memory", args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
    }

    @Test
    fun `update_memory corrects a short term entry and requeues it`() = runBlocking {
        val character = Character(
            id = "c1",
            instant = listOf(userTurn("u1", "不是那样，是你记错了")),
            shortTerm = listOf(
                ShortTermMemory(
                    id = "s1",
                    content = "用户2026-08-01上午把咖啡洒在角色身上",
                    sourceMessageIds = listOf("u0"),
                    eventTime = "2026-08-01T10:00:00Z",
                ),
                ShortTermMemory(
                    id = "s2",
                    content = "用户2026-08-01中午的另一件事",
                    sourceMessageIds = listOf("u0"),
                    eventTime = "2026-08-01T12:00:00Z",
                ),
            ),
        )
        val args = """{"id":"s1","value":"用户2026-08-01上午把水洒在角色身上","sourceMessageIds":["u1"],"evidence":"是你记错了"}"""
        val result = UpdateMemoryTool(memory).execute(call("update_memory", args), context(character))

        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertTrue(result.contentJson.contains("shortTerm"))
        val updated = result.updatedCharacter!!
        assertEquals(2, updated.shortTerm.size)
        // Moved to the tail so the fold pass (which walks the window from the front)
        // re-reads the corrected wording instead of keeping the old long-term copy.
        assertEquals("s2", updated.shortTerm.first().id)
        val corrected = updated.shortTerm.last()
        assertEquals("s1", corrected.id)
        assertTrue(corrected.content.contains("水"))
        assertTrue("a correction invalidates both consumers", corrected.revision > 1)
    }

    @Test
    fun `update_memory still requires the user's verbatim evidence for short term`() = runBlocking {
        val character = Character(
            id = "c1",
            instant = listOf(userTurn("u1", "不是那样，是你记错了")),
            shortTerm = listOf(ShortTermMemory(id = "s1", content = "用户洒了咖啡")),
        )
        val args = """{"id":"s1","value":"用户洒了水","sourceMessageIds":["u1"],"evidence":"我从没说过这句"}"""
        val result = UpdateMemoryTool(memory).execute(call("update_memory", args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
    }

    @Test
    fun `delete_memory removes a short term entry`() = runBlocking {
        val character = Character(
            id = "c1",
            shortTerm = listOf(ShortTermMemory(id = "s1", content = "用户被记错的偏好")),
        )
        val result = DeleteMemoryTool(memory).execute(call("delete_memory", """{"id":"s1"}"""), context(character))
        assertTrue(result.contentJson.contains("\"store\":\"shortTerm\""))
        assertEquals(0, result.updatedCharacter!!.shortTerm.size)
    }

    @Test
    fun `search_memory keyword recall also returns short term hits`() = runBlocking {
        val character = Character(
            id = "c1",
            shortTerm = listOf(
                ShortTermMemory(
                    id = "s1",
                    content = "用户2026-08-01下午提到下周要去看牙医",
                    sourceMessageIds = listOf("u1"),
                ),
            ),
        )
        val result = SearchMemoryTool(memory).execute(call("search_memory", """{"query":"牙医"}"""), context(character))
        assertTrue(result.contentJson.contains("\"shortTerm\""))
        assertTrue(result.contentJson.contains("\"s1\""))
    }

    @Test
    fun `update_character_field rejects static fields on groups`() = runBlocking {
        val group = Character(id = "g1", isGroup = true)
        val args = """{"field":"gender","value":"女","sourceMessageIds":["m1"],"evidence":"改成女"}"""
        val result = UpdateCharacterFieldTool().execute(call("update_character_field", args), context(group))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("群组只有公用字段可改"))
    }

    @Test
    fun `update_character_field cleans meta prefixes and parentheticals`() = runBlocking {
        val character = Character(
            id = "c1",
            instant = listOf(userTurn("m1", "你性格改成更冷静些")),
        )
        val args = """
            {"field":"personality","value":"性格：冷静（用户要求）",
             "sourceMessageIds":["m1"],"evidence":"你性格改成更冷静些"}
        """.trimIndent()
        val result = UpdateCharacterFieldTool().execute(call("update_character_field", args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertEquals("冷静", result.updatedCharacter!!.staticProfile.personality)
    }

    @Test
    fun `update_character_field keeps the plain reason for a non-manual field`() = runBlocking {
        val character = Character(
            id = "c1",
            instant = listOf(userTurn("m1", "今天我心情很好")),
            staticProfile = StaticProfile(personality = "冷静"),
        )
        val args = """{"field":"personality","value":"更外向","sourceMessageIds":["m1"],"evidence":"今天我心情很好"}"""
        val result = UpdateCharacterFieldTool().execute(call("update_character_field", args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("用户并未明确要求修改"))
    }

    @Test
    fun `search_memory bumps usage and honours the relevance filter`() = runBlocking {
        val character = Character(
            id = "c1",
            longTerm = listOf(mem("m1", key = "喜好", value = "用户喜欢喝咖啡")),
        )
        val tool = SearchMemoryTool(memory)
        val result = tool.execute(call("search_memory", """{"query":"咖啡"}"""), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertTrue(result.contentJson.contains("\"total\":1"))
        assertTrue(result.contentJson.contains("\"eventTime\""))
        val updated = result.updatedCharacter!!.longTerm.first()
        assertEquals(1, updated.usageCount)
        assertNotNull(updated.lastUsageAt)
    }

    @Test
    fun `upsert_lorebook_entry validates real sources and writes the entry`() = runBlocking {
        val character = Character(
            id = "c1",
            shortTerm = listOf(ShortTermMemory(id = "s1", content = "潮汐镇终年多雾，是海边小城")),
        )
        val args = """
            {"name":"潮汐镇","content":"潮汐镇终年多雾。","keywords":["潮汐镇"],
             "sourceMessageIds":["s1"],"evidence":"潮汐镇终年多雾"}
        """.trimIndent()
        val result = UpsertLorebookEntryTool().execute(call("upsert_lorebook_entry", args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertEquals(1, result.updatedCharacter!!.lorebook.size)

        val badArgs = """
            {"name":"潮汐镇","content":"潮汐镇终年多雾。","keywords":["潮汐镇"],
             "sourceMessageIds":["missing"],"evidence":"潮汐镇终年多雾"}
        """.trimIndent()
        val rejected = UpsertLorebookEntryTool().execute(call("upsert_lorebook_entry", badArgs), context(character))
        assertTrue(rejected.contentJson.contains("\"ok\":false"))
    }

    @Test
    fun `upsert_lorebook_entry rejects over-long content and falls back to the name as keyword`() = runBlocking {
        val character = Character(
            id = "c1",
            shortTerm = listOf(ShortTermMemory(id = "s1", content = "潮汐镇终年多雾，是海边小城")),
        )
        // 450 characters: the tool asks the model to shorten instead of storing a cut entry.
        val longArgs = """
            {"name":"潮汐镇","content":"${"多雾。".repeat(150)}",
             "sourceMessageIds":["s1"],"evidence":"潮汐镇终年多雾"}
        """.trimIndent()
        val rejected = UpsertLorebookEntryTool().execute(call("upsert_lorebook_entry", longArgs), context(character))
        assertTrue(rejected.contentJson.contains("\"ok\":false"))

        val noKeywords = """
            {"name":"潮汐镇","content":"潮汐镇终年多雾。",
             "sourceMessageIds":["s1"],"evidence":"潮汐镇终年多雾"}
        """.trimIndent()
        val created = UpsertLorebookEntryTool().execute(call("upsert_lorebook_entry", noKeywords), context(character))
        assertTrue(created.contentJson.contains("\"ok\":true"))
        assertEquals(listOf("潮汐镇"), created.updatedCharacter!!.lorebook.first().keywords)
    }

    @Test
    fun `send_sticker schema exposes the character tag enum through the registry`() {
        val character = Character(
            id = "c1",
            stickers = listOf(
                Sticker(id = "s1", tag = "开心", fileRef = "f1"),
                Sticker(id = "s2", tag = "难过", fileRef = "f2"),
                Sticker(id = "s3", tag = "开心", fileRef = "f3"),
            ),
        )
        val registry = ToolRegistry(listOf(SendStickerTool()))
        val definition = registry.definitions(context(character)).single { it.name == "send_sticker" }
        assertTrue(definition.parametersJson.contains("\"开心\""))
        assertTrue(definition.parametersJson.contains("\"难过\""))
        // Duplicate tags are collapsed to a single enum value.
        assertTrue(Regex("\"开心\"").findAll(definition.parametersJson).count() == 1)
        assertTrue(definition.description.contains("开心"))
    }

    @Test
    fun `search_memory recalls a day's raw messages by date`() = runBlocking {
        val character = Character(
            id = "c1",
            instant = listOf(
                ChatMessage(id = "u1", role = Role.User, content = "九号晚上一起看流星雨", timestamp = "2026-10-09T12:00:00Z"),
                ChatMessage(id = "u2", role = Role.Assistant, content = "嗯，我记得那一夜。", timestamp = "2026-10-09T12:05:00Z"),
                ChatMessage(id = "u3", role = Role.User, content = "十号之后就没提过了", timestamp = "2026-10-20T12:00:00Z"),
            ),
            shortTerm = listOf(
                ShortTermMemory(id = "s1", content = "2026-10-09 深夜一起看流星雨", eventTime = "2026-10-09T12:00:00Z"),
                ShortTermMemory(id = "s2", content = "2026-10-20 深夜聊了别的", eventTime = "2026-10-20T12:00:00Z"),
            ),
            longTerm = listOf(
                mem("m1", key = "流星雨", value = "2026-10-09 深夜两人一起看流星雨", createdAt = "2026-10-09T12:00:00Z"),
                mem("m2", key = "闲聊", value = "2026-10-20 深夜聊了别的", createdAt = "2026-10-20T12:00:00Z"),
            ),
        )
        val result = SearchMemoryTool(memory).execute(
            call("search_memory", """{"date":"2026-10-09"}"""),
            context(character),
        )
        val json = result.contentJson
        assertTrue(json.contains("\"mode\":\"date\""))
        assertTrue(json.contains("九号晚上一起看流星雨"))
        assertTrue(json.contains("流星雨"))
        // Another day must not leak into the result.
        assertTrue(!json.contains("十号之后就没提过了"))
        assertTrue(!json.contains("聊了别的"))
    }
}
