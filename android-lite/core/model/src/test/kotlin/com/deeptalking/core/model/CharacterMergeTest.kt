package com.deeptalking.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write path merges a writer's **changes** onto the freshest row, so a world-book
 * pass running for seconds cannot roll back what the user or the chat turn changed in the
 * meantime (and vice versa).
 */
class CharacterMergeTest {

    private fun entry(id: String, content: String) = LorebookEntry(
        id = id,
        name = "条目$id",
        content = content,
        origin = LorebookOrigin.Model,
    )

    @Test
    fun dynamicStateFieldsFromTwoWritersBothSurvive() {
        val before = Character(id = "c1")
        // The worker changed the mood.
        val after = before.copy(dynamicState = DynamicState(currentMood = "有点累"))
        // While it ran, the user edited the situation.
        val live = before.copy(dynamicState = DynamicState(currentSituation = "在美术馆"))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals("在美术馆", merged.dynamicState.currentSituation)
        assertEquals("有点累", merged.dynamicState.currentMood)
    }

    @Test
    fun aFoldKeepsItsTwentyMessageWindowEvenWhenLiveIsStale() {
        val before = Character(
            id = "c1",
            instant = (0 until 30).map {
                ChatMessage(id = "m$it", role = Role.User, content = "消息$it", sequence = it)
            },
        )
        val after = before.copy(instant = before.instant.takeLast(20))
        val staleLive = before.copy(instant = before.instant.takeLast(18))

        val merged = mergeCharacterChanges(before, after, staleLive)

        assertEquals(after.instant.map { it.id }, merged.instant.map { it.id })
    }

    @Test
    fun aFoldAppendsMessagesThatArrivedAfterItsBatch() {
        val before = Character(
            id = "c1",
            instant = (0 until 30).map {
                ChatMessage(id = "m$it", role = Role.User, content = "消息$it", sequence = it)
            },
        )
        val after = before.copy(instant = before.instant.takeLast(20))
        val liveWithNewer = before.copy(
            instant = before.instant.takeLast(20) +
                ChatMessage(id = "m30", role = Role.User, content = "新消息", sequence = 30),
        )

        val merged = mergeCharacterChanges(before, after, liveWithNewer)

        assertEquals(21, merged.instant.size)
        assertEquals("m30", merged.instant.last().id)
    }

    @Test
    fun workerIssuesFollowTheTurnWriter() {
        val before = Character(id = "c1", workerIssues = listOf("待办：期限不明"))
        val after = before.copy(workerIssues = listOf("情绪：证据不符"))
        val live = before.copy(name = "Alice")

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(listOf("情绪：证据不符"), merged.workerIssues)
    }

    @Test
    fun perFieldTimestampsAreMergedByKey() {
        val before = Character(id = "c1")
        val after = before.copy(
            dynamicStateMeta = mapOf("currentMood" to DynamicStateMeta("2026-10-20T12:00:00Z")),
        )
        val live = before.copy(
            dynamicStateMeta = mapOf("currentSituation" to DynamicStateMeta("2026-10-20T11:00:00Z")),
        )

        val merged = mergeCharacterChanges(before, after, live)

        assertNotNull(merged.dynamicStateMeta["currentMood"])
        assertNotNull(merged.dynamicStateMeta["currentSituation"])
    }

    @Test
    fun staticProfileFieldsFromTwoWritersBothSurvive() {
        val before = Character(id = "c1")
        val after = before.copy(staticProfile = StaticProfile(personality = "冷静"))
        val live = before.copy(staticProfile = StaticProfile(userAddress = "明明"))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals("冷静", merged.staticProfile.personality)
        assertEquals("明明", merged.staticProfile.userAddress)
    }

    @Test
    fun onlyTheFieldsAWriterChangedAreWritten() {
        val before = Character(id = "c1", name = "原名", lorebook = listOf(entry("l1", "旧内容")))
        val after = before.copy(lorebook = listOf(entry("l1", "新内容")))
        // Another writer renamed the character while we were editing the world book.
        val live = before.copy(name = "改过的名字")

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals("改过的名字", merged.name)
        assertEquals("新内容", merged.lorebook.single().content)
    }

