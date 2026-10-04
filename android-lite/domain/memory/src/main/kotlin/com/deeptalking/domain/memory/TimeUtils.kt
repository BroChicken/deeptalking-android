package com.deeptalking.domain.memory

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Pure time helpers ported from the legacy `src/js/memory/time.js`.
 *
 * All functions are deterministic for a given (base instant, zone) pair: they
 * never read the wall clock themselves. The logical day starts at 02:00, so
 * 00:00-02:00 belongs to the previous logical day, mirroring the JS module.
 */

const val DAY_START_HOUR: Int = 2

/** A time-of-day bucket. [start]/[end]/[isoHour] use fractional hours. */
data class TimeSlotDef(
    val slot: String,
    val start: Double,
    val end: Double,
    val isoHour: Double,
)

val TIME_SLOT_TABLE: List<TimeSlotDef> = listOf(
    TimeSlotDef("深夜", 0.0, 4.0, 2.0),
    TimeSlotDef("凌晨", 4.0, 6.0, 4.0),
    TimeSlotDef("清晨", 6.0, 7.5, 6.0),
    TimeSlotDef("早晨", 7.5, 10.5, 7.5),
    TimeSlotDef("上午", 10.5, 12.0, 10.5),
    TimeSlotDef("中午", 12.0, 13.5, 12.0),
    TimeSlotDef("下午", 13.5, 17.0, 13.5),
    TimeSlotDef("傍晚", 17.0, 19.0, 17.0),
    TimeSlotDef("晚上", 19.0, 22.0, 19.0),
    TimeSlotDef("夜里", 22.0, 24.0, 22.0),
)

val TIME_SLOTS: List<String> = TIME_SLOT_TABLE.map { it.slot }

val TIME_SLOT_ALIASES: Map<String, String> = mapOf("半夜" to "深夜", "早上" to "早晨")

val TIME_REF_ANCHORS: List<String> = listOf(
    "today", "tomorrow", "yesterday", "day_after_tomorrow", "day_before_yesterday",
    "this_week", "next_week", "last_week", "this_month", "next_month", "last_month",
    "this_year", "next_year", "last_year",
)

val TIME_REF_ANCHOR_LABELS: Map<String, String> = mapOf(
    "today" to "今天", "tomorrow" to "明天", "yesterday" to "昨天",
    "day_after_tomorrow" to "后天", "day_before_yesterday" to "前天",
    "this_week" to "本周", "next_week" to "下周", "last_week" to "上周",
    "this_month" to "本月", "next_month" to "下个月", "last_month" to "上个月",
    "this_year" to "今年", "next_year" to "明年", "last_year" to "去年",
)

val TIME_REF_WEEKDAYS: List<String> = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

/** Structured parse of a legacy `timeRef` token. */
data class TimeRef(
    val explicit: String? = null,
    val anchor: String? = null,
    val offsetDays: Int? = null,
    val slot: String? = null,
    val weekday: String? = null,
)

/** Result of resolving a [TimeRef] to a human label plus an optional ISO instant. */
data class ResolvedTime(
    val day: String?,
    val range: String?,
    val slot: String,
    val text: String,
    val iso: String?,
)

fun padTwoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()

fun normalizeTimeSlot(value: String?): String {
    val slot = value.orEmpty().trim()
    if (slot in TIME_SLOTS) return slot
    return TIME_SLOT_ALIASES[slot].orEmpty()
}

fun getTimeSlot(time: LocalTime): String {
    val hour = time.hour + time.minute / 60.0
    for (entry in TIME_SLOT_TABLE) {
        if (hour >= entry.start && hour < entry.end) return entry.slot
    }
    return "深夜"
}

/** Logical day: the calendar day after subtracting [DAY_START_HOUR]. */
fun logicalDay(base: ZonedDateTime): LocalDate =
    base.minusHours(DAY_START_HOUR.toLong()).toLocalDate()

