package com.deeptalking.domain.agent.background

import com.deeptalking.core.model.ShortTermMemory
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory bookkeeping for a world-book pass that failed.
 *
 * The world-book pass reads a batch of short-term memories at the very moment the
 * long→term analysis is about to consume (and drop) them, so "just run it again later"
 * cannot recover a failure — the batch would be gone. The batch is therefore held here,
 * in memory, and re-fed on the next maintenance opportunity together with whatever the
 * next fold produced. After [maxAttempts] failures the batch stops being retried
 * silently and is handed to the UI ([BackgroundTasks.onLorebookRetryNeeded]), which asks
 * the user whether to retry once more or drop it; dropping is the user's call and is
 * reported when it happens, so nothing disappears without a trace.
 *
 * Deliberately in-memory: retrying across an app restart would require a persisted
 * "world-book scanned" marker on every short-term entry, and the failures this covers
 * (transient network/endpoint errors) resolve inside a single run.
 */
class LorebookRetryStore(private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS) {

    /** A held batch, how many times it has been attempted, and whether it awaits the user. */
    data class Pending(
        val batch: List<ShortTermMemory>,
        val attempts: Int,
        val awaitingUser: Boolean,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    fun get(characterId: String): Pending? = pending[characterId]

    /** The held batch, or empty when nothing is pending. */
    fun batch(characterId: String): List<ShortTermMemory> = pending[characterId]?.batch.orEmpty()

    /** True once the batch failed [maxAttempts] times and must not be retried silently. */
    fun awaitingUser(characterId: String): Boolean = pending[characterId]?.awaitingUser == true

    /** Records one more failure for [batch] and returns the new state. */
    fun recordFailure(characterId: String, batch: List<ShortTermMemory>): Pending {
        val attempts = (pending[characterId]?.attempts ?: 0) + 1
        val state = Pending(batch = batch, attempts = attempts, awaitingUser = attempts >= maxAttempts)
        pending[characterId] = state
        return state
    }

    /** Clears the held batch after a success. */
    fun clear(characterId: String) {
        pending.remove(characterId)
    }

    /** Drops the held batch (user chose not to retry) and returns what was dropped. */
    fun drop(characterId: String): List<ShortTermMemory> = pending.remove(characterId)?.batch.orEmpty()

    companion object {
        /** Failures before the user is asked (3 attempts ≈ three maintenance windows). */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
