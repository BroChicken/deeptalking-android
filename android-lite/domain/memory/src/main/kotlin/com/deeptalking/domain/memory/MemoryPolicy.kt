package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemoryConflict
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.SourceEvidence
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Pure memory-housekeeping helpers ported from `src/js/memory/policy.js`.
 *
 * Every function returns new lists and never mutates its inputs. Several JS
 * branches depend on fields the native model does not carry yet (participants,
 * location, sourceRoles, userEvidence, learnedBonus, revision/analyzedAt);
 * those branches are documented as SIMPLIFIED below and degrade to the fields
 * available on [LongTermMemory] / [ShortTermMemory].
 */

internal const val MILLIS_PER_DAY = 86_400_000.0

/** Parses an ISO-8601 timestamp (offset, instant or local) to epoch millis. */
internal fun parseTimestampMillis(value: String?): Long? {
    val text = value.orEmpty().trim()
    if (text.isEmpty()) return null
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        .recoverCatching { Instant.parse(text).toEpochMilli() }
        .recoverCatching { LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        .getOrNull()
}

internal fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

/** Strips punctuation/symbols/whitespace and lowercases, so two phrasings compare on content alone. */
internal fun normalizeMemoryText(text: String): String =
    text.lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

/** Character bigrams of [text]; used as a cheap bag-of-features for similarity. */
internal fun charBigrams(text: String): Set<String> {
    if (text.length < 2) return if (text.isEmpty()) emptySet() else setOf(text)
    val set = HashSet<String>(text.length)
    for (index in 0 until text.length - 1) set.add(text.substring(index, index + 2))
    return set
}

/** Jaccard similarity of two texts over their normalized character bigrams (0.0–1.0). */
fun textSimilarity(a: String, b: String): Double {
    val na = normalizeMemoryText(a)
    val nb = normalizeMemoryText(b)
    if (na.isEmpty() || nb.isEmpty()) return 0.0
    if (na == nb) return 1.0
    val sa = charBigrams(na)
    val sb = charBigrams(nb)
    if (sa.isEmpty() || sb.isEmpty()) return 0.0
    val intersection = sa.count { it in sb }
    val union = sa.size + sb.size - intersection
    return if (union == 0) 0.0 else intersection.toDouble() / union
}

/** True when [a] and [b] describe the same fact closely enough to merge (deterministic pass). */
fun areNearDuplicate(a: String, b: String, threshold: Double = AppLimits.Memory.NEAR_DUP_SIMILARITY): Boolean =
    textSimilarity(a, b) >= threshold

/**
 * Effective importance after time decay. Ported from `computeEffectiveImportance`,
 * including the self-learned `learnedBonus` delta.
 */
fun computeEffectiveImportance(memory: LongTermMemory, now: Long = System.currentTimeMillis()): Double {
    val importance = clamp(memory.importance + memory.learnedBonus, 0, 10)
    val lastRef = parseTimestampMillis(memory.lastRecalled)
        ?: parseTimestampMillis(memory.updatedAt)
        ?: parseTimestampMillis(memory.createdAt)
        ?: 0L
    val daysSince = if (lastRef > 0L) {
        (now - lastRef).coerceAtLeast(0L) / MILLIS_PER_DAY
    } else {
        999.0
    }
    val decay = when {
        daysSince > 180 -> 0.1
        daysSince > 30 -> 1 - 0.9 * (daysSince - 30) / 150
        else -> 1.0
    }
    return importance * decay
}

/** Sort key mirroring `memorySortScore`, used by pruning and recall ordering. */
fun memorySortScore(memory: LongTermMemory, now: Long = System.currentTimeMillis()): Double {
    val importance = computeEffectiveImportance(memory, now)
    val recallCount = memory.recallCount
    val updatedAt = parseTimestampMillis(memory.updatedAt)
        ?: parseTimestampMillis(memory.createdAt)
        ?: 0L
    return importance * 1_000_000_000_000.0 + recallCount * 100_000_000_000.0 + updatedAt
}

/**
 * Applies the accumulated-conflict adjudication after an optional count trim.
 * Long-term memory is kept unbounded by default (`limit = Int.MAX_VALUE`), so
 * this only resolves conflicts; a caller that still wants a hard bound can pass
 * one explicitly. Embedding the adjudication here mirrors legacy
 * `trimCharacterMemory`, which called `resolveMemoryConflicts` after every trim
 * — so background writes resolve conflicts too, not only the main turn.
 */
fun pruneLongTerm(
    items: List<LongTermMemory>,
    limit: Int = Int.MAX_VALUE,
    now: Long = System.currentTimeMillis(),
): List<LongTermMemory> {
    val trimmed = if (items.size <= limit) items else items.sortedByDescending { memorySortScore(it, now) }.take(limit)
    return resolveMemoryConflicts(trimmed)
}

/**
 * Batch trim of the legacy instant window (legacy `trimCharacterMemory` instant
 * branch). At the high watermark it drops already-extracted messages FIFO down to
 * the low watermark; un-extracted messages are never dropped.
 */
fun trimInstant(
    items: List<ChatMessage>,
    limit: Int = AppLimits.Memory.INSTANT,
    floor: Int = AppLimits.Memory.INSTANT_TRIM_FLOOR,
): List<ChatMessage> {
    if (items.size < limit) return items
    var removeCount = (items.size - floor).coerceAtLeast(0)
    var removed = 0
    return items.filter { message ->
        if (removed < removeCount && message.extractedAt != null) {
            removed++
            false
        } else {
            true
        }
    }
}

/**
 * Trims short-term memory (legacy `trimShortTermList`): keep the newest [floor]
 * entries plus any older entry that either consumer has not yet acknowledged for
 * its current `revision`.
 */
fun trimShortTerm(
    items: List<ShortTermMemory>,
    limit: Int = AppLimits.Memory.SHORT_TERM,
    floor: Int = AppLimits.Memory.SHORT_TERM_TRIM_FLOOR,
): List<ShortTermMemory> {
    if (items.isEmpty()) return items
    val recentStart = (items.size - floor).coerceAtLeast(0)
    return items.filterIndexed { index, item ->
        val revision = if (item.revision == 0) 1 else item.revision
        val analyzedRevision = if (item.analyzedRevision == 0) 1 else item.analyzedRevision
        val scannedRevision = if (item.lorebookScannedRevision == 0) 1 else item.lorebookScannedRevision
        val analyzed = item.analyzedAt != null && analyzedRevision == revision
        val scanned = item.lorebookScannedAt != null && scannedRevision == revision
        index >= recentStart || !analyzed || !scanned
    }
}

/**
 * Legacy `ensureMessageSequences`: assign each instant message a monotonic
 * `sequence` from `counters.messageSequence` (never decreasing), persist the new
 * high-water mark, and derive `sceneState.startSequence` when unset.
 */
fun ensureMessageSequences(character: Character): Character {
    var last = 0
    var next = character.counters.messageSequence.coerceAtLeast(0)
    val instant = character.instant.map { message ->
        var sequence = message.sequence
        if (sequence <= last) sequence = maxOf(last, next) + 1
        last = sequence
        next = maxOf(next, sequence)
        message.copy(sequence = sequence)
    }
    var scene = character.sceneState ?: com.deeptalking.core.model.SceneState()
    if (scene.startSequence == null) {
        val scenes = character.scenes
        val end = scenes.lastOrNull()?.endedAt?.let { parseZoned(it)?.toInstant()?.toEpochMilli() }
        var anchor = 0
        if (end != null) {
            character.instant.forEach { message ->
                val time = parseZoned(message.timestamp)?.toInstant()?.toEpochMilli()
                if (time != null && time <= end) anchor = message.sequence
            }
        } else if (scene.startCount > 0 && scene.startCount <= character.instant.size) {
            anchor = character.instant[scene.startCount - 1].sequence
        }
        scene = scene.copy(startSequence = anchor)
    }
    scene = scene.copy(startSequence = scene.startSequence?.coerceIn(0, next) ?: 0)
    return character.copy(
        instant = instant,
        counters = character.counters.copy(messageSequence = next),
        sceneState = scene,
    )
}

/** Logical-day + time-slot identity for an event timestamp. */
fun eventIdentity(eventTime: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    val zoned = parseZoned(eventTime, zone) ?: return ""
    val day = logicalDay(zoned)
    return "${day.year}年${day.monthValue}月${day.dayOfMonth}日[${getTimeSlot(zoned.toLocalTime())}]"
}

/** Normalizes an ISO timestamp to an Instant string, falling back when unparseable. */
internal fun normalizeTimestampIso(value: String?, fallback: String?): String {
    parseZoned(value)?.let { return it.toInstant().toString() }
    parseZoned(fallback)?.let { return it.toInstant().toString() }
    return fallback.orEmpty()
}

/** Trims/sorts/dedupes event participants (legacy `normalizeParticipants`). */
fun normalizeParticipantsList(values: List<String>): List<String> =
    values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8).sorted()

