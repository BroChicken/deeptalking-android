package com.deeptalking.domain.agent

/**
 * Request-level retry policy ported from the legacy `src/js/api/retry.js`.
 *
 * Transient failures (network errors, timeouts/aborts, HTTP 429 and 5xx) are
 * retried up to [MAX_API_RETRIES] times with the [API_RETRY_DELAYS_MS] backoff;
 * business 4xx errors are not retried.
 */
object ApiRetry {
    const val MAX_API_RETRIES: Int = 3
    val API_RETRY_DELAYS_MS: List<Long> = listOf(1000L, 2000L, 4000L)

    /** Delay before the [attempt]-th retry (1-based). */
    fun delayFor(attempt: Int): Long =
        API_RETRY_DELAYS_MS.getOrElse(attempt - 1) { API_RETRY_DELAYS_MS.last() }
}

/**
 * True when [error] is worth retrying: aborted/timeout, network failure, or a
 * transient HTTP status (429 / 5xx). Mirrors `isTransientApiError`.
 *
 * The native transport surfaces these as an [IllegalStateException] whose
 * message starts with `Responses API HTTP <code>` (non-streaming) or
 * `Responses SSE HTTP <code>` (streaming); [java.io.IOException] covers
 * network/timeout failures.
 */
fun isTransientApiError(error: Throwable): Boolean {
    if (error is java.io.IOException) return true
    val code = httpStatusOf(error.message) ?: return false
    return code == 429 || code >= 500
}

/** Extracts the HTTP status code from a transport error message, if present. */
fun httpStatusOf(message: String?): Int? {
    if (message == null) return null
    val marker = "HTTP "
    val index = message.indexOf(marker)
    if (index < 0) return null
    val digits = message.substring(index + marker.length).takeWhile { it.isDigit() }
    return digits.toIntOrNull()
}