fun formatAbsoluteDate(date: LocalDate): String =
    "${date.year}-${padTwoDigits(date.monthValue)}-${padTwoDigits(date.dayOfMonth)}"

fun shiftDays(date: LocalDate, days: Long): LocalDate = date.plusDays(days)

/** Monday-based week start (JS `getWeekStart`, where Sunday maps back 6 days). */
fun getWeekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

fun parseZoned(value: String?, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
    val text = value.orEmpty().trim()
    if (text.isEmpty()) return null
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    val normalized = text.replace('/', '-').replace(' ', 'T')
    runCatching { return LocalDateTime.parse(normalized).atZone(zone) }
    return null
}

fun buildResolvedTime(
    dayDate: LocalDate?,
    slot: String?,
    rangeLabel: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): ResolvedTime? {
    val normalizedSlot = normalizeTimeSlot(slot)
    val dayLabel = dayDate?.let { formatAbsoluteDate(it) }
    val suffix = if (normalizedSlot.isNotEmpty()) " $normalizedSlot" else ""
    val text = when {
        dayLabel != null -> dayLabel + suffix
        rangeLabel != null -> rangeLabel + suffix
        else -> ""
    }
    if (text.isEmpty()) return null
    var iso: String? = null
    if (dayDate != null && normalizedSlot.isNotEmpty()) {
        val hour = TIME_SLOT_TABLE.first { it.slot == normalizedSlot }.isoHour
        val whole = hour.toInt()
        val minute = ((hour - whole) * 60).roundToInt()
        iso = ZonedDateTime.of(dayDate, LocalTime.of(whole, minute), zone).toInstant().toString()
    }
    return ResolvedTime(day = dayLabel, range = rangeLabel, slot = normalizedSlot, text = text, iso = iso)
}

private fun resolveExplicit(raw: String, slot: String, zone: ZoneId): ResolvedTime? {
    val dateOnly = Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$").matchEntire(raw)
    if (dateOnly != null) {
        val year = dateOnly.groupValues[1].toInt()
        val month = dateOnly.groupValues[2].toInt()
        val day = dateOnly.groupValues[3].toInt()
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return buildResolvedTime(date, slot, null, zone)
    }
    val zoned = parseZoned(raw, zone) ?: return null
    val hasTime = Regex("[T ]\\d{2}:\\d{2}").containsMatchIn(raw)
    return if (hasTime) {
        ResolvedTime(
            day = formatAbsoluteDate(zoned.toLocalDate()),
            range = null,
            slot = getTimeSlot(zoned.toLocalTime()),
            text = raw,
            iso = zoned.toInstant().toString(),
        )
    } else {
        buildResolvedTime(zoned.toLocalDate(), slot, null, zone)
    }
}

