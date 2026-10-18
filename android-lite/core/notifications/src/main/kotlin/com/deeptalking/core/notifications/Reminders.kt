package com.deeptalking.core.notifications

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Reminder delivery: [schedule] enqueues a one-shot [ReminderWorker] through WorkManager
 * (`ExistingWorkPolicy.REPLACE` = at most one pending reminder per id), [post] pushes the
 * notification immediately, and [cancel] retracts both halves — the pending work *and* a
 * notification that already fired — so a promise that is completed/cancelled stops reminding.
 *
 * Every id is tracked in [ReminderRegistry], which is the observable "this reminder is still
 * pending" view callers (and tests) can query.
 */
object Reminders {

    fun schedule(context: Context, id: String, triggerAtMillis: Long, title: String, text: String) {
        NotificationChannels.ensure(context)
        val delayMillis = (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .addTag(REMINDER_WORK_TAG)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ReminderWorker.EXTRA_ID to id,
                    ReminderWorker.EXTRA_TITLE to title,
                    ReminderWorker.EXTRA_TEXT to text,
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            reminderWorkName(id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        ReminderRegistry.register(id)
    }

    /**
     * Cancels the reminder for [id]: the pending work is removed, a notification that already
     * fired is dismissed, and the id stops being reported as scheduled. Callers invoke this
     * when the backing promise is completed or cancelled. Safe to call for an id that has no
     * pending reminder.
     */
    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(reminderWorkName(id))
        NotificationManagerCompat.from(context).cancel(reminderNotificationTag(id))
        ReminderRegistry.unregister(id)
    }

    /**
     * Retracts **every** reminder. Used after an import/restore *replaces* the whole data set:
     * without it, a reminder scheduled for a promise that no longer exists would still fire
     * ("数据里没这条承诺，通知却响了").
     *
     * Two halves, because the in-process registry is not durable:
     * 1. `cancelAllWorkByTag` drops every enqueued job — including the ones a **previous run**
     *    of the app created (the registry is empty after a restart, so iterating it alone would
     *    silently miss them);
     * 2. the registry snapshot dismisses the notifications those ids already posted.
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(REMINDER_WORK_TAG)
        // Iterating a copy is deliberate — [cancel] mutates the registry.
        ReminderRegistry.scheduledIds().forEach { cancel(context, it) }
    }

    fun post(context: Context, id: String, title: String, text: String) {
        NotificationChannels.ensure(context)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                reminderNotificationTag(id),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_deeptalking)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()

        try {
            NotificationManagerCompat.from(context).notify(reminderNotificationTag(id), notification)
        } catch (t: SecurityException) {
            // POST_NOTIFICATIONS not granted; nothing else to do.
        }
    }
}
