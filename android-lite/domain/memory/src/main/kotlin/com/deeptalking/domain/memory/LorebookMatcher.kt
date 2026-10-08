package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import java.text.Collator
import java.util.Locale

/**
 * Lorebook selection, similarity merge/dedup and eviction helpers ported from
 * `src/js/memory/lorebook.js`. Pure and deterministic: inputs are never mutated.
 *
 * Ordering honours the legacy `order` field through [matchLorebookEntries]'s
 * `orderOf` selector; the native [LorebookEntry] does not carry `order` yet, so
 * callers with that data pass a selector and every other call keeps the legacy
 * default ([DEFAULT_LOREBOOK_ORDER]).
 */

/** Legacy entry `order` fallback when the field is absent (`normalizeLorebook`). */
const val DEFAULT_LOREBOOK_ORDER = 100

/** Tunable limits for lorebook matching/merge. Defaults come from [AppLimits]. */
data class LorebookLimits(
    val injectEntries: Int = AppLimits.Lorebook.INJECT_ENTRIES,
    val injectChars: Int = AppLimits.Lorebook.INJECT_CHARS,
    val maxAlwaysActive: Int = AppLimits.Lorebook.MAX_ALWAYS_ACTIVE,
    val keywordsPerEntry: Int = AppLimits.Lorebook.KEYWORDS_PER_ENTRY,
    val contentChars: Int = AppLimits.Lorebook.CONTENT_CHARS,
    val nameSimilarity: Double = 0.5,
    val contentSimilarity: Double = 0.45,
    val mergeSentenceSimilarity: Double = 0.6,
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

private fun splitSentences(text: String): List<String> {
    val parts = text.split(Regex("(?<=[。！？!?；;\\n])"))
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Appends sentences from [incoming] that are not already present (verbatim or
 * near-duplicate) in [existing], truncated to [LorebookLimits.contentChars].
 */
fun mergeLorebookContent(
    existing: String?,
    incoming: String?,
    limits: LorebookLimits = LorebookLimits(),
): String {
    val base = existing.orEmpty().trim()
    val known = splitSentences(base).toMutableList()
    val added = mutableListOf<String>()
    for (sentence in splitSentences(incoming.orEmpty())) {
        if (sentence.length < 2 || base.contains(sentence)) continue
        val duplicate = (known + added).any { similarity(it, sentence) >= limits.mergeSentenceSimilarity }
        if (!duplicate) added += sentence
    }
    return (base + added.joinToString("")).trimTo(limits.contentChars)
}

/**
 * Merges [incoming] into [existing] (content, keywords, always-active flag).
 * Returns a new entry; neither argument is modified.
 */
fun mergeLorebookEntry(
    existing: LorebookEntry,
    incoming: LorebookEntry,
    limits: LorebookLimits = LorebookLimits(),
): LorebookEntry = existing.copy(
    content = mergeLorebookContent(existing.content, incoming.content, limits),
    keywords = (existing.keywords + incoming.keywords).distinct().take(limits.keywordsPerEntry),
    alwaysActive = existing.alwaysActive || incoming.alwaysActive,
    enabled = true,
    misses = 0,
    updatedAt = incoming.updatedAt ?: existing.updatedAt,
)

private fun applyCharCap(hits: List<LorebookEntry>, maxChars: Int): List<LorebookEntry> {
    val used = mutableListOf<LorebookEntry>()
    var chars = 0
    for (entry in hits) {
        val name = entry.name.trim()
        val content = entry.content.trim()
        var line = "- " + (if (name.isNotEmpty()) "$name：" else "") + content
        if (line.length > maxChars) line = line.take(maxChars) + "…"
        if (chars + line.length > maxChars) break
        chars += line.length
        used += entry
    }
    return used
}

/**
 * Raw hits from `matchLorebookEntries`: always-active entries are unconditional,
 * keyword entries match the haystack built from [query] plus the last
 * [LorebookLimits.scanMessages] of [recentMessages] (case-insensitive). Sorted by
 * always-active, then [orderOf], then name, and capped at [LorebookLimits.injectEntries].
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
        if (!entry.enabled || entry.content.isBlank()) return@filter false
        if (entry.alwaysActive) return@filter true
        if (haystack.isBlank()) return@filter false
        entry.keywords.any { keyword ->
            val needle = keyword.trim().lowercase()
            needle.isNotEmpty() && haystack.contains(needle)
        }
    }.sortedWith { a, b ->
        val alwaysDiff = (if (b.alwaysActive) 1 else 0) - (if (a.alwaysActive) 1 else 0)
        if (alwaysDiff != 0) {
            alwaysDiff
        } else {
            val orderDiff = orderOf(a) - orderOf(b)
            if (orderDiff != 0) orderDiff else collator.compare(a.name, b.name)
        }
    }
    return hits.take(limits.injectEntries)
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
