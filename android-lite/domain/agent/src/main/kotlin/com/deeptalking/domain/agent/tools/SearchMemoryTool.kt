package com.deeptalking.domain.agent.tools

import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        val entries = memory.retrieve(host, query, 6)
        val results = buildJsonArray {
            entries.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("category", item.category.name)
                        put("subject", item.subject.name.lowercase())
                        put("subjectLabel", MemoryToolSupport.actorLabel(character, item.subject))
                        put("key", item.key.take(80))
                        put("value", item.value.take(420))
                        put("importance", item.importance)
                    },
                )
            }
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("query", query)
            put("total", entries.size)
            put("results", results)
        }
        return AgentToolResult(payload.toString())
    }
}
