package com.deeptalking.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies cached-token resolution against legacy `recordCacheUsage`
 * (`src/js/api/responses.js:271-279`).
 */
class ResponsesUsageTest {

    @Test
    fun promptCacheFieldsTakePriority() {
        val usage = UsageDto(
            inputTokens = 100,
            outputTokens = 5,
            inputTokensDetails = InputTokensDetails(cachedTokens = 10),
            promptCacheHitTokens = 80,
            promptCacheMissTokens = 20,
        )
        assertEquals(80, resolveCachedTokens(usage))
    }

    @Test
    fun fallsBackToInputTokensDetails() {
        val usage = UsageDto(inputTokens = 100, outputTokens = 5, inputTokensDetails = InputTokensDetails(20))
        assertEquals(20, resolveCachedTokens(usage))
    }

    @Test
    fun clampsNegativeValues() {
        val usage = UsageDto(promptCacheHitTokens = -3, promptCacheMissTokens = 0)
        assertEquals(0, resolveCachedTokens(usage))
    }

    @Test
    fun noCacheFieldsYieldsNull() {
        assertEquals(null, resolveCachedTokens(UsageDto(inputTokens = 100, outputTokens = 5)))
    }

    @Test
    fun partialPromptCacheFieldsFallBackToDetails() {
        val usage = UsageDto(inputTokens = 100, inputTokensDetails = InputTokensDetails(30), promptCacheHitTokens = 80)
        assertEquals(30, resolveCachedTokens(usage))
    }
}
