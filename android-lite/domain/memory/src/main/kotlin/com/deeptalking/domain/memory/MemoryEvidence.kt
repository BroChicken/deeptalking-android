package com.deeptalking.domain.memory

import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.alignSources
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay

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

/**
 * A resolved message source: its id, role (`user`/`assistant`) and raw text.
 *
 * [timestamp] is the source message's own time when it is known (`instant` messages carry
 * one; short-term entries fall back to their `eventTime`). It is only needed to resolve the
 * *relative* time wording inside the text ("明天" means a different day depending on when it
 * was said), so an unknown timestamp degrades a time check to "a time word is present".
 */
data class SourceRef(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: String? = null,
)

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
            sources[message.id] =
                SourceRef(message.id, message.role.name.lowercase(), message.content, message.timestamp)
        }
    }
    character.shortTerm.forEach { item ->
        // `sourceRoles` is index-aligned with `sourceMessageIds`; a legacy malformed
        // entry may carry a short list, so resolve roles through [alignSources]
        // rather than reading `sourceRoles[index]` directly (which would leave the
        // source role-less and make every memory citing it unresolvable).
        val aligned = alignSources(item.sourceMessageIds, item.sourceRoles, item.userEvidence)
        aligned.sourceMessageIds.forEachIndexed { index, id ->
            if (!sources.containsKey(id)) {
                val evidence = aligned.userEvidence.firstOrNull { it.sourceMessageId == id }
                val role = aligned.sourceRoles.getOrNull(index).orEmpty()
                // 相对时间要按"这条话是什么时候说的"换算：`_sourceTs` 记的就是来源消息时间，
                // 优先级高于 eventTime（事件发生时间可能与发消息不在同一天）。原生写入的短
                // 记忆没有 `_sourceTs`，仍回落到 eventTime。
                val timestamp = item.sourceTs ?: item.eventTime
                sources[id] = SourceRef(id, role, evidence?.text ?: item.content, timestamp)
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
            return listOf(SourceRef(message.id, "assistant", message.content, message.timestamp))
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
        return listOf(SourceRef(message.id, "assistant", message.content, message.timestamp))
    }
    return hasValidUserEvidence(character, ids, evidence, sources)
}

// ---- promise confirmation eligibility (约定确认：记忆侧元信息，与待办无关) --------------

/**
 * Promisor values used by the prompt contract (rule 5.5):
 * - `user`         —— 用户自己的承诺（subject=user）；
 * - `character`    —— 角色单方面的承诺（subject=relationship，`current_response` 取证）；
 * - `relationship` —— 双方共同约定的承诺（subject=relationship）。
 *
 * Anything else (a group member's name, an empty/legacy value) is not covered by the
 * contract and is therefore never treated as user-confirmed below.
 */
const val PROMISOR_USER: String = "user"
const val PROMISOR_CHARACTER: String = "character"
const val PROMISOR_RELATIONSHIP: String = "relationship"

/** Why a stored promise is (not) counted as a user-confirmed agreement — see [promisePushDecision]. */
enum class PromisePushReason {
    /** 用户明确同意（单方承诺 = 用户原话；双方约定 = 双方原话都有）：计入「已确认」。 */
    USER_CONFIRMED,

    /** 角色单方面的承诺（`promisor=character`）：照常存进记忆，但不计入「已确认」。 */
    CHARACTER_SELF_PROMISE,

    /** `promisor` 不是 `user`/`relationship`（群组成员名、空、旧数据）：没有"用户明确同意"这个依据。 */
    NOT_USER_PROMISOR,

    /** `promisor=relationship` 但只引用了用户单方原话：模型自行推断的"双方约定"，不算数。 */
    RELATIONSHIP_NEEDS_BOTH_SIDES,

    /** 引用来源解析不了，或没有一条能逐字对上用户原话：[LongTermMemory.evidence] 不是用户说的。 */
    NO_USER_EVIDENCE,

    /** 不是一条仍然有效的约定（已 resolved/cancelled，或根本不是 promises 类别）。 */
    NOT_ACTIVE_PROMISE,
}

/**
 * Verdict of [promisePushDecision]: whether a stored promise is a **user-confirmed agreement**,
 * and — when it is — which due time the *speaker's own words* actually support.
 *
 * [pushable] answers "did the user themself agree to this?", independently of the due time:
 * an eligible promise with [dueAtNeedsConfirmation] has no confirmed date yet, while
 * [confirmedDueAt] is the only due time a caller may treat as confirmed.
 *
 * (The name is historical: this gate used to decide what to push as a notification, then fed the
 * settings 待办 list — both are gone. It now only classifies the promise **as memory metadata**;
 * 待办 is a separate, independent entity. See [hasValidPushConfirmation].)
 */
data class PromisePushDecision(
    /** True when the user (or both sides) agreed to this promise; false for a character self-promise. */
    val pushable: Boolean,
    val reason: PromisePushReason,
    /** The due time the user's own words locate, ISO, or null when there is none / it is unbacked. */
    val confirmedDueAt: String? = null,
    val confirmedDueAtMillis: Long? = null,
    /** The model supplied a due time but the user's words do not locate it → 待确认期限。 */
    val dueAtNeedsConfirmation: Boolean = false,
)

/**
 * The confirmation gate over one stored promise (约定确认，仅作记忆元信息).
 *
 * A promise counts as a user-confirmed agreement only when **the user themself agreed to it**:
 *
 * - `promisor=character` (角色的单方面承诺) → not confirmed. The memory is still stored
 *   normally ([isValidAutomaticMemory] keeps accepting it); it simply never counts as a
 *   confirmed agreement.
 * - `promisor=user` → confirmed only when [LongTermMemory.sourceMessageIds] resolve and
 *   every resolved source is the user's (`evidence` must be a verbatim user excerpt).
 * - `promisor=relationship` (双方约定) → confirmed only when, besides the user's verbatim
 *   words, an assistant source that overlaps the evidence is also cited. An agreement the
 *   model inferred from the user's line alone is therefore not confirmed.
 * - anything else → not confirmed.
 *
 * The due time is checked separately: [dueAtBackedByEvidence] must locate the model's `dueAt`
 * in those original words. When it cannot, the decision returns `confirmedDueAt = null` and
 * `dueAtNeedsConfirmation = true`.
 *
 * Callers read `pushable`, `confirmedDueAtMillis` and `dueAtNeedsConfirmation` (the
 * confirmation-stamping pass in `:app`); nothing is delivered to the user any more.
 */
fun promisePushDecision(
    character: Character,
    item: LongTermMemory,
    sources: Map<String, SourceRef> = knownSources(character),
    assistantMessage: ChatMessage? = null,
): PromisePushDecision {
    if (item.category != MemoryCategory.Promises || item.status != PromiseStatus.Active) {
        return PromisePushDecision(pushable = false, reason = PromisePushReason.NOT_ACTIVE_PROMISE)
    }
    val promisor = item.promisor?.trim().orEmpty().lowercase()
    if (promisor == PROMISOR_CHARACTER) {
        // 角色的单方承诺：记忆照存，但不推送。
        return PromisePushDecision(pushable = false, reason = PromisePushReason.CHARACTER_SELF_PROMISE)
    }
    if (promisor != PROMISOR_USER && promisor != PROMISOR_RELATIONSHIP) {
        return PromisePushDecision(pushable = false, reason = PromisePushReason.NOT_USER_PROMISOR)
    }
    // 已有有效确认（最近一次严格闸门通过时落盘）：引用消息滑出窗口后，来源可能再也
    // 解析不出来，但"用户当时确实确认过这条约定"是既成事实，不应退化成「不提醒」。
    // 确认按 `dueAt` 失效：一旦期限被改写（或删除），标记即作废，回到下面的严格判定。
    if (hasValidPushConfirmation(item)) {
        val dueIso = item.dueAt?.trim()?.takeIf { it.isNotEmpty() }
        return PromisePushDecision(
            pushable = true,
            reason = PromisePushReason.USER_CONFIRMED,
            confirmedDueAt = dueIso,
            confirmedDueAtMillis = dueIso?.let { parseZoned(it)?.toInstant()?.toEpochMilli() },
            dueAtNeedsConfirmation = false,
        )
    }
    val evidence = item.evidence.trimTo(300)
    if (evidence.length < 2) {
        return PromisePushDecision(pushable = false, reason = PromisePushReason.NO_USER_EVIDENCE)
    }
    val resolved = resolveMemorySources(character, item.sourceMessageIds, sources)
        ?: return PromisePushDecision(pushable = false, reason = PromisePushReason.NO_USER_EVIDENCE)
    val userSources = resolved.filter { it.role == PROMISOR_USER }
    if (userSources.none { evidenceMatchesSource(it.text, evidence) }) {
        return PromisePushDecision(pushable = false, reason = PromisePushReason.NO_USER_EVIDENCE)
    }
    if (promisor == PROMISOR_USER) {
        if (resolved.any { it.role != PROMISOR_USER }) {
            // 用户承诺只允许引用用户自己的话（与 isValidAutomaticMemory 的 all-user 规则一致）。
            return PromisePushDecision(pushable = false, reason = PromisePushReason.NO_USER_EVIDENCE)
        }
    } else if (resolved.none { it.role != PROMISOR_USER && evidenceMatchesSummary(it.text, evidence) }) {
        // 双方约定必须有另一方的原话，且那句话确实在说同一件事。
        return PromisePushDecision(pushable = false, reason = PromisePushReason.RELATIONSHIP_NEEDS_BOTH_SIDES)
    }

    val dueIso = item.dueAt?.trim()?.takeIf { it.isNotEmpty() }
        ?: return PromisePushDecision(pushable = true, reason = PromisePushReason.USER_CONFIRMED)
    if (!dueAtBackedByEvidence(userSources, dueIso)) {
        return PromisePushDecision(
            pushable = true,
            reason = PromisePushReason.USER_CONFIRMED,
            confirmedDueAt = null,
            confirmedDueAtMillis = null,
            dueAtNeedsConfirmation = true,
        )
    }
    return PromisePushDecision(
        pushable = true,
        reason = PromisePushReason.USER_CONFIRMED,
        confirmedDueAt = dueIso,
        confirmedDueAtMillis = parseZoned(dueIso)?.toInstant()?.toEpochMilli(),
        dueAtNeedsConfirmation = false,
    )
}

/**
 * True when [item] carries a persisted, still-valid push confirmation: a strict-gate pass was
 * recorded ([LongTermMemory.pushConfirmedAt]) and the confirmed due time still matches the
 * current [LongTermMemory.dueAt] (a rewritten/removed due invalidates the marker).
 */
fun hasValidPushConfirmation(item: LongTermMemory): Boolean =
    item.pushConfirmedAt != null && item.dueAt?.trim() == item.pushConfirmedDueAt?.trim()

// ---- due-time locatability (期限必须落在用户原话里) --------------------------------------

private val ISO_DATE = Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})")

