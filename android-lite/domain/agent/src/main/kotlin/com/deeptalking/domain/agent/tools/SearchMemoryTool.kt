package com.deeptalking.domain.agent.tools

import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.prompts.maskUserWord
import com.deeptalking.domain.agent.prompts.trimText
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.domain.memory.score as memoryScore
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.math.round

/** Port of the `search_memory` branch in tool-execution.js. */
class SearchMemoryTool(private val memory: MemoryService) : AgentTool {

    override val definition = ToolDefinition(
        name = "search_memory",
        description = "检索记忆库中与关键词相关的内容：过往约定、用户喜好与习惯、共同经历的事件、关系事实等。需要回忆过去的事情、确认之前的承诺或约定时使用；结果按相关度排序，包含每条记忆的时间、分类、主体（subjectLabel 表示这条记忆讲的是谁）与内容。默认检索全员共享记忆；群组对话中若想检索某位成员的私人记忆，可传入 memberName（该成员自己的记忆，如\"张三\"。注意：若该成员的私人记忆里没有，也可能是共享记忆，必要时可再不带 memberName 检索一次）。",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"要检索的关键词或短语"},"memberName":{"type":"string","description":"可选，群组对话中指定检索某位成员的私人记忆（不传则检索共享记忆）"}},"required":["query"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val query = ToolArgs.string(args, "query").trim()
        if (query.isEmpty()) {
            return AgentToolResult(errorJson("缺少检索关键词，请补充 query 参数（要回忆的关键词）后再试"))
        }
        val memberName = ToolArgs.string(args, "memberName").trim()
        val character = context.character
        val member = if (memberName.isNotEmpty()) MemoryToolSupport.resolveMember(character, memberName) else null
        val host = if (member != null) MemoryToolSupport.memberAsCharacter(character, member) else character

        val now = System.currentTimeMillis()
        // Legacy `retrieveRelevantMemories`: persisted pending-recall items are
        // force-included first (score 999) before ordinary keyword hits.
        val pending = host.pendingRecall
        val pendingIds = pending.map { it.id }.toSet()
        val candidates = (
            pending.map { it to 999.0 } +
                host.longTerm.filter { it.id !in pendingIds }.map { it to memoryScore(query, it, now) }
            )
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(MAX_CANDIDATES)

        // Legacy bumps usage for every retrieved candidate (before the relevance filter).
        val nowIso = Instant.now().toString()
        val bumpIds = candidates.map { it.first.id }.filter { it.isNotEmpty() }.toSet()
        val updated = bumpUsage(character, member, bumpIds, nowIso)

        val items = buildJsonArray {
            candidates.asSequence()
                .filter { it.second >= MIN_RELEVANCE }
                .take(MAX_RESULTS)
                .forEach { (item, score) ->
                    add(
                        buildJsonObject {
                            put("id", item.id)
                            put("category", item.category.legacyKey)
                            put("subject", item.subject.name.lowercase())
                            put("subjectLabel", MemoryToolSupport.actorLabel(host, item.subject))
                            put("key", maskUserWord(host, trimText(item.key, 80)))
                            put("value", maskUserWord(host, trimText(item.value, 420)))
                            put("eventTime", MemoryToolSupport.formatContextTime(item.eventTime ?: item.createdAt))
                            put("importance", item.importance)
                            put("relevance", round(score * 100) / 100)
                        },
                    )
                }
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("query", query)
            put("total", items.size)
            put("results", items)
        }
        return AgentToolResult(payload.toString(), updatedCharacter = updated)
    }

    private fun bumpUsage(
        character: com.deeptalking.core.model.Character,
        member: com.deeptalking.core.model.GroupMember?,
        ids: Set<String>,
        nowIso: String,
    ): com.deeptalking.core.model.Character {
        fun bump(item: com.deeptalking.core.model.LongTermMemory) =
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

    private companion object {
        const val MIN_RELEVANCE = 4.0
        const val MAX_CANDIDATES = 12
        const val MAX_RESULTS = 6
    }
}