/** Generates a memory id (legacy `createMemoryId`). */
internal fun defaultMemoryId(prefix: String, nowMillis: Long = System.currentTimeMillis()): String =
    prefix + "_" + nowMillis + "_" + Random.nextInt(100_000, 1_000_000)

/**
 * Decay filter mirroring `applyMemoryDecay`:
 * - events: forgotten once older than `30 + importance * 15` days;
 * - promises: resolved/cancelled after 90 idle days, overdue active promises
 *   after 30 idle days;
 * - all other categories are long-lived and never auto-removed.
 */
private fun isMemoryDecayed(item: LongTermMemory, category: MemoryCategory, now: Long): Boolean {
    if (category != MemoryCategory.Events && category != MemoryCategory.Promises) return false
    val lastRef = parseTimestampMillis(item.lastRecalled)
        ?: parseTimestampMillis(item.updatedAt)
        ?: parseTimestampMillis(item.createdAt)
        ?: 0L
    val daysSince = if (lastRef > 0L) {
        ((now - lastRef).coerceAtLeast(0L)) / MILLIS_PER_DAY
    } else {
        9999.0
    }
    return when (category) {
        MemoryCategory.Events -> {
            val maxAge = 30.0 + clamp(item.importance, 0, 10) * 15
            daysSince >= maxAge
        }
        MemoryCategory.Promises -> {
            if (item.status != PromiseStatus.Active) {
                daysSince >= 90
            } else {
                val due = parseTimestampMillis(item.dueAt) ?: 0L
                due > 0L && due < now && daysSince > 30
            }
        }
        else -> false
    }
}

