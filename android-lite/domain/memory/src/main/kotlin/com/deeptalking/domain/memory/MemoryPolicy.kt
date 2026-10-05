package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.ShortTermMemory
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

/** Keeps at most [limit] items, highest [memorySortScore] first. */
fun pruneLongTerm(
    items: List<LongTermMemory>,
    limit: Int = AppLimits.Memory.LONG_TERM_PER_CATEGORY,
    now: Long = System.currentTimeMillis(),
): List<LongTermMemory> {
    if (items.size <= limit) return items
    return items.sortedByDescending { memorySortScore(it, now) }.take(limit)
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
 * Trims short-term memory (legacy `trimShortTermList`): always keep the newest
 * [floor] entries plus any older entry neither analyzed nor lorebook-scanned.
 *
 * SIMPLIFIED: the native [ShortTermMemory] has no `revision` / `analyzedRevision`
 * / `lorebookScannedRevision` counters, so a timestamp means "done for its only
 * revision"; the revision-aware branch cannot be reproduced.
 */
@Suppress("UNUSED_PARAMETER")
fun trimShortTerm(
    items: List<ShortTermMemory>,
    limit: Int = AppLimits.Memory.SHORT_TERM,
    floor: Int = AppLimits.Memory.SHORT_TERM_TRIM_FLOOR,
): List<ShortTermMemory> {
    if (items.isEmpty()) return items
    val recentStart = (items.size - floor).coerceAtLeast(0)
    return items.filterIndexed { index, item ->
        val analyzed = item.analyzedAt != null
        val scanned = item.lorebookScannedAt != null
        index >= recentStart || !analyzed || !scanned
    }
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
    return first.value.trimTo(900) + "\n" + second.value.trimTo(900)
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
            sourceMessageIds = (existing.sourceMessageIds + incoming.sourceMessageIds).distinct().take(8),
        )
    } else if (incomingTime >= existingTime) {
        // Never silently overwrite a semantically different value: preserve both
        // so `resolveMemoryConflicts` / the user can reconcile later.
        val conflict = memoriesSemanticallyDiffer(incoming.value, existing.value)
        merged = merged.copy(
            value = if (conflict) mergeConflictValue(existing.value, incoming.value) else incoming.value.ifEmpty { existing.value },
            subject = if (conflict) existing.subject else incoming.subject,
            sourceMessageIds = (existing.sourceMessageIds + incoming.sourceMessageIds).distinct().take(8),
            evidence = if (conflict) mergeEvidenceText(existing.evidence, incoming.evidence) else incoming.evidence.ifEmpty { existing.evidence },
            status = if (conflict) existing.status else incoming.status,
            dueAt = incoming.dueAt ?: existing.dueAt,
            updatedAt = incoming.updatedAt ?: incoming.createdAt ?: existing.updatedAt,
            arcOf = incoming.arcOf ?: existing.arcOf,
            arcStage = incoming.arcStage ?: existing.arcStage,
            conflicts = if (conflict) (existing.conflicts + incoming.value).distinct().take(8) else existing.conflicts,
            conflictedAt = if (conflict) {
                incoming.updatedAt ?: incoming.createdAt ?: existing.conflictedAt
            } else {
                existing.conflictedAt
            },
            relatedTo = (existing.relatedTo + incoming.relatedTo).distinct().take(8),
            sourceRoles = (existing.sourceRoles + incoming.sourceRoles).distinct(),
            userEvidence = existing.userEvidence.ifEmpty { incoming.userEvidence },
        )
    }
    return merged.copy(
        tags = (existing.tags + incoming.tags).distinct().take(8),
        importance = maxOf(existing.importance, incoming.importance),
    )
}

/** Preserves a semantically different incoming value instead of dropping it. */
private fun mergeConflictValue(existing: String, incoming: String): String {
    if (incoming.isBlank() || existing.contains(incoming)) return existing
    return if (existing.isBlank()) incoming.trim() else existing.trim() + "\n" + incoming.trim()
}

/** Unions two evidence snippets, preserving both on a semantic conflict. */
private fun mergeEvidenceText(existing: String, incoming: String): String {
    if (incoming.isBlank() || existing.contains(incoming)) return existing
    return if (existing.isBlank()) incoming.trim() else existing.trim() + "\n" + incoming.trim()
}

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
 * the event time.
 *
 * SIMPLIFIED: `sourceRoles` / `userEvidence` / revision counters have no native
 * home, so only the source ids are retained.
 */
