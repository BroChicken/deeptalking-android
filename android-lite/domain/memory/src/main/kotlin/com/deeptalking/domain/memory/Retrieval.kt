package com.deeptalking.domain.memory

import com.deeptalking.core.model.LongTermMemory

/**
 * Keyword relevance ranking over the long-term store, ported from
 * `retrieveRelevantMemories` in `src/js/memory/updates.js`.
 */

private val SEARCH_STOP_WORDS: Set<String> = setOf(
    "不知道", "知道", "不知", "什么", "怎么", "这个", "那个", "自己", "你的", "我的",
    "不是", "是的", "一次", "次了", "之前", "现在", "时候", "刚才", "谢谢", "好的", "用户", "角色",
    "know", "dont", "what", "this", "that", "please", "your", "with",
)

/** Tokenizes a query the same way the legacy `getSearchTerms` did (CJK bigrams + words). */
fun searchTerms(text: String?): List<String> {
    val normalized = text.orEmpty().lowercase()
    val terms = Regex("[a-z0-9_]{2,}|[\\u4e00-\\u9fff]{2,}").findAll(normalized).map { it.value }.toList()
    val expanded = mutableListOf<String>()
    for (term in terms) {
        expanded += term
        if (Regex("^[\\u4e00-\\u9fff]+$").matches(term) && term.length > 2) {
            for (index in 0 until term.length - 1) {
                expanded += term.substring(index, index + 2)
            }
        }
    }
    return expanded.distinct().filter { it !in SEARCH_STOP_WORDS }.take(80)
}

/**
 * Relevance score for a single memory: effective importance, keyword overlap on
 * key/value/tags and a recency bonus. Returns `0.0` when no term matches, so
 * [topK] naturally filters unrelated memories.
 */
fun score(query: String, memory: LongTermMemory, now: Long = System.currentTimeMillis()): Double {
    val terms = searchTerms(query)
    val searchable = "${memory.key} ${memory.value} ${memory.tags.joinToString(" ")}".lowercase()
    var matched = 0
    var total = computeEffectiveImportance(memory, now) * 0.45
    for (term in terms) {
        if (searchable.contains(term)) {
            total += if (term.length > 2) 4 else 2
            matched++
        }
    }
    if (matched == 0) return 0.0
    // Legacy `retrieveRelevantMemories`: a story-arc memory gets a flat +3 bonus.
    if (!memory.arcOf.isNullOrBlank()) total += 3.0
    val memoryTime = parseTimestampMillis(memory.eventTime)
        ?: parseTimestampMillis(memory.createdAt)
        ?: 0L
    val ageDays = if (memoryTime > 0L) {
        ((now - memoryTime).coerceAtLeast(0L)) / MILLIS_PER_DAY
    } else {
        9999.0
    }
    total += (2 - ageDays / 90).coerceAtLeast(0.0)
    return total
}

/** Top [k] memories by [score]; unrelated memories (score 0) are dropped. */
fun topK(
    query: String,
    memories: List<LongTermMemory>,
    k: Int,
    now: Long = System.currentTimeMillis(),
): List<LongTermMemory> {
    if (k <= 0) return emptyList()
    return memories
        .map { it to score(query, it, now) }
        .filter { it.second > 0.0 }
        .sortedByDescending { it.second }
        .take(k)
        .map { it.first }
}