fun decayLongTerm(
    items: List<LongTermMemory>,
    category: MemoryCategory,
    now: Long = System.currentTimeMillis(),
): List<LongTermMemory> = items.filterNot { isMemoryDecayed(it, category, now) }

/**
 * Forgets aged-out memories across every category (legacy `applyMemoryDecay`):
 * events past `30 + importance * 15` days, stale resolved/overdue promises;
 * `userProfile` / `relationship` / `habits` are long-lived assets and are only
 * down-weighted, never deleted.
 */
fun applyMemoryDecay(character: Character, now: Long = System.currentTimeMillis()): Character {
    fun decay(list: List<LongTermMemory>): List<LongTermMemory> =
        list.filterNot { isMemoryDecayed(it, it.category, now) }
    return character.copy(
        longTerm = decay(character.longTerm),
        members = character.members.map { it.copy(longTerm = decay(it.longTerm)) },
    )
}

private fun earlierTimestamp(a: String?, b: String?): String? {
    val aTime = parseTimestampMillis(a)
    val bTime = parseTimestampMillis(b)
    if (aTime == null) return b ?: a
    if (bTime == null) return a ?: b
    return if (aTime <= bTime) a else b
}

private fun mergeTimelineValue(base: LongTermMemory, incoming: LongTermMemory): String {
    val baseTime = parseTimestampMillis(base.eventTime) ?: 0L
    val incomingTime = parseTimestampMillis(incoming.eventTime) ?: 0L
    val first = if (baseTime <= incomingTime) base else incoming
    val second = if (baseTime <= incomingTime) incoming else base
    return first.value + "\n" + second.value
}

