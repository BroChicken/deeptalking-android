package com.deeptalking.core.common

/** Central limits ported from the legacy frontend constants. */
object AppLimits {
    const val STORAGE_KEY = "deeptalking_data_v1"

    object Api {
        const val AUXILIARY_TIMEOUT_MS = 90_000L
        const val REQUEST_METRICS = 60
        const val PREFIX_SNAPSHOTS = 12
    }

    object Memory {
        const val INSTANT = 160
        const val INSTANT_TRIM_FLOOR = 40
        const val SHORT_TERM = 80
        const val SHORT_TERM_TRIM_FLOOR = 20
        const val LONG_TERM_PER_CATEGORY = 40
        const val PENDING_RECALL = 6
        const val ANALYSIS_BATCH = 40
        const val SUMMARY_SOURCES = 160
    }

    object Prompt {
        const val ROLE_CHARS = 8_000
        const val MEMBER_CHARS = 900
    }

    object Lorebook {
        const val ENTRIES = 200
        const val KEYWORDS_PER_ENTRY = 20
        const val NAME_CHARS = 60
        const val CONTENT_CHARS = 2_000
        const val INJECT_ENTRIES = 6
        const val INJECT_CHARS = 1_400
        const val MAX_ALWAYS_ACTIVE = 6
    }

    object Agent {
        const val MAX_TOOL_ROUNDS = 5
        const val MAX_TOOL_CALLS = 12
        const val TOOL_MEMORY_INJECT_LIMIT = 5
    }

    object Sticker {
        const val MAX_DIMENSION = 384
        const val TARGET_BYTES = 120 * 1024
    }

    object Media {
        const val MAX_IMAGE_DIMENSION = 1280
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}