    @Test
    fun anUnchangedFieldIsNotTouchedEvenWhenTheSnapshotIsStale() {
        val before = Character(id = "c1", staticProfile = StaticProfile(personality = "冷静"))
        val after = before.copy(lorebook = listOf(entry("l1", "x")))
        // A concurrent edit changed the personality after our snapshot was taken.
        val live = before.copy(staticProfile = StaticProfile(personality = "热情"))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals("热情", merged.staticProfile.personality)
        assertEquals(1, merged.lorebook.size)
    }

    @Test
    fun concurrentWorldBookAdditionsBothSurvive() {        val before = Character(id = "c1")
        val ours = before.copy(lorebook = listOf(entry("ours", "我们写的")))
        val live = before.copy(lorebook = listOf(entry("theirs", "别人写的")))

        val merged = mergeCharacterChanges(before, ours, live)

        assertEquals(listOf("ours", "theirs"), merged.lorebook.map { it.id }.sorted())
    }

    @Test
    fun aRemovalIsAppliedButAConcurrentAdditionIsNotDropped() {
        val before = Character(id = "c1", lorebook = listOf(entry("keep", "a"), entry("drop", "b")))
        val after = before.copy(lorebook = listOf(entry("keep", "a")))
        val live = before.copy(lorebook = listOf(entry("keep", "a"), entry("drop", "b"), entry("theirs", "c")))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(listOf("keep", "theirs"), merged.lorebook.map { it.id }.sorted())
    }

    @Test
    fun theLorebookArchiveMergesPerEntryToo() {
        val before = Character(id = "c1", lorebook = listOf(entry("live", "a")))
        val after = before.copy(lorebookArchive = listOf(entry("arc", "归档")))
        // Another writer archived a different entry while we were working.
        val live = before.copy(lorebookArchive = listOf(entry("other", "别人归档的")))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(listOf("arc", "other"), merged.lorebookArchive.map { it.id }.sorted())
        assertEquals(listOf("live"), merged.lorebook.map { it.id })
    }

    @Test
    fun memberWorldBooksMergePerEntryToo() {
        val member = GroupMember(id = "m1", name = "甲", lorebook = listOf(entry("a", "旧")))
        val before = Character(id = "c1", isGroup = true, members = listOf(member))
        val after = before.copy(
            members = listOf(member.copy(lorebook = listOf(entry("a", "新")))),
        )
        val live = before.copy(
            members = listOf(member.copy(emoji = "🙂")),
        )

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals("🙂", merged.members.single().emoji)
        assertEquals("新", merged.members.single().lorebook.single().content)
    }

    @Test
    fun anIdenticalChangeWritesNothing() {
        val before = Character(id = "c1", lorebook = listOf(entry("l1", "x")))
        val live = before.copy(name = "并发改名")

        val merged = mergeCharacterChanges(before, before, live)

        assertEquals(live, merged)
    }

    private fun memory(
        id: String,
        key: String = "k$id",
        due: String? = null,
        pushConfirmedAt: String? = null,
    ) = LongTermMemory(
        id = id,
        category = MemoryCategory.Promises,
        key = key,
        value = "v$id",
        dueAt = due,
        pushConfirmedAt = pushConfirmedAt,
    )

    @Test
    fun stampingOneMemoryDoesNotDropTheOneABackgroundPassJustWrote() {
        val before = Character(id = "c1", longTerm = listOf(memory("p1", due = "2026-08-10")))
        // This turn only stamped its own confirmation onto p1 …
        val after = before.copy(
            longTerm = listOf(memory("p1", due = "2026-08-10", pushConfirmedAt = "2026-08-09T10:00:00Z")),
        )
        // … while a background pass wrote a brand-new memory during the reply.
        val live = before.copy(longTerm = before.longTerm + memory("new"))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(listOf("p1", "new"), merged.longTerm.map { it.id })
        assertNotNull(merged.longTerm.first { it.id == "p1" }.pushConfirmedAt)
    }

