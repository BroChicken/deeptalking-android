package com.deeptalking.domain.memory

import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import java.time.Instant

/**
 * Evidence/source validation helpers ported from `src/js/memory/updates.js`.
 *
 * The legacy model carried `sourceRoles` and `userEvidence` on each short-term
 * entry; the native model reconstructs an id -> (role, text) table from
 * `character.instant` (live messages) and the short-term source ids. A source id
 * that cannot be resolved to a real message with a known role is rejected, which
 * is the whole point of these checks: automatic writes must be traceable to a
 * message the user actually sent.
 */

/** A resolved message source: its id, role (`user`/`assistant`) and raw text. */
data class SourceRef(val id: String, val role: String, val text: String)

private fun normalizeEvidenceText(value: String?): String =
    value.orEmpty().lowercase()
        .replace(Regex("[\\s\\u3000，。！？、；：,.!?;:'\"“”‘’（）()【】\\[\\]{}]"), "")

/** Verbatim (whitespace/punctuation-insensitive) containment of [evidence] in [source]. */
fun evidenceMatchesSource(source: String?, evidence: String?): Boolean {
    val sourceText = normalizeEvidenceText(source)
    val evidenceText = normalizeEvidenceText(evidence)
    return evidenceText.length >= 2 && sourceText.contains(evidenceText)
}

/**
 * Summarized evidence still needs multiple overlapping substantive fragments:
 * either verbatim containment, or >=2 shared 2-grams covering >=35% of the
 * evidence's distinct bigrams.
 */
fun evidenceMatchesSummary(source: String?, evidence: String?): Boolean {
    if (evidenceMatchesSource(source, evidence)) return true
    val ev = evidence.orEmpty().trim()
    val src = source.orEmpty().lowercase()
    if (ev.length < 4 || src.length < 4) return false
    val pairs = mutableListOf<String>()
    var matches = 0
    var i = 0
    while (i + 2 <= ev.length) {
        val pair = ev.substring(i, i + 2).lowercase()
        if (!Regex("^[\\u4e00-\\u9fff\\w]{2}$").matches(pair) || pair in pairs) {
            i++
            continue
        }
        pairs += pair
        if (src.contains(pair)) matches++
        i++
    }
    return matches >= 2 && matches.toDouble() / maxOf(1, pairs.size) >= 0.35
}

/** Builds the id -> source table for a character (live instant + short-term source ids). */
fun knownSources(character: Character): Map<String, SourceRef> {
    val sources = LinkedHashMap<String, SourceRef>()
    character.instant.forEach { message ->
        if (message.id.isNotEmpty() && !message.isLoading) {
            sources[message.id] = SourceRef(message.id, message.role.name.lowercase(), message.content)
        }
    }
    character.shortTerm.forEach { item ->
        item.sourceMessageIds.forEach { id ->
            if (!sources.containsKey(id)) {
                // Short-term sources no longer present in the live window keep an
                // unknown role, so they cannot satisfy a user-evidence check.
                sources[id] = SourceRef(id, "", item.content)
            }
        }
    }
    return sources
}

/** Resolves [sourceIds] (at most 8) to real sources, or null if any is unknown/role-less. */
fun resolveMemorySources(
    character: Character,
    sourceIds: List<String>,
    sources: Map<String, SourceRef> = knownSources(character),
): List<SourceRef>? {
    val ids = sourceIds.map { it.trim() }.filter { it.isNotEmpty() }.take(8)
    val resolved = ids.map { id -> sources[id] ?: return null }
    if (resolved.isEmpty() || resolved.any { it.role.isEmpty() }) return null
    return resolved
}

/** Resolves sources and requires at least one *user* source whose text contains [evidence]. */
fun hasValidUserEvidence(
    character: Character,
    sourceIds: List<String>,
    evidence: String?,
    sources: Map<String, SourceRef> = knownSources(character),
): List<SourceRef>? {
    val excerpt = evidence?.trimTo(300).orEmpty()
    if (excerpt.isEmpty()) return null
    val resolved = resolveMemorySources(character, sourceIds, sources) ?: return null
    if (resolved.none { it.role == "user" && evidenceMatchesSource(it.text, excerpt) }) return null
    return resolved
}

private val STATIC_FIELD_ALIASES: Map<String, Regex> = mapOf(
    "gender" to Regex("性别|gender"),
    "age" to Regex("年龄|岁|age"),
    "race" to Regex("种族|race|species"),
    "appearance" to Regex("外貌|长相|头发|衣服|appearance"),
    "personality" to Regex("性格|personality"),
    "values" to Regex("价值观|values"),
    "fears" to Regex("恐惧|弱点|害怕|fears"),
    "background" to Regex("背景|经历|background"),
    "keyEvents" to Regex("关键过往|里程碑|key.events"),
    "speakingStyle" to Regex("说话|语气|口吻|风格|speaking.style|tone"),
    "language" to Regex("语言|方言|中文|英文|language"),
    "userAddress" to Regex("叫我|称呼|喊我|call.me|address"),
)

