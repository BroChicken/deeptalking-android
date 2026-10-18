package com.deeptalking.feature.settings

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.domain.memory.PromisePushDecision
import com.deeptalking.domain.memory.promisePushDecision

/**
 * The 待办 bucket one stored promise appears in.
 *
 * Everything here is **derived**: a section is a function of the push decision
 * ([promisePushDecision]), the agreement's own wording and "now vs the confirmed due time".
 * No memory field is added for the 待办 view — in particular 已超时 is spelled
 * `dueAt` in the past, not a new `expired` flag.
 */
enum class PromiseTodoSection {
    /** 进行中: the user confirmed it and its (user-located) due time is still ahead — or it has none. */
    ONGOING,

    /**
     * 待确认期限: the user did confirm the promise, but the model's `dueAt` is not locatable in
     * the user's own words. Deliberately never scheduled — the list asks the user instead of
     * firing a reminder on a date nobody agreed to.
     */
    NEEDS_DUE_CONFIRMATION,

    /**
     * 周期性/长期约定 ("每晚…", "一直…"): shown, but **never** treated as overdue — a nightly
     * agreement is not a one-off item that "expired" at its first due time.
     */
    RECURRING,

    /** 已超时: a one-off, user-confirmed agreement past its confirmed due time; moved out of 进行中. */
    OVERDUE,

    /**
     * 不提醒: the memory is kept, but the *user* is not the one who agreed it (角色的单方承诺,
     * 缺少用户原话) — so it never becomes a reminder. It is still listed here (collapsed) because
     * it is a real memory the user may want to see or close; nothing is deleted.
     */
    NOT_PUSHABLE,
}

/** One row of the 待办 list, already assigned to its [section]. */
data class PromiseTodo(
    val id: String,
    val key: String,
    val value: String,
    val section: PromiseTodoSection,
    /** The due time the user's own words locate, epoch millis; null when unconfirmed/absent. */
    val dueAtMillis: Long?,
    /** The model's unconfirmed `dueAt`, ISO — shown only when [dueAtMillis] is null. */
    val unconfirmedDueAt: String?,
)

/**
 * Keywords that mark a repeating (or open-ended) agreement. Deterministic on purpose: a wrong
 * "recurring" verdict only costs a missing 已超时 entry, while a wrong "one-off" verdict would
 * silently retire an ongoing agreement — so the trade is deliberately conservative (any hit
 * keeps the item out of 已超时).
 */
private val RECURRING_KEYWORDS = listOf("每", "一直", "永远")

/** True when the agreement's text marks it as repeating / open-ended ("每晚…", "一直…"). */
fun isRecurringPromise(memory: LongTermMemory): Boolean {
    if (memory.category != MemoryCategory.Promises) return false
    val text = "${memory.key} ${memory.value}"
    return RECURRING_KEYWORDS.any { text.contains(it) }
}

/**
 * The 待办 view of [character]'s own promises, oldest-due first inside each section.
 *
 * Only active `Promises` memories of the character itself are listed (member promises live in
 * the member stores and are not scanned by the due-reminder pass either). A promise that is not
 * pushable stays visible under [PromiseTodoSection.NOT_PUSHABLE] — it is never deleted, it just
 * never becomes a reminder.
 */
fun promiseTodos(character: Character, nowMillis: Long = System.currentTimeMillis()): List<PromiseTodo> =
    character.longTerm
        .mapNotNull { memory -> todoOf(character, memory, nowMillis) }
        .sortedWith(compareBy({ it.section.ordinal }, { it.dueAtMillis ?: Long.MAX_VALUE }, { it.key }))

private fun todoOf(character: Character, memory: LongTermMemory, nowMillis: Long): PromiseTodo? {
    if (memory.category != MemoryCategory.Promises || memory.status != PromiseStatus.Active) return null
    val decision = promisePushDecision(character, memory)
    // Read once: a cross-module public property cannot be smart-cast.
    val confirmedDueAtMillis = decision.confirmedDueAtMillis
    return PromiseTodo(
        id = memory.id,
        key = memory.key,
        value = memory.value,
        section = sectionOf(decision, memory, nowMillis),
        dueAtMillis = confirmedDueAtMillis,
        unconfirmedDueAt = if (confirmedDueAtMillis == null) {
            memory.dueAt?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        },
    )
}

private fun sectionOf(
    decision: PromisePushDecision,
    memory: LongTermMemory,
    nowMillis: Long,
): PromiseTodoSection {
    val dueAtMillis = decision.confirmedDueAtMillis
    return when {
        !decision.pushable -> PromiseTodoSection.NOT_PUSHABLE
        // Recurring is checked before the due time: "每晚十点" is not an item that "expired" tonight.
        isRecurringPromise(memory) -> PromiseTodoSection.RECURRING
        decision.dueAtNeedsConfirmation -> PromiseTodoSection.NEEDS_DUE_CONFIRMATION
        dueAtMillis == null -> PromiseTodoSection.ONGOING
        dueAtMillis <= nowMillis -> PromiseTodoSection.OVERDUE
        else -> PromiseTodoSection.ONGOING
    }
}

/**
 * Ids of promises that were active in [before] and are **no longer** active in [after] —
 * resolved, cancelled or dropped entirely. These are exactly the reminders that must be
 * retracted (the pending work *and* a notification that already fired).
 *
 * Targeted on purpose: cancelling every non-active promise on every write would also tear
 * down reminders that are unrelated to this write.
 */
fun promisesLosingActiveStatus(before: Character, after: Character): List<String> {
    val stillActive = after.longTerm
        .filter { it.category == MemoryCategory.Promises && it.status == PromiseStatus.Active }
        .mapTo(HashSet()) { it.id }
    return before.longTerm
        .filter { it.category == MemoryCategory.Promises && it.status == PromiseStatus.Active }
        .map { it.id }
        .filter { it.isNotEmpty() && it !in stillActive }
        .distinct()
}

/** Every active promise id on [character], for a wholesale retraction (character deleted). */
fun activePromiseIds(character: Character): List<String> =
    character.longTerm
        .filter { it.category == MemoryCategory.Promises && it.status == PromiseStatus.Active }
        .map { it.id }
        .filter { it.isNotEmpty() }
        .distinct()
