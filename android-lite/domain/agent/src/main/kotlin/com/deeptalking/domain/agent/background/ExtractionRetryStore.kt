package com.deeptalking.domain.agent.background

import com.deeptalking.core.model.ChatMessage
import com.deeptalking.engine.ondevice.LlmRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * One short-term extraction request kept after a failure.
 *
 * The instant window is folded immediately after the request is started, so the batch is
 * no longer in `character.instant` when the request fails. Retrying therefore resends this
 * exact request instead of trying to reconstruct a batch from the folded window or from
 * history.
 */
data class PendingExtraction(
    val request: LlmRequest,
    val sourceMessages: List<ChatMessage>,
    val migration: Boolean = false,
)

/** In-memory pending extraction requests, shared by all per-turn [BackgroundTasks]. */
class ExtractionRetryStore {
    private val pending = ConcurrentHashMap<String, PendingExtraction>()

    fun get(characterId: String): PendingExtraction? = pending[characterId]

    fun put(characterId: String, value: PendingExtraction) {
        pending[characterId] = value
    }

    fun clear(characterId: String) {
        pending.remove(characterId)
    }
}