/**
 * Merges [incoming] into [existing] for one category. Non-event categories keep
 * the newer value; events concatenate their timelines. Returns a new value.
 */
fun mergeLongTerm(
    existing: LongTermMemory,
    incoming: LongTermMemory,
    category: MemoryCategory,
): LongTermMemory {
    val existingTime = parseTimestampMillis(existing.updatedAt)
        ?: parseTimestampMillis(existing.createdAt)
        ?: 0L
    val incomingTime = parseTimestampMillis(incoming.updatedAt)
        ?: parseTimestampMillis(incoming.createdAt)
        ?: 0L

    var merged = existing
    if (category == MemoryCategory.Events) {
        merged = merged.copy(
            value = mergeTimelineValue(existing, incoming),
            eventTime = earlierTimestamp(existing.eventTime, incoming.eventTime),
            participants = (existing.participants + incoming.participants).distinct().take(8),
            location = mergeLocations(existing.location, incoming.location),
        )
    } else if (incomingTime >= existingTime) {
        if (existing.conflictedAt != null || incoming.conflictedAt != null) {
            val conflicts = existing.conflicts.take(4).toMutableList()
            if (incoming.value != existing.value && conflicts.none { it.value == incoming.value }) {
                conflicts += MemoryConflict(
                    value = incoming.value,
                    evidence = incoming.evidence,
                    sourceMessageIds = incoming.sourceMessageIds,
                    at = incoming.updatedAt ?: incoming.createdAt,
                )
            }
            merged = merged.copy(
                conflicts = conflicts,
                conflictedAt = existing.conflictedAt ?: incoming.conflictedAt
                    ?: incoming.updatedAt ?: existing.updatedAt,
                sourceMessageIds = (existing.sourceMessageIds + incoming.sourceMessageIds).distinct().take(8),
            )
        } else {
            merged = merged.copy(
                value = incoming.value.ifEmpty { existing.value },
                subject = incoming.subject,
                sourceMessageIds = incoming.sourceMessageIds,
                sourceRoles = incoming.sourceRoles,
                evidence = incoming.evidence.ifEmpty { existing.evidence },
                status = incoming.status,
                dueAt = incoming.dueAt ?: existing.dueAt,
                updatedAt = incoming.updatedAt ?: incoming.createdAt ?: existing.updatedAt,
            )
        }
    }
    return merged.copy(
        tags = (existing.tags + incoming.tags).distinct().take(8),
        importance = maxOf(existing.importance, incoming.importance),
    )
}

/** Unions two user-evidence lists, de-duplicating identical id/text pairs (legacy `mergeUserEvidence`). */
private fun mergeUserEvidence(
    existing: List<SourceEvidence>,
    incoming: List<SourceEvidence>,
): List<SourceEvidence> = (existing + incoming).distinct().take(8)

private fun mergeLocations(a: String, b: String): String {
    val set = listOf(a.trim().take(160), b.trim().take(160)).filter { it.isNotEmpty() }.distinct()
    return set.joinToString("、").ifEmpty { "未说明" }
}

/**
 * Local dedup mirroring `dedupeLongTermList`. Identity is the lowercased key for
 * regular categories and logical event identity + key for events (the JS
 * participants/location parts are unavailable, noted SIMPLIFIED). Duplicates are
 * merged into the first occurrence; the input list is not modified.
 */
fun dedupeLongTerm(items: List<LongTermMemory>, category: MemoryCategory): List<LongTermMemory> {
    val result = mutableListOf<LongTermMemory>()
    val seen = mutableMapOf<String, LongTermMemory>()
    for (item in items) {
        val keyPart = item.key.trim().lowercase()
        val identity = if (category == MemoryCategory.Events) {
            "${eventIdentity(item.eventTime)}:$keyPart"
        } else {
            keyPart
        }
        if (identity.isEmpty()) {
            result += item
            continue
        }
        val existing = seen[identity]
        if (existing == null) {
            seen[identity] = item
            result += item
            continue
        }
        val merged = mergeLongTerm(existing, item, category)
        val index = result.indexOf(existing)
        if (index >= 0) result[index] = merged
        seen[identity] = merged
    }
    return result
}

