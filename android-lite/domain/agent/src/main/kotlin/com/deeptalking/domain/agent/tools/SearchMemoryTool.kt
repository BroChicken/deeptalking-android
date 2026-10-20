package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.ShortTermMemory
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
 * Memory recall. Three modes, in priority order:
 *
 * 1. **inventory** (`category`) — only ids, keys and update times of that category; the
 *    model uses it to spot stale/duplicate/wrong entries before `memory_write`;
 * 2. **date recall** (`date` / `timeRef`) — that day's raw messages, summaries and
 *    long-term memories (a spoken date cannot match a stored absolute date through the
 *    keyword scorer, so the model normalizes it and asks here);
 * 3. **keyword recall** (`query`) — ranked long-term hits plus recent short-term hits.
 */
class SearchMemoryTool(private val memory: MemoryService) : AgentTool {

    override val definition = ToolDefinition(
        name = "search_memory",
        description = "检索记忆库。①**按日期召回**——用户问起某个具体日期/某天发生的事时（如“九号晚上”“三号”“上周五”“那天记不记得”），用 date 或 timeRef 按日期召回，会返回那天的**原始对话**、当天摘要与当天长期记忆；date 写 YYYY-MM-DD（依据系统给的时间基准把口语日期换算成绝对日期），相对说法（昨天/上周五/三天前）用 timeRef。②**按关键词检索**——回忆过往约定、用户喜好与习惯、共同经历、关系事实，或**刚滑出近期摘要窗口的事**时用 query；返回 results（长期记忆）与 shortTerm（短期摘要）。③**盘点**——给 category 时只返回该分类条目的 id、关键词与更新时间（不含详情），用于发现过期、重复或错误的记忆，再交给 memory_write 修正。query/date/timeRef/category 至少给一个。默认检索全员共享记忆；群组对话中若想检索某位成员的私人记忆，可传入 memberName（不传则检索共享记忆，必要时可再带 memberName 检索一次）。",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"要检索的关键词或短语（按主题检索时使用）"},"date":{"type":"string","description":"按日期召回：YYYY-MM-DD，须依据时间基准把口语日期换算成绝对日期"},"timeRef":{"type":"object","additionalProperties":false,"description":"相对时间词元：anchor/offsetDays/weekday/slot/explicit；explicit 已是绝对时间时填它，其余忽略","properties":{"anchor":{"type":"string"},"offsetDays":{"type":"integer"},"weekday":{"type":"string"},"slot":{"type":"string","description":"时段：深夜/凌晨/清晨/早晨/上午/中午/下午/傍晚/晚上/夜里"},"explicit":{"type":"string"}}},"category":{"type":"string","enum":["userProfile","relationship","events","promises","habits"],"description":"盘点模式：只返回该分类条目的ID、关键词与更新时间"},"limit":{"type":"integer","minimum":1,"maximum":50,"description":"返回条数，默认 10"},"memberName":{"type":"string","description":"可选，群组对话中指定检索某位成员的私人记忆（不传则检索共享记忆）"}},"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val query = ToolArgs.string(args, "query").trim()
        val dateArg = ToolArgs.string(args, "date").trim()
        val category = ToolArgs.string(args, "category").trim()
        val memberName = ToolArgs.string(args, "memberName").trim()
        val timeRef = ToolArgs.timeRef(args, "timeRef")
        val limit = (ToolArgs.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        if (query.isEmpty() && dateArg.isEmpty() && timeRef == null && category.isEmpty()) {
            return AgentToolResult(errorJson("请至少给出 query、date、timeRef 或 category 之一"))
        }

        if (category.isNotEmpty()) {
            if (category !in CATEGORIES) {
                return AgentToolResult(errorJson("category 只能是 userProfile / relationship / events / promises / habits"))
            }
            return inventory(context.character, category, query, memberName, limit)
        }

        val nowZoned = ZonedDateTime.now()
        val resolved = resolveTimeRef(timeRef, nowZoned)
        val slotArg = ToolArgs.string(args, "slot").trim()
        val requestedDate = requestedDate(dateArg, resolved)
        if (dateArg.isNotEmpty() && requestedDate == null) {
            return AgentToolResult(errorJson("date 必须是 YYYY-MM-DD（如 2026-10-09），相对说法请改用 timeRef"))
        }
        if (requestedDate == null && query.isEmpty()) {
            return AgentToolResult(errorJson("timeRef 无法换算成具体日期，请改用 date（YYYY-MM-DD）或补充 query"))
        }
        val requestedSlot = normalizeTimeSlot(
            slotArg.ifEmpty { resolved?.slot.orEmpty() },
        )

        val character = context.character
        val member = if (memberName.isNotEmpty()) MemoryToolSupport.resolveMember(character, memberName) else null
        val host = if (member != null) MemoryToolSupport.memberAsCharacter(character, member) else character

        return if (requestedDate != null) {
            recallByDate(character, host, member, requestedDate, requestedSlot, query, limit)
        } else {
            keywordSearch(character, host, member, query, limit)
        }
    }

    // ---- inventory -------------------------------------------------------------------

    private fun inventory(
        character: Character,
        category: String,
        keyword: String,
        memberName: String,
        limit: Int,
    ): AgentToolResult {
        val listed = memory
            .listMemories(character, category, keyword, memberName.ifEmpty { null }, limit)
            .sortedByDescending { it.importance }
            .take(limit)
        val items = buildJsonArray {
            listed.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("category", item.category.legacyKey)
                        put("key", trimText(item.key, 100))
                        put("updatedAt", MemoryToolSupport.formatContextTime(item.updatedAt ?: item.createdAt))
                    },
                )
            }
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("mode", "inventory")
            put("category", category)
            if (keyword.isNotEmpty()) put("keyword", keyword)
            put("count", listed.size)
            put("items", items)
        }
        return AgentToolResult(payload.toString())
    }

    // ---- keyword recall (legacy path) -------------------------------------------------

    private fun keywordSearch(
        character: Character,
        host: Character,
        member: com.deeptalking.core.model.GroupMember?,
        query: String,
        limit: Int,
    ): AgentToolResult {
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
            .take(maxOf(MAX_CANDIDATES, limit))

        // Legacy bumps usage for every retrieved candidate (before the relevance filter).
        val nowIso = Instant.now().toString()
        val bumpIds = candidates.map { it.first.id }.filter { it.isNotEmpty() }.toSet()
        val updated = bumpUsage(character, member, bumpIds, nowIso)

        val items = buildJsonArray {
            candidates.take(limit).forEach { (item, score) -> add(longTermJson(host, item, score)) }
        }
        // The recent window is injected in full every turn, but anything that slid out of
        // it is otherwise unreachable without a date: keyword recall used to search
        // long-term only, so "我上次说的那件事" could not find a recent short-term entry.
        val shorts = host.shortTerm
            .map { it to shortTermScore(query, it.content) }
            .filter { it.second >= MIN_SHORT_TERM_MATCH_SCORE }
            .sortedByDescending { it.second }
            .take(minOf(MAX_SHORT, limit))
        val shortItems = buildJsonArray {
            shorts.forEach { (item, score) -> add(shortTermJson(host, item, score)) }
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("query", query)
            put("total", items.size + shortItems.size)
            put("results", items)
            put("shortTerm", shortItems)
        }
        return AgentToolResult(payload.toString(), updatedCharacter = updated)
    }

    // ---- date recall ------------------------------------------------------------------

    private fun recallByDate(
        character: Character,
        host: Character,
        member: com.deeptalking.core.model.GroupMember?,
        date: String,
        slot: String,
        keyword: String,
        limit: Int,
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
                        put("content", maskUserWord(character, trimText(message.content, 1200)))
                    },
                )
            }
        }

        val shorts = host.shortTerm
            .filter { isoMatches(it.eventTime ?: it.createdAt, date, slot) }
            .filter { needle.isEmpty() || it.content.lowercase().contains(needle) }
            .takeLast(minOf(MAX_SHORT, limit))
        val shortItems = buildJsonArray { shorts.forEach { add(shortTermJson(host, it, 0.0)) } }

        val longs = host.longTerm
            .filter { isoMatches(it.eventTime ?: it.createdAt, date, slot) }
            .filter { needle.isEmpty() || searchableText(it).contains(needle) }
            .take(minOf(MAX_LONG, limit))
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

    private fun longTermJson(host: Character, item: LongTermMemory, score: Double) = buildJsonObject {
        put("id", item.id)
        put("category", item.category.legacyKey)
        put("subject", item.subject.name.lowercase())
        put("subjectLabel", MemoryToolSupport.actorLabel(host, item.subject))
        put("key", maskUserWord(host, trimText(item.key, 120)))
        put("value", maskUserWord(host, trimText(item.value, 1200)))
        put("eventTime", MemoryToolSupport.formatContextTime(item.eventTime ?: item.createdAt))
        put("importance", item.importance)
        if (score > 0.0) put("relevance", round(score * 100) / 100)
    }

    private fun shortTermJson(host: Character, item: ShortTermMemory, score: Double) = buildJsonObject {
        put("id", item.id)
        put("store", "shortTerm")
        put("time", MemoryToolSupport.formatContextTime(item.eventTime ?: item.createdAt))
        put("content", maskUserWord(host, trimText(item.content, 1200)))
        if (score > 0.0) put("relevance", round(score * 100) / 100)
    }

    /**
     * Lightweight relevance for short-term text (long-term ranking uses tags/keys, which
     * short-term entries do not have): full containment scores 1.0, otherwise the share
     * of the query's character bigrams that appear in the text.
     */
    private fun shortTermScore(query: String, content: String): Double {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return 0.0
        val text = content.lowercase()
        if (text.contains(q)) return 1.0
        val chars = q.replace(Regex("[\\s，。！？、,.;:：\"'“”]"), "")
        if (chars.length < 2) return 0.0
        val grams = chars.windowed(2)
        return grams.count { text.contains(it) }.toDouble() / grams.size
    }

    private fun searchableText(item: LongTermMemory): String =
        (item.key + " " + item.value + " " + item.tags.joinToString(" ")).lowercase()

    private fun bumpUsage(
        character: Character,
        member: com.deeptalking.core.model.GroupMember?,
        ids: Set<String>,
        nowIso: String,
    ): Character {
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
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
        const val MAX_CANDIDATES = 12
        const val MAX_RAW_MESSAGES = 12
        const val MAX_SHORT = 8
        const val MAX_LONG = 12
        val CATEGORIES = setOf("userProfile", "relationship", "events", "promises", "habits")

        /**
         * Short-term relevance is scored on a 0..1 scale (bigram overlap), while the
         * long-term ranker uses the legacy >1 scale — so it needs its own floor.
         */
        const val MIN_SHORT_TERM_MATCH_SCORE = 0.5
    }
}
