package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.ShortTermMemory
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

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
 * Effective importance after time decay. Ported from `computeEffectiveImportance`.
 * The JS `learnedBonus` field is not on the native model, so it is treated as 0.
 */
fun computeEffectiveImportance(memory: LongTermMemory, now: Long = System.currentTimeMillis()): Double {
    val importance = clamp(memory.importance, 0, 10)
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
 * Batch trim of the legacy instant window. The native [ChatMessage] has no
 * `extractedAt`, so this SIMPLIFIED version keeps the newest messages: once the
 * high watermark is reached it drops down to the low watermark.
 */
fun trimInstant(
    items: List<ChatMessage>,
    limit: Int = AppLimits.Memory.INSTANT,
    floor: Int = AppLimits.Memory.INSTANT_TRIM_FLOOR,
): List<ChatMessage> {
    if (items.size < limit) return items
    return items.takeLast(floor.coerceAtLeast(0))
}

/**
 * Trims short-term memory. The JS version keeps recent items plus unanalyzed
 * ones via revision counters that the native model does not store; this
 * SIMPLIFIED version applies the same high/low watermark on the newest items.
 */
fun trimShortTerm(
    items: List<ShortTermMemory>,
    limit: Int = AppLimits.Memory.SHORT_TERM,
    floor: Int = AppLimits.Memory.SHORT_TERM_TRIM_FLOOR,
): List<ShortTermMemory> {
    if (items.size < limit) return items
    return items.takeLast(floor.coerceAtLeast(0))
}

/** Logical-day + time-slot identity for an event timestamp. */
fun eventIdentity(eventTime: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    val zoned = parseZoned(eventTime, zone) ?: return ""
    val day = logicalDay(zoned)
    return "${day.year}年${day.monthValue}月${day.dayOfMonth}日[${getTimeSlot(zoned.toLocalTime())}]"
}

/**
 * Decay filter mirroring `applyMemoryDecay`:
 * - events: forgotten once older than `30 + importance * 15` days;
 * - promises: resolved/cancelled after 90 idle days, overdue active promises
 *   after 30 idle days;
 * - all other categories are long-lived and never auto-removed.
 */
fun decayLongTerm(
    items: List<LongTermMemory>,
    category: MemoryCategory,
    now: Long = System.currentTimeMillis(),
): List<LongTermMemory> {
    if (category != MemoryCategory.Events && category != MemoryCategory.Promises) return items
    return items.filter { item ->
        val lastRef = parseTimestampMillis(item.lastRecalled)
            ?: parseTimestampMillis(item.updatedAt)
            ?: parseTimestampMillis(item.createdAt)
            ?: 0L
        val daysSince = if (lastRef > 0L) {
            ((now - lastRef).coerceAtLeast(0L)) / MILLIS_PER_DAY
        } else {
            9999.0
        }
        when (category) {
            MemoryCategory.Events -> {
                val maxAge = 30.0 + clamp(item.importance, 0, 10) * 15
                daysSince < maxAge
            }
            MemoryCategory.Promises -> {
                if (item.status != PromiseStatus.Active) {
                    daysSince < 90
                } else {
                    val due = parseTimestampMillis(item.dueAt) ?: 0L
                    !(due > 0L && due < now && daysSince > 30)
                }
            }
            else -> true
        }
    }
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
        )
    } else if (incomingTime >= existingTime) {
        merged = merged.copy(
            value = incoming.value.ifEmpty { existing.value },
            subject = incoming.subject,
            sourceMessageIds = incoming.sourceMessageIds.ifEmpty { existing.sourceMessageIds },
            evidence = incoming.evidence.ifEmpty { existing.evidence },
            status = incoming.status,
            dueAt = incoming.dueAt ?: existing.dueAt,
            updatedAt = incoming.updatedAt ?: incoming.createdAt ?: existing.updatedAt,
        )
    }
    return merged.copy(
        tags = (existing.tags + incoming.tags).distinct().take(8),
        importance = maxOf(existing.importance, incoming.importance),
    )
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
