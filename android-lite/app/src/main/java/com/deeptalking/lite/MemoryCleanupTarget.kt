package com.deeptalking.lite

import com.deeptalking.core.common.AppLimits

/**
 * What the short-term "整理记忆" prompt sees right now.
 *
 * Only a window above the high watermark [AppLimits.Memory.SHORT_TERM] is a backlog: a
 * window sitting between the low and the high watermark (20..40) is normal operation and
 * is consumed by the ordinary per-turn analysis. [shortTermPending] therefore counts only
 * the overflow, never the normal window — the old `size - lowWater` count made a 21..40
 * window look like pending work and let the image cleanup trigger a needless short→long
 * analysis pass.
 *
 * Image cleanup is a separate flow (`NativeCore.pendingImageCleanupCount` /
 * `NativeCore.pruneOldMessageImages`) and never touches short-term memory.
 */
data class MemoryCleanupTarget(
    val characters: Int,
    val shortTermTotal: Int,
    val shortTermPending: Int,
    /** True when some character's short-term is over [AppLimits.Memory.SHORT_TERM]. */
    val overLimit: Boolean,
) {
    /** True when there is still a backlog to consume. */
    val hasWork: Boolean get() = overLimit

    companion object {
        /** Pure sizing rule shared by the prompt, the drain and the tests. */
        fun of(sizes: List<Int>): MemoryCleanupTarget {
            val high = AppLimits.Memory.SHORT_TERM
            return MemoryCleanupTarget(
                characters = sizes.count { it > high },
                shortTermTotal = sizes.sum(),
                shortTermPending = sizes.sumOf { (it - high).coerceAtLeast(0) },
                overLimit = sizes.any { it > high },
            )
        }
    }
}
