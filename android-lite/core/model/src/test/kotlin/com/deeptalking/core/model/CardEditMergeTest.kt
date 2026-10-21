package com.deeptalking.core.model

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card editor writes only what the user actually changed: static fields are merged field
 * by field (and only the changed keys get a timestamp), todos are merged by id, and a member's
 * static/name/emoji edits persist while the member's background-owned data stays live.
 */
class CardEditMergeTest {

    private fun todo(id: String, title: String = "约定$id") = Todo(
        id = id,
        title = title,
        dueAt = "2026-10-30T12:00:00Z",
    )

    @Test
    fun `static edits are merged field by field`() {
        val live = StaticProfile(personality = "后台补全的性格", age = "30")
        val draft = StaticProfile(personality = "用户改的性格", age = "30", userAddress = "明明")

        val merged = mergeStaticProfile(live, draft, live)

        assertEquals("用户改的性格", merged.personality)
        assertEquals("明明", merged.userAddress)
        assertEquals("30", merged.age)
    }

    @Test
    fun `only the changed static keys are stamped and other timestamps survive`() {
        val live = StaticProfile(personality = "旧性格", age = "30")
        val draft = live.copy(personality = "新性格")
        val existing = withStaticFieldTimestamps(emptyMap(), setOf("age"), "2026-10-19T08:00:00Z")

        val keys = changedStaticProfileKeys(live, draft)
        val meta = withStaticFieldTimestamps(existing, keys, "2026-10-20T12:00:00Z")

        assertEquals(setOf("personality"), keys)
        assertEquals(
            "2026-10-20T12:00:00Z",
            meta.getValue("personality").jsonObject.getValue("updatedAt").jsonPrimitive.content,
        )
        assertEquals(
            "2026-10-19T08:00:00Z",
            meta.getValue("age").jsonObject.getValue("updatedAt").jsonPrimitive.content,
        )
    }

    @Test
    fun `saving without static changes leaves the timestamps untouched`() {
        val profile = StaticProfile(personality = "性格")
        val meta = withStaticFieldTimestamps(emptyMap(), setOf("personality"), "2026-10-19T08:00:00Z")

        val merged = withStaticFieldTimestamps(meta, changedStaticProfileKeys(profile, profile.copy()), "later")

        assertEquals(meta, merged)
    }

    @Test
    fun `todos are merged by id and the editor's list wins`() {
        val live = listOf(todo("t1"))
        val draft = listOf(
            todo("t1").copy(status = TodoStatus.Done, closedAt = "2026-10-20T10:00:00Z"),
            todo("t3"),
        )

        val merged = mergeTodos(live, draft, live)

        assertEquals(TodoStatus.Done, merged.first { it.id == "t1" }.status)
        assertTrue("a todo added in the editor is kept", merged.any { it.id == "t3" })
        assertEquals(2, merged.size)
    }

    @Test
    fun `a todo the user deleted is removed even when the live row still has it`() {
        val live = listOf(todo("t1"), todo("t2"))
        val draft = listOf(todo("t2"))

        val merged = mergeTodos(live, draft, live)

        assertEquals(listOf("t2"), merged.map { it.id })
    }

    @Test
    fun `member card edits keep static name emoji and role`() {
        val live = GroupMember(
            id = "m1",
            name = "旧名",
            emoji = "👤",
            roleInGroup = "旧定位",
            staticProfile = StaticProfile(personality = "旧性格"),
            lorebook = listOf(LorebookEntry(id = "l1", name = "书", content = "实时内容")),
        )
        val draft = live.copy(
            name = "新名",
            emoji = "🩺",
            roleInGroup = "护士",
            staticProfile = StaticProfile(personality = "新性格", age = "28"),
        )

        val merged = mergeMemberCardEdits(live, draft, "2026-10-20T12:00:00Z")

        assertEquals("新名", merged.name)
        assertEquals("🩺", merged.emoji)
        assertEquals("护士", merged.roleInGroup)
        assertEquals("新性格", merged.staticProfile.personality)
        assertEquals("28", merged.staticProfile.age)
        assertEquals("实时内容", merged.lorebook.single().content)
        assertEquals(
            "2026-10-20T12:00:00Z",
            merged.staticFieldMeta.getValue("personality").jsonObject.getValue("updatedAt").jsonPrimitive.content,
        )
        assertTrue("a newly filled field is stamped as user-edited", merged.staticFieldMeta.containsKey("age"))
    }

    @Test
    fun `member dynamic edits stay per key and stamp only what moved`() {
        val live = GroupMember(
            id = "m1",
            dynamicState = DynamicState(currentMood = "平静", currentGoal = "值夜班"),
            dynamicStateMeta = mapOf("currentGoal" to DynamicStateMeta("2026-10-19T08:00:00Z")),
        )
        val draft = live.copy(dynamicState = live.dynamicState.copy(currentMood = "紧张", currentGoal = "值夜班"))

        val merged = mergeMemberCardEdits(live, draft, "2026-10-20T12:00:00Z")

        assertEquals("紧张", merged.dynamicState.currentMood)
        assertEquals("2026-10-20T12:00:00Z", merged.dynamicStateMeta.getValue("currentMood").updatedAt)
        assertEquals("2026-10-19T08:00:00Z", merged.dynamicStateMeta.getValue("currentGoal").updatedAt)
    }

    @Test
    fun `group shared state timestamps are add only`() {
        val live = mapOf("currentSituation" to DynamicStateMeta("2026-10-19T08:00:00Z"))
        val draft = mapOf("currentLocation" to DynamicStateMeta("2026-10-19T09:00:00Z"))

        val merged = stampDynamicStateMeta(live, draft, setOf("currentLocation"), "2026-10-20T12:00:00Z")

        assertEquals("2026-10-19T08:00:00Z", merged.getValue("currentSituation").updatedAt)
        assertEquals("2026-10-20T12:00:00Z", merged.getValue("currentLocation").updatedAt)
    }
}
