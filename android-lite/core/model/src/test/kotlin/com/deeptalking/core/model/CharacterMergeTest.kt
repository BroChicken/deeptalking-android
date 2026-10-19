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
}
