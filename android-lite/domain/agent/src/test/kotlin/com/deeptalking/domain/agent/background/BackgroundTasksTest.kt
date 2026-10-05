package com.deeptalking.domain.agent.background

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.SceneState
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.StaticFillMeta
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmChunk
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private class ScriptedLlm(private val responses: MutableList<String>) : LlmBackend {
    override val id = "scripted"
    val requests = mutableListOf<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmResult {
        requests += request
        return LlmResult(text = responses.removeFirstOrNull() ?: "")
    }

    override fun stream(request: LlmRequest): Flow<LlmChunk> = flowOf()
}

class BackgroundTasksTest {

    private fun tasks(responses: List<String>): Pair<BackgroundTasks, ScriptedLlm> {
        val llm = ScriptedLlm(responses.toMutableList())
        return BackgroundTasks(llm, MemoryServiceImpl()) to llm
    }

    @Test
    fun consolidatesLorebookAndMarksScanned() = runBlocking {
        val (background, llm) = tasks(
            listOf("""{"entries":[{"name":"银月商会","keywords":["银月"],"content":"银月商会在西街，经营药材","sourceShortTermIds":["s1"]}]}"""),
        )
        val character = Character(
            id = "c1",
            shortTerm = listOf(
                ShortTermMemory(id = "s1", content = "银月商会在西街，经营药材", sourceMessageIds = listOf("m1"), analyzedAt = "done"),
            ),
        )
        val (status, updated) = background.consolidateLorebook(character)
        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        assertEquals(1, updated.lorebook.size)
        assertEquals("银月商会", updated.lorebook[0].name)
        assertEquals(1, llm.requests.size)
        assertTrue(updated.shortTerm[0].lorebookScannedAt != null)
    }

    @Test
    fun malformedLorebookOutputTouchesNothing() = runBlocking {
        val (background, _) = tasks(listOf("{}"))
        val character = Character(
            id = "c1",
            lorebook = emptyList(),
            shortTerm = listOf(ShortTermMemory(id = "s1", content = "已知地点", analyzedAt = "done")),
        )
        val (status, updated) = background.consolidateLorebook(character)
        assertEquals(BackgroundTasks.TaskStatus.Failure, status)
        assertTrue(updated.lorebook.isEmpty())
        assertTrue(updated.shortTerm[0].lorebookScannedAt == null)
    }

    @Test
    fun unknownSourceIdRejectsWholeBatch() = runBlocking {
        val (background, _) = tasks(
            listOf("""{"entries":[{"name":"X","keywords":["x"],"content":"内容","sourceShortTermIds":["ghost"]}]}"""),
        )
        val character = Character(
            id = "c1",
            shortTerm = listOf(ShortTermMemory(id = "s1", content = "真实内容", analyzedAt = "done")),
        )
        val (status, updated) = background.consolidateLorebook(character)
        assertEquals(BackgroundTasks.TaskStatus.Failure, status)
        assertTrue(updated.lorebook.isEmpty())
    }

    @Test
    fun sceneSummarizedWhenLocationChanges() = runBlocking {
        val (background, _) = tasks(listOf("用户和小雨在咖啡馆聊了很久，约好周末一起去看展。"))
        val messages = (1..6).map { index ->
            ChatMessage(
                id = "m$index",
                role = if (index % 2 == 0) Role.Assistant else Role.User,
                content = "对话内容$index",
            )
        }
        val character = Character(
            id = "c1",
            name = "小雨",
            instant = messages,
            dynamicState = DynamicState(currentLocation = "咖啡馆"),
            sceneState = SceneState(key = "图书馆", startMessageId = null, messageCount = 0),
        )
        val (status, updated) = background.checkScene(character)
        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        assertEquals(1, updated.scenes.size)
        assertEquals("咖啡馆", updated.sceneState?.key)
    }

    @Test
    fun sceneNotSummarizedWithTooFewMessages() = runBlocking {
        val (background, llm) = tasks(listOf("不应被调用"))
        val character = Character(
            id = "c1",
            instant = (1..3).map { ChatMessage(id = "m$it", role = Role.User, content = "短") },
            dynamicState = DynamicState(currentLocation = "咖啡馆"),
            sceneState = SceneState(key = "图书馆"),
        )
        val (status, updated) = background.checkScene(character)
        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        assertTrue(updated.scenes.isEmpty())
        assertEquals(0, llm.requests.size)
    }

    @Test
    fun autoFillFillsOnlyEmptyFields() = runBlocking {
        val (background, _) = tasks(listOf("""{"personality":"性格冷静克制。"}"""))
        val character = Character(id = "c1", name = "小雨")
        val updated = background.autoFillStaticFields(listOf(character))
        assertEquals("性格冷静克制。", updated[0].staticProfile.personality)
        assertEquals(0, updated[0].staticFillMeta?.failures)
    }

    @Test
    fun staticFillCooldownLogic() {
        val (background, _) = tasks(emptyList())
        assertTrue(background.canAttemptStaticFill(null))
        val now = Instant.now()
        assertFalse(
            background.canAttemptStaticFill(
                StaticFillMeta(retryAt = now.plusSeconds(600).toString()),
                nowMillis = now.toEpochMilli(),
            ),
        )
        assertFalse(
            background.canAttemptStaticFill(
                StaticFillMeta(attemptedAt = now.minusSeconds(60).toString(), failures = 0),
                nowMillis = now.toEpochMilli(),
            ),
        )
        assertTrue(
            background.canAttemptStaticFill(
                StaticFillMeta(attemptedAt = now.minusSeconds(600).toString(), failures = 1),
                nowMillis = now.toEpochMilli(),
            ),
        )
    }

    @Test
    fun migrateWorldLoreSeedsLorebookAndTrimsSource() = runBlocking {
        val (background, _) = tasks(
            listOf(
                """{"entries":[{"name":"赤月王国","keywords":["赤月"],"content":"位于大陆西侧，终年飘雪","alwaysActive":true}],"trimmedSource":"角色本人是一名旅者。"}""",
            ),
        )
        val character = Character(
            id = "c1",
            name = "旅者",
            staticProfile = StaticProfile(background = "赤月王国位于大陆西侧，终年飘雪。角色本人是一名旅者。"),
        )
        val updated = background.migrateWorldLore(character, trimSource = true)
        assertEquals(1, updated.lorebook.size)
        assertEquals("赤月王国", updated.lorebook[0].name)
        assertEquals("角色本人是一名旅者。", updated.staticProfile.background)
        assertTrue(updated.lorebookMigratedAt != null)
    }

    @Test
    fun remapFieldsAppliesDynamicStateAndBackground() = runBlocking {
        val (background, _) = tasks(listOf("""{"dynamicState":{"currentGoal":"想去看海"},"background":"喜欢旅行。"}"""))
        val character = Character(id = "c1", name = "旅者")
        val updated = background.remapFields(character)
        assertEquals("想去看海", updated.dynamicState.currentGoal)
        assertEquals("喜欢旅行。", updated.staticProfile.background)
        assertEquals("1.2.0", updated.fieldsMigrationVersion)
    }

    @Test
    fun sceneKeyFallsBackToUnspecified() {
        val (background, _) = tasks(emptyList())
        assertEquals("咖啡馆", background.sceneKey(Character(id = "c1", dynamicState = DynamicState(currentLocation = "咖啡馆"))))
        assertEquals("未说明", background.sceneKey(Character(id = "c1", dynamicState = DynamicState(currentLocation = "   "))))
    }
}
