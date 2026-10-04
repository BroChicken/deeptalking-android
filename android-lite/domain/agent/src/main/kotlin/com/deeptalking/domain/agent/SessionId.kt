package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character

/**
 * Stable per-character session id, mirroring the legacy `getSessionIdFor`
 * (`src/js/core/normalization.js`). The OpenCode Go gateway requires this to be
 * sent as `x-opencode-session` on every request; a missing header returns
 * `400 MissingSessionID`.
 */
fun sessionIdFor(character: Character?): String =
    character?.id?.takeIf { it.isNotBlank() }?.let { "deeptalking-$it" } ?: "deeptalking-general"
