package com.deeptalking.core.common

/** Central limits ported from the legacy frontend constants. */
object AppLimits {
    const val STORAGE_KEY = "deeptalking_data_v1"

    object Api {
        const val AUXILIARY_TIMEOUT_MS = 90_000L
        const val REQUEST_METRICS = 60
        const val PREFIX_SNAPSHOTS = 12

        /**
         * Output cap for background/auxiliary calls, sent explicitly instead of
         * relying on the provider default. The short→long analysis reply must echo
         * every input id *and* carry the whole long-term payload; with no explicit cap
         * an over-long reply is silently truncated, the JSON fails to parse, and the
         * batch is discarded — which is exactly how analysis used to fail on every
         * pass while nothing was ever marked analyzed.
         */
        const val AUXILIARY_MAX_OUTPUT_TOKENS = 4_096
    }

    object Memory {
        const val INSTANT = 30
        const val INSTANT_TRIM_FLOOR = 10
        const val SHORT_TERM = 80
        const val SHORT_TERM_TRIM_FLOOR = 20

        /**
         * Legacy hard per-category cap. No longer used to evict entries (long-term is
         * kept unbounded; only time decay removes events/promises), retained for
         * reference by callers that still want a bound.
         */
        const val LONG_TERM_PER_CATEGORY = 40
        const val PENDING_RECALL = 6
        const val ANALYSIS_BATCH = 20
        const val SUMMARY_SOURCES = 20

        /**
         * Short-term items one short→long analysis request carries. Deliberately much
         * smaller than [ANALYSIS_BATCH]: the reply has to echo every input id and carry
         * the full long-term payload, so a large request is the one that overruns the
         * provider's output cap and gets truncated (see
         * [AppLimits.Api.AUXILIARY_MAX_OUTPUT_TOKENS]).
         */
        const val ANALYSIS_ITEM_LIMIT = 8

        /** Low-water mark: short-term is "consumed" once it settles at this size. */
        const val SHORT_TERM_LOW_WATER = SHORT_TERM_TRIM_FLOOR

        /**
         * Analysis batches the cleanup drain runs per invocation. Deliberately small
         * ("慢慢处理"): each batch converts up to [ANALYSIS_ITEM_LIMIT] short-term items
         * into long-term memory, so the backlog shrinks gradually instead of being
         * truncated away.
         */
        const val CLEANUP_BATCHES_PER_RUN = 5

        /**
         * Character-bigram Jaccard similarity at which two memory texts are treated
         * as near-duplicates and merged (deterministic pass).
         */
        const val NEAR_DUP_SIMILARITY = 0.72
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
        /** Max entries a single consolidation pass may add/update. */
        const val AUTO_ENTRIES_PER_PASS = 3
        /** Fresh short-term items that trigger a consolidation pass. */
        const val CONSOLIDATE_SPAN = 8
    }

    object Scene {
        /** Max scene summaries retained (legacy `slice(-8)`). */
        const val RETAIN = 8
        /** Max scene summaries injected (legacy `CONTEXT_BUDGET.sceneSummaries`). */
        const val SUMMARIES = 2
        /** Messages after the scene cursor needed to summarize. */
        const val SPAN = 12
        /** Minimum messages before a scene may be summarized. */
        const val MIN_MESSAGES = 6
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

        /**
         * How many recent messages keep their image attachments. Older messages
         * keep their text but lose the images (files are deleted), so history stays
         * readable while storage/export do not grow without bound.
         */
        const val IMAGE_RETENTION_MESSAGES = 50
    }
}
