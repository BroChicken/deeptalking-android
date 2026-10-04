package com.deeptalking.domain.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the retry policy against the legacy `src/js/api/retry.js` contract.
 */
class ApiRetryTest {

    @Test
    fun transientStatusesAreRetried() {
        assertTrue(isTransientApiError(IllegalStateException("Responses API HTTP 429: rate limited")))
        assertTrue(isTransientApiError(IllegalStateException("Responses API HTTP 500: boom")))
        assertTrue(isTransientApiError(IllegalStateException("Responses SSE HTTP 503: unavailable")))
    }

    @Test
    fun businessStatusesAreNotRetried() {
        assertFalse(isTransientApiError(IllegalStateException("Responses API HTTP 400: bad request")))
        assertFalse(isTransientApiError(IllegalStateException("Responses API HTTP 401: unauthorized")))
        assertFalse(isTransientApiError(IllegalStateException("Responses API HTTP 404: not found")))
    }

    @Test
    fun networkFailuresAreRetried() {
        assertTrue(isTransientApiError(java.io.IOException("connection reset")))
        assertTrue(isTransientApiError(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun unknownErrorsAreNotRetried() {
        assertFalse(isTransientApiError(IllegalStateException("something else")))
    }

    @Test
    fun statusCodeIsParsedFromMessage() {
        assertEquals(429, httpStatusOf("Responses API HTTP 429: rate limited"))
        assertEquals(503, httpStatusOf("Responses SSE HTTP 503: unavailable"))
        assertEquals(null, httpStatusOf("no code here"))
        assertEquals(null, httpStatusOf(null))
    }

    @Test
    fun backoffSequenceMatchesLegacy() {
        assertEquals(1000L, ApiRetry.delayFor(1))
        assertEquals(2000L, ApiRetry.delayFor(2))
        assertEquals(4000L, ApiRetry.delayFor(3))
    }
}