// ---- short-term writes ----------------------------------------------------------------

/**
 * One-time deterministic repair of an already-loaded store: short-term reconciled +
 * near-duplicate merged, each long-term category de-duplicated. Idempotent and
 * offline (no LLM); used by the `memoryRepairVersion` startup migration to fold
 * the accumulated duplicates left by earlier re-extraction loops.
 */
fun repairMemories(character: Character): Character {
    fun repairLong(list: List<LongTermMemory>): List<LongTermMemory> =
        MemoryCategory.entries.flatMap { category ->
            dedupeLongTerm(list.filter { it.category == category }, category)
        }
    return character.copy(
        shortTerm = dedupeShortTerm(character.shortTerm),
        longTerm = repairLong(character.longTerm),
        members = character.members.map {
            it.copy(shortTerm = dedupeShortTerm(it.shortTerm), longTerm = repairLong(it.longTerm))
        },
    )
}

/** Current target of the one-time deterministic memory repair. */
const val MEMORY_REPAIR_VERSION = 1

/**
 * Deterministic near-duplicate pass over short-term memory: items sharing an event
 * identity (or whose content is a near-duplicate of an earlier item) collapse into
 * the first occurrence, unioning their sources/evidence. Unlike [reconcileLegacyMemories]
 * this also merges re-phrasings that share no exact identity, so repeated extraction
 * of the same beat cannot pile up.
 */
fun dedupeShortTerm(items: List<ShortTermMemory>): List<ShortTermMemory> {
    val result = mutableListOf<ShortTermMemory>()
    for (item in items) {
        val identity = eventIdentity(item.eventTime)
        val dupIndex = result.indexOfFirst { existing ->
            val sameIdentity = identity.isNotEmpty() && eventIdentity(existing.eventTime) == identity
            sameIdentity || areNearDuplicate(existing.content, item.content)
        }
        if (dupIndex < 0) {
            result += item
            continue
        }
        val existing = result[dupIndex]
        result[dupIndex] = existing.copy(
            content = if (item.content.length > existing.content.length) item.content else existing.content,
            sourceMessageIds = (existing.sourceMessageIds + item.sourceMessageIds).distinct()
                .takeLast(AppLimits.Memory.SUMMARY_SOURCES),
            sourceRoles = (existing.sourceRoles + item.sourceRoles).distinct(),
            userEvidence = (existing.userEvidence + item.userEvidence)
                .distinctBy { it.sourceMessageId to it.text },
            participants = normalizeParticipantsList(existing.participants + item.participants),
            location = if (item.location.isNotBlank()) item.location else existing.location,
            eventTime = earlierTimestamp(existing.eventTime, item.eventTime) ?: existing.eventTime,
            createdAt = earlierTimestamp(existing.createdAt, item.createdAt) ?: existing.createdAt,
            // Merged content invalidates the prior analysis, so force a re-analysis.
            analyzedAt = null,
        )
    }
    return result
}

/** Input to [addShortTermMemory] (legacy `sourceData`). */
data class ShortTermDraft(
    val content: String,
    val sourceMessageIds: List<String> = emptyList(),
    val eventTime: String? = null,
    val participants: List<String> = emptyList(),
    val location: String = "",
)

/** Result of a short-term write: the new list and whether it was accepted. */
data class ShortTermAddResult(val list: List<ShortTermMemory>, val accepted: Boolean)

/**
 * Adds one short-term entry with the legacy source/evidence/event-identity rules
 * (`addShortTermMemory`): every cited source must resolve, the event identity
 * merges into an existing item, and relative time wording is normalized against
 * the event time. Source roles and verbatim user evidence are captured from the
 * resolved sources, and merged when the write folds into an existing item.
 */