private val EDIT_INTENT = Regex(
    "改|修改|设置|设定|换|叫我|喊我|别叫|不要|说话.{0,12}(点|些|一点)|用.{0,12}(说|讲)|请.{0,16}(说|讲)|change|set|call.me|speak|use ",
)

private val EDIT_INTENT_DENY = Regex("不要(?:修改|改变|改|换)|别(?:修改|改|换)|do not change|don't change", RegexOption.IGNORE_CASE)

/** True when a user source explicitly requests a change to [fieldKey]. */
fun hasStaticEditIntent(fieldKey: String, resolved: List<SourceRef>): Boolean {
    val alias = STATIC_FIELD_ALIASES[fieldKey] ?: return false
    return resolved.any { entry ->
        val text = entry.text
        if (EDIT_INTENT_DENY.containsMatchIn(text)) return@any false
        entry.role == "user" && alias.containsMatchIn(text.lowercase()) && EDIT_INTENT.containsMatchIn(text)
    }
}

/**
 * Resolves the sources backing a promise entry (legacy `resolvePromiseSources`).
 * A character promise may cite `current_response` (validated against
 * [assistantMessage]); otherwise the cited sources must resolve, with an
 * assistant message whose text contains [LongTermMemory.evidence]. Non-character
 * promises must be backed by real user evidence.
 */
fun resolvePromiseSources(
    character: Character,
    item: LongTermMemory,
    assistantMessage: ChatMessage?,
    sources: Map<String, SourceRef> = knownSources(character),
): List<SourceRef>? {
    val ids = item.sourceMessageIds.map { it.trim() }.filter { it.isNotEmpty() }.take(8)
    val evidence = item.evidence.trimTo(300)
    if (evidence.length < 2) return null
    if (item.promisor == "character") {
        if (ids.contains("current_response")) {
            val message = assistantMessage ?: return null
            if (!evidenceMatchesSummary(message.content, evidence)) return null
            return listOf(SourceRef(message.id, "assistant", message.content))
        }
        val resolvedAssistant = resolveMemorySources(character, item.sourceMessageIds, sources) ?: return null
        if (resolvedAssistant.none { it.role == "assistant" && it.text.contains(evidence) }) return null
        return resolvedAssistant
    }
    return hasValidUserEvidence(character, item.sourceMessageIds, evidence, sources)
}

/**
 * Resolves a dynamic-state update's sources (legacy `resolveDynamicStateSources`):
 * `current_response` alone maps to the assistant message when its content matches
 * [evidence]; everything else must satisfy the user-evidence rule.
 */
fun resolveDynamicStateSources(
    character: Character,
    sourceMessageIds: List<String>,
    evidence: String?,
    assistantMessage: ChatMessage?,
    sources: Map<String, SourceRef> = knownSources(character),
): List<SourceRef>? {
    val ids = sourceMessageIds.map { it.trim() }.filter { it.isNotEmpty() }.take(8)
    if (ids.contains("current_response")) {
        if (ids.size != 1) return null
        val message = assistantMessage ?: return null
        if (!evidenceMatchesSummary(message.content, evidence)) return null
        return listOf(SourceRef(message.id, "assistant", message.content))
    }
    return hasValidUserEvidence(character, ids, evidence, sources)
}

/**
 * Category/subject/evidence/role gate for automatic long-term writes
 * (legacy `isValidAutomaticMemory`). Model-authored entries must trace to a real
 * message with the right role; a `legacy`/`character` subject or missing evidence
 * is always rejected.
 *
 * SIMPLIFIED: the legacy `userEvidence`/`sourceRoles` short-term arrays are not
 * stored on [com.deeptalking.core.model.ShortTermMemory], so only sources still
 * present in `character.instant` can satisfy the user-evidence rule.
 */
fun isValidAutomaticMemory(
    character: Character,
    item: LongTermMemory,
    sources: Map<String, SourceRef> = knownSources(character),
    assistantMessage: ChatMessage? = null,
): Boolean {
    val subject = item.subject
    if (subject == MemorySubject.Legacy) return false
    val evidence = item.evidence.trimTo(300)
    if (evidence.length < 2) return false
    if (item.category == MemoryCategory.Promises) {
        if (item.promisor == "character") {
            if (subject == MemorySubject.User) return false
            return resolvePromiseSources(character, item, assistantMessage, sources) != null
        }
        if (subject == MemorySubject.Character) return false
        val promiseSources = resolveMemorySources(character, item.sourceMessageIds, sources) ?: return false
        if (promiseSources.none { it.text.contains(evidence) }) return false
        val promiseRoles = promiseSources.map { it.role }
        return (subject == MemorySubject.User && promiseRoles.all { it == "user" }) ||
            (subject == MemorySubject.Relationship && promiseRoles.contains("user"))
    }
    if (subject == MemorySubject.Character) return false
    val resolved = resolveMemorySources(character, item.sourceMessageIds, sources) ?: return false
    if (resolved.none { it.text.contains(evidence) }) return false
    val roles = resolved.map { it.role }
    val allUser = roles.all { it == "user" }
    val includesUser = roles.contains("user")
    return when (item.category) {
        MemoryCategory.UserProfile, MemoryCategory.Habits -> subject == MemorySubject.User && allUser
        MemoryCategory.Relationship -> subject == MemorySubject.Relationship && includesUser
        MemoryCategory.Events ->
            eventIdentity(item.eventTime).isNotEmpty() &&
                ((subject == MemorySubject.User && allUser) ||
                    ((subject == MemorySubject.Relationship || subject == MemorySubject.World) && includesUser))
        else -> false
    }
}

