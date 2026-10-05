package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.domain.agent.prompts.DYNAMIC_STATE_FIELDS
import com.deeptalking.domain.agent.prompts.STATIC_PROFILE_FIELDS
import com.deeptalking.domain.agent.prompts.TimeContext
import com.deeptalking.domain.agent.prompts.trimText
import com.deeptalking.domain.memory.evidenceMatchesSource
import com.deeptalking.domain.memory.knownSources
import com.deeptalking.domain.memory.parseZoned
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Helpers shared by the memory tools (port of resolveMemoryHost / actorLabel / formatContextTime). */
internal object MemoryToolSupport {

    fun resolveMember(character: Character, memberName: String): GroupMember? {
        val name = memberName.trim()
        if (name.isEmpty()) return null
        return character.members.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** Mirrors the legacy member store view used by search/list/delete. */
    fun memberAsCharacter(character: Character, member: GroupMember): Character = Character(
        id = member.id,
        name = member.name,
        emoji = member.emoji,
        staticProfile = member.staticProfile,
        dynamicState = member.dynamicState,
        shortTerm = member.shortTerm,
        longTerm = member.longTerm,
        lorebook = member.lorebook,
    )

    fun actorLabel(character: Character, subject: MemorySubject?): String = when (subject) {
        MemorySubject.User -> character.staticProfile.userAddress.replace(Regex("\\s+"), "").ifBlank { "对方" }
        MemorySubject.Character -> character.name.ifBlank { "角色本人" }
        MemorySubject.Relationship -> "你们"
        MemorySubject.World -> "背景"
        else -> "相关记忆"
    }

    private val ISO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Legacy `normalizeTimestamp`: parse to an ISO (ms) instant, else the fallback. */
    fun normalizeTimestamp(value: String?, fallback: String?): String? {
        val text = value.orEmpty().trim()
        if (text.isEmpty()) return fallback
        val zoned = parseZoned(text) ?: return fallback
        return ISO_MILLIS.format(zoned.toInstant())
    }

    /** Legacy `formatContextTime`: "2026年10月04日星期日 18:00:00 (Asia/Shanghai, UTC+08:00)". */
    fun formatContextTime(iso: String?): String {
        val text = iso.orEmpty().trim()
        val instant = if (text.isEmpty()) {
            Instant.now()
        } else {
            runCatching { Instant.parse(text) }.getOrNull()
                ?: parseZoned(text)?.toInstant()
                ?: Instant.now()
        }
        val snapshot = TimeContext.snapshot(instant)
        return "${snapshot.local} (${snapshot.timeZone}, ${snapshot.offset})"
    }

    /**
     * Port of legacy `resolveLorebookSources`: all source ids must resolve through
     * `getKnownSources` (live instant messages + short-term source ids) and at
     * least one user/assistant source must loosely overlap the evidence.
     */
    fun validLorebookSources(character: Character, sourceIds: List<String>, evidence: String?): Boolean {
        val excerpt = trimText(evidence, 300)
        if (excerpt.length < 2) return false
        val ids = sourceIds.map { it.trim() }.filter { it.isNotEmpty() }.take(8)
        if (ids.isEmpty()) return false
        val sources = knownSources(character)
        val resolved = ids.map { sources[it] }
        if (resolved.any { it == null }) return false
        return resolved.any { source ->
            val role = source!!.role
            (role == "user" || role == "assistant") && lorebookEvidenceOverlaps(source.text, excerpt)
        }
    }

    private fun lorebookEvidenceOverlaps(source: String?, evidence: String?): Boolean {
        if (evidenceMatchesSource(source, evidence)) return true
        val ev = evidence.orEmpty().trim()
        val src = source.orEmpty().lowercase()
        if (ev.length < 4 || src.length < 4) return false
        var index = 0
        while (index + 2 <= ev.length) {
            val pair = ev.substring(index, index + 2).lowercase()
            if (Regex("^[\\u4e00-\\u9fff\\w]{2}$").matches(pair) && src.contains(pair)) return true
            index++
        }
        return false
    }

    // ---- cleanFieldValue (port of time.js) ----------------------------------

    private val FIELD_KEYS: Set<String> =
        (STATIC_PROFILE_FIELDS.map { it.first } + DYNAMIC_STATE_FIELDS.map { it.first }).toSet()

    private val FIELD_LABELS: List<String> =
        STATIC_PROFILE_FIELDS.map { it.second } + DYNAMIC_STATE_FIELDS.map { it.second }

    private val FIELD_META_MARKERS = listOf(
        "用户", "角色", "玩家", "希望", "要求", "因为", "由于", "所以", "说明", "备注",
        "设定", "剧情", "依据", "来源", "规则", "指令", "系统", "evidence",
    )

    private val SHORT_FACT_FIELDS = setOf(
        "gender", "age", "race", "language", "userAddress",
        "roleInGroup", "currentOccupation", "currentLocation", "currentMood", "currentGoal",
    )

    /**
     * Port of `cleanFieldValue`: strips a leading field-name prefix, removes only
     * meta parentheticals and, for short-fact fields, explanatory sentences.
     */
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
            text = splitFieldSentences(text).filter { !isExplanatorySentence(it) }.joinToString("")
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

    private fun splitFieldSentences(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()
        for (ch in text) {
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
