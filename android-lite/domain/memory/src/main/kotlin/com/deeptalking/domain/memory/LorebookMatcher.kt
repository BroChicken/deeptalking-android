package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin

/**
 * Lorebook selection, similarity merge/dedup and eviction helpers ported from
 * `src/js/memory/lorebook.js`. Pure and deterministic: inputs are never mutated.
 *
 * NOTE (SIMPLIFIED): the legacy entry carried `enabled`, `order`, `mentions`,
 * `lastMentionedAt`, `sourceMessageIds` and `evidence`. The native
 * [LorebookEntry] only keeps id/name/content/keywords/alwaysActive/origin/
 * memberName/timestamps/misses, so ordering falls back to always-active then
 * name, and eviction only tracks `misses`.
 */

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
)

/** Removes whitespace, quotes/brackets and punctuation so near names collide. */
fun normalizeLorebookName(value: String?): String =
    value.orEmpty().trim().lowercase()
        .replace(Regex("[\\s\\u3000]"), "")
        .replace(Regex("[「」『』“”‘’\"'《》〈〉（）()\\[\\]【】{}<>]"), "")
        .replace(Regex("[，。！？、；：,.!?;:·—_\\-]"), "")

private fun tokenizeForJaccard(value: String?): Set<String> {
    val text = value.orEmpty().lowercase()
    val tokens = mutableSetOf<String>()
    Regex("[a-z0-9_]+").findAll(text).forEach { tokens += it.value }
    Regex("[\\u4e00-\\u9fff]+").findAll(text).forEach { match ->
        val run = match.value
        for (index in run.indices) {
            tokens += run[index].toString()
            if (index + 2 <= run.length) tokens += run.substring(index, index + 2)
        }
    }
    return tokens
}

/** Token Jaccard similarity in `[0, 1]`. */
fun similarity(a: String?, b: String?): Double {
    val left = tokenizeForJaccard(a)
    val right = tokenizeForJaccard(b)
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
 * Selects entries for injection: always-active entries are unconditional, then
 * keyword hits over [recentText] (case-insensitive). Always-active entries sort
 * first, then by name. Applies the inject-entry cap and the char budget.
 */
fun select(
    entries: List<LorebookEntry>,
    recentText: String,
    limits: LorebookLimits = LorebookLimits(),
): List<LorebookEntry> {
    val haystack = recentText.lowercase()
    val hits = entries.filter { entry ->
        entry.alwaysActive || (
            haystack.isNotBlank() &&
                entry.keywords.any { keyword ->
                    val needle = keyword.trim().lowercase()
                    needle.isNotEmpty() && haystack.contains(needle)
                }
            )
    }.sortedWith(
        compareByDescending<LorebookEntry> { it.alwaysActive }.thenBy { it.name },
    )
    return applyCharCap(hits.take(limits.injectEntries), limits.injectChars)
}

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
