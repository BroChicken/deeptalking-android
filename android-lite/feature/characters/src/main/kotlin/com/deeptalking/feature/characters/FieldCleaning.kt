package com.deeptalking.feature.characters

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure field-cleaning / time-normalization helpers ported from the legacy
 * `src/js/memory/time.js` and `src/js/core/normalization.js`.
 *
 * The domain module already contains equivalent logic (`MemoryToolSupport`
 * `cleanFieldValue`, `TimeUtils.parseRelativeText`), but both are either
 * `internal` or behind a module boundary that `:feature:characters` cannot
 * reach, so the editor flows carry their own copy.
 */
object FieldCleaning {

    private const val DAY_START_HOUR = 2

    private val STATIC_FIELD_KEYS = listOf(
        "gender", "age", "race", "appearance", "personality", "values", "fears",
        "background", "keyEvents", "speakingStyle", "language", "userAddress",
    )

    private val DYNAMIC_FIELD_KEYS = listOf(
        "currentSituation", "currentLocation", "currentMood", "currentOccupation",
        "currentGoal", "currentRelationship", "currentImportantOthers",
    )

    private val STATIC_FIELD_LABELS = listOf(
        "性别", "年龄", "种族", "外貌特征", "性格特征", "价值观", "恐惧/弱点",
        "个人背景", "关键过往（里程碑）", "说话风格", "语言/方言", "对用户的称呼",
    )

    private val DYNAMIC_FIELD_LABELS = listOf(
        "当前处境", "当前位置/场景", "当前情绪", "当前职业/身份", "当前目标", "当前关系", "当前重要他人",
    )

    private val FIELD_KEYS: Set<String> = (STATIC_FIELD_KEYS + DYNAMIC_FIELD_KEYS).toSet()

    private val FIELD_LABELS: List<String> = STATIC_FIELD_LABELS + DYNAMIC_FIELD_LABELS

    private val FIELD_META_MARKERS = listOf(
        "用户", "角色", "玩家", "希望", "要求", "因为", "由于", "所以", "说明", "备注",
        "设定", "剧情", "依据", "来源", "规则", "指令", "系统", "evidence",
    )

    private val SHORT_FACT_FIELDS = setOf(
        "gender", "age", "race", "language", "userAddress",
        "roleInGroup", "currentOccupation", "currentLocation", "currentMood", "currentGoal",
    )

    private val TIME_SLOTS = listOf(
        "深夜", "凌晨", "清晨", "早晨", "上午", "中午", "下午", "傍晚", "晚上", "夜里",
    )

    private val TIME_SLOT_ALIASES = mapOf("半夜" to "深夜", "早上" to "早晨")

    private val RELATIVE_DAY_ANCHORS = listOf(
        3 to "大后天", -3 to "大前天", 2 to "后天", -2 to "前天",
        1 to "明天", 1 to "明日", 1 to "次日", 1 to "翌日",
        0 to "今天", 0 to "今日", 0 to "当天", 0 to "当日",
        -1 to "昨天", -1 to "昨日",
    )

    private val RELATIVE_SHORT_ANCHORS = listOf(1 to "明", 0 to "今", -1 to "昨")

    private val RELATIVE_SHORT_SLOTS = mapOf("早" to "早晨", "晨" to "清晨", "午" to "中午", "晚" to "晚上", "夜" to "夜里")

    private val RELATIVE_CHINESE_NUMBERS = mapOf(
        "一" to 1, "两" to 2, "二" to 2, "三" to 3, "四" to 4,
        "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
    )

    private val RELATIVE_ANCHOR_BLOCKERS = mapOf(
        "今天" to listOf("气"),
        "今日" to listOf("头"),
        "明天" to listOf("理"),
    )

    private data class RelativePhrase(val literal: String, val offset: Int, val slot: String, val anchorWord: String)

    private val RELATIVE_TIME_PHRASES: List<RelativePhrase> by lazy {
        val slotWords = TIME_SLOTS + TIME_SLOT_ALIASES.keys
        val phrases = mutableListOf<RelativePhrase>()
        RELATIVE_DAY_ANCHORS.forEach { (offset, word) ->
            slotWords.forEach { slotWord -> phrases += RelativePhrase(word + slotWord, offset, slotWord, word) }
            phrases += RelativePhrase(word, offset, "", word)
        }
        RELATIVE_SHORT_ANCHORS.forEach { (offset, word) ->
            RELATIVE_SHORT_SLOTS.forEach { (shortSlot, slot) ->
                phrases += RelativePhrase(word + shortSlot, offset, slot, "")
            }
        }
        phrases.sortedByDescending { it.literal.length }
    }

    private fun padTwoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()

    private fun formatDayLabel(date: LocalDate): String =
        "${date.year}-${padTwoDigits(date.monthValue)}-${padTwoDigits(date.dayOfMonth)}"

    private fun logicalDay(base: ZonedDateTime): LocalDate =
        base.minusHours(DAY_START_HOUR.toLong()).toLocalDate()

