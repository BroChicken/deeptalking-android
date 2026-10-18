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
