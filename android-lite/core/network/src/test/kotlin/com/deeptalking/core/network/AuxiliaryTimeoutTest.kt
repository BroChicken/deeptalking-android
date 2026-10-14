package com.deeptalking.core.network

import com.deeptalking.core.common.AppLimits
import com.deeptalking.engine.ondevice.LlmRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Chat turns keep the full 180s budget, background/auxiliary calls get 90s.
 *
 * Guards the wiring of `AppLimits.Api.AUXILIARY_TIMEOUT_MS`: it used to be a
 * declared-but-unreferenced constant while every auxiliary call ran on the chat
 * timeout, so a stuck maintenance task could hold the app for 180s per attempt.
 */
class AuxiliaryTimeoutTest {

    @Test
    fun chatTurnsKeepTheFullBudget() {
        assertEquals(180L, callTimeoutSeconds(auxiliary = false))
    }

    @Test
    fun auxiliaryCallsUseTheSharedNinetySecondBudget() {
        assertEquals(90L, callTimeoutSeconds(auxiliary = true))
        assertEquals(
            AppLimits.Api.AUXILIARY_TIMEOUT_MS,
            callTimeoutSeconds(auxiliary = true) * 1_000,
        )
    }

    @Test
    fun requestsDefaultToTheChatBudget() {
        assertFalse(LlmRequest(model = "m").auxiliary)
        assertTrue(LlmRequest(model = "m", auxiliary = true).auxiliary)
    }
}
