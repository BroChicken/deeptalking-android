package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.Sticker
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.ToolRegistry
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `set_reminder validates verbatim evidence and returns the memory id`() = runBlocking {
        val character = Character(
            id = "c1",
            instant = listOf(userTurn("m1", "我明天要交报告")),
        )
        val args = """
            {"key":"交报告","value":"明天交报告","sourceMessageIds":["m1"],
             "evidence":"我明天要交报告","timeRef":{"explicit":"2026-08-10T09:00:00"}}
        """.trimIndent()
        val result = SetReminderTool(memory).execute(call("set_reminder", args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertTrue(result.contentJson.contains("\"id\""))
        val promise = result.updatedCharacter!!.longTerm.first { it.category == MemoryCategory.Promises }
        assertEquals("2026-08-11交报告", promise.value)
        assertTrue(promise.dueAt!!.startsWith("2026-08-10T01:00:00"))
    }

    @Test
    fun `set_reminder rejects evidence the user did not say`() = runBlocking {
        val character = Character(
            id = "c1",
            instant = listOf(userTurn("m1", "我明天要交报告")),
        )
        val args = """{"key":"交报告","value":"交报告","sourceMessageIds":["m1"],"evidence":"我要交报告"}"""
        val result = SetReminderTool(memory).execute(call("set_reminder", args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
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
}
