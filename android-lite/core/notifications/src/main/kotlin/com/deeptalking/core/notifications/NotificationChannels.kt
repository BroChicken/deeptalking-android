package com.deeptalking.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

object NotificationChannels {

    const val REMINDERS_CHANNEL_ID = "deeptalking_reminders"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REMINDERS_CHANNEL_ID,
                "提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "角色提醒与定时通知"
            }
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }
    }
}
