package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the session-id derivation against the legacy `getSessionIdFor`
 * (`src/js/core/normalization.js`).
 */
class SessionIdTest {

    @Test
    fun usesCharacterIdWhenPresent() {
        assertEquals("deeptalking-abc", sessionIdFor(Character(id = "abc")))
    }

    @Test
    fun fallsBackToGeneralWhenMissing() {
        assertEquals("deeptalking-general", sessionIdFor(null))
        assertEquals("deeptalking-general", sessionIdFor(Character(id = "")))
    }
}
