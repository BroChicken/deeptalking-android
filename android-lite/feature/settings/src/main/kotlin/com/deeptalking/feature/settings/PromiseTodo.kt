package com.deeptalking.feature.settings

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.domain.memory.PromisePushDecision
import com.deeptalking.domain.memory.promisePushDecision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 待办行上的期限只在**已在对话里说清、可核对**时显示，且一律渲染成绝对时间。 */
internal val TODO_DUE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 只写到"日"的期限（用户原话里没有具体几点）用这个格式，不要凭空补一个 00:00。 */
internal val TODO_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * 待办的期限文案。
 *
 * 确认过的期限渲染成本地绝对时间；未确认的期限**不展示模型给的相对说法**（"稍后/今晚/下周"这类），
 * 只提示去把具体日期时间说清楚——待办里不出现相对时间。
 */
internal fun dueLabel(todo: PromiseTodo): String = when {
    todo.dueAtMillis != null && todo.dueIsDayOnly ->
        "期限：" + TODO_DAY_FORMAT.format(
            Instant.ofEpochMilli(todo.dueAtMillis).atZone(ZoneId.systemDefault()),
        )
    todo.dueAtMillis != null ->
        "期限：" + TODO_DUE_FORMAT.format(
            Instant.ofEpochMilli(todo.dueAtMillis).atZone(ZoneId.systemDefault()),
        )
    !todo.unconfirmedDueAt.isNullOrBlank() ->
        // 只有"用户可以自己把期限说清楚"的那一桶才提示去对话里补日期；不提醒桶的原因是
        // 承诺方不是用户（或证据不足），提示他"去说明日期"既解决不了问题也指错了方向。
        if (todo.section == PromiseTodoSection.NEEDS_DUE_CONFIRMATION) {
            "待确认期限（请在对话里说明具体日期与时间）"
        } else {
            "期限未确认"
        }
    else -> "无期限"
}

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
     * the user's own words. The list asks the user instead of showing a date nobody agreed to.
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
     * 缺少用户原话) — so it never counts as a confirmed agreement. It is still listed here
     * (collapsed) because it is a real memory the user may want to see or close; nothing is
     * deleted.
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
    /**
     * True when the located due time names a **day only** (`2026-08-10`, no clock time). The
     * deadline then lasts the whole day: it is not 已超时 at 00:00, and the label shows no time.
     */
    val dueIsDayOnly: Boolean = false,
    /** The model's unconfirmed `dueAt`, ISO — shown only when [dueAtMillis] is null. */
    val unconfirmedDueAt: String?,
    /** Owning character id, so a cross-character row can be resolved back to its store. */
    val characterId: String = "",
    /** Owning character name, shown only when the list spans more than one character. */
    val characterName: String = "",
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
 * the member stores and are not scanned by the confirmation pass either). A promise that is not
 * pushable stays visible under [PromiseTodoSection.NOT_PUSHABLE] — it is never deleted, it just
 * never counts as a confirmed agreement.
 */
fun promiseTodos(character: Character, nowMillis: Long = System.currentTimeMillis()): List<PromiseTodo> =
    character.longTerm
        .mapNotNull { memory -> todoOf(character, memory, nowMillis) }
        .sortedWith(TODO_ORDER)

/**
 * The 待办 view across **every** character: each character's rows concatenated, then ordered by
 * the same rule as [promiseTodos] (section → due → key), with the owning character name/id as a
 * final, stable tie-breaker. A single-character list is byte-for-byte the same order as before.
 */
fun promiseTodos(characters: List<Character>, nowMillis: Long = System.currentTimeMillis()): List<PromiseTodo> =
    characters
        .flatMap { character -> promiseTodos(character, nowMillis) }
        .sortedWith(TODO_ORDER)

private val TODO_ORDER: Comparator<PromiseTodo> = compareBy(
    { it.section.ordinal },
    { it.dueAtMillis ?: Long.MAX_VALUE },
    { it.key },
    { it.characterName },
    { it.characterId },
    { it.id },
)

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
        dueIsDayOnly = confirmedDueAtMillis != null && dayOnlyEndMillis(decision.confirmedDueAt) != null,
        unconfirmedDueAt = if (confirmedDueAtMillis == null) {
            memory.dueAt?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        },
        characterId = character.id,
        characterName = character.name,
    )
}

/**
 * Flips [promiseId] out of `Active` on [character] (完成/关闭 from the 待办 list), returning the
 * updated character — or null when it is not an active promise of that character. Pure: the
 * caller resolves the row's owning character and persists the result, so a flip on one character
 * can never touch another's promises.
 */
fun resolvePromiseIn(
    character: Character,
    promiseId: String,
    status: PromiseStatus,
    nowIso: String,
): Character? {
    if (promiseId.isBlank() || status == PromiseStatus.Active) return null
    if (character.longTerm.none { it.id == promiseId && it.status == PromiseStatus.Active }) return null
    return character.copy(
        longTerm = character.longTerm.map { memory ->
            if (memory.id != promiseId) {
                memory
            } else {
                memory.copy(status = status, updatedAt = nowIso)
            }
        },
    )
}

private fun sectionOf(
    decision: PromisePushDecision,
    memory: LongTermMemory,
    nowMillis: Long,
): PromiseTodoSection {
    val dueAtMillis = decision.confirmedDueAtMillis
    // 只写到"日"的期限，整天才算到期：`parseZoned("2026-08-10")` 落在当天 00:00，直接比大小
    // 会让它在到期日的一整天里都显示"已超时"。
    val overdueAfter = dayOnlyEndMillis(decision.confirmedDueAt) ?: dueAtMillis
    return when {
        !decision.pushable -> PromiseTodoSection.NOT_PUSHABLE
        // Recurring is checked before the due time: "每晚十点" is not an item that "expired" tonight.
        isRecurringPromise(memory) -> PromiseTodoSection.RECURRING
        decision.dueAtNeedsConfirmation -> PromiseTodoSection.NEEDS_DUE_CONFIRMATION
        overdueAfter == null -> PromiseTodoSection.ONGOING
        overdueAfter <= nowMillis -> PromiseTodoSection.OVERDUE
        else -> PromiseTodoSection.ONGOING
    }
}

/**
 * End of the local day named by a **day-only** due time (`2026-08-10` → 2026-08-11T00:00),
 * or null when the value is not a day-only date (missing, relative wording, or it carries a
 * clock time — those are compared at their exact instant).
 */
internal fun dayOnlyEndMillis(dueAt: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val text = dueAt?.trim().orEmpty()
    if (text.isEmpty() || text.contains('T') || text.contains(':')) return null
    val date = runCatching { LocalDate.parse(text.replace('/', '-')) }.getOrNull() ?: return null
    return date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
