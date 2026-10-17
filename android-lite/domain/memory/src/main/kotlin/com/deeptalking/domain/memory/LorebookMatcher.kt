package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import java.text.Collator
import java.util.Locale

/**
 * Lorebook selection, similarity-based replacement/dedup and eviction helpers
 * ported from `src/js/memory/lorebook.js`. Pure and deterministic: inputs are
 * never mutated.
 *
 * Ordering honours the legacy `order` field through [matchLorebookEntries]'s
 * `orderOf` selector; the native [LorebookEntry] does not carry `order` yet, so
 * callers with that data pass a selector and every other call keeps the legacy
 * default ([DEFAULT_LOREBOOK_ORDER]).
 */

/** Legacy entry `order` fallback when the field is absent (`normalizeLorebook`). */
const val DEFAULT_LOREBOOK_ORDER = 100

/** Tunable limits for lorebook matching/replacement. Defaults come from [AppLimits]. */
data class LorebookLimits(
    val injectEntries: Int = AppLimits.Lorebook.INJECT_ENTRIES,
    val injectChars: Int = AppLimits.Lorebook.INJECT_CHARS,
    val maxAlwaysActive: Int = AppLimits.Lorebook.MAX_ALWAYS_ACTIVE,
    val keywordsPerEntry: Int = AppLimits.Lorebook.KEYWORDS_PER_ENTRY,
    val contentChars: Int = AppLimits.Lorebook.CONTENT_CHARS,
    val nameSimilarity: Double = 0.5,
    val contentSimilarity: Double = 0.45,
    val evictionMisses: Int = 3,
    /** How many recent instant messages feed the keyword haystack (legacy `scanMessages`). */
    val scanMessages: Int = 6,
)

/** Removes whitespace, quotes/brackets and punctuation so near names collide. */
fun normalizeLorebookName(value: String?): String =
    value.orEmpty().trim().lowercase()
        .replace(Regex("[\\s\\u3000]"), "")
        .replace(Regex("[「」『』“”‘’\"'《》〈〉（）()\\[\\]【】{}<>]"), "")
        .replace(Regex("[，。！？、；：,.!?;:·—_\\-]"), "")

private val LOREBOOK_BIGRAM = Regex("^[\\u4e00-\\u9fff\\w]{2}$")

/**
 * Sliding 2-gram set over a normalized name (legacy `lorebookBigrams`): keeps a
 * pair only when both UTF-16 units are CJK or word characters.
 */
private fun lorebookBigrams(value: String?): Set<String> {
    val normalized = normalizeLorebookName(value)
    val pairs = mutableSetOf<String>()
    var index = 0
    while (index + 2 <= normalized.length) {
        val pair = normalized.substring(index, index + 2)
        if (LOREBOOK_BIGRAM.matches(pair)) pairs += pair
        index++
    }
    return pairs
}