    private fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    private fun normalizeTimeSlot(value: String?): String {
        val slot = value.orEmpty().trim()
        if (slot in TIME_SLOTS) return slot
        return TIME_SLOT_ALIASES[slot].orEmpty()
    }

    private fun trimText(value: String?, maxLength: Int = 0): String {
        val text = value.orEmpty().trim()
        return if (maxLength > 0 && text.length > maxLength) text.take(maxLength) + "…" else text
    }

    /** Legacy `normalizeUserAddress`: single short address token, whitespace stripped, ≤20 chars. */
    fun normalizeUserAddress(value: String?): String =
        value.orEmpty().replace(Regex("\\s+"), "").take(20)

    /** Free-text relative time → absolute date (legacy `parseRelativeText`). */
    fun parseRelativeText(text: String?, base: ZonedDateTime, zone: ZoneId = base.zone): String {
        val value = text.orEmpty()
        if (value.isEmpty()) return value
        val logical = logicalDay(base.withZoneSameInstant(zone))
        var result = value

        RELATIVE_TIME_PHRASES.forEach { phrase ->
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
                val replacement = formatDayLabel(logical.plusDays(phrase.offset.toLong())) +
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
            val shift = when (prefix) {
                "下" -> 7L
                "上" -> -7L
                else -> 0L
            }
            formatDayLabel(weekStart(logical).plusDays(shift + weekdayIndex))
        }

        result = Regex("(这个月|本月|上个月|下个月|上月|下月)").replace(result) { match ->
            val shift = when (match.value) {
                "上个月", "上月" -> -1L
                "下个月", "下月" -> 1L
                else -> 0L
            }
            val anchor = LocalDate.of(logical.year, logical.monthValue, 1).plusMonths(shift)
            "${anchor.year}-${padTwoDigits(anchor.monthValue)}"
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
            formatDayLabel(logical.plusDays(if (isFuture) amount.toLong() else -amount.toLong()))
        }

        return result
    }

    /** Legacy `sanitizeDynamicStateField`: relative wording normalized to an absolute date. */
    fun sanitizeDynamicStateField(key: String, value: String?, base: ZonedDateTime, zone: ZoneId = base.zone): String =
        cleanFieldValue(key, parseRelativeText(value, base, zone))

    /** Basic static field write path: relative time normalized, `userAddress` collapsed to one token. */
    fun normalizeStaticFieldValue(key: String, value: String?, base: ZonedDateTime, zone: ZoneId = base.zone): String =
        if (key == "userAddress") normalizeUserAddress(value) else cleanFieldValue(key, parseRelativeText(value, base, zone))

    /** Port of legacy `cleanFieldValue` (`src/js/memory/time.js`). */
    fun cleanFieldValue(key: String, value: String?): String {
        var text = value.orEmpty()

        val prefixMatch = Regex("^\\s*[^：:\\n]{1,24}[：:]\\s*").find(text)
        if (prefixMatch != null) {
            val prefix = prefixMatch.value.replace(Regex("[：:]"), "").trim()
            val prefixIsField = prefix in FIELD_KEYS || FIELD_LABELS.any { label ->
                label == prefix ||
                    (label.length >= 3 && prefix.length >= 2 && (label.contains(prefix) || prefix.contains(label)))
            }
            if (prefixIsField) text = text.substring(prefixMatch.value.length)
        }

        text = stripMetaParentheticals(text)

        if (key in SHORT_FACT_FIELDS) {
            text = splitSentences(text).filter { !isExplanatorySentence(it) }.joinToString("")
            text = text.replace(Regex("[，,。；;、\\s]+$"), "")
        }

        return trimText(text.replace(Regex("\\s+"), " ").trim(), 700)
    }

    private fun isMetaParenthetical(inner: String): Boolean =
        FIELD_META_MARKERS.any { inner.contains(it) }

    private fun stripMetaParentheticals(text: String): String {
        var previous: String
        var result = text
        do {
            previous = result
            result = Regex("（([^（）()]*)）|\\(([^（）()]*)\\)").replace(result) { match ->
                val inner = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (isMetaParenthetical(inner)) " " else match.value
            }
        } while (result != previous)
        return result
    }

    private fun splitSentences(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()
        text.forEach { ch ->
            buffer.append(ch)
            if (ch in "。！？；，,") {
                chunks += buffer.toString()
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) chunks += buffer.toString()
        return chunks
    }

    private fun isExplanatorySentence(sentence: String): Boolean {
        val text = sentence.trim()
        if (text.isEmpty()) return true
        val hasActor = Regex("用户|角色|玩家").containsMatchIn(text)
        val hasMetaVerb = Regex("希望|要求|提到|决定|改成|改为|设定|剧情需要|说明|备注").containsMatchIn(text)
        val startsMeta = Regex("^(这是|原因是|所以|因此|因为|由于|其实|说明|备注)").containsMatchIn(text)
        if (startsMeta && hasActor) return true
        if (hasActor && hasMetaVerb) return true
        return false
    }
}
