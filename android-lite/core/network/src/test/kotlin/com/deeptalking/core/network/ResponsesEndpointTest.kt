package com.deeptalking.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies the request-shaping helpers against the legacy JS contract
 * (`src/js/prompts/request.js` `getResponsesEndpoint` and
 * `src/js/core/normalization.js` `buildApiHeaders`).
 */
class ResponsesEndpointTest {

    @Test
    fun deepseekStripsTrailingV1() {
        assertEquals(
            "https://api.deepseek.com/responses",
            responsesEndpoint("https://api.deepseek.com/v1", "deepseek"),
        )
    }

    @Test
    fun opencodeKeepsV1() {
        assertEquals(
            "https://opencode.ai/zen/go/v1/responses",
            responsesEndpoint("https://opencode.ai/zen/go/v1", "opencode"),
        )
    }

    @Test
    fun stripsChatCompletionsSuffixBeforeAppending() {
        assertEquals(
            "https://api.deepseek.com/responses",
            responsesEndpoint("https://api.deepseek.com/v1/chat/completions", "deepseek"),
        )
    }

    @Test
    fun opencodeAlwaysSendsSessionHeader() {
        assertEquals(
            "x-opencode-session" to "deeptalking-abc",
            opencodeSessionHeader("opencode", "deeptalking-abc"),
        )
    }

    @Test
    fun opencodeFallsBackToGeneralSession() {
        assertEquals(
            "x-opencode-session" to "deeptalking-general",
            opencodeSessionHeader("opencode", null),
        )
    }

    @Test
    fun nonOpencodePlatformsOmitSessionHeader() {
        assertNull(opencodeSessionHeader("deepseek", "deeptalking-abc"))
        assertNull(opencodeSessionHeader("custom", "deeptalking-abc"))
        assertNull(opencodeSessionHeader(null, "deeptalking-abc"))
    }
}
