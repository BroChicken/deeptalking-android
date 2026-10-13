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
    fun consolidatesLorebookFromTheAnalyzedBatch() = runBlocking {
        val (background, llm) = tasks(
            listOf("""{"entries":[{"name":"银月商会","keywords":["银月"],"content":"银月商会在西街，经营药材","sourceShortTermIds":["s1"]}]}"""),
        )
        val character = Character(
            id = "c1",
            shortTerm = listOf(
                ShortTermMemory(id = "s1", content = "银月商会在西街，经营药材", sourceMessageIds = listOf("m1")),
            ),
        )
        // The world book reads the batch the analysis pass just consumed.
        val (status, updated) = background.consolidateLorebook(character, character.shortTerm)
        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        assertEquals(1, updated.lorebook.size)
        assertEquals("银月商会", updated.lorebook[0].name)
        assertEquals(1, llm.requests.size)
        assertEquals(1, updated.counters.lorebookScannedCount)
    }

    @Test
    fun lorebookPassDoesNothingWithoutABatch() = runBlocking {
        val (background, llm) = tasks(emptyList())
        val character = Character(id = "c1", shortTerm = listOf(ShortTermMemory(id = "s1", content = "已消费")))
        val (status, updated) = background.consolidateLorebook(character, emptyList())
        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        assertEquals(character, updated)
        assertEquals(0, llm.requests.size)
    }

    @Test
    fun overLongLorebookEntryIsSkippedInsteadOfFailingTheBatch() = runBlocking {
        // 400+ characters: the cap is a writing rule, so the entry is dropped (never cut)
        // and the rest of the batch still lands — rejecting everything here is what used to
        // stop the world book from being updated at all.
        val failures = mutableListOf<Pair<String, String>>()
        val llm = ScriptedLlm(
            mutableListOf(
                """{"entries":[""" +
                    """{"name":"银月商会","keywords":["银月"],"content":"${"多雾的旧设定".repeat(70)}","sourceShortTermIds":["s1"]},""" +
                    """{"name":"西街","keywords":["西街"],"content":"西街有一家药材铺","sourceShortTermIds":["s1"]}]}""",
            ),
        )
        val background = BackgroundTasks(llm, MemoryServiceImpl(), onAuxiliaryFailure = { task, reason -> failures += task to reason })
        val character = Character(
            id = "c1",
            shortTerm = listOf(ShortTermMemory(id = "s1", content = "银月商会在西街", sourceMessageIds = listOf("m1"))),
        )
        val (status, updated) = background.consolidateLorebook(character, character.shortTerm)
        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        assertEquals(listOf("西街"), updated.lorebook.map { it.name })
        assertEquals(1, failures.size)
        assertEquals("lorebook", failures[0].first)
        assertTrue(failures[0].second.contains("skipped"))
    }

    @Test
    fun malformedLorebookOutputTouchesNothing() = runBlocking {
        val (background, _) = tasks(listOf("{}"))
        val character = Character(
            id = "c1",
            lorebook = emptyList(),
            shortTerm = listOf(ShortTermMemory(id = "s1", content = "已知地点", sourceMessageIds = listOf("m1"))),
        )
        val (status, updated) = background.consolidateLorebook(character, character.shortTerm)
        assertEquals(BackgroundTasks.TaskStatus.Failure, status)
        assertTrue(updated.lorebook.isEmpty())
    }

    @Test
    fun unknownSourceIdIsSkipped() = runBlocking {
        val (background, _) = tasks(
            listOf("""{"entries":[{"name":"X","keywords":["x"],"content":"内容","sourceShortTermIds":["ghost"]}]}"""),
        )
        val character = Character(
            id = "c1",
            shortTerm = listOf(ShortTermMemory(id = "s1", content = "真实内容")),
        )
        val (status, updated) = background.consolidateLorebook(character, character.shortTerm)
        assertEquals(BackgroundTasks.TaskStatus.Success, status)
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
        // resolve it from the short-term item's captured userEvidence. The 20 fillers are
        // the low-water window: they ride along as context and are never consumed.
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
            )
        }
        return Character(id = "c1", shortTerm = listOf(target) + filler)
    }

    @Test
    fun analyzeWritesValidatedLongTermAndConsumesTheBatch() = runBlocking {
        val response = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[{"category":"userProfile","subject":"user","key":"饮品","value":"用户喜欢喝美式咖啡。","sourceShortTermIds":["s1"],"sourceMessageIds":["u1"],"evidence":"用户喜欢喝美式咖啡。"}]}"""
        val (background, _) = tasks(listOf(response))
        val updated = background.analyzeShortToLongTerm(analyzableCharacter())
        assertEquals(1, updated.longTerm.size)
        // The batch is consumed by position: the analyzed entry leaves the window.
        assertTrue(updated.shortTerm.none { it.id == "s1" })
        assertEquals(20, updated.shortTerm.size)
    }

    @Test
    fun analyzeReanchorsAHallucinatedSourceIdOnTheCapturedUserEvidence() = runBlocking {
        // The model invented a message id. Ids are no longer a gate: the evidence is
        // matched against the user lines the cited short-term entries carry, and the
        // item is re-anchored on that exact line before the validated write.
        val response = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[{"category":"userProfile","subject":"user","key":"饮品","value":"用户喜欢喝美式咖啡。","sourceShortTermIds":["s1"],"sourceMessageIds":["ghost"],"evidence":"用户喜欢喝美式咖啡。"}]}"""
        val (background, _) = tasks(listOf(response))
        val character = analyzableCharacter()
        val updated = background.analyzeShortToLongTerm(character)
        assertEquals(1, updated.longTerm.size)
        assertEquals(listOf("u1"), updated.longTerm.single().sourceMessageIds)
        assertTrue(updated.shortTerm.none { it.id == "s1" })
    }

    @Test
    fun analyzeSkipsEntryWhoseEvidenceIsNotInUserEvidence() = runBlocking {
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
            )
        }
        // `instant` still resolves evidence "用户喜欢喝茶。", but no claimed short-term
        // item carries it in userEvidence, so this entry is dropped (the batch is
        // still consumed rather than retried forever).
        val character = Character(
            id = "c1",
            instant = listOf(ChatMessage(id = "u1", role = Role.User, content = "用户喜欢喝茶。")),
            shortTerm = listOf(target) + filler,
        )
        val updated = background.analyzeShortToLongTerm(character)
        assertTrue(updated.longTerm.isEmpty())
        assertTrue(updated.shortTerm.none { it.id == "s1" })
    }

    @Test
    fun parseMemoryFromTextAppliesMemUpdate() {
        val (background, _) = tasks(emptyList())
        val character = Character(
            id = "c1",
            instant = listOf(ChatMessage(id = "u1", role = Role.User, content = "用户喜欢咖啡")),
        )
        val reply = """<MEM_UPDATE>{"shortTerm":[{"content":"用户喜欢咖啡","sourceMessageIds":["u1"]}],""" +
            """"longTerm":[{"category":"userProfile","subject":"user","key":"用户饮品偏好","value":"用户喜欢咖啡","sourceMessageIds":["u1"],"evidence":"用户喜欢咖啡"}]}</MEM_UPDATE>"""
        val updated = background.parseMemoryFromText(character, reply, null)
        assertTrue(updated != null)
        // Only the long-term part of a legacy block folds now: the short-term window is
        // owned by the whole-window extraction pass.
        assertTrue(updated!!.longTerm.any { it.value.contains("咖啡") })
        assertTrue("legacy block must not write short-term", updated.shortTerm.isEmpty())
        assertEquals(null, background.parseMemoryFromText(character, "无标签", null))
    }

    @Test
    fun initialMemoryMigrationIsNoOpForAnOrdinaryWindow() = runBlocking {
        val (background, _) = tasks(emptyList())
        val character = Character(
            id = "c1",
            instant = listOf(ChatMessage(id = "u1", role = Role.User, content = "你好")),
        )
        val (status, migrated) = background.runInitialMemoryMigration(character)
        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        assertEquals(character, migrated)
    }

    @Test
    fun oversizedWindowIsCaughtUpInBatchesOldestFirst() = runBlocking {
        // Three distinct batches (distinct event times so the entries do not merge).
        val (background, llm) = tasks(
            listOf(
                """[{"content":"2026-08-01 下午在旧书店找书。","eventTime":"2026-08-01T15:00:00Z","participants":["用户"],"location":"旧书店"}]""",
                """[{"content":"2026-08-02 下午在河边散步。","eventTime":"2026-08-02T15:00:00Z","participants":["用户"],"location":"河边"}]""",
                """[{"content":"2026-08-03 下午在咖啡馆等人。","eventTime":"2026-08-03T15:00:00Z","participants":["用户"],"location":"咖啡馆"}]""",
            ),
        )
        val backlog = AppLimits.Memory.INSTANT_MIGRATION_THRESHOLD + 80
        val character = Character(
            id = "c1",
            instant = (0 until backlog).map { index ->
                ChatMessage(
                    id = "m$index",
                    role = Role.User,
                    content = "消息$index",
                    timestamp = "2026-08-01T15:00:00Z",
                )
            },
        )
        assertTrue(background.hasOversizedWindow(character))

        val (status, updated) = background.migrateOversizedWindow(character)

        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        // Back inside the normal window range, folded oldest-first.
        assertTrue(updated.instant.size <= AppLimits.Memory.INSTANT)
        assertTrue(updated.instant.none { it.id == "m0" })
        // One pass per batch, each producing an entry — nothing was dropped un-folded.
        assertEquals(3, llm.requests.size)
        assertEquals(3, updated.shortTerm.size)
    }

    @Test
    fun oversizedLorebookEntryIsRewrittenIntoConciseEntries() = runBlocking {
        val blob = "银月商会在西街，经营药材，" + "很长的旧内容".repeat(80)
        val (background, llm) = tasks(
            listOf(
                """{"entries":[""" +
                    """{"name":"银月商会","keywords":["银月","商会"],"content":"银月商会在西街，经营药材。","alwaysActive":false},""" +
                    """{"name":"商会位置","keywords":["西街"],"content":"商会位于西街。","alwaysActive":false}]}""",
            ),
        )
        val user = com.deeptalking.core.model.LorebookEntry(
            id = "u1",
            name = "用户条目",
            content = blob,
            origin = com.deeptalking.core.model.LorebookOrigin.User,
        )
        val character = Character(
            id = "c1",
            lorebook = listOf(
                com.deeptalking.core.model.LorebookEntry(
                    id = "l1",
                    name = "银月商会",
                    content = blob,
                    keywords = listOf("银月"),
                    origin = com.deeptalking.core.model.LorebookOrigin.Model,
                ),
                user,
            ),
        )
        assertEquals(1, background.oversizedLorebookCount(character))

        val (status, updated) = background.rebuildOversizedLorebook(character)

        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        assertEquals(1, llm.requests.size)
        // The original id stays on the first rewritten entry (injection/mention history).
        assertEquals(3, updated.lorebook.size)
        assertEquals("l1", updated.lorebook.first().id)
        // User-written entries are never touched, so only the AI ones must be within the cap.
        assertTrue(
            updated.lorebook
                .filter { it.origin == com.deeptalking.core.model.LorebookOrigin.Model }
                .all { it.content.length <= AppLimits.Lorebook.CONTENT_CHARS },
        )
        assertEquals(blob, updated.lorebook.last().content)
        assertEquals(user, updated.lorebook.last())
    }

    @Test
    fun overLongLorebookRewriteKeepsTheOriginalEntry() = runBlocking {
        val blob = "银月商会在西街，经营药材，" + "很长的旧内容".repeat(80)
        val (background, _) = tasks(
            // 480 characters: over the cap, so the rewrite is dropped rather than cut.
            listOf(
                """{"entries":[{"name":"银月商会","keywords":["银月"],""" +
                    """"content":"${"很长的新内容".repeat(80)}","alwaysActive":false}]}""",
            ),
        )
        val entry = com.deeptalking.core.model.LorebookEntry(
            id = "l1",
            name = "银月商会",
            content = blob,
            keywords = listOf("银月"),
            origin = com.deeptalking.core.model.LorebookOrigin.Model,
        )
        val (status, updated) = background.rebuildOversizedLorebook(Character(id = "c1", lorebook = listOf(entry)))
        assertEquals(BackgroundTasks.TaskStatus.Failure, status)
        assertEquals(listOf(entry), updated.lorebook)
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
        val outcome = background.analyzeShortToLongTermStatus(character)
        assertEquals(BackgroundTasks.TaskStatus.Stale, outcome.status)
        assertEquals(character, outcome.character)
    }

    @Test
    fun currentAnalysisIsApplied() = runBlocking {
        val response = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[]}"""
        val llm = ScriptedLlm(mutableListOf(response))
        val character = analyzableCharacter()
        val background = BackgroundTasks(llm, MemoryServiceImpl(), liveCharacter = { character })
        val outcome = background.analyzeShortToLongTermStatus(character)
        assertEquals(BackgroundTasks.TaskStatus.Success, outcome.status)
        assertTrue(outcome.character.shortTerm.none { it.id == "s1" })
        assertEquals(listOf("s1"), outcome.consumed.map { it.id })
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

    // ---- analysis robustness (the pass that used to fail on every single run) ----

    private fun filler(from: Int, to: Int) = (from..to).map { index ->
        ShortTermMemory(
            id = "f$index",
            content = "占位$index",
            sourceMessageIds = listOf("u1"),
            eventTime = "2026-08-01T12:00:00Z",
        )
    }

    @Test
    fun analysisWithNothingToConsumeSucceedsWithoutFailures() = runBlocking {
        // A window at (or below) its low-water mark has no work — that is a success, not a
        // failed pass. Reporting "no pending items" as a failure is what used to burn the
        // retry gate and show "整理失败" for a window that was simply fine.
        val (background, llm) = tasks(emptyList())
        val character = Character(id = "c1", shortTerm = filler(1, 20))
        val outcome = background.analyzeShortToLongTermStatus(character)
        assertEquals(BackgroundTasks.TaskStatus.Success, outcome.status)
        assertEquals(character, outcome.character)
        assertTrue(outcome.consumed.isEmpty())
        assertEquals(0, llm.requests.size)
    }

    @Test
    fun analysisConsumesTheOldestBatchAndKeepsTheLowWaterContext() = runBlocking {
        // The shape a legacy pile-up leaves behind: 76 entries, the oldest ones already
        // turned into long-term memory by an older build. The batch is the oldest
        // `ANALYSIS_BATCH` entries; the newest 20 ride along as context and stay.
        val items = (1..76).map { index ->
            ShortTermMemory(
                id = "s$index",
                content = "事件$index",
                sourceMessageIds = listOf("u1"),
                eventTime = "2026-08-01T12:00:00Z",
            )
        }
        val acked = items.take(40).joinToString(",", "[", "]") { "\"" + it.id + "\"" }
        val (background, llm) = tasks(listOf("""{"status":"ok","analyzedShortTermIds":$acked,"longTerm":[]}"""))
        val outcome = background.analyzeShortToLongTermStatus(Character(id = "c1", shortTerm = items))

        assertEquals(BackgroundTasks.TaskStatus.Success, outcome.status)
        assertEquals(40, outcome.consumed.size)
        assertEquals("s1", outcome.consumed.first().id)
        assertEquals(36, outcome.character.shortTerm.size)
        assertEquals("s41", outcome.character.shortTerm.first().id)
        val prompt = llm.requests.single().input.last().content
        assertTrue(prompt.contains("\"analyzed\":false"))
        assertTrue(prompt.contains("\"analyzed\":true"))
        assertTrue(prompt.contains("\"s76\""))
    }

    @Test
    fun aCorrectionDuringThePassKeepsTheEntryForTheNextPass() = runBlocking {
        // `update_memory` rewrote the entry while the request was in flight (its revision
        // moved). Dropping it would silently discard the correction.
        val response = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[]}"""
        val llm = ScriptedLlm(mutableListOf(response))
        val character = analyzableCharacter()
        val live = character.copy(
            shortTerm = character.shortTerm.map {
                if (it.id == "s1") it.copy(content = "改过的事实", revision = 2) else it
            },
        )
        val background = BackgroundTasks(llm, MemoryServiceImpl(), liveCharacter = { live })
        val outcome = background.analyzeShortToLongTermStatus(character)
        assertEquals(BackgroundTasks.TaskStatus.Success, outcome.status)
        assertTrue(outcome.character.shortTerm.any { it.id == "s1" })
        assertTrue(outcome.consumed.isEmpty())
    }

    @Test
    fun analyzeAcceptsAPartialAcknowledgement() = runBlocking {
        // The model echoed only one of the two ids it was given: the acknowledged item
        // is consumed and the other stays for a later pass. Requiring an exact set made
        // every imperfect reply fail the pass, so nothing was ever consumed.
        val (background, _) = tasks(listOf("""{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[]}"""))
        val character = Character(
            id = "c1",
            shortTerm = listOf(
                ShortTermMemory(id = "s1", content = "事件一", sourceMessageIds = listOf("u1"), eventTime = "2026-08-01T12:00:00Z"),
                ShortTermMemory(id = "s2", content = "事件二", sourceMessageIds = listOf("u1"), eventTime = "2026-08-01T13:00:00Z"),
            ) + filler(3, 22),
        )
        val outcome = background.analyzeShortToLongTermStatus(character)
        assertEquals(BackgroundTasks.TaskStatus.Success, outcome.status)
        assertTrue(outcome.character.shortTerm.none { it.id == "s1" })
        assertTrue(outcome.character.shortTerm.any { it.id == "s2" })
    }

    @Test
    fun analyzeIgnoresAcknowledgedIdsOutsideTheBatch() = runBlocking {
        val (background, _) = tasks(
            listOf("""{"status":"ok","analyzedShortTermIds":["s1","ghost"],"longTerm":[]}"""),
        )
        val outcome = background.analyzeShortToLongTermStatus(analyzableCharacter())
        assertEquals(BackgroundTasks.TaskStatus.Success, outcome.status)
        assertTrue(outcome.character.shortTerm.none { it.id == "s1" })
    }

    @Test
    fun analyzeSalvagesATruncatedReply() = runBlocking {
        // Cut off mid-element: the complete element and the echoed ids still land.
        val element = """{"category":"userProfile","subject":"user","key":"饮品",""" +
            """"value":"用户喜欢喝美式咖啡。","sourceShortTermIds":["s1"],"sourceMessageIds":["u1"],""" +
            """"evidence":"用户喜欢喝美式咖啡。"}"""
        val truncated = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[$element,""" +
            """{"category":"events","subject":"legacy","key":"未完"""
        val (background, _) = tasks(listOf(truncated))
        val outcome = background.analyzeShortToLongTermStatus(analyzableCharacter())
        assertEquals(BackgroundTasks.TaskStatus.Success, outcome.status)
        assertEquals(1, outcome.character.longTerm.size)
        assertTrue(outcome.character.shortTerm.none { it.id == "s1" })
    }

    @Test
    fun analyzeSendsOneWholeWindowRequestAndKeepsTheFailure() = runBlocking {
        // The pass is a single request (no batch ladder): a failed reply leaves the window
        // untouched and is retried by the caller's backoff, never by shrinking the request
        // — the batch is the unit, so a partial batch would break continuity.
        val good = """{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[]}"""
        val (background, llm) = tasks(listOf("抱歉，我无法完成。", good))
        val outcome = background.analyzeShortToLongTermStatus(analyzableCharacter())
        assertEquals(BackgroundTasks.TaskStatus.Failure, outcome.status)
        assertEquals(1, llm.requests.size)
        assertTrue(outcome.character.shortTerm.any { it.id == "s1" })
    }

    @Test
    fun analysisFailureIsRecordedInCountersAndTheWindowIsUntouched() = runBlocking {
        val (background, _) = tasks(listOf("抱歉，我无法完成。"))
        val updated = background.analyzeShortToLongTerm(analyzableCharacter())
        assertTrue(updated.counters.analysisLastError.contains("unparsable"))
        assertTrue(updated.shortTerm.any { it.id == "s1" })
    }

    @Test
    fun analysisSuccessClearsTheRecordedFailure() = runBlocking {
        val (background, _) = tasks(listOf("""{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[]}"""))
        val character = analyzableCharacter()
        val failed = character.copy(counters = character.counters.copy(analysisLastError = "unparsable | x"))
        val updated = background.analyzeShortToLongTerm(failed)
        assertEquals("", updated.counters.analysisLastError)
    }

    @Test
    fun aFailedAnalysisStillLetsTheWorldBookRun() = runBlocking {
        // The cleanup path used to skip the world book whenever the analysis failed, so one
        // broken analysis silently stopped the world book from ever being updated.
        val (background, _) = tasks(listOf("抱歉，我无法完成。"))
        val character = Character(
            id = "c1",
            shortTerm = filler(1, 20) + ShortTermMemory(
                id = "s1",
                content = "银月商会在西街",
                sourceMessageIds = listOf("u1"),
                eventTime = "2026-08-01T12:00:00Z",
            ),
        )
        val outcome = background.drainMemoryBacklog(character)
        assertFalse(outcome.progressed)
        // The scene step of the maintenance ran (the scene state was normalized), which is
        // what proves the maintenance is no longer short-circuited.
        assertTrue(outcome.character.sceneState != null)
    }

    @Test
    fun drainReportsNoProgressButKeepsTheDiagnostic() = runBlocking {
        val (background, _) = tasks(listOf("抱歉，我无法完成。"))
        val outcome = background.drainMemoryBacklog(analyzableCharacter())
        assertFalse(outcome.progressed)
        assertTrue(outcome.character.counters.analysisLastError.isNotBlank())
    }

    @Test
    fun extractionFoldsTheWholeWindowAndDropsToTheLowWatermark() = runBlocking {
        val (background, llm) = tasks(
            listOf(
                """[{"content":"用户2026-08-01下午在家看书","eventTime":"2026-08-01T15:00:00Z",""" +
                    """"participants":["用户"],"location":"家","sourceMessageIds":["m2"]}]""",
            ),
        )
        // The window is full (high watermark): the whole window is the fold unit, and the
        // fold drops it back to the low watermark.
        val instant = buildList {
            repeat(AppLimits.Memory.INSTANT - 2) { i ->
                add(
                    ChatMessage(
                        id = "old$i",
                        role = Role.User,
                        content = "旧消息$i",
                        timestamp = "2026-08-01T10:00:00Z",
                    ),
                )
            }
            add(ChatMessage(id = "m2", role = Role.User, content = "我今天在家看书", timestamp = "2026-08-01T15:00:00Z"))
            add(ChatMessage(id = "m3", role = Role.Assistant, content = "嗯，挺好的", timestamp = "2026-08-01T15:01:00Z"))
        }
        val character = Character(id = "c1", instant = instant)
        val (status, updated) = background.extractMemoryStatus(character, instant)

        assertEquals(BackgroundTasks.TaskStatus.Success, status)
        // The request carried the whole window (one whole-window pass, no id citations).
        val prompt = llm.requests.single().input.last().content
        assertTrue(prompt.contains("\"m2\""))
        assertTrue(prompt.contains("\"old0\""))
        assertEquals(1, updated.shortTerm.size)
        // Evidence is collected client-side from the user line in the same day+slot.
        assertTrue(updated.shortTerm.single().sourceMessageIds.contains("m2"))
        // The fold compacted the window down to the low watermark.
        assertEquals(AppLimits.Memory.INSTANT_TRIM_FLOOR, updated.instant.size)
    }

    @Test
    fun analysisSendsTheBatchAndCarriesTheLowWaterEntriesAsContext() = runBlocking {
        // 21 entries: the oldest one is the batch, the newest 20 ride along as context
        // (`analyzed: true`) and are never consumed.
        val (background, llm) = tasks(listOf("""{"status":"ok","analyzedShortTermIds":["s1"],"longTerm":[]}"""))
        val character = Character(
            id = "c1",
            shortTerm = listOf(
                ShortTermMemory(
                    id = "s1",
                    content = "用户2026-08-01下午待整理的事",
                    sourceMessageIds = listOf("m1"),
                    eventTime = "2026-08-01T15:00:00Z",
                ),
            ) + filler(2, 21),
        )
        val outcome = background.analyzeShortToLongTermStatus(character)

        assertEquals(BackgroundTasks.TaskStatus.Success, outcome.status)
        // The low-water leftovers are re-read as context (continuity) but produce nothing.
        val prompt = llm.requests.single().input.last().content
        assertTrue(prompt.contains("\"analyzed\":true"))
        assertTrue(prompt.contains("\"analyzed\":false"))
        assertTrue(outcome.character.shortTerm.none { it.id == "s1" })
        assertEquals(20, outcome.character.shortTerm.size)
        assertTrue(outcome.character.shortTerm.all { it.id.startsWith("f") })
    }
}