    @Test
    fun aMemoryRemovalIsAppliedButAConcurrentMemoryIsNotDropped() {
        val before = Character(id = "c1", longTerm = listOf(memory("keep"), memory("drop")))
        val after = before.copy(longTerm = listOf(memory("keep")))
        val live = before.copy(longTerm = before.longTerm + memory("theirs"))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(listOf("keep", "theirs"), merged.longTerm.map { it.id })
    }

    @Test
    fun memoriesWithoutLocatableIdsFallBackToTheWholeListRule() {
        // 旧数据可能没有 id：无法定位条目，就整表按"改过就写"处理，绝不按 "" 合并。
        val before = Character(id = "c1", longTerm = listOf(memory(""), memory("")))
        val after = before.copy(longTerm = listOf(memory("", key = "改过的")))
        val live = before.copy(longTerm = before.longTerm + memory("obj"))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(after.longTerm, merged.longTerm)
    }

    @Test
    fun concurrentTodoAdditionsBothSurviveAndARemovalIsHonoured() {
        val todo = { id: String -> Todo(id = id, title = id, detail = "", dueAt = "2026-08-10T09:00:00.000Z") }
        val before = Character(id = "c1", todos = listOf(todo("a"), todo("b")))
        // The card editor removes "b" and adds "c".
        val after = before.copy(todos = listOf(todo("a"), todo("c")))
        // A turn concurrently recorded "d".
        val live = before.copy(todos = before.todos + todo("d"))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(listOf("a", "c", "d"), merged.todos.map { it.id }.sorted())
    }

    // ------------------------------------------------------------ windows / scenes

    private fun message(id: String, content: String = "消息$id") =
        ChatMessage(id = id, role = Role.User, content = content)

    private fun short(id: String, content: String = "摘要$id") = ShortTermMemory(id = id, content = content)

    private fun scene(id: String) = SceneSummary(id = id, key = id, content = "场景$id")

    @Test
    fun aFoldConsumingTheWindowDoesNotDropTheTurnJustAppended() {
        val before = Character(id = "c1", instant = listOf(message("m1"), message("m2")))
        // The fold consumed m1 while the turn appended m3 to the same window.
        val after = before.copy(instant = listOf(message("m2")))
        val live = before.copy(instant = before.instant + message("m3"))

        val merged = mergeCharacterChanges(before, after, live)

        // The consumption of m1 applies, and the turn's m3 is not rolled back.
        assertEquals(listOf("m2", "m3"), merged.instant.map { it.id })
    }

    @Test
    fun shortTermConsumptionKeepsWhatTheTurnAddedMeanwhile() {
        val before = Character(id = "c1", shortTerm = listOf(short("s1"), short("s2"), short("s3")))
        // The analysis pass consumed the first two entries …
        val after = before.copy(shortTerm = listOf(short("s3")))
        // … while the turn appended a new summary to the window.
        val live = before.copy(shortTerm = before.shortTerm + short("s4"))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(listOf("s3", "s4"), merged.shortTerm.map { it.id })
    }

    @Test
    fun sceneSummariesAddedByTwoWritersBothSurvive() {
        val before = Character(id = "c1")
        val after = before.copy(scenes = listOf(scene("scene-a")))
        val live = before.copy(scenes = listOf(scene("scene-b")))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(listOf("scene-a", "scene-b"), merged.scenes.map { it.id }.sorted())
    }

