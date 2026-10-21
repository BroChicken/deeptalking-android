package com.deeptalking.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A field the user is typing in is registered here; writers skip it so a value computed from
 * an older snapshot cannot overwrite the half-typed text.
 */
class FieldEditRegistryTest {

    @Before
    fun setUp() = FieldEditRegistry.clearAll()

    @After
    fun tearDown() = FieldEditRegistry.clearAll()

    private fun key(field: String, memberId: String? = null, section: FieldSection = FieldSection.Static) =
        FieldKey.of("c1", memberId, section, field)

    @Test
    fun `focus and blur track the field`() {
        val key = key("personality")
        assertFalse(FieldEditRegistry.isFocused(key))

        FieldEditRegistry.focus(key, "editor-1")
        assertTrue(FieldEditRegistry.isFocused(key))
        assertTrue(FieldEditRegistry.isFocused("c1", null, FieldSection.Static, "personality"))

        FieldEditRegistry.blur(key, "editor-1")
        assertFalse(FieldEditRegistry.isFocused(key))
    }

    @Test
    fun `a stale blur does not clear another editor's focus`() {
        val key = key("personality")
        FieldEditRegistry.focus(key, "editor-2")

        FieldEditRegistry.blur(key, "editor-1")

        assertTrue(FieldEditRegistry.isFocused(key))
    }

    @Test
    fun `clearOwner only drops that editor's fields`() {
        FieldEditRegistry.focus(key("personality"), "editor-1")
        FieldEditRegistry.focus(key("appearance"), "editor-1")
        FieldEditRegistry.focus(key("age"), "editor-2")

        FieldEditRegistry.clearOwner("editor-1")

        assertFalse(FieldEditRegistry.isFocused(key("personality")))
        assertFalse(FieldEditRegistry.isFocused(key("appearance")))
        assertTrue(FieldEditRegistry.isFocused(key("age")))
    }

    @Test
    fun `members sections and fields are independent keys`() {
        FieldEditRegistry.focus(key("currentMood", section = FieldSection.Dynamic), "edit-entity")
        FieldEditRegistry.focus(key("currentMood", memberId = "m1", section = FieldSection.Dynamic), "edit-member")

        assertTrue(FieldEditRegistry.isFocused("c1", null, FieldSection.Dynamic, "currentMood"))
        assertTrue(FieldEditRegistry.isFocused("c1", "m1", FieldSection.Dynamic, "currentMood"))
        assertFalse(FieldEditRegistry.isFocused("c1", "m2", FieldSection.Dynamic, "currentMood"))
        assertFalse(FieldEditRegistry.isFocused("c1", null, FieldSection.Static, "currentMood"))

        assertEquals(setOf("currentMood"), FieldEditRegistry.focusedFields("c1", "m1", FieldSection.Dynamic))
        assertEquals(emptySet<String>(), FieldEditRegistry.focusedFields("c1", "m2", FieldSection.Dynamic))
    }

    @Test
    fun `a blank member id addresses the entity itself`() {
        val blank = FieldKey.of("c1", "", FieldSection.Dynamic, "currentGoal")
        assertEquals(FieldKey.SELF_MEMBER, blank.memberId)
        assertEquals(blank, FieldKey.of("c1", null, FieldSection.Dynamic, "currentGoal"))

        FieldEditRegistry.focus(blank, "editor")
        assertTrue(FieldEditRegistry.isFocused("c1", "", FieldSection.Dynamic, "currentGoal"))
    }

    @Test
    fun `concurrent focus and blur stay consistent`() {
        val key = key("fears")
        val threads = (1..8).map { index ->
            Thread {
                repeat(500) {
                    FieldEditRegistry.focus(key, "owner-$index")
                    FieldEditRegistry.blur(key, "owner-$index")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertFalse(FieldEditRegistry.isFocused(key))
    }
}
