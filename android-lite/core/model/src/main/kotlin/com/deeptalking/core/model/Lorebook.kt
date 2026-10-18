package com.deeptalking.core.model

import com.deeptalking.core.common.AppLimits
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LorebookOrigin {
    @SerialName("user") User,
    // Legacy JS stores model-authored entries with origin "ai" (see lorebook.js).
    @SerialName("ai") Model,
}

@Serializable
data class LorebookEntry(
    val id: String = "",
    val name: String = "",
    val content: String = "",
    val keywords: List<String> = emptyList(),
    val enabled: Boolean = true,
    val alwaysActive: Boolean = false,
    val origin: LorebookOrigin = LorebookOrigin.Model,
    val memberName: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Times this entry was injected into context (legacy `mentions`). */
    val mentions: Int = 0,
    /** When the entry was last injected (legacy `lastMentionedAt`). */
    val lastMentionedAt: String? = null,
    /**
     * Consecutive consolidation passes without a hit (legacy `misses`). Kept for archive
     * compatibility only — the capacity-driven eviction no longer reads it.
     */
    val misses: Int = 0,
    /** Sort priority for injection order (legacy `order`, default 100). */
    val order: Int = 100,
)

/** True when the user hand-writes this entry: it is never counted, evicted or rewritten. */
val LorebookEntry.isManual: Boolean
    get() = origin == LorebookOrigin.User

/**
 * Active time = `max(lastMentionedAt, createdAt)` — the single ranking key for eviction.
 * Both sources are equal-weight; `updatedAt` is deliberately **not** used (a rewrite is not
 * an activity). Blank only when neither time is known; such an entry sorts as the newest
 * and is therefore never evicted first (a missing timestamp must never cost an entry).
 */
val LorebookEntry.activeAt: String
    get() {
        val mentioned = lastMentionedAt?.takeIf { it.isNotBlank() }
        val created = createdAt?.takeIf { it.isNotBlank() }
        return when {
            mentioned == null -> created.orEmpty()
            created == null -> mentioned
            else -> if (mentioned >= created) mentioned else created
        }
    }

/**
 * Whether the capacity rule may archive this entry to make room: model-maintained and not
 * 常驻 (`alwaysActive`). Manual entries and 常驻 entries are protected — they still count
 * toward the 200-slot ceiling but are never archived automatically.
 */
val LorebookEntry.isEvictable: Boolean
    get() = !isManual && !alwaysActive

/** Where an entry currently lives: `memberId == null` means the shared (本体) book. */
data class LorebookBookRef(val memberId: String?, val entry: LorebookEntry)

/** Sentinel that sorts after every real ISO-8601 timestamp (blank = newest). */
private const val NEWEST_ACTIVE_AT = "\uFFFF"

/**
 * Deterministic oldest-first ranking: active time ascending (blank last), then fewer
 * mentions first, then entry id. The same input always produces the same order.
 */
fun rankLorebookForEviction(entries: List<LorebookEntry>): List<LorebookEntry> =
    entries.sortedWith(
        compareBy<LorebookEntry> { it.activeAt.ifEmpty { NEWEST_ACTIVE_AT } }
            .thenBy { it.mentions }
            .thenBy { it.id },
    )

private fun rankLorebookBookRefs(refs: List<LorebookBookRef>): List<LorebookBookRef> =
    refs.sortedWith(
        compareBy<LorebookBookRef> { it.entry.activeAt.ifEmpty { NEWEST_ACTIVE_AT } }
            .thenBy { it.entry.mentions }
            .thenBy { it.entry.id },
    )

/**
 * Live, capacity-consuming entries: every **model-maintained** entry across the shared book
 * and all members. Manual entries and the archive are excluded — they neither count toward
 * the ceiling nor take part in eviction.
 */
fun Character.capacityLorebookEntries(): List<LorebookEntry> =
    lorebook.filterNot { it.isManual } + members.flatMap { member -> member.lorebook.filterNot { it.isManual } }

/** Global capacity count: shared book + every member, manual entries excluded. */
fun Character.lorebookCapacityCount(): Int = capacityLorebookEntries().size

/** Every entry the capacity rule may archive (shared book + members), minus [excludeIds]. */
fun Character.evictableLorebookEntries(excludeIds: Set<String> = emptySet()): List<LorebookBookRef> =
    lorebook.filter { it.isEvictable && it.id !in excludeIds }.map { LorebookBookRef(null, it) } +
        members.flatMap { member ->
            member.lorebook.filter { it.isEvictable && it.id !in excludeIds }.map { LorebookBookRef(member.id, it) }
        }

/**
 * Moves the [count] globally-oldest evictable entries into the archive list of the book they
 * came from, so ownership ("来自本体还是哪位成员") is preserved. [excludeIds] entries are not
 * archived (used by restore so the entry being restored cannot immediately evict itself).
 * Returns the updated character and the archived entries in ranked order. Never touches
 * manual/常驻 entries; when the pool is smaller than [count] it archives what it can.
 */
fun Character.archiveOldestLorebookEntries(
    count: Int,
    excludeIds: Set<String> = emptySet(),
): Pair<Character, List<LorebookEntry>> {
    if (count <= 0) return this to emptyList()
    val picked = rankLorebookBookRefs(evictableLorebookEntries(excludeIds)).take(count)
    if (picked.isEmpty()) return this to emptyList()
    val sharedIds = picked.filter { it.memberId == null }.map { it.entry.id }.toSet()
    val memberIds = picked.filter { it.memberId != null }.groupBy({ it.memberId!! }, { it.entry.id })
    val archivedShared = lorebook.filter { it.id in sharedIds }
    val updated = copy(
        lorebook = lorebook.filterNot { it.id in sharedIds },
        lorebookArchive = lorebookArchive + archivedShared,
        members = members.map { member ->
            val ids = memberIds[member.id]?.toSet() ?: return@map member
            member.copy(
                lorebook = member.lorebook.filterNot { it.id in ids },
                lorebookArchive = member.lorebookArchive + member.lorebook.filter { it.id in ids },
            )
        },
    )
    return updated to picked.map { it.entry }
}

/**
 * One-time convergence of an over-capacity world book (e.g. a legacy import that carried
 * more than the ceiling): archives the oldest overflow until exactly the ceiling is live.
 * Manual/常驻 entries are never archived — if they alone exceed the ceiling nothing is
 * removed (protected content always wins over the count).
 */
fun Character.convergeLorebookCapacity(): Character {
    val overflow = lorebookCapacityCount() - AppLimits.Lorebook.ENTRIES
    if (overflow <= 0) return this
    return archiveOldestLorebookEntries(overflow).first
}

/**
 * Restores an archived entry to its **original** book: the shared archive first, then each
 * member's own archive — the archive list itself records where the entry came from. Returns
 * the character unchanged when no archive holds the id.
 */
fun Character.restoreArchivedLorebookEntry(entryId: String): Character {
    lorebookArchive.firstOrNull { it.id == entryId }?.let { entry ->
        return copy(lorebook = lorebook + entry, lorebookArchive = lorebookArchive.filterNot { it.id == entryId })
    }
    val owner = members.firstOrNull { member -> member.lorebookArchive.any { it.id == entryId } } ?: return this
    val entry = owner.lorebookArchive.first { it.id == entryId }
    return copy(
        members = members.map { member ->
            if (member.id == owner.id) {
                member.copy(
                    lorebook = member.lorebook + entry,
                    lorebookArchive = member.lorebookArchive.filterNot { it.id == entryId },
                )
            } else {
                member
            }
        },
    )
}
