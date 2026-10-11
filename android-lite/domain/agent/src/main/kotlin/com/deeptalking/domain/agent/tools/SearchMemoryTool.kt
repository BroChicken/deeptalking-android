package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.prompts.maskUserWord
import com.deeptalking.domain.agent.prompts.trimText
import com.deeptalking.domain.memory.MIN_MATCH_SCORE
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.domain.memory.ResolvedTime
import com.deeptalking.domain.memory.formatAbsoluteDate
import com.deeptalking.domain.memory.getTimeSlot
import com.deeptalking.domain.memory.logicalDay
import com.deeptalking.domain.memory.normalizeTimeSlot
import com.deeptalking.domain.memory.parseZoned
import com.deeptalking.domain.memory.rankMemories
import com.deeptalking.domain.memory.resolveTimeRef
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.round

/**
 * Port of the `search_memory` branch in tool-execution.js, extended (deliberate
 * deviation) so the model can recall by **date** as well as by keyword.
 *
 * Keyword recall is the legacy path. Date recall exists because a spoken date
 * ("九号晚上") cannot match the stored absolute date (`2026-10-09`) through the
 * client's character-bigram keyword scorer — the model detects the date, normalizes
 * it to `YYYY-MM-DD` (using the system time base it is given), and calls this tool
 * to pull that day's raw messages plus its summaries and long-term memories.
 */
class SearchMemoryTool(private val memory: MemoryService) : AgentTool {