/** `2026年8月10日` / `8月10号` — the year is optional and is group 1 when present. */
private val CHINESE_DATE = Regex("(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]")

private val CLOCK_TIME = Regex("\\d{1,2}\\s*[:：]\\s*\\d{2}|\\d{1,2}\\s*点")

/** 今/明/昨/后/前/次/翌 + 天/日/早/晚/夜/午/晨, plus 当天/当日/第N天. */
private val DAY_ANCHORS = Regex(
    "(今|明|昨|后|前|次|翌)\\s*(天|日|早|晚|夜|午|晨)|当天|当日|第\\s*[0-9一二三四五六七八九十]+\\s*天",
)

/** `下周` / `本周三` / `周末` / `星期天` / `礼拜一` — a bare `周` (e.g. 周围) must not count. */
private val WEEK_REF = Regex("(这|本|上|下|每)\\s*(周|星期|礼拜)|(周|星期|礼拜)[一二三四五六日天末]")

/** `这个月` / `下月` / `今年` / `明年` / `年底` / `3月(份)`. */
private val MONTH_YEAR_REF = Regex(
    "(这|本|上|下|每)\\s*个?\\s*月|(今|明|去|来|本|上|下)\\s*年|年(底|初)|月(底|初)|\\d{1,2}\\s*月",
)

