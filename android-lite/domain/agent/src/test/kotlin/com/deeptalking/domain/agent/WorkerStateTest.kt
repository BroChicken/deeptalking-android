package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.Role
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.domain.memory.knownSources
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmChunk
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * Turn-worker patches that used to be invisible: member `currentTone`, `todos` create with an
 * optional `detail`, and the notes a rejected entry now leaves behind. These are pure enough to
 * test without a `BackgroundTasks` instance.
 */
class WorkerStateTest {

    private val json = Json { isLenient = true }

    private class UnusedLlm : LlmBackend {
        override val id = "unused"
        override fun stream(request: LlmRequest): Flow<LlmChunk> = flow { error("unused") }
        override suspend fun complete(request: LlmRequest): LlmResult = error("unused")
    }

    private fun orchestrator() = ChatOrchestrator(
        llm = UnusedLlm(),
        tools = ToolRegistry(emptyList()),
        memory = MemoryServiceImpl(),
    )

    private fun groupCharacter() = Character(
        id = "g1",
        name = "三人行",
        isGroup = true,
        groupSharedDynamic = DynamicState(currentSituation = "客厅", currentLocation = "家里"),
        members = listOf(
            GroupMember(id = "gm1", name = "张三", dynamicState = DynamicState(currentTone = "平淡")),
            GroupMember(id = "gm2", name = "李四"),
        ),
    )

    private fun userTodoCharacter() = Character(
        id = "c1",
        name = "Alice",
        emoji = "🙂",
        instant = listOf(
            ChatMessage(
                id = "u1",
                role = Role.User,
                content = "2026-10-25 上午九点提醒我交报告",
                timestamp = "2026-10-24T20:00:00+08:00",
            ),
        ),
    )

    private fun assistantMessage() = ChatMessage(id = "a1", role = Role.Assistant, content = "好，记下了")

    // ------------------------------------------------------------ member state (item 3)

    @Test
    fun `member currentTone from the worker is applied and stamped`() {
        val character = groupCharacter()
        val elements = json.parseToJsonElement(
            """
            [{"memberName":"张三","dynamicState":{
                "currentTone":{"value":"压低声音、语速偏快"},
                "currentMood":{"value":"担心"}
            }}]
            """.trimIndent(),
        ).jsonArray

        val updated = orchestrator().applyMemberDynamicState(
            character = character,
            elements = elements,
            assistant = ChatMessage(id = "a1", role = Role.Assistant, content = "张三：早"),
            sources = knownSources(character),
            nowZoned = ZonedDateTime.now(),
            nowIso = "2026-10-21T10:00:00Z",
        )

        assertEquals("压低声音、语速偏快", updated.members[0].dynamicState.currentTone)
        assertEquals(
            "2026-10-21T10:00:00Z",
            updated.members[0].dynamicStateMeta["currentTone"]?.updatedAt,
        )
        // Facts still need evidence: the mood had none, so it is dropped while the
        // self-referential tone (the delivery instruction) goes through.
        assertEquals("", updated.members[0].dynamicState.currentMood)
        // Other members and the shared state are untouched.
        assertEquals("", updated.members[1].dynamicState.currentTone)
        assertEquals(
            DynamicState(currentSituation = "客厅", currentLocation = "家里"),
            updated.groupSharedDynamic,
        )
    }

    // ------------------------------------------------------------ todos create (item 6)

    @Test
    fun `todo create without detail keeps the entry with a blank detail`() {
        val character = userTodoCharacter()
        val obj = json.parseToJsonElement(
            """
            {"op":"create","title":"交报告","proposer":"user",
             "dueAt":"2026-10-25T09:00:00+08:00",
             "sourceMessageIds":["u1"],"evidence":"提醒我交报告"}
            """.trimIndent(),
        ).jsonObject

        val outcome = orchestrator().applyWorkerTodo(
            character = character,
            obj = obj,
            assistant = assistantMessage(),
            sources = knownSources(character),
            nowZoned = ZonedDateTime.now(),
        )

        assertEquals(1, outcome.character.todos.size)
        val todo = outcome.character.todos.single()
        assertEquals("交报告", todo.title)
        assertEquals("a missing detail must not drop the todo", "", todo.detail)
        assertTrue("a successful create has nothing to report", outcome.notes.isEmpty())
    }

    @Test
    fun `todo create rejected by the policy leaves a note instead of vanishing`() {
        val character = userTodoCharacter()
        // No due time: `TodoPolicy.validateTodoCreation` rejects it.
        val obj = json.parseToJsonElement(
            """
            {"op":"create","title":"交报告","proposer":"user",
             "sourceMessageIds":["u1"],"evidence":"提醒我交报告"}
            """.trimIndent(),
        ).jsonObject

        val outcome = orchestrator().applyWorkerTodo(
            character = character,
            obj = obj,
            assistant = assistantMessage(),
            sources = knownSources(character),
            nowZoned = ZonedDateTime.now(),
        )

        assertTrue(outcome.character.todos.isEmpty())
        val note = outcome.notes.single()
        assertTrue("the reason must carry the policy verdict", note.contains("未通过校验"))
        assertTrue("the title must be traceable", note.contains("交报告"))
    }

    @Test
    fun `todo create without a title is reported, never silently dropped`() {
        val character = userTodoCharacter()
        val obj = json.parseToJsonElement(
            """
            {"op":"create","proposer":"user","dueAt":"2026-10-25T09:00:00+08:00",
             "sourceMessageIds":["u1"],"evidence":"提醒我交报告"}
            """.trimIndent(),
        ).jsonObject

        val outcome = orchestrator().applyWorkerTodo(
            character = character,
            obj = obj,
            assistant = assistantMessage(),
            sources = knownSources(character),
            nowZoned = ZonedDateTime.now(),
        )

        assertTrue(outcome.character.todos.isEmpty())
        assertTrue(outcome.notes.single().contains("缺少 title"))
    }

    @Test
    fun `todo create text requires a title but not a detail`() {
        assertNull(workerTodoText("   ", "细节"))
        assertEquals("", workerTodoText("标题", "  ")?.detail)
        assertEquals("细节", workerTodoText("标题", "细节")?.detail)
        assertEquals("标题", workerTodoText("  标题  ", "")?.title)
    }
}
