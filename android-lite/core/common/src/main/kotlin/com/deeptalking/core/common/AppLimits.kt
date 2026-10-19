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
         * relying on the provider default. A whole-window analysis reply echoes the
         * processed ids *and* carries the whole long-term payload; with no explicit
         * cap an over-long reply is silently truncated, the JSON fails to parse, and
         * the batch is discarded — which is exactly how analysis used to fail on every
         * pass while nothing was ever marked analyzed.
         */
        const val AUXILIARY_MAX_OUTPUT_TOKENS = 8_192
    }

    object Memory {
        /**
         * Instant (raw chat history) window — high/low watermark pair.
         *
         * The window grows append-only from [INSTANT_TRIM_FLOOR] to [INSTANT], is
         * folded into short-term memory in one whole-window pass at the high
         * watermark, and drops back to [INSTANT_TRIM_FLOOR]. Injecting the *whole*
         * window is what keeps the request prefix append-only in between folds (the
         * only cache-relevant cost is the single fold at the high watermark).
         */
        const val INSTANT = 60
        const val INSTANT_TRIM_FLOOR = 20

        /**
         * Emergency ceiling: only reached when the fold keeps failing, in which case
         * the oldest entries are dropped regardless of coverage and the reason is
         * reported through `counters.analysisLastError`. Without it a permanently
         * failing fold would grow the window without bound.
         */
        const val INSTANT_HARD_CAP = 120

        /**
         * Window size above which the window is a legacy/anomalous backlog: it is
         * compressed by the one-time migration (batched, run to completion, blocking
         * overlay) instead of the normal whole-window fold, and no positional trim is
         * applied to it, so a message no pass has read yet can never be dropped.
         */
        const val INSTANT_MIGRATION_THRESHOLD = INSTANT_HARD_CAP

        /**
         * Short-term window — the same high/low watermark shape as [INSTANT] (40 → 20).
         *
         * The window is consumed **by position**, never by per-entry bookkeeping:
         * everything older than the newest [SHORT_TERM_TRIM_FLOOR] entries is one batch,
         * it is analyzed and then dropped, and the newest floor entries stay as the
         * context the next batch (and the injected block) reads. Consumption therefore
         * needs no "已分析 / 已扫过" marker, and a failed pass loses nothing: the batch is
         * simply still there for the retry.
         */
        const val SHORT_TERM = 40
        const val SHORT_TERM_TRIM_FLOOR = 20

        /** Emergency ceiling for short-term (see [INSTANT_HARD_CAP]). */
        const val SHORT_TERM_HARD_CAP = 80

        /**
         * How many trailing short-term entries a new write may merge into. Keeping the
         * merge local means a write never rewrites an entry in the middle of the block,
         * so the injected window stays append-only between folds.
         */
        const val SHORT_TERM_MERGE_WINDOW = 5

        /**
         * Legacy hard per-category cap. No longer used to evict entries (long-term is
         * kept unbounded; only time decay removes events/promises), retained for
         * reference by callers that still want a bound.
         */
        const val LONG_TERM_PER_CATEGORY = 40
        const val PENDING_RECALL = 6

        /**
         * Short-term items one short→long analysis pass carries: the *whole* window
         * ([SHORT_TERM]), so the low-water leftovers are re-read as context and the
         * fold stays coherent. Only a backlog above the window (a stuck fold) makes a
         * pass carry less than everything.
         */
        const val ANALYSIS_BATCH = SHORT_TERM

        /** Instant messages one extract pass carries: the whole window ([INSTANT]). */
        const val SUMMARY_SOURCES = INSTANT

        /** Low-water mark: short-term is "consumed" once it settles at this size. */
        const val SHORT_TERM_LOW_WATER = SHORT_TERM_TRIM_FLOOR

        /**
         * Analysis passes the cleanup drain runs per invocation. One whole-window pass
         * already folds everything pending, so a single pass converges; extra passes
         * only matter for a backlog that is larger than the window itself.
         */
        const val CLEANUP_BATCHES_PER_RUN = 1

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

    /**
     * 基础设定字段的长度上限。设定要**精炼**：一个字段一件事，写成明确的简短陈述句。
     *
     * 写入路径（`cleanFieldValue`）按这里硬截断，所以模型自己改字段时也不会把设定
     * 越写越长（信息量大的背景故事单独放宽）。`limitFor` 只覆盖基础设定字段；动态状态
     * 等其它字段仍走各自原有的上限。
     */
    object StaticProfile {
        /** 短字段：性别/年龄/种族/语言/对用户的称呼（群成员另有"群内定位"）。 */
        const val SHORT_CHARS = 40

        /** 叙述字段：外貌特征/性格特征/恐惧弱点/说话风格。 */
        const val PROSE_CHARS = 120

        /** 背景故事：信息量最大，单独放宽，但同样要求只写人物经历本身。 */
        const val BACKGROUND_CHARS = 250

        private val SHORT_KEYS = setOf("gender", "age", "race", "language", "userAddress", "roleInGroup")

        /** 该静态字段的写入上限（非静态字段返回 null，交由调用方用自己的上限）。 */
        fun limitFor(key: String): Int? = when (key) {
            "background" -> BACKGROUND_CHARS
            in SHORT_KEYS -> SHORT_CHARS
            "appearance", "personality", "fears", "speakingStyle" -> PROSE_CHARS
            else -> null
        }
    }

    object Lorebook {
        const val ENTRIES = 200
        const val KEYWORDS_PER_ENTRY = 8
        const val NAME_CHARS = 60

        /**
         * One entry = one concept. Reference implementations (SillyTavern lorebooks)
         * keep entries at roughly 50–200 characters; 400 is the generous ceiling for
         * auto-generated entries, small enough that a normal hit set fits
         * [INJECT_CHARS] instead of the entries starving each other. The write path
         * rejects anything over it (never truncates), and anything already over it is
         * rewritten by the one-time lorebook rebuild.
         */
        const val CONTENT_CHARS = 400
        const val INJECT_ENTRIES = 6
        const val INJECT_CHARS = 1_400
        const val MAX_ALWAYS_ACTIVE = 6
        /** Max entries a single consolidation pass may add/update. */
        const val AUTO_ENTRIES_PER_PASS = 3

        /**
         * Hard ceiling on how many entries one capacity-driven eviction may archive. A normal
         * batch only ever needs [AUTO_ENTRIES_PER_PASS] at most; the cap exists so one
         * abnormal batch can never clear a world book in a single pass.
         */
        const val EVICT_MAX_PER_PASS = 4
    }

    object Todo {
        /** 已完成 / 已取消的条目在列表与存储里保留的天数，超过即自动清理。 */
        const val CLOSED_RETENTION_DAYS = 30

        /** 单条待办注入 volatile 上下文的条数上限（只注入仍未结束的）。 */
        const val INJECT_LIMIT = 4

        /** 注入摘要每行的字符上限。 */
        const val LINE_CHARS = 100

        /** 整个待办注入段的字符上限（含标题）。 */
        const val BLOCK_CHARS = 420
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
         *
         * Must be >= [Memory.INSTANT]: pruning a message that is still inside the
         * injected history window would rewrite an already-sent request prefix
         * mid-stable-period (a cache miss) and silently drop an image the model had
         * already seen.
         */
        const val IMAGE_RETENTION_MESSAGES = Memory.INSTANT

        /**
         * Imported images are re-encoded to this size/quality: they are only needed to
         * look back at the chat, and a backup can carry arbitrarily large payloads.
         */
        const val IMPORTED_IMAGE_DIMENSION = 512
        const val IMPORTED_IMAGE_QUALITY = 70

        /**
         * One thumbnail size/quality for everything that does not need the original:
         * the preview inlined for each archive image on export, and the archive images
         * that fall outside [IMAGE_RETENTION_MESSAGES] (shrunk in place, not deleted).
         * The full-resolution file only ever lives on the device for the newest window.
         */
        const val THUMBNAIL_DIMENSION = 384
        const val THUMBNAIL_QUALITY = 60
    }

    object History {
        /**
         * Max chat-archive rows kept per conversation. The archive (`history`) is a
         * plain bounded FIFO used only to look back at the chat; it never feeds the
         * memory window, which is maintained separately on the character.
         */
        const val ARCHIVE_MESSAGES = 400

        /** Messages rendered per page; older pages load when the list is scrolled to the top. */
        const val PAGE = 50
    }
}
