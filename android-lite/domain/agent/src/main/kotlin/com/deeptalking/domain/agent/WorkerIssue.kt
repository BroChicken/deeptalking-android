package com.deeptalking.domain.agent

/**
 * One local rule rejection kept for the next worker turn.
 *
 * [target] is a human label (a todo title, a field label, a member name) and [reason]
 * is a short, fixed label such as `期限不明` or `证据不符`. The full reason is still
 * written to the request log; only this compact line is replayed in the next worker
 * prompt so a stale or verbose rejection cannot grow the context.
 */
internal data class WorkerIssue(val target: String, val reason: String) {
    fun line(): String {
        val cleanTarget = target.trim().take(40)
        val cleanReason = reason.trim().take(20)
        return if (cleanTarget.isEmpty()) cleanReason else "$cleanTarget：$cleanReason"
    }
}

/** Stable, deduplicated prompt lines; at most [max] entries. */
internal fun workerIssueLines(issues: List<WorkerIssue>, max: Int = 8): List<String> =
    issues.map { it.line() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(max)