/** timeRef token -> absolute time; null means it could not be resolved. */
fun resolveTimeRef(ref: TimeRef?, base: ZonedDateTime, zone: ZoneId = base.zone): ResolvedTime? {
    ref ?: return null
    val slot = normalizeTimeSlot(ref.slot)
    val explicitRaw = ref.explicit.orEmpty().trim()
    if (explicitRaw.isNotEmpty()) return resolveExplicit(explicitRaw, ref.slot.orEmpty(), zone)

    var anchor = ref.anchor.orEmpty().trim().lowercase()
    if (anchor.isEmpty() && (ref.offsetDays != null || slot.isNotEmpty())) anchor = "today"
    if (anchor !in TIME_REF_ANCHORS) return null

    val logical = logicalDay(base)
    val weekday = TIME_REF_WEEKDAYS.indexOf(ref.weekday.orEmpty().trim().lowercase())
    val extraDays = (ref.offsetDays ?: 0).coerceIn(-60, 60)

    val dayAnchors = mapOf(
        "today" to 0L,
        "tomorrow" to 1L,
        "yesterday" to -1L,
        "day_after_tomorrow" to 2L,
        "day_before_yesterday" to -2L,
    )
    dayAnchors[anchor]?.let { return buildResolvedTime(logical.plusDays(it + extraDays), ref.slot, null, zone) }

    when (anchor) {
        "this_week", "next_week", "last_week" -> {
            val shift = when (anchor) {
                "next_week" -> 7L
                "last_week" -> -7L
                else -> 0L
            }
            val weekStart = getWeekStart(logical).plusDays(shift)
            return if (weekday >= 0) {
                buildResolvedTime(weekStart.plusDays(weekday.toLong()), ref.slot, null, zone)
            } else {
                buildResolvedTime(
                    null,
                    ref.slot,
                    formatAbsoluteDate(weekStart) + "～" + formatAbsoluteDate(weekStart.plusDays(6)),
                    zone,
                )
            }
        }
        "this_month", "next_month", "last_month" -> {
            val shift = when (anchor) {
                "next_month" -> 1L
                "last_month" -> -1L
                else -> 0L
            }
            val monthAnchor = LocalDate.of(logical.year, logical.monthValue, 1).plusMonths(shift)
            return if (weekday >= 0) {
                val dow = monthAnchor.dayOfWeek.value - 1
                val offset = ((weekday - dow) % 7 + 7) % 7
                buildResolvedTime(monthAnchor.plusDays(offset.toLong()), ref.slot, null, zone)
            } else {
                buildResolvedTime(
                    null,
                    ref.slot,
                    "${monthAnchor.year}-${padTwoDigits(monthAnchor.monthValue)}（${TIME_REF_ANCHOR_LABELS[anchor]}）",
                    zone,
                )
            }
        }
        "this_year", "next_year", "last_year" -> {
            val shift = when (anchor) {
                "next_year" -> 1L
                "last_year" -> -1L
                else -> 0L
            }
            return buildResolvedTime(
                null,
                ref.slot,
                "${logical.year + shift}年（${TIME_REF_ANCHOR_LABELS[anchor]}）",
                zone,
            )
        }
    }
    return null
}

private data class RelativeDayAnchor(val word: String, val offset: Int)
private data class RelativeShortAnchor(val word: String, val offset: Int)
private data class RelativePhrase(val literal: String, val offset: Int, val slot: String, val anchorWord: String)

private val RELATIVE_DAY_ANCHORS = listOf(
    RelativeDayAnchor("大后天", 3), RelativeDayAnchor("大前天", -3),
    RelativeDayAnchor("后天", 2), RelativeDayAnchor("前天", -2),
    RelativeDayAnchor("明天", 1), RelativeDayAnchor("明日", 1),
    RelativeDayAnchor("次日", 1), RelativeDayAnchor("翌日", 1),
    RelativeDayAnchor("今天", 0), RelativeDayAnchor("今日", 0),
    RelativeDayAnchor("当天", 0), RelativeDayAnchor("当日", 0),
    RelativeDayAnchor("昨天", -1), RelativeDayAnchor("昨日", -1),
)

private val RELATIVE_SHORT_ANCHORS = listOf(
    RelativeShortAnchor("明", 1), RelativeShortAnchor("今", 0), RelativeShortAnchor("昨", -1),
)

private val RELATIVE_SHORT_SLOTS = mapOf(
    "早" to "早晨", "晨" to "清晨", "午" to "中午", "晚" to "晚上", "夜" to "夜里",
)

private val RELATIVE_CHINESE_NUMBERS = mapOf(
    "一" to 1, "两" to 2, "二" to 2, "三" to 3, "四" to 4,
    "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
)

private val RELATIVE_ANCHOR_BLOCKERS = mapOf(
    "今天" to listOf("气"),
    "今日" to listOf("头"),
    "明天" to listOf("理"),
)