fun addShortTermMemory(
    list: List<ShortTermMemory>,
    draft: ShortTermDraft,
    timestamp: String?,
    sources: Map<String, SourceRef>,
    nowMillis: Long = System.currentTimeMillis(),
): ShortTermAddResult {
    var text = draft.content.trim()
    if (text.isEmpty()) return ShortTermAddResult(list, false)
    val sourceIds = draft.sourceMessageIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .take(AppLimits.Memory.SUMMARY_SOURCES)
    if (sourceIds.isEmpty() || sourceIds.any { !sources.containsKey(it) }) return ShortTermAddResult(list, false)
    val sourceRoles = sourceIds.map { sources.getValue(it).role }
    val userEvidence = sourceIds.mapNotNull { id ->
        val source = sources.getValue(id)
        if (source.role == "user") SourceEvidence(sourceMessageId = id, text = source.text.trimTo(300)) else null
    }
    val nowIso = Instant.ofEpochMilli(nowMillis).toString()
    val eventTime = normalizeTimestampIso(draft.eventTime, timestamp ?: nowIso)
    val base = parseZoned(eventTime) ?: parseZoned(timestamp) ?: parseZoned(nowIso) ?: return ShortTermAddResult(list, false)
    text = parseRelativeText(text, base)
    val participants = normalizeParticipantsList(draft.participants)
    val location = draft.location.trim().take(160)
    val identity = eventIdentity(eventTime)
    if (identity.isNotEmpty()) {
        val existingIndex = list.indexOfFirst { item ->
            eventIdentity(item.eventTime) == identity &&
                (item.content == text || areNearDuplicate(item.content, text))
        }
        if (existingIndex >= 0) {
            val existing = list[existingIndex]
            val mergedIds = (existing.sourceMessageIds + sourceIds).distinct()
                .takeLast(AppLimits.Memory.SUMMARY_SOURCES)
            val mergedRoles = mergedIds.map { sources[it]?.role.orEmpty() }
            val mergedEvidence = (existing.userEvidence + userEvidence).distinct()
                .filter { it.sourceMessageId in mergedIds }
                .takeLast(AppLimits.Memory.SUMMARY_SOURCES)
            val updated = list.toMutableList()
            updated[existingIndex] = existing.copy(
                sourceMessageIds = mergedIds,
                sourceRoles = mergedRoles,
                userEvidence = mergedEvidence,
                createdAt = timestamp ?: existing.createdAt,
            )
            return ShortTermAddResult(updated, true)
        }
    }
    val item = ShortTermMemory(
        id = defaultMemoryId("short", nowMillis),
        content = text,
        sourceMessageIds = sourceIds,
        eventTime = eventTime,
        createdAt = timestamp ?: nowIso,
        analyzedAt = null,
        participants = participants,
        location = location,
        sourceRoles = sourceRoles,
        userEvidence = userEvidence,
    )
    return ShortTermAddResult(trimShortTerm(list + item), true)
}

// ---- long-term upsert -----------------------------------------------------------------

/**
 * Validated long-term upsert (legacy `upsertLongTermMemory`): rejects anything
 * failing [isValidAutomaticMemory], resolves the promise/source rules, normalizes
 * key/value time wording, then merges or appends.
 *
 * SIMPLIFIED: semantic conflicts are preserved inline (see [mergeConflictValue])
 * because [LongTermMemory] has no `conflicts` / `conflictedAt` fields.
 */