/** True when a short user turn is substantive enough to capture (legacy `shouldCaptureUserTurn`). */
fun shouldCaptureUserTurn(text: String): Boolean {
    val value = text.trim()
    if (value.length < 3) return false
    return !Regex("^(你好|您好|嗨|哈喽|早安|晚安|在吗|谢谢|好的|嗯+|哈哈+)[！!。.，,\\s]*$").matches(value)
}

// ---- semantic conflict + self-learned importance --------------------------------------

private fun tokenizeForCompare(text: String): List<String> {
    val normalized = text.lowercase().replace(Regex("[^\\w\\u4e00-\\u9fa5]"), " ")
    val terms = mutableListOf<String>()
    for (chunk in normalized.split(Regex("\\s+"))) {
        if (chunk.isEmpty()) continue
        if (!Regex("[\\u4e00-\\u9fa5]").containsMatchIn(chunk)) {
            terms += chunk
            continue
        }
        for (i in chunk.indices) {
            terms += chunk[i].toString()
            if (i + 2 <= chunk.length) terms += chunk.substring(i, i + 2)
        }
    }
    return terms
}

/**
 * Heuristic semantic conflict: below 35% token overlap (against the larger side)
 * means the two values are saying different things, so the incoming value must
 * not silently overwrite the existing one.
 */
fun memoriesSemanticallyDiffer(a: String?, b: String?): Boolean {
    val tokensA = tokenizeForCompare(a.orEmpty())
    val tokensB = tokenizeForCompare(b.orEmpty())
    if (tokensA.isEmpty() || tokensB.isEmpty()) return false
    val smaller = if (tokensA.size <= tokensB.size) tokensA else tokensB
    val larger = if (tokensA.size <= tokensB.size) tokensB else tokensA
    val hits = smaller.count { it in larger }
    return hits.toDouble() / larger.size < 0.35
}

/**
 * Adaptive importance (legacy `selfLearnMemoryImportance`): memories used within
 * 30 days get a bonus from cumulative usage (capped +3); userProfile/habits that
 * have not been recalled for a long time are down-weighted (never deleted).
 * Returns a character copy with recalculated `learnedBonus` values.
 */
fun selfLearnMemoryImportance(
    character: Character,
    nowMillis: Long = System.currentTimeMillis(),
    dayMillis: Long = 86_400_000L,
): Character {
    fun learn(item: LongTermMemory): LongTermMemory {
        var bonus = item.learnedBonus
        val lastUsageAt = parseZoned(item.lastUsageAt)?.toInstant()?.toEpochMilli() ?: 0L
        if (lastUsageAt > 0 && (nowMillis - lastUsageAt) < 30 * dayMillis) {
            val target = minOf(3, item.usageCount / 2)
            if (target > bonus) bonus = target
        }
        if (item.category == MemoryCategory.UserProfile || item.category == MemoryCategory.Habits) {
            val lastRef = parseZoned(item.lastRecalled)?.toInstant()?.toEpochMilli()
                ?: parseZoned(item.updatedAt)?.toInstant()?.toEpochMilli()
                ?: parseZoned(item.createdAt)?.toInstant()?.toEpochMilli() ?: 0L
            val daysSince = if (lastRef > 0) (nowMillis - lastRef).toDouble() / dayMillis else 999.0
            bonus = when {
                daysSince > 180 -> minOf(bonus, -3)
                daysSince > 90 -> minOf(bonus, -2)
                daysSince > 45 -> minOf(bonus, -1)
                else -> bonus
            }
        }
        bonus = bonus.coerceIn(-3, 3)
        return if (bonus != item.learnedBonus) {
            item.copy(learnedBonus = bonus, updatedAt = Instant.ofEpochMilli(nowMillis).toString())
        } else {
            item
        }
    }

    val updatedRoot = character.longTerm.map(::learn)
    val updatedMembers = character.members.map { member -> member.copy(longTerm = member.longTerm.map(::learn)) }
    return character.copy(longTerm = updatedRoot, members = updatedMembers)
}
