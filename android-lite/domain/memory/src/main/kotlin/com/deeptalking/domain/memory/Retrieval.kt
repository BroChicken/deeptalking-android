package com.deeptalking.domain.memory

import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import kotlin.math.ln

/**
 * Keyword relevance ranking over the long-term store, ported from
 * `retrieveRelevantMemories` in `src/js/memory/updates.js`.
 *
 * **Deliberate deviation from the legacy scorer** (documented in
 * `docs/AGENT_ARCHITECTURE.md`): the legacy score added a flat +2/+4 per matching
 * term, so common filler words ("晚上", "事情") counted the same as a rare proper
 * noun and a single generic word could drag an unrelated memory into the top 5.
 * This version weights each matched term by inverse document frequency, by which
 * field it hit (tags > key > value) and by its length, then rewards *coverage*
 * (how much of the query a memory accounts for). Ranking and the inclusion floor
 * are shared by the automatic injection and the `search_memory` tool.
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

/** One scored retrieval hit: [score] ranks, [matchScore] is the term-only relevance. */
data class MemoryMatch(
    val memory: LongTermMemory,
    val score: Double,
    val matchScore: Double,
)

/**
 * Ranks [memories] against [query] with corpus-aware term weighting. Memories with
 * no matching term are omitted. Shared by [topK] and `search_memory`.
 */
fun rankMemories(
    query: String,
    memories: List<LongTermMemory>,
    now: Long = System.currentTimeMillis(),
): List<MemoryMatch> {
    val terms = searchTerms(query)
    if (terms.isEmpty() || memories.isEmpty()) return emptyList()

    // Pre-lowercase the searchable fields once; also used to compute document frequency.
    val fields = memories.map { memory ->
        val key = memory.key.lowercase()
        val value = memory.value.lowercase()
        val tags = memory.tags.joinToString(" ").lowercase()
        Triple(key, value, tags)
    }
    val corpus = memories.size
    val df = HashMap<String, Int>(terms.size)
    for (term in terms) {
        var count = 0
        for ((key, value, tags) in fields) {
            if (key.contains(term) || value.contains(term) || tags.contains(term)) count++
        }
        df[term] = count
    }

    val matches = ArrayList<MemoryMatch>(memories.size)
    for (index in memories.indices) {
        val memory = memories[index]
        val (key, value, tags) = fields[index]
        var matchScore = 0.0
        var matched = 0
        val idfDenominator = ln(1.0 + corpus.toDouble())
        for (term in terms) {
            val inTags = tags.contains(term)
            val inKey = key.contains(term)
            val inValue = value.contains(term)
            if (!inTags && !inKey && !inValue) continue
            val fieldWeight = if (inTags) 2.0 else if (inKey) 1.5 else 1.0
            // Normalized specificity in (0, 1]: 1.0 for a term unique to one memory,
            // shrinking toward ln2/ln(1+N) for a term shared across the whole corpus.
            val dfValue = (df[term] ?: 1).coerceAtLeast(1)
            val specificity = ln(1.0 + corpus.toDouble() / dfValue) / idfDenominator
            matchScore += SPECIFICITY_SCALE * specificity * fieldWeight * lengthFactor(term)
            matched++
        }
        if (matched == 0) continue
        val coverage = matched.toDouble() / terms.size
        val total = matchScore * (1.0 + 0.5 * coverage) +
            computeEffectiveImportance(memory, now) * 0.45 +
            (if (!memory.arcOf.isNullOrBlank()) ARC_BONUS else 0.0) +
            recencyBonus(memory, now)
        matches += MemoryMatch(memory, total, matchScore)
    }
    return matches.sortedByDescending { it.score }
}

/** Longer matches are more specific than a lone bigram. */
private fun lengthFactor(term: String): Double = when {
    term.length <= 2 -> 1.0
    term.length == 3 -> 1.4
    else -> 1.8
}

/** Legacy recency term: +2 for a fresh memory, fading to 0 by ~180 days. */
private fun recencyBonus(memory: LongTermMemory, now: Long): Double {
    val memoryTime = parseTimestampMillis(memory.eventTime)
        ?: parseTimestampMillis(memory.createdAt)
        ?: 0L
    val ageDays = if (memoryTime > 0L) {
        ((now - memoryTime).coerceAtLeast(0L)) / MILLIS_PER_DAY
    } else {
        9999.0
    }
    return (2.0 - ageDays / 90.0).coerceAtLeast(0.0)
}

/**
 * Top [k] memories for the automatic injection. Applies a relevance floor so a
 * single common word cannot pull an unrelated memory in, then diversifies (at most
 * one entry per event identity, at most [MAX_PER_CATEGORY] per category) so the
 * small injection budget is not filled by several near-identical beats. The floor
 * is skipped when it would empty an otherwise non-empty result, so recall never
 * regresses to nothing.
 */
fun topK(
    query: String,
    memories: List<LongTermMemory>,
    k: Int,
    now: Long = System.currentTimeMillis(),
): List<LongTermMemory> {
    if (k <= 0) return emptyList()
    val ranked = rankMemories(query, memories, now)
    if (ranked.isEmpty()) return emptyList()
    val aboveFloor = ranked.filter { it.matchScore >= MIN_MATCH_SCORE }
    val pool = if (aboveFloor.isNotEmpty()) aboveFloor else ranked

    val kept = ArrayList<LongTermMemory>(k)
    val seenEvents = HashSet<String>()
    val perCategory = HashMap<MemoryCategory, Int>()
    for (match in pool) {
        val memory = match.memory
        val identity = eventIdentity(memory.eventTime)
        if (identity.isNotEmpty() && !seenEvents.add(identity)) continue
        val used = perCategory[memory.category] ?: 0
        if (used >= MAX_PER_CATEGORY) continue
        perCategory[memory.category] = used + 1
        kept += memory
        if (kept.size >= k) break
    }
    return kept
}

/** Term-only relevance floor, in [SPECIFICITY_SCALE]-weighted units; a term shared
 * across a whole large corpus scores ~0.6, a term unique to one memory scores 4.0. */
const val MIN_MATCH_SCORE: Double = 1.5

/** Scale applied to a term's normalized specificity to form its base weight. */
private const val SPECIFICITY_SCALE: Double = 4.0

/** Diversity cap for the automatic injection: at most this many per category. */
const val MAX_PER_CATEGORY: Int = 3

/** Legacy `retrieveRelevantMemories`: a story-arc memory gets a flat bonus. */
private const val ARC_BONUS: Double = 3.0
