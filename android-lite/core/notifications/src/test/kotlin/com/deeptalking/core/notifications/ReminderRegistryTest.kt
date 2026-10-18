package com.deeptalking.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorkManager itself is not exercisable in a plain JVM test (no Robolectric in this project),
 * so these tests pin the parts of the reminder lifecycle that are pure Kotlin: the id → work
 * name / notification tag mapping shared by schedule+post+cancel, and the [ReminderRegistry]
 * bookkeeping that drives "is this reminder still pending?".
 */
class ReminderRegistryTest {

    @Test
    fun `work name and notification tag are derived from the id and stable`() {
        assertEquals(reminderWorkName("promise-1"), reminderWorkName("promise-1"))
        assertNotEquals(reminderWorkName("promise-1"), reminderWorkName("promise-2"))
        assertEquals(reminderNotificationTag("promise-1"), "promise-1".hashCode())
        // The tag is what post() notifies under and cancel() retracts, so they must agree.
        assertEquals(reminderNotificationTag("promise-1"), reminderNotificationTag("promise-1"))
    }

    @Test
    fun `scheduling registers the reminder and cancelling clears the registration`() {
        ReminderRegistry.register("promise-1")
        assertTrue(ReminderRegistry.isScheduled("promise-1"))
        assertTrue("promise-1" in ReminderRegistry.scheduledIds())

        // Equivalent to what Reminders.cancel performs for a promise that was completed.
        ReminderRegistry.unregister("promise-1")
        assertFalse("a cancelled reminder must not stay pending", ReminderRegistry.isScheduled("promise-1"))
        assertTrue(ReminderRegistry.scheduledIds().isEmpty())
    }

    @Test
    fun `re-scheduling the same id stays a single pending reminder`() {
        ReminderRegistry.register("promise-2")
        ReminderRegistry.register("promise-2")
        assertEquals(setOf("promise-2"), ReminderRegistry.scheduledIds())

        ReminderRegistry.unregister("promise-2")
        assertFalse(ReminderRegistry.isScheduled("promise-2"))
    }

    @Test
    fun `blank ids are ignored`() {
        ReminderRegistry.register("   ")
        assertFalse(ReminderRegistry.isScheduled(""))
        ReminderRegistry.register("promise-3")
        ReminderRegistry.unregister("")
        assertTrue("unregistering a blank id must not clear real reminders", ReminderRegistry.isScheduled("promise-3"))
        ReminderRegistry.unregister("promise-3")
        assertFalse(ReminderRegistry.isScheduled("promise-3"))
    }
}