/** `3天后` / `两个星期之后` / `半小时前` / `三天内`. */
private val RELATIVE_OFFSET = Regex(
    "(\\d{1,3}|[一两二三四五六七八九十半])\\s*(分钟|小时|钟头|天|日|周|星期|个?月|年)\\s*(后|以后|之后|前|以前|之前|内)",
)

/** 时段词（上午/晚上/深夜/早上…），复用 [TIME_SLOTS] 与别名表。 */
private val TIME_SLOT_WORD = Regex(
    (TIME_SLOTS + TIME_SLOT_ALIASES.keys).sortedByDescending { it.length }.joinToString("|"),
)

/**
 * True when [text] contains a *resolvable* calendar/time-of-day expression: an absolute date
 * (`2026-08-10`, `8月10日`), a clock time (`9:30`, `9点`), a day/week/month/year anchor
 * (`明天`, `下周三`, `这个月`), a relative offset (`三天后`) or a time slot (`晚上`).
 *
 * Deliberately excludes vague fillers ("稍后", "一会儿", "以后") that name no time at all —
 * a due time has to be locatable, and "以后再说" must not justify one.
 */
fun containsTemporalExpression(text: String?): Boolean {
    val value = text.orEmpty()
    if (value.isEmpty()) return false
    return ISO_DATE.containsMatchIn(value) ||
        CHINESE_DATE.containsMatchIn(value) ||
        CLOCK_TIME.containsMatchIn(value) ||
        DAY_ANCHORS.containsMatchIn(value) ||
        WEEK_REF.containsMatchIn(value) ||
        MONTH_YEAR_REF.containsMatchIn(value) ||
        RELATIVE_OFFSET.containsMatchIn(value) ||
        TIME_SLOT_WORD.containsMatchIn(value)
}

