package com.deeptalking.core.model

import org.junit.Assert.assertEquals
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
    fun concurrentWorldBookAdditionsBothSurvive() {
        val before = Character(id = "c1")
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
}
