package com.deeptalking.domain.agent.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs background work, each task independently.
 *
 * There is deliberately **no shared lock and no concurrency cap**: the tasks are
 * separate API calls (the reply, the short-term fold, the long-term analysis, scene /
 * world-book upkeep, the one-time catch-up), and at most a handful are ever in flight.
 * Serializing them made a slow task block every other one — e.g. the one-time
 * catch-up kept scene summaries and quick-reply repair waiting for minutes. Tasks only
 * ever merge their result into the live character, so overlapping runs are safe.
 */
class BackgroundTaskQueue(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    fun enqueue(task: suspend () -> Unit): Job = scope.launch {
        try {
            task()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Background work must never crash the process.
        }
    }
}