    @Test
    fun pendingRecallAdditionsBothSurviveAndAReplacementStillDropsTheOldSet() {
        // A concurrent writer selected a recall item …
        val before = Character(id = "c1")
        val live = before.copy(pendingRecall = listOf(memory("theirs")))
        val after = before.copy(pendingRecall = listOf(memory("ours")))

        val mergedAdditive = mergeCharacterChanges(before, after, live)
        assertEquals(listOf("ours", "theirs"), mergedAdditive.pendingRecall.map { it.id }.sorted())

        // … and a recall that replaces the set still removes the previous items.
        val previous = Character(id = "c1", pendingRecall = listOf(memory("old1"), memory("old2")))
        val recalled = previous.copy(pendingRecall = listOf(memory("fresh")))

        val mergedReplacement = mergeCharacterChanges(previous, recalled, previous)

        assertEquals(listOf("fresh"), mergedReplacement.pendingRecall.map { it.id })
    }

    @Test
    fun legacyMessagesWithoutIdsAreStillConsumedAsAGroup() {
        // 旧数据可能整窗没有 id：它们无法逐条定位，只能作为一组整体被消费。
        val before = Character(id = "c1", instant = listOf(message(""), message("")))
        val after = before.copy(instant = emptyList())
        val live = before.copy(instant = before.instant + message("m9"))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(listOf("m9"), merged.instant.map { it.id })
    }

    @Test
    fun blankIdsAreNeverCollapsedOntoOneKey() {
        val blankA = message("", "第一条")
        val blankB = message("", "第二条")
        val before = Character(id = "c1", instant = listOf(blankA))
        // This writer only renamed the character, so it has no opinion on the window.
        val after = before.copy(name = "改过的名字")
        val live = before.copy(instant = listOf(blankA, blankB))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals("改过的名字", merged.name)
        // Both unlocatable live entries survive; nothing is keyed on "".
        assertEquals(2, merged.instant.size)
        assertEquals(listOf("第一条", "第二条"), merged.instant.map { it.content })
    }

    @Test
    fun aDuplicatedIdIsNotMergedEntryWise() {
        val first = message("d", "第一版")
        val second = message("d", "第二版")
        val before = Character(id = "c1", instant = listOf(first, second))
        // The writer dropped one of the two rows sharing the id — it cannot say which.
        val after = before.copy(instant = listOf(first))
        val live = before.copy(instant = listOf(first, second, message("other", "别的")))

        val merged = mergeCharacterChanges(before, after, live)

        // The duplicated group is replaced as a whole instead of guessing per row.
        assertEquals(listOf("第一版", "别的"), merged.instant.map { it.content })
    }

    @Test
    fun duplicatesOnlyInTheLiveRowAreNotDropped() {
        val first = message("d", "第一版")
        val before = Character(id = "c1", instant = listOf(first))
        val after = before.copy(name = "改过的名字")
        val live = before.copy(instant = listOf(first, message("d", "重复的一条")))

        val merged = mergeCharacterChanges(before, after, live)

        assertEquals(2, merged.instant.size)
    }

    @Test
    fun memberWindowsMergePerEntryToo() {
        val member = GroupMember(
            id = "m1",
            name = "甲",
            shortTerm = listOf(short("s1")),
            instant = listOf(message("m1")),
            scenes = listOf(scene("scene-1")),
            pendingRecall = listOf(memory("r1")),
        )
        val before = Character(id = "g1", isGroup = true, members = listOf(member))
        // The fold consumed the member's window while the turn appended a new message.
        val after = before.copy(
            members = listOf(member.copy(shortTerm = emptyList(), instant = emptyList())),
        )
        val live = before.copy(
            members = listOf(
                member.copy(
                    shortTerm = member.shortTerm + short("s2"),
                    instant = member.instant + message("m2"),
                    scenes = member.scenes + scene("scene-2"),
                    pendingRecall = member.pendingRecall + memory("r2"),
                ),
            ),
        )

        val merged = mergeCharacterChanges(before, after, live)
        val result = merged.members.single()

        assertEquals(listOf("s2"), result.shortTerm.map { it.id })
        assertEquals(listOf("m2"), result.instant.map { it.id })
        assertEquals(listOf("scene-1", "scene-2"), result.scenes.map { it.id })
        assertEquals(listOf("r1", "r2"), result.pendingRecall.map { it.id }.sorted())
    }
}
