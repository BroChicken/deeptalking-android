package com.deeptalking.domain.agent.background

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.SceneState
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.SourceEvidence
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
                sequence = index,
            )
        }
        val character = Character(
            id = "c1",
            name = "小雨",
            instant = messages,
            dynamicState = DynamicState(currentLocation = "咖啡馆"),
            sceneState = SceneState(key = "图书馆", startCount = 0, startSequence = 0, messageCount = 0),
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
    fun migrateWorldLoreWithExistingEntriesSerializes() = runBlocking {
        // Regression: a non-empty existing lorebook used to hit a missing
        // @Serializable serializer for LorebookProposal and throw, which made
        // every migration report 0 characters migrated and never stamp
        // lorebookMigratedAt (so the prompt reappeared forever).
        val (background, _) = tasks(
            listOf("""{"entries":[{"name":"赤月王国","keywords":["赤月"],"content":"位于大陆西侧"}],"trimmedSource":"旅者。"}"""),
        )
        val character = Character(
            id = "c1",
            name = "旅者",
            staticProfile = StaticProfile(background = "赤月王国位于大陆西侧。旅者四处游历。"),
            lorebook = listOf(
                com.deeptalking.core.model.LorebookEntry(id = "l1", name = "旧条目", content = "旧内容", keywords = listOf("旧")),
            ),
        )
        val updated = background.migrateWorldLore(character, trimSource = false)
        assertTrue(updated.lorebook.any { it.name == "赤月王国" })
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

    @Test
    fun memoryRetryBacksOffThenResets() {
        var counters = scheduleMemoryRetry(MemoryTaskCounters(), MemoryTaskKind.Extraction, nowMillis = 0L)
        assertEquals(1, counters.extractionFailures)
        assertFalse(canRunMemoryTask(counters, MemoryTaskKind.Extraction, nowMillis = 0L))
        assertFalse(canRunMemoryTask(counters, MemoryTaskKind.Extraction, nowMillis = 5 * 60_000L - 1))
        assertTrue(canRunMemoryTask(counters, MemoryTaskKind.Extraction, nowMillis = 5 * 60_000L + 1))
        counters = resetMemoryRetry(counters, MemoryTaskKind.Extraction)
        assertEquals(0, counters.extractionFailures)
        assertTrue(canRunMemoryTask(counters, MemoryTaskKind.Extraction, nowMillis = 0L))
    }

    private fun analyzableCharacter(): Character {
        // The source message has already been evicted from `instant`; analysis must
        // resolve it from the short-term item's captured userEvidence.
        val target = ShortTermMemory(
            id = "s1",
            content = "用户喜欢喝美式咖啡。",
            sourceMessageIds = listOf("u1"),
            eventTime = "2026-08-01T12:00:00Z",
            sourceRoles = listOf("user"),
            userEvidence = listOf(SourceEvidence(sourceMessageId = "u1", text = "用户喜欢喝美式咖啡。")),
        )
        val filler = (2..21).map { index ->
            ShortTermMemory(
                id = "s$index",
                content = "占位$index",
                sourceMessageIds = listOf("u1"),
                eventTime = "2026-08-01T12:00:00Z",
                analyzedAt = "done",
            )
        }
        return Character(id = "c1", shortTerm = listOf(target) + filler)
    }

    @Test
    fun analyzeWritesValidatedLongTermAndMarksAnalyzed() = runBlocking {
        val response = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[{"category":"userProfile","subject":"user","key":"饮品","value":"用户喜欢喝美式咖啡。","sourceShortTermIds":["s1"],"sourceMessageIds":["u1"],"evidence":"用户喜欢喝美式咖啡。"}]}"""
        val (background, _) = tasks(listOf(response))
        val updated = background.analyzeShortToLongTerm(analyzableCharacter())
        assertEquals(1, updated.longTerm.size)
        assertTrue(updated.shortTerm.first { it.id == "s1" }.analyzedAt != null)
    }

    @Test
    fun analyzeRollsBackWhenSourceIsInvalid() = runBlocking {
        val response = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[{"category":"userProfile","subject":"user","key":"饮品","value":"用户喜欢喝美式咖啡。","sourceShortTermIds":["s1"],"sourceMessageIds":["ghost"],"evidence":"用户喜欢喝美式咖啡。"}]}"""
        val (background, _) = tasks(listOf(response))
        val character = analyzableCharacter()
        val updated = background.analyzeShortToLongTerm(character)
        assertTrue(updated.longTerm.isEmpty())
        assertEquals(null, updated.shortTerm.first { it.id == "s1" }.analyzedAt)
    }

    @Test
    fun analyzeRejectsEvidenceNotPresentInUserEvidence() = runBlocking {
        val response = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[{"category":"userProfile","subject":"user","key":"饮品","value":"用户喜欢喝茶。","sourceShortTermIds":["s1"],"sourceMessageIds":["u1"],"evidence":"用户喜欢喝茶。"}]}"""
        val (background, _) = tasks(listOf(response))
        val target = ShortTermMemory(
            id = "s1",
            content = "用户喜欢喝美式咖啡。",
            sourceMessageIds = listOf("u1"),
            eventTime = "2026-08-01T12:00:00Z",
            sourceRoles = listOf("user"),
            userEvidence = listOf(SourceEvidence(sourceMessageId = "u1", text = "用户喜欢喝美式咖啡。")),
        )
        val filler = (2..21).map { index ->
            ShortTermMemory(
                id = "s$index",
                content = "占位$index",
                sourceMessageIds = listOf("u1"),
                eventTime = "2026-08-01T12:00:00Z",
                analyzedAt = "done",
            )
        }
        // `instant` still resolves evidence "用户喜欢喝茶。", but no claimed short-term
        // item carries it in userEvidence, so the analysis must be rejected.
        val character = Character(
            id = "c1",
            instant = listOf(ChatMessage(id = "u1", role = Role.User, content = "用户喜欢喝茶。")),
            shortTerm = listOf(target) + filler,
        )
        val updated = background.analyzeShortToLongTerm(character)
        assertTrue(updated.longTerm.isEmpty())
        assertEquals(null, updated.shortTerm.first { it.id == "s1" }.analyzedAt)
    }

    @Test
    fun parseMemoryFromTextAppliesMemUpdate() {
        val (background, _) = tasks(emptyList())
        val character = Character(
            id = "c1",
            instant = listOf(ChatMessage(id = "u1", role = Role.User, content = "用户喜欢咖啡")),
        )
        val reply = """<MEM_UPDATE>{"shortTerm":[{"content":"用户喜欢咖啡","sourceMessageIds":["u1"]}]}</MEM_UPDATE>"""
        val updated = background.parseMemoryFromText(character, reply, null)
        assertTrue(updated != null)
        assertEquals(1, updated!!.shortTerm.size)
        assertEquals(null, background.parseMemoryFromText(character, "无标签", null))
    }

    @Test
    fun initialMemoryMigrationIsNoOpForSmallWindow() = runBlocking {
        val (background, _) = tasks(emptyList())
        val character = Character(
            id = "c1",
            instant = listOf(ChatMessage(id = "u1", role = Role.User, content = "你好")),
        )
        assertEquals(character, background.runInitialMemoryMigration(character))
    }

    @Test
    fun staleAnalysisIsDiscarded() = runBlocking {
        val response = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[]}"""
        val llm = ScriptedLlm(mutableListOf(response))
        val background = BackgroundTasks(
            llm,
            MemoryServiceImpl(),
            liveCharacter = { Character(id = "c1", revision = 99) },
        )
        val character = analyzableCharacter()
        val (status, updated) = background.analyzeShortToLongTermStatus(character)
        assertEquals(BackgroundTasks.TaskStatus.Stale, status)
        assertEquals(character, updated)
    }

    @Test
    fun currentAnalysisIsApplied() = runBlocking {
        val response = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[]}"""
        val llm = ScriptedLlm(mutableListOf(response))
        val character = analyzableCharacter()
        val background = BackgroundTasks(llm, MemoryServiceImpl(), liveCharacter = { character })
        val (status, updated) = background.analyzeShortToLongTermStatus(character)
        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        assertTrue(updated.shortTerm.first { it.id == "s1" }.analyzedAt != null)
    }

    @Test
    fun autoFillCleansFieldValues() = runBlocking {
        val (background, _) = tasks(listOf("""{"language":"语言：普通话。用户希望我这样做。"}"""))
        val updated = background.autoFillStaticFields(listOf(Character(id = "c1", name = "小雨")))
        assertEquals("普通话", updated[0].staticProfile.language)
    }

    @Test
    fun remapFieldsWritesGroupDescription() = runBlocking {
        val (background, _) = tasks(
            listOf("""{"dynamicState":{"currentGoal":"举办一场宴会"},"description":"这群人是同门师兄弟，因师门任务聚在一起。"}"""),
        )
        val group = Character(id = "g1", name = "师门", isGroup = true)
        val updated = background.remapFields(group)
        assertEquals("这群人是同门师兄弟，因师门任务聚在一起。", updated.description)
    }

    @Test
    fun analyzeEmitsStatusAndUsage() = runBlocking {
        val statuses = mutableListOf<String>()
        val usages = mutableListOf<Pair<String, com.deeptalking.engine.ondevice.TokenUsage>>()
        val response = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[]}"""
        val llm = object : LlmBackend {
            override val id = "usage"
            override suspend fun complete(request: LlmRequest): LlmResult =
                LlmResult(text = response, usage = com.deeptalking.engine.ondevice.TokenUsage(3, 5, 1))
            override fun stream(request: LlmRequest): Flow<LlmChunk> = flowOf()
        }
        val background = BackgroundTasks(
            llm,
            MemoryServiceImpl(),
            onStatus = { statuses += it },
            onAuxiliaryUsage = { task, usage -> usages += task to usage },
        )
        background.analyzeShortToLongTerm(analyzableCharacter())
        assertTrue(statuses.contains("正在分析长期记忆…"))
        assertEquals("analysis", usages.first().first)
        assertEquals(3, usages.first().second.inputTokens)
    }

    @Test
    fun consolidateMemoryMergesDuplicateGroup() = runBlocking {
        val (background, _) = tasks(
            listOf(
                """{"status":"ok","groups":[{"keepId":"a1","mergeIds":["a2"],"key":"用户喜欢喝美式咖啡","value":"用户喜欢喝美式咖啡，每天早上一杯。","importance":6,"tags":["咖啡"]}]}""",
            ),
        )
        val now = Instant.now().toString()
        val entries = (1..AppLimits.Memory.CONSOLIDATE_TRIGGER).map { index ->
            LongTermMemory(
                id = "a$index",
                category = MemoryCategory.UserProfile,
                subject = MemorySubject.User,
                key = "键$index",
                value = "值$index",
                createdAt = now,
            )
        }
        val (status, updated) = background.consolidateMemory(Character(id = "c1", longTerm = entries))
        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        assertEquals(AppLimits.Memory.CONSOLIDATE_TRIGGER - 1, updated.longTerm.size)
        assertTrue(updated.longTerm.none { it.id == "a2" })
        assertEquals("用户喜欢喝美式咖啡", updated.longTerm.first { it.id == "a1" }.key)
    }

    @Test
    fun consolidateMemoryMalformedOutputTouchesNothing() = runBlocking {
        val (background, _) = tasks(listOf("{}"))
        val now = Instant.now().toString()
        val entries = (1..AppLimits.Memory.CONSOLIDATE_TRIGGER).map { index ->
            LongTermMemory(id = "a$index", category = MemoryCategory.UserProfile, key = "键$index", value = "值$index", createdAt = now)
        }
        val character = Character(id = "c1", longTerm = entries)
        val (status, updated) = background.consolidateMemory(character)
        assertEquals(BackgroundTasks.TaskStatus.Failure, status)
        assertEquals(entries.size, updated.longTerm.size)
    }
}
