package com.deeptalking.lite

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class InstantWindowTest {

    private fun message(id: String, sequence: Int = 0) = ChatMessage(
        id = id,
        role = Role.User,
        content = id,
        sequence = sequence,
    )

    private fun room(size: Int) = (1..size).map { message("m$it", it) }

    @Test
    fun `a collapsed window with one synthetic reply is rebuilt from the room`() {
        val grown = growInstantWindow(listOf(message("assistant_c1")), room(400))
        assertEquals(AppLimits.Memory.INSTANT, grown.size)
        assertEquals("m${400 - AppLimits.Memory.INSTANT + 1}", grown.first().id)
        assertEquals("m400", grown.last().id)
    }

    @Test
    fun `a single real message below the low-water mark is rebuilt when history exists`() {
        val grown = growInstantWindow(listOf(message("m400", 400)), room(400))
        assertEquals(AppLimits.Memory.INSTANT, grown.size)
        assertEquals("m400", grown.last().id)
    }

    @Test
    fun `a genuinely short conversation is not inflated`() {
        val grown = growInstantWindow(listOf(message("m1", 1)), room(1))
        assertEquals(listOf("m1"), grown.map { it.id })
    }

    @Test
    fun `a normal window only appends messages it has not seen`() {
        val instant = (1..20).map { message("m$it", it) }
        val grown = growInstantWindow(instant, room(25))
        assertEquals((1..25).map { "m$it" }, grown.map { it.id })
    }

    @Test
    fun `a legitimate short tail is not rebuilt from history`() {
        // A fold can legitimately leave fewer than 20 messages (for example while the
        // newest batch is still being resolved). It is a tail, not a collapsed window.
        val instant = (380..396).map { message("m$it", it) }
        val grown = growInstantWindow(instant, room(400))
        assertEquals((380..400).map { "m$it" }, grown.map { it.id })
    }
}