fun upsertLongTermMemory(
    character: Character,
    item: LongTermMemory,
    store: List<LongTermMemory>,
    sources: Map<String, SourceRef> = knownSources(character),
    assistantMessage: ChatMessage? = null,
    nowMillis: Long = System.currentTimeMillis(),
    newId: () -> String = { defaultMemoryId("mem", nowMillis) },
): Pair<List<LongTermMemory>, Boolean> {
    if (!isValidAutomaticMemory(character, item, sources, assistantMessage)) return store to false
    val characterPromise = item.category == MemoryCategory.Promises && item.promisor == "character"
    val resolvedSources = if (characterPromise) {
        resolvePromiseSources(character, item, assistantMessage, sources)
    } else {
        resolveMemorySources(character, item.sourceMessageIds, sources)
    }
    if (resolvedSources.isNullOrEmpty()) return store to false

    val nowIso = Instant.ofEpochMilli(nowMillis).toString()
    val base = parseZoned(item.eventTime) ?: parseZoned(nowIso) ?: return store to false
    val key = parseRelativeText(item.key.trimTo(100), base)
    val value = parseRelativeText(item.value.trim(), base)
    if (key.isBlank() || value.isBlank()) return store to false

    val eventTime = normalizeTimestampIso(item.eventTime, nowIso)
    val participants = normalizeParticipantsList(item.participants)
    val location = item.location.trim().take(160)
    val identity = if (item.category == MemoryCategory.Events) eventIdentity(eventTime) else ""
    // Exact key+identity wins; otherwise fold into a same-category near-duplicate
    // (key similarity for regular categories, event identity for events) so
    // re-phrasings of one fact do not accumulate as separate entries.
    val index = store.indexOfFirst { memory ->
        memory.key.trim().lowercase() == key.lowercase() &&
            (identity.isEmpty() || eventIdentity(memory.eventTime) == identity)
    }
    val target = if (index >= 0) {
        index
    } else {
        store.indexOfFirst { memory ->
            memory.category == item.category && memory.subject == item.subject &&
                if (item.category == MemoryCategory.Events) {
                    identity.isNotEmpty() && eventIdentity(memory.eventTime) == identity
                } else {
                    areNearDuplicate(memory.key, key)
                }
        }
    }
    if (item.category == MemoryCategory.Promises && target >= 0 && store[target].status != PromiseStatus.Active) {
        return store to false
    }

    val importance = if (item.importance == 0) 5 else clamp(item.importance, 0, 10)
    val tags = item.tags.map { it.trim().take(30) }.filter { it.isNotEmpty() }.distinct().take(8)
    val dueAt = if (item.category == MemoryCategory.Promises) {
        parseTimestampMillis(item.dueAt)?.let { Instant.ofEpochMilli(it).toString() }
    } else {
        null
    }
    val resolvedIds = item.sourceMessageIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)

    val updated = store.toMutableList()
    if (target >= 0) {
        val existing = store[target]
        updated[target] = if (memoriesSemanticallyDiffer(value, existing.value)) {
            // 语义冲突：不静默覆盖，保留双方证据并标记，交由 resolveMemoryConflicts 裁决
            val conflicts = existing.conflicts.take(4).toMutableList()
            if (conflicts.none { it.value == value }) {
                conflicts += MemoryConflict(
                    value = value,
                    evidence = item.evidence.trimTo(300),
                    sourceMessageIds = resolvedIds.take(8),
                    at = nowIso,
                )
            }
            existing.copy(
                tags = (existing.tags + tags).distinct().take(8),
                importance = maxOf(existing.importance, importance),
                sourceMessageIds = (existing.sourceMessageIds + resolvedIds).distinct().take(8),
                conflicts = conflicts,
                conflictedAt = existing.conflictedAt ?: nowIso,
                updatedAt = eventTime.ifBlank { nowIso },
                arcOf = item.arcOf ?: existing.arcOf,
                arcStage = item.arcStage ?: existing.arcStage,
            )
        } else {
            existing.copy(
                value = value,
                tags = (existing.tags + tags).distinct().take(8),
                importance = maxOf(existing.importance, importance),
                subject = item.subject,
                sourceMessageIds = resolvedIds,
                sourceRoles = resolvedSources.map { it.role },
                evidence = item.evidence.trimTo(300),
                eventTime = eventTime.ifBlank { existing.eventTime ?: nowIso },
                participants = if (item.category == MemoryCategory.Events) participants else existing.participants,
                location = if (item.category == MemoryCategory.Events) location else existing.location,
                status = if (item.category == MemoryCategory.Promises) PromiseStatus.Active else existing.status,
                dueAt = if (item.category == MemoryCategory.Promises) (dueAt ?: existing.dueAt) else existing.dueAt,
                promisor = if (item.category == MemoryCategory.Promises) item.promisor?.take(40) else existing.promisor,
                promisee = if (item.category == MemoryCategory.Promises) item.promisee?.take(40) else existing.promisee,
                updatedAt = eventTime.ifBlank { nowIso },
                conflictedAt = null,
                conflicts = emptyList(),
                arcOf = item.arcOf ?: existing.arcOf,
                arcStage = item.arcStage ?: existing.arcStage,
            )
        }
    } else {
        updated += LongTermMemory(
            id = newId(),
            category = item.category,
            subject = item.subject,
            key = key,
            value = value,
            tags = tags,
            importance = importance,
            sourceMessageIds = resolvedIds,
            evidence = item.evidence.trimTo(300),
            eventTime = eventTime.ifBlank { nowIso },
            participants = if (item.category == MemoryCategory.Events) participants else emptyList(),
            location = if (item.category == MemoryCategory.Events) location else "",
            dueAt = dueAt,
            promisor = item.promisor?.take(40),
            promisee = item.promisee?.take(40),
            status = PromiseStatus.Active,
            arcOf = item.arcOf,
            arcStage = item.arcStage,
            recordedAt = nowIso,
            createdAt = eventTime.ifBlank { nowIso },
            updatedAt = eventTime.ifBlank { nowIso },
        )
    }
    return pruneLongTerm(updated, now = nowMillis) to true
}