    override val definition = ToolDefinition(
        name = "search_memory",
        description = "检索记忆库。两种用法：①**按日期召回**——用户问起某个具体日期/某天发生的事时（如“九号晚上”“三号”“上周五”“那天记不记得”），用 date 或 timeRef 按日期召回，会返回那天的**原始对话**、当天摘要与当天长期记忆，比凭摘要回忆更准确；date 写 YYYY-MM-DD，请依据系统给的时间基准把用户的口语日期换算成绝对日期，相对说法（昨天/上周五/三天前）改用 timeRef。②**按关键词检索**——回忆过往约定、用户喜好与习惯、共同经历、关系事实时用 query。query/date/timeRef 至少给一个。默认检索全员共享记忆；群组对话中若想检索某位成员的私人记忆，可传入 memberName（该成员自己的记忆，如\"张三\"。注意：若该成员的私人记忆里没有，也可能是共享记忆，必要时可再不带 memberName 检索一次）。",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"要检索的关键词或短语（按主题检索时使用）"},"date":{"type":"string","description":"按日期召回：YYYY-MM-DD，须依据时间基准把口语日期换算成绝对日期"},"timeRef":{"type":"object","description":"按日期召回：相对时间词元（昨天/上周五/三天前等），由客户端换算成绝对日期","additionalProperties":false,"properties":{"anchor":{"type":"string","description":"相对基准：today/tomorrow/yesterday/day_after_tomorrow/day_before_yesterday/this_week/next_week/last_week/this_month/next_month/last_month/this_year/next_year/last_year"},"offsetDays":{"type":"integer","description":"在anchor基础上的天数偏移(-60~60)"},"weekday":{"type":"string","description":"星期：mon/tue/wed/thu/fri/sat/sun"},"slot":{"type":"string","description":"时段：深夜/凌晨/清晨/早晨/上午/中午/下午/傍晚/晚上/夜里"},"explicit":{"type":"string","description":"已是绝对时间（ISO 或 YYYY-MM-DD），填了则忽略其余"}}},"slot":{"type":"string","description":"可选，限定当天的时段：深夜/凌晨/清晨/早晨/上午/中午/下午/傍晚/晚上/夜里"},"memberName":{"type":"string","description":"可选，群组对话中指定检索某位成员的私人记忆（不传则检索共享记忆）"}},"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val query = ToolArgs.string(args, "query").trim()
        val dateArg = ToolArgs.string(args, "date").trim()
        val memberName = ToolArgs.string(args, "memberName").trim()

        val nowZoned = ZonedDateTime.now()
        val resolved = resolveTimeRef(ToolArgs.timeRef(args, "timeRef"), nowZoned)
        val requestedDate = requestedDate(dateArg, resolved)
        val requestedSlot = normalizeTimeSlot(ToolArgs.string(args, "slot").trim().ifEmpty { resolved?.slot.orEmpty() })

        val character = context.character
        val member = if (memberName.isNotEmpty()) MemoryToolSupport.resolveMember(character, memberName) else null
        val host = if (member != null) MemoryToolSupport.memberAsCharacter(character, member) else character

        return if (requestedDate != null) {
            recallByDate(character, host, member, requestedDate, requestedSlot, query)
        } else {
            keywordSearch(character, host, member, query)
        }
    }

    // ---- keyword recall (legacy path) -------------------------------------------------

    private fun keywordSearch(
        character: com.deeptalking.core.model.Character,
        host: com.deeptalking.core.model.Character,
        member: com.deeptalking.core.model.GroupMember?,
        query: String,
    ): AgentToolResult {
        if (query.isEmpty()) {
            return AgentToolResult(errorJson("缺少检索内容：请提供 query 关键词，或提供 date/timeRef 按日期召回"))
        }
        val now = System.currentTimeMillis()
        // Legacy `retrieveRelevantMemories`: persisted pending-recall items are
        // force-included first (score 999) before ordinary keyword hits.
        val pending = host.pendingRecall
        val pendingIds = pending.map { it.id }.toSet()
        val candidates = (
            pending.map { it to 999.0 } +
                rankMemories(query, host.longTerm.filter { it.id !in pendingIds }, now)
                    .filter { it.matchScore >= MIN_MATCH_SCORE }
                    .map { it.memory to it.score }
            )
            .sortedByDescending { it.second }
            .take(MAX_CANDIDATES)

        // Legacy bumps usage for every retrieved candidate (before the relevance filter).
        val nowIso = Instant.now().toString()
        val bumpIds = candidates.map { it.first.id }.filter { it.isNotEmpty() }.toSet()
        val updated = bumpUsage(character, member, bumpIds, nowIso)

        val items = buildJsonArray {
            candidates.take(MAX_RESULTS).forEach { (item, score) -> add(longTermJson(host, item, score)) }
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("query", query)
            put("total", items.size)
            put("results", items)
        }
        return AgentToolResult(payload.toString(), updatedCharacter = updated)
    }

    // ---- date recall ------------------------------------------------------------------

    private fun recallByDate(
        character: com.deeptalking.core.model.Character,
        host: com.deeptalking.core.model.Character,
        member: com.deeptalking.core.model.GroupMember?,
        date: String,
        slot: String,
        keyword: String,
    ): AgentToolResult {
        val needle = keyword.trim().lowercase()

        val raw = character.instant
            .filter { !it.isLoading && !it.internalOnly && it.content.isNotBlank() }
            .filter { isoMatches(it.timestamp, date, slot) }
            .filter { needle.isEmpty() || it.content.lowercase().contains(needle) }
            .takeLast(MAX_RAW_MESSAGES)
        val rawItems = buildJsonArray {
            raw.forEach { message ->
                add(
                    buildJsonObject {
                        put("id", message.id)
                        put("role", message.role.name.lowercase())
                        put("time", MemoryToolSupport.formatContextTime(message.timestamp))
                        put("content", maskUserWord(character, trimText(message.content, 360)))
                    },
                )
            }
        }

        val shorts = host.shortTerm
            .filter { isoMatches(it.eventTime ?: it.createdAt, date, slot) }
            .filter { needle.isEmpty() || it.content.lowercase().contains(needle) }
            .takeLast(MAX_SHORT)
        val shortItems = buildJsonArray {
            shorts.forEach { item ->
                add(
                    buildJsonObject {
                        put("time", MemoryToolSupport.formatContextTime(item.eventTime ?: item.createdAt))
                        put("content", maskUserWord(host, trimText(item.content, 320)))
                    },
                )
            }
        }

        val longs = host.longTerm
            .filter { isoMatches(it.eventTime ?: it.createdAt, date, slot) }
            .filter { needle.isEmpty() || searchableText(it).contains(needle) }
            .take(MAX_LONG)
        val longItems = buildJsonArray { longs.forEach { add(longTermJson(host, it, 0.0)) } }

        val nowIso = Instant.now().toString()
        val updated = bumpUsage(character, member, longs.map { it.id }.filter { it.isNotEmpty() }.toSet(), nowIso)

        val payload = buildJsonObject {
            put("ok", true)
            put("mode", "date")
            put("date", date)
            if (slot.isNotEmpty()) put("slot", slot)
            put("total", rawItems.size + shortItems.size + longItems.size)
            put("rawMessages", rawItems)
            put("shortTerm", shortItems)
            put("longTerm", longItems)
            put("note", "以上是该日期的原始对话与记忆。原始对话最完整，优先据此回答；不足时再参考摘要与长期记忆。")
        }
        return AgentToolResult(payload.toString(), updatedCharacter = updated)
    }

    // ---- helpers ----------------------------------------------------------------------

    private fun longTermJson(host: com.deeptalking.core.model.Character, item: LongTermMemory, score: Double) = buildJsonObject {
        put("id", item.id)
        put("category", item.category.legacyKey)
        put("subject", item.subject.name.lowercase())
        put("subjectLabel", MemoryToolSupport.actorLabel(host, item.subject))
        put("key", maskUserWord(host, trimText(item.key, 80)))
        put("value", maskUserWord(host, trimText(item.value, 420)))
        put("eventTime", MemoryToolSupport.formatContextTime(item.eventTime ?: item.createdAt))
        put("importance", item.importance)
        if (score > 0.0) put("relevance", round(score * 100) / 100)
    }

    private fun searchableText(item: LongTermMemory): String =
        (item.key + " " + item.value + " " + item.tags.joinToString(" ")).lowercase()

    private fun bumpUsage(
        character: com.deeptalking.core.model.Character,
        member: com.deeptalking.core.model.GroupMember?,
        ids: Set<String>,
        nowIso: String,
    ): com.deeptalking.core.model.Character {
        fun bump(item: LongTermMemory) =
            if (item.id in ids) item.copy(usageCount = item.usageCount + 1, lastUsageAt = nowIso) else item
        if (ids.isEmpty()) return character
        return if (member != null) {
            character.copy(
                members = character.members.map { m ->
                    if (m.id == member.id) m.copy(longTerm = m.longTerm.map(::bump)) else m
                },
            )
        } else {
            character.copy(longTerm = character.longTerm.map(::bump))
        }
    }

    /** Resolves the requested logical date from an explicit `date` arg or a `timeRef`. */
    private fun requestedDate(dateArg: String, resolved: ResolvedTime?): String? {
        if (dateArg.isNotEmpty()) {
            val match = Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$").matchEntire(dateArg)
            if (match != null) {
                return "%04d-%02d-%02d".format(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                )
            }
            return parseZoned(dateArg)?.let { formatAbsoluteDate(it.toLocalDate()) }
        }
        resolved ?: return null
        resolved.day?.let { return it }
        return resolved.iso?.let { parseZoned(it)?.let { zoned -> formatAbsoluteDate(zoned.toLocalDate()) } }
    }

    /**
     * True when [iso] belongs to the requested date. Matches the calendar day *or*
     * the app's logical day (a day starts at 02:00), so both the model's plain
     * `YYYY-MM-DD` and the "深夜 belongs to the previous day" convention line up.
     */
    private fun isoMatches(iso: String?, date: String, slot: String): Boolean {
        val zoned = parseZoned(iso) ?: return false
        if (slot.isNotEmpty() && getTimeSlot(zoned.toLocalTime()) != slot) return false
        val calendar = formatAbsoluteDate(zoned.toLocalDate())
        val logical = formatAbsoluteDate(logicalDay(zoned))
        return date == calendar || date == logical
    }

    private companion object {
        const val MAX_CANDIDATES = 12
        const val MAX_RESULTS = 6
        const val MAX_RAW_MESSAGES = 12
        const val MAX_SHORT = 8
        const val MAX_LONG = 12
    }
}