fun addShortTermMemory(
    list: List<ShortTermMemory>,
    draft: ShortTermDraft,
    timestamp: String?,
    sources: Map<String, SourceRef>,
    nowMillis: Long = System.currentTimeMillis(),
): ShortTermAddResult {
    var text = draft.content.trimTo(500)
    if (text.isEmpty()) return ShortTermAddResult(list, false)
    val sourceIds = draft.sourceMessageIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .take(AppLimits.Memory.SUMMARY_SOURCES)
    if (sourceIds.isEmpty() || sourceIds.any { !sources.containsKey(it) }) return ShortTermAddResult(list, false)
    val nowIso = Instant.ofEpochMilli(nowMillis).toString()
    val eventTime = normalizeTimestampIso(draft.eventTime, timestamp ?: nowIso)
    val base = parseZoned(eventTime) ?: parseZoned(timestamp) ?: parseZoned(nowIso) ?: return ShortTermAddResult(list, false)
    text = parseRelativeText(text, base)
    val participants = normalizeParticipantsList(draft.participants)
    val location = draft.location.trim().take(160)
    val identity = eventIdentity(eventTime)
    if (identity.isNotEmpty()) {
        val existingIndex = list.indexOfFirst { item ->
            item.content == text && eventIdentity(item.eventTime) == identity
        }
        if (existingIndex >= 0) {
            val existing = list[existingIndex]
            val mergedIds = (existing.sourceMessageIds + sourceIds).distinct()
                .takeLast(AppLimits.Memory.SUMMARY_SOURCES)
            val updated = list.toMutableList()
            updated[existingIndex] = existing.copy(
                sourceMessageIds = mergedIds,
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
    val value = parseRelativeText(item.value.trimTo(900), base)
    if (key.isBlank() || value.isBlank()) return store to false

    val eventTime = normalizeTimestampIso(item.eventTime, nowIso)
    val participants = normalizeParticipantsList(item.participants)
    val location = item.location.trim().take(160)
    val identity = if (item.category == MemoryCategory.Events) eventIdentity(eventTime) else ""
    val index = store.indexOfFirst { memory ->
        memory.key.trim().lowercase() == key.lowercase() &&
            (identity.isEmpty() || eventIdentity(memory.eventTime) == identity)
    }
    if (item.category == MemoryCategory.Promises && index >= 0 && store[index].status != PromiseStatus.Active) {
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
    if (index >= 0) {
        val existing = store[index]
        updated[index] = if (memoriesSemanticallyDiffer(value, existing.value)) {
            existing.copy(
                value = mergeConflictValue(existing.value, value),
                evidence = mergeEvidenceText(existing.evidence, item.evidence.trimTo(300)),
                sourceMessageIds = (existing.sourceMessageIds + resolvedIds).distinct().take(8),
                tags = (existing.tags + tags).distinct().take(8),
                importance = maxOf(existing.importance, importance),
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
                evidence = item.evidence.trimTo(300),
                eventTime = eventTime.ifBlank { existing.eventTime ?: nowIso },
                participants = if (item.category == MemoryCategory.Events) participants else existing.participants,
                location = if (item.category == MemoryCategory.Events) location else existing.location,
                status = if (item.category == MemoryCategory.Promises) PromiseStatus.Active else existing.status,
                dueAt = if (item.category == MemoryCategory.Promises) (dueAt ?: existing.dueAt) else existing.dueAt,
                promisor = if (item.category == MemoryCategory.Promises) item.promisor?.take(40) else existing.promisor,
                promisee = if (item.category == MemoryCategory.Promises) item.promisee?.take(40) else existing.promisee,
                updatedAt = eventTime.ifBlank { nowIso },
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
 * competing values, keep the winner (newest, then longer evidence, then primary)
 * as `value` and clear the conflict set.
 */
fun resolveMemoryConflicts(character: Character): Character {
    fun resolve(list: List<LongTermMemory>): List<LongTermMemory> = list.map { memory ->
        if (memory.conflicts.isEmpty()) return@map memory
        val base = memory.updatedAt ?: memory.createdAt ?: ""
        val candidates = buildList {
            add(MemoryConflictCandidate(memory.value, memory.evidence, memory.sourceMessageIds, base, true))
            memory.conflicts.forEach { value ->
                add(MemoryConflictCandidate(value, "", emptyList(), memory.conflictedAt ?: base, false))
            }
        }
        val winner = resolveMemoryConflict(candidates)
        memory.copy(
            value = winner?.value?.ifBlank { memory.value } ?: memory.value,
            conflicts = emptyList(),
            conflictedAt = null,
        )
    }
    return character.copy(
        longTerm = resolve(character.longTerm),
        members = character.members.map { it.copy(longTerm = resolve(it.longTerm)) },
    )
}
