package com.deeptalking.lite

import com.deeptalking.domain.agent.background.MemoryTaskKind

/** Result of one manual memory retry; `noop` means there was nothing eligible to fold. */
data class MemoryRetryOutcome(
    val ok: Boolean,
    val noop: Boolean = false,
    val reason: String = "",
)

/** A failed extraction/analysis request waiting for the top-right retry affordance. */
data class MemoryRetryUi(
    val kind: MemoryTaskKind,
    val characterId: String,
    val characterName: String,
    val reason: String,
) {
    val title: String
        get() = when (kind) {
            MemoryTaskKind.Extraction -> "短期记忆生成失败"
            MemoryTaskKind.Analysis -> "长期记忆生成失败"
            else -> "记忆整理失败"
        }
}