/** Absolute ISO-ish dates (`2026-08-10`, `2026/8/10`) written in [text]. */
private fun isoDatesIn(text: String): List<LocalDate> =
    ISO_DATE.findAll(text).mapNotNull { match ->
        runCatching {
            LocalDate.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }.getOrNull()
    }.toList()

/** Chinese dates in [text] as (year?, month-day); the year is null when the user omitted it. */
private fun chineseDatesIn(text: String): List<Pair<Int?, MonthDay>> =
    CHINESE_DATE.findAll(text).mapNotNull { match ->
        val monthDay = runCatching {
            MonthDay.of(match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }.getOrNull() ?: return@mapNotNull null
        match.groupValues[1].toIntOrNull() to monthDay
    }.toList()

/**
 * True when the due time [dueAtIso] is locatable in the given [sources] — the original words of
 * whoever stated the deadline (for a promise that is the user themself; a todo may be stated by
 * either side, see `TodoPolicy`).
 *
 * The rule is deliberately asymmetric:
 *
 * - the words must carry a [containsTemporalExpression] anchor — a due time nobody mentioned is
 *   rejected outright (no schedule; the caller asks the user instead);
 * - when those words also pin an absolute date, [dueAtIso] must fall on **that** date (a
 *   relative word like "明天" is resolved against [SourceRef.timestamp] — for a short-term
 *   source that is the recorded source-message time (`_sourceTs`) when present, otherwise the
 *   entry's event time — so a model-invented `dueAt` on a different day is rejected);
 * - when the anchor is only coarse ("下周" without a weekday, "晚上" without a day) the due
 *   time is accepted, because the speaker *did* name a time and nothing contradicts it;
 * - a source with no timestamp cannot resolve relative wording (it is deliberately **not**
 *   resolved against the wall clock, so the verdict does not depend on when it is evaluated),
 *   so the presence check is all that applies to it (absolute dates in the text still compare).
 */
fun dueAtBackedByEvidence(
    sources: List<SourceRef>,
    dueAtIso: String?,
): Boolean {
    val dueAt = parseZoned(dueAtIso) ?: return false
    if (sources.isEmpty()) return false
    val dueDate = dueAt.toLocalDate()
    val dueMonthDay = MonthDay.from(dueDate)
    var sawTemporalAnchor = false
    var sawDifferentDate = false

    for (source in sources) {
        val text = source.text
        if (text.isEmpty()) continue
        val base = parseZoned(source.timestamp)
        // Only *relative* wording needs the message time; absolute dates compare either way.
        val normalized = if (base != null) parseRelativeText(text, base) else text
        val isoDates = isoDatesIn(normalized)
        val chineseDates = chineseDatesIn(normalized)
        if (isoDates.isEmpty() && chineseDates.isEmpty() && !containsTemporalExpression(text)) continue
        sawTemporalAnchor = true
        if (isoDates.any { it == dueDate }) return true
        if (chineseDates.any { (year, monthDay) ->
                if (year != null) year == dueDate.year && monthDay == dueMonthDay else monthDay == dueMonthDay
            }
        ) {
            return true
        }
        if (isoDates.isNotEmpty() || chineseDates.isNotEmpty()) {
            // The user pinned a day and it is not the one the model picked.
            sawDifferentDate = true
        }
    }
    return sawTemporalAnchor && !sawDifferentDate
}

/**
 * Category/subject/evidence/role gate for automatic long-term writes
 * (legacy `isValidAutomaticMemory`). Model-authored entries must trace to a real
 * message with the right role; a `legacy`/`character` subject or missing evidence
 * is always rejected.
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