/** Bigram Jaccard overlap in `[0, 1]` (legacy `bigramOverlapRatio`). */
fun similarity(a: String?, b: String?): Double {
    val left = lorebookBigrams(a)
    val right = lorebookBigrams(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val intersection = left.count { it in right }
    val union = left.size + right.size - intersection
    return if (union == 0) 0.0 else intersection.toDouble() / union
}

private fun normalizeKeywordList(values: List<String>): List<String> =
    values.map { normalizeLorebookName(it) }.filter { it.isNotEmpty() }

/** Whether two entries describe the same subject (name / shared keyword / content). */
fun lorebookEntriesSimilar(
    left: LorebookEntry?,
    right: LorebookEntry?,
    limits: LorebookLimits = LorebookLimits(),
): Boolean {
    if (left == null || right == null) return false
    val leftName = normalizeLorebookName(left.name)
    val rightName = normalizeLorebookName(right.name)
    if (leftName.isNotEmpty() && rightName.isNotEmpty()) {
        if (leftName == rightName) return true
        val nameScore = if (leftName.contains(rightName) || rightName.contains(leftName)) {
            1.0
        } else {
            similarity(leftName, rightName)
        }
        if (nameScore >= limits.nameSimilarity) return true
    }
    val leftKeywords = normalizeKeywordList(left.keywords)
    val rightKeywords = normalizeKeywordList(right.keywords)
    if (leftKeywords.isNotEmpty() && leftKeywords.any { it in rightKeywords }) return true
    if (left.content.isNotEmpty() && right.content.isNotEmpty() &&
        similarity(left.content, right.content) >= limits.contentSimilarity
    ) {
        return true
    }
    return false
}

/** First entry similar to [payload], or null. */
fun findSimilarLorebookEntry(
    list: List<LorebookEntry>,
    payload: LorebookEntry,
    limits: LorebookLimits = LorebookLimits(),
): LorebookEntry? = list.firstOrNull { lorebookEntriesSimilar(payload, it, limits) }

/**
 * Replaces the updateable fields of [existing] with the complete current content
 * of [incoming]. Updating a world-book entry is a **replacement**, never a merge:
 * [incoming]'s content is the entry's whole new body (it may be shorter than
 * before) and every sentence-appending / concatenating merge has been removed.
 * Only the fields [incoming] legitimately owns are taken; identity and
 * bookkeeping (`id`, `createdAt`, `mentions`, `lastMentionedAt`, `order`,
 * `memberName`) stay with [existing]. Returns a new entry; neither argument is
 * modified.
 *
 * - name: kept from [existing] unless it is empty, then [incoming]'s.
 * - content: [incoming]'s verbatim. `LorebookLimits.contentChars` is a *writing* rule
 *   enforced before the call (an over-long body is rejected, not cut) — see
 *   `upsertLorebookEntry`.
 * - keywords: [incoming]'s (deduped, capped); kept from [existing] when
 *   [incoming] carries none, so a replacement never becomes unhittable.
 * - alwaysActive: taken verbatim from [incoming] (it can flip back to false).
 * - `enabled = true`, `misses = 0`, `updatedAt = incoming.updatedAt ?: existing`.
 */
fun replaceLorebookEntry(
    existing: LorebookEntry,
    incoming: LorebookEntry,
    limits: LorebookLimits = LorebookLimits(),
): LorebookEntry = existing.copy(
    name = existing.name.ifEmpty { incoming.name },
    content = incoming.content,
    keywords = incoming.keywords.distinct().take(limits.keywordsPerEntry).ifEmpty { existing.keywords },
    alwaysActive = incoming.alwaysActive,
    enabled = true,
    misses = 0,
    updatedAt = incoming.updatedAt ?: existing.updatedAt,
)

/**
 * Applies the injection char budget. An entry that no longer fits is **skipped**
 * rather than stopping the pass, so one long entry can no longer starve every
 * shorter entry behind it. An individual line over [maxChars] is still truncated
 * to `maxChars + "…"` as a fallback, and the first hit is always attempted.
 */
private fun applyCharCap(hits: List<LorebookEntry>, maxChars: Int): List<LorebookEntry> {
    val used = mutableListOf<LorebookEntry>()
    var chars = 0
    for (entry in hits) {
        val name = entry.name.trim()
        val content = entry.content.trim()
        var line = "- " + (if (name.isNotEmpty()) "$name：" else "") + content
        if (line.length > maxChars) line = line.take(maxChars) + "…"
        if (chars + line.length > maxChars) continue
        chars += line.length
        used += entry
    }
    return used
}

/**
 * Raw hits from `matchLorebookEntries`: always-active entries are unconditional,
 * keyword entries match the haystack built from [query] plus the last
 * [LorebookLimits.scanMessages] of [recentMessages] (case-insensitive). A **body-less
 * placeholder** (an entry the user created but has not filled in yet) is the one
 * exception to the always-active rule: it is injected only while its own keywords are
 * on the table, which is exactly when the model can see it and fill it in. Sorted by
 * always-active, then [orderOf] ascending, then most recently updated first
 * (entries without an [LorebookEntry.updatedAt] sort last), then name as a final
 * tie-break, and capped at [LorebookLimits.injectEntries].
 *
 * The char budget is intentionally not applied here (legacy `buildLorebookLines`
 * does that); [select] wraps this and applies it.
 */
fun matchLorebookEntries(
    entries: List<LorebookEntry>,
    query: String,
    recentMessages: List<String> = emptyList(),
    limits: LorebookLimits = LorebookLimits(),
    orderOf: (LorebookEntry) -> Int = { DEFAULT_LOREBOOK_ORDER },
): List<LorebookEntry> {
    val parts = mutableListOf(query)
    recentMessages.takeLast(limits.scanMessages.coerceAtLeast(0)).forEach { parts += it }
    val haystack = parts.joinToString("\n").lowercase()
    val collator = Collator.getInstance(Locale.CHINA)
    val hits = entries.filter { entry ->
        if (!entry.enabled) return@filter false
        val keywordHit = haystack.isNotBlank() && entry.keywords.any { keyword ->
            val needle = keyword.trim().lowercase()
            needle.isNotEmpty() && haystack.contains(needle)
        }
        if (entry.content.isBlank()) return@filter keywordHit
        entry.alwaysActive || keywordHit
    }.sortedWith { a, b ->
        val alwaysDiff = (if (b.alwaysActive) 1 else 0) - (if (a.alwaysActive) 1 else 0)
        if (alwaysDiff != 0) {
            alwaysDiff
        } else {
            val orderDiff = orderOf(a) - orderOf(b)
            if (orderDiff != 0) {
                orderDiff
            } else {
                val updatedDiff = compareUpdatedAtDescending(a.updatedAt, b.updatedAt)
                if (updatedDiff != 0) updatedDiff else collator.compare(a.name, b.name)
            }
        }
    }
    return hits.take(limits.injectEntries)
}

/**
 * Most recently updated first; entries without a timestamp sort after every dated
 * one. ISO-8601 timestamps compare correctly as plain strings.
 */
private fun compareUpdatedAtDescending(a: String?, b: String?): Int = when {
    a == b -> 0
    a.isNullOrBlank() -> 1
    b.isNullOrBlank() -> -1
    else -> b.compareTo(a)
}

/**
 * Selects entries for injection and applies the char budget. Backwards-compatible
 * entry point for callers that only have the current query text.
 */
fun select(
    entries: List<LorebookEntry>,
    recentText: String,
    limits: LorebookLimits = LorebookLimits(),
): List<LorebookEntry> = select(entries, recentText, emptyList(), limits)

/**
 * Selects entries using both the current [query] and the [recentMessages] window
 * (legacy `matchLorebookEntries` scanning `char.memory.instant`).
 */
fun select(
    entries: List<LorebookEntry>,
    query: String,
    recentMessages: List<String>,
    limits: LorebookLimits = LorebookLimits(),
    orderOf: (LorebookEntry) -> Int = { DEFAULT_LOREBOOK_ORDER },
): List<LorebookEntry> =
    applyCharCap(matchLorebookEntries(entries, query, recentMessages, limits, orderOf), limits.injectChars)

/** Result of an eviction pass: entries to keep and entries retired this round. */
data class EvictionResult(
    val kept: List<LorebookEntry>,
    val removed: List<LorebookEntry>,
)

/** Miss count after one consolidation pass that did not mention the entry. */
fun nextMisses(entry: LorebookEntry): Int = entry.misses + 1

/** Whether an entry should be retired after a miss. Always-active/user entries never are. */
fun shouldEvict(entry: LorebookEntry, threshold: Int = LorebookLimits().evictionMisses): Boolean =
    entry.origin == LorebookOrigin.Model && !entry.alwaysActive && nextMisses(entry) >= threshold

/**
 * Evicts stale AI entries: model-origin, non-always-active entries whose miss
 * streak reaches [threshold]. Kept stale entries get their miss counter bumped.
 */
fun evictStaleLorebookEntries(
    entries: List<LorebookEntry>,
    threshold: Int = LorebookLimits().evictionMisses,
): EvictionResult {
    val kept = mutableListOf<LorebookEntry>()
    val removed = mutableListOf<LorebookEntry>()
    for (entry in entries) {
        if (entry.origin != LorebookOrigin.Model || entry.alwaysActive) {
            kept += entry
            continue
        }
        val bumped = entry.copy(misses = nextMisses(entry))
        if (bumped.misses >= threshold) removed += bumped else kept += bumped
    }
    return EvictionResult(kept = kept, removed = removed)
}
