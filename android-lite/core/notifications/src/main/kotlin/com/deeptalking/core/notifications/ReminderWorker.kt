package com.deeptalking.core.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Posts a scheduled reminder notification. */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(EXTRA_ID) ?: return Result.success()
        val title = inputData.getString(EXTRA_TITLE).orEmpty()
        val text = inputData.getString(EXTRA_TEXT).orEmpty()
        Reminders.post(applicationContext, id, title, text)
        return Result.success()
    }

    companion object {
        const val EXTRA_ID = "extra_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
    }
}
