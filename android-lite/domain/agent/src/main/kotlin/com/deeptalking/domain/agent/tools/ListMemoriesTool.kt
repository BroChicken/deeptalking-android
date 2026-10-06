package com.deeptalking.domain.agent.tools

import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.prompts.maskUserWord
import com.deeptalking.domain.agent.prompts.trimText
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Port of the `list_memories` branch in tool-execution.js.
 *
 * Enumeration/category/keyword filtering (and `memberName` private stores) are
 * delegated to [MemoryService.listMemories].
 */
class ListMemoriesTool(private val memory: MemoryService) : AgentTool {

    override val definition = ToolDefinition(
        name = "list_memories",
        description = "盘点指定分类的长期记忆清单（不含内容详情），用于主动发现过期、重复或错误的记忆。分类：userProfile（用户信息）、relationship（关系）、events（共同事件）、promises（约定）、habits（习惯）。可选按关键词过滤。返回每条记忆的ID、分类、关键词与更新时间。群组对话中可传 memberName 盘点某位成员的私人记忆。",
        parametersJson = """{"type":"object","properties":{"category":{"type":"string","enum":["userProfile","relationship","events","promises","habits",""]},"keyword":{"type":"string","description":"可选过滤关键词"},"limit":{"type":"integer","minimum":1,"maximum":50},"memberName":{"type":"string","description":"可选，群组对话中指定盘点某位成员的私人记忆（不传则盘点共享记忆）"}},"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val category = ToolArgs.string(args, "category").trim()
        val keyword = ToolArgs.string(args, "keyword").trim()
        val limit = (ToolArgs.int(args, "limit") ?: 30).coerceIn(1, 50)
        val memberName = ToolArgs.string(args, "memberName").trim().ifEmpty { null }
        val character = context.character
        val member = memberName?.let { MemoryToolSupport.resolveMember(character, it) }
        val host = if (member != null) MemoryToolSupport.memberAsCharacter(character, member) else character

        val listed = memory
            .listMemories(character, category = category, keyword = keyword, memberName = memberName, limit = limit)
            .sortedByDescending { it.importance }

        val items = buildJsonArray {
            listed.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("category", item.category.legacyKey)
                        put("subject", item.subject.name.lowercase())
                        put("subjectLabel", MemoryToolSupport.actorLabel(host, item.subject))
                        put("key", maskUserWord(host, trimText(item.key, 100)))
                        put("importance", item.importance)
                        put("status", item.status.name.lowercase())
                        put("updatedAt", MemoryToolSupport.formatContextTime(item.updatedAt ?: item.createdAt))
                    },
                )
            }
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("category", category.ifEmpty { "all" })
            put("keyword", if (keyword.isEmpty()) JsonNull else kotlinx.serialization.json.JsonPrimitive(keyword.lowercase()))
            put("count", listed.size)
            put("items", items)
        }
        return AgentToolResult(payload.toString())
    }
}
