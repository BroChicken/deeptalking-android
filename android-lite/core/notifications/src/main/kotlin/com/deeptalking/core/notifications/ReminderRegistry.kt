package com.deeptalking.core.notifications

import java.util.concurrent.ConcurrentHashMap

/**
 * Unique WorkManager work name for reminder [id]. [Reminders.schedule] and [Reminders.cancel]
 * both go through this function, so a cancel can never miss the work a schedule enqueued.
 */
fun reminderWorkName(id: String): String = "deeptalking_reminder_$id"

/**
 * Notification tag used by [Reminders.post] and [Reminders.cancel] for [id]. The tag doubles
 * as the `PendingIntent` request code and as the notification id, so cancelling by the same
 * tag retracts an already-posted notification.
 */
fun reminderNotificationTag(id: String): Int = id.hashCode()

/**
 * In-process bookkeeping of the reminder ids that are currently *pending*: [Reminders.schedule]
 * registers, [Reminders.cancel] clears.
 *
 * WorkManager stays the delivery source of truth; this mirror is what callers can query
 * ("is this promise's reminder still coming?") and what unit tests assert against, because
 * WorkManager itself cannot be exercised in a plain JVM test. It is rebuilt as the app
 * schedules/cancels; a process restart empties it, and the next due-reminder scan registers
 * whatever is still pending.
 */
object ReminderRegistry {

    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Marks [id] as scheduled. Blank ids are ignored (nothing was enqueued for them). */
    fun register(id: String) {
        val key = id.trim()
        if (key.isEmpty()) return
        pending += key
    }

    /** Marks [id] as no longer scheduled. Blank ids are ignored. */
    fun unregister(id: String) {
        val key = id.trim()
        if (key.isEmpty()) return
        pending -= key
    }

    /** True when a reminder is currently pending for [id] (scheduled and not cancelled since). */
    fun isScheduled(id: String): Boolean = id.trim() in pending

    /** Snapshot of every pending reminder id. */
    fun scheduledIds(): Set<String> = pending.toSet()
}