// ---- conflict resolution --------------------------------------------------------------

/**
 * Legacy `consumeInjectedRecalls`: remove ONLY the injected recall ids from the
 * persisted request and clear the injected-id marker.
 */
fun consumeInjectedRecalls(character: Character, injectedIds: List<String>): Character {
    val ids = injectedIds.toSet()
    val remaining = if (ids.isEmpty()) character.pendingRecall else character.pendingRecall.filterNot { it.id in ids }
    return character.copy(pendingRecall = remaining, lastInjectedRecallIds = emptyList())
}

/** One side of a memory conflict (legacy conflict candidate). */
data class MemoryConflictCandidate(
    val value: String,
    val evidence: String,
    val sourceMessageIds: List<String>,
    val updatedAt: String,
    val isMain: Boolean,
)

/**
 * Winner rule from `resolveMemoryConflicts`: newest `updatedAt` wins, then longer
 * evidence, then the primary value on a tie.
 */
fun resolveMemoryConflict(candidates: List<MemoryConflictCandidate>): MemoryConflictCandidate? =
    candidates.sortedWith(
        compareByDescending<MemoryConflictCandidate> { parseTimestampMillis(it.updatedAt) ?: 0L }
            .thenByDescending { it.evidence.length }
            .thenBy { if (it.isMain) 0 else 1 },
    ).firstOrNull()

/**
 * Adjudicates accumulated conflicts (`updates.js:640-673`): for each memory with
 * competing values AND a `conflictedAt`, keep the winner (newest, then longer
 * evidence, then primary) as `value`, union its sources, and clear the conflict set.
 */
fun resolveMemoryConflicts(items: List<LongTermMemory>, now: String = Instant.now().toString()): List<LongTermMemory> =
    items.map { memory ->
        if (memory.conflicts.isEmpty() || memory.conflictedAt == null) return@map memory
        val base = memory.updatedAt ?: memory.createdAt ?: ""
        val candidates = buildList {
            add(MemoryConflictCandidate(memory.value, memory.evidence, memory.sourceMessageIds, base, true))
            memory.conflicts.forEach { conflict ->
                add(
                    MemoryConflictCandidate(
                        value = conflict.value,
                        evidence = conflict.evidence,
                        sourceMessageIds = conflict.sourceMessageIds,
                        updatedAt = conflict.at ?: "",
                        isMain = false,
                    ),
                )
            }
        }
        val winner = resolveMemoryConflict(candidates) ?: return@map memory
        memory.copy(
            value = winner.value,
            evidence = winner.evidence,
            sourceMessageIds = (memory.sourceMessageIds + winner.sourceMessageIds).distinct().take(8),
            conflicts = emptyList(),
            conflictedAt = null,
            updatedAt = now,
        )
    }

/** Character-level conflict adjudication across the shared store and every member store. */
fun resolveMemoryConflicts(character: Character, now: String = Instant.now().toString()): Character =
    character.copy(
        longTerm = resolveMemoryConflicts(character.longTerm, now),
        members = character.members.map { it.copy(longTerm = resolveMemoryConflicts(it.longTerm, now)) },
    )