private val RELATIVE_TIME_PHRASES: List<RelativePhrase> by lazy {
    val slotWords = TIME_SLOTS + TIME_SLOT_ALIASES.keys
    val phrases = mutableListOf<RelativePhrase>()
    for (anchor in RELATIVE_DAY_ANCHORS) {
        for (slotWord in slotWords) {
            phrases += RelativePhrase(anchor.word + slotWord, anchor.offset, slotWord, anchor.word)
        }
        phrases += RelativePhrase(anchor.word, anchor.offset, "", anchor.word)
    }
    for (anchor in RELATIVE_SHORT_ANCHORS) {
        for ((shortSlot, slot) in RELATIVE_SHORT_SLOTS) {
            phrases += RelativePhrase(anchor.word + shortSlot, anchor.offset, slot, "")
        }
    }
    phrases.sortedByDescending { it.literal.length }
}

/** Free-text relative time -> absolute date (legacy compatibility and write-time fallback). */
fun parseRelativeText(text: String?, base: ZonedDateTime, zone: ZoneId = base.zone): String {
    val value = text.orEmpty()
    if (value.isEmpty()) return value
    val logical = logicalDay(base)
    var result = value

    for (phrase in RELATIVE_TIME_PHRASES) {
        var from = 0
        while (true) {
            val index = result.indexOf(phrase.literal, from)
            if (index < 0) break
            val next = result.getOrNull(index + phrase.literal.length)?.toString()
            val blockers = RELATIVE_ANCHOR_BLOCKERS[phrase.anchorWord].orEmpty()
            if (phrase.slot.isEmpty() && next != null && blockers.contains(next)) {
                from = index + phrase.literal.length
                continue
            }
            val replacement = formatAbsoluteDate(logical.plusDays(phrase.offset.toLong())) +
                if (phrase.slot.isNotEmpty()) " ${normalizeTimeSlot(phrase.slot)}" else ""
            result = result.substring(0, index) + replacement + result.substring(index + phrase.literal.length)
            from = index + replacement.length
        }
    }

    result = Regex("(这|本|上|下)周([一二三四五六日天])").replace(result) { match ->
        val prefix = match.groupValues[1]
        val weekdayChar = match.groupValues[2]
        var weekdayIndex = "一二三四五六日".indexOf(weekdayChar)
        if (weekdayIndex < 0) weekdayIndex = 6
        val weekShift = when (prefix) {
            "下" -> 7L
            "上" -> -7L
            else -> 0L
        }
        formatAbsoluteDate(getWeekStart(logical).plusDays(weekShift + weekdayIndex))
    }

    result = Regex("(这个月|本月|上个月|下个月|上月|下月)").replace(result) { match ->
        val shift = when (match.value) {
            "上个月", "上月" -> -1L
            "下个月", "下月" -> 1L
            else -> 0L
        }
        val monthAnchor = LocalDate.of(logical.year, logical.monthValue, 1).plusMonths(shift)
        "${monthAnchor.year}-${padTwoDigits(monthAnchor.monthValue)}"
    }

    result = Regex("(今年|去年|明年)").replace(result) { match ->
        val shift = when (match.value) {
            "去年" -> -1L
            "明年" -> 1L
            else -> 0L
        }
        "${logical.year + shift}年"
    }

    result = Regex("(\\d{1,3}|[一两二三四五六七八九十])\\s*天\\s*(以后|之后|以前|之前|后|前)").replace(result) { match ->
        val amountText = match.groupValues[1]
        val amount = if (Regex("^\\d+$").matches(amountText)) {
            amountText.toInt()
        } else {
            RELATIVE_CHINESE_NUMBERS[amountText] ?: 0
        }
        val isFuture = match.groupValues[2] in listOf("以后", "之后", "后")
        formatAbsoluteDate(logical.plusDays(if (isFuture) amount.toLong() else -amount.toLong()))
    }

    return result
}

/** Alias used by the memory pipeline: normalize relative wording before persisting a field. */
fun cleanRelativeTimeText(text: String?, base: ZonedDateTime, zone: ZoneId = base.zone): String =
    parseRelativeText(text, base, zone)
