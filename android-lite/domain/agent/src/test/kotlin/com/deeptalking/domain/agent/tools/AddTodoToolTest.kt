package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.TodoProposer
import com.deeptalking.core.model.TodoStatus
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 待办新建闸门：明确期限 + 双方同意，缺一不记；且只写 `todos`，绝不碰长期记忆。 */
class AddTodoToolTest {

    private fun context(character: Character) = AgentContext(character = character, activeCharacterId = character.id)

    private fun call(args: String) = ToolCall(id = "call-1", name = "add_todo", arguments = args)

    private fun user(id: String, content: String, timestamp: String? = null) =
        ChatMessage(id = id, role = Role.User, content = content, timestamp = timestamp)

    private fun assistant(id: String, content: String, timestamp: String? = null) =
        ChatMessage(id = id, role = Role.Assistant, content = content, timestamp = timestamp)

    @Test
    fun `user-proposed todo with a stated date is added and never touches long-term memory`() = runBlocking {
        val character = Character(id = "c1", instant = listOf(user("m1", "我2026-08-10上午要交给老师报告")))
        val args = """
            {"title":"交报告","detail":"2026-08-10 上午给老师交报告","proposer":"user",
             "sourceMessageIds":["m1"],"evidence":"我2026-08-10上午要交给老师报告",
             "dueAt":"2026-08-10T09:00:00"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
        val updated = result.updatedCharacter!!
        assertEquals(1, updated.todos.size)
        val todo = updated.todos.single()
        assertEquals("交报告", todo.title)
        assertEquals(TodoStatus.Active, todo.status)
        assertEquals(TodoProposer.User, todo.proposer)
        assertTrue(todo.dueAt.startsWith("2026-08-10"))
        assertTrue("待办不得写进长期记忆", updated.longTerm.isEmpty())
    }

    @Test
    fun `rejects evidence the user did not say`() = runBlocking {
        val character = Character(id = "c1", instant = listOf(user("m1", "我明天要交报告")))
        val args = """
            {"title":"交报告","detail":"交报告","proposer":"user",
             "sourceMessageIds":["m1"],"evidence":"我要交报告","dueAt":"2026-08-10T09:00:00"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertNull(result.updatedCharacter)
    }

    @Test
    fun `rejects a todo without a concrete date`() = runBlocking {
        val character = Character(id = "c1", instant = listOf(user("m1", "记得帮我买药")))
        val args = """
            {"title":"买药","detail":"帮用户买药","proposer":"user",
             "sourceMessageIds":["m1"],"evidence":"记得帮我买药"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("期限"))
        assertNull(result.updatedCharacter)
    }

    @Test
    fun `rejects a due time the user never mentioned`() = runBlocking {
        val character = Character(id = "c1", instant = listOf(user("m1", "记得帮我买药")))
        val args = """
            {"title":"买药","detail":"帮用户买药","proposer":"user",
             "sourceMessageIds":["m1"],"evidence":"记得帮我买药","dueAt":"2026-08-10T09:00:00"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("期限"))
    }

    @Test
    fun `rejects a due time that contradicts what the user said`() = runBlocking {
        val character = Character(id = "c1", instant = listOf(user("m1", "我2026-08-10要交报告")))
        val args = """
            {"title":"交报告","detail":"交报告","proposer":"user",
             "sourceMessageIds":["m1"],"evidence":"我2026-08-10要交报告","dueAt":"2026-08-12T09:00:00"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
    }

    @Test
    fun `character-proposed todo needs the user's agreeing words`() = runBlocking {
        val character = Character(id = "c1", instant = listOf(user("m2", "好呀，那就2026-08-10下午三点见")))
        val args = """
            {"title":"看电影","detail":"2026-08-10 下午三点去看电影","proposer":"character",
             "sourceMessageIds":["m2"],"evidence":"好呀，那就2026-08-10下午三点见",
             "dueAt":"2026-08-10T15:00:00"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertEquals(TodoProposer.Character, result.updatedCharacter!!.todos.single().proposer)
    }

    @Test
    fun `a clear refusal is vetoed by the keyword stage`() = runBlocking {
        val character = Character(id = "c1", instant = listOf(user("m2", "不用了，我不想去")))
        val args = """
            {"title":"看电影","detail":"2026-08-10 下午三点去看电影","proposer":"character",
             "sourceMessageIds":["m2"],"evidence":"不用了，我不想去","dueAt":"2026-08-10T15:00:00"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertFalse(result.contentJson.contains("\"ok\":true"))
    }

    @Test
    fun `an agreement the keyword list cannot cover is still accepted on the model's judgment`() = runBlocking {
        // “那就听你的吧”不含任何同意关键词——同意与否由模型判断，关键词只拦明确拒绝。
        val character = Character(id = "c1", instant = listOf(user("m2", "那就听你的吧，2026-08-10下午三点")))
        val args = """
            {"title":"看电影","detail":"2026-08-10 下午三点去看电影","proposer":"character",
             "sourceMessageIds":["m2"],"evidence":"那就听你的吧，2026-08-10下午三点",
             "dueAt":"2026-08-10T15:00:00"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
    }

    @Test
    fun `character-proposed todo is rejected when only the character spoke`() = runBlocking {
        val character = Character(id = "c1", instant = listOf(assistant("a1", "我们2026-08-10下午三点去看电影吧")))
        val args = """
            {"title":"看电影","detail":"2026-08-10 下午三点去看电影","proposer":"character",
             "sourceMessageIds":["current_response"],"evidence":"我们2026-08-10下午三点去看电影吧",
             "dueAt":"2026-08-10T15:00:00"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":false"))
    }

    @Test
    fun `the character's own earlier reply can anchor the date`() = runBlocking {
        val character = Character(
            id = "c1",
            instant = listOf(
                assistant("a1", "我们明天下午三点去看电影吧", timestamp = "2026-08-09T20:00:00"),
                user("m2", "好呀", timestamp = "2026-08-09T20:01:00"),
            ),
        )
        val args = """
            {"title":"看电影","detail":"明天下午三点去看电影","proposer":"character",
             "sourceMessageIds":["m2"],"evidence":"好呀","dueAt":"2026-08-10T15:00:00"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertNotNull(result.updatedCharacter!!.todos.single().dueAt)
    }

    @Test
    fun `an existing active todo with the same title is updated in place`() = runBlocking {
        val existing = com.deeptalking.core.model.Todo(
            id = "t1",
            title = "交报告",
            detail = "旧的",
            dueAt = "2026-08-09T09:00:00.000Z",
            proposer = TodoProposer.User,
        )
        val character = Character(id = "c1", instant = listOf(user("m1", "我2026-08-10要交报告")), todos = listOf(existing))
        val args = """
            {"title":"交报告","detail":"2026-08-10 交报告","proposer":"user",
             "sourceMessageIds":["m1"],"evidence":"我2026-08-10要交报告","dueAt":"2026-08-10T09:00:00"}
        """.trimIndent()
        val result = AddTodoTool().execute(call(args), context(character))
        assertTrue(result.contentJson.contains("\"ok\":true"))
        val todos = result.updatedCharacter!!.todos
        assertEquals(1, todos.size)
        assertEquals("t1", todos.single().id)
        assertEquals("2026-08-10 交报告", todos.single().detail)
    }
}
