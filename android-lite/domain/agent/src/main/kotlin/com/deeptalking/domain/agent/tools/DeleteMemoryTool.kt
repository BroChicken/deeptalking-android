package com.deeptalking.domain.agent.tools

import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/** Port of the `delete_memory` branch in tool-execution.js. */
class DeleteMemoryTool(private val memory: MemoryService) : AgentTool {

    override val definition = ToolDefinition(
        name = "delete_memory",
        description = "删除一条长期记忆（按ID）。仅用于：内容已被明确推翻或已过时失效、重复记录、或用户明确否认/纠正过的错误记忆。删除前建议先用 list_memories 或 search_memory 确认目标ID。群组对话中删除某位成员私人记忆时传入对应 memberName。",
        parametersJson = """{"type":"object","properties":{"id":{"type":"string","description":"要删除的记忆ID"},"memberName":{"type":"string","description":"可选，群组对话中指定删除某位成员的私人记忆（不传则删除共享记忆）"}},"required":["id"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val id = ToolArgs.string(args, "id").trim()
        if (id.isEmpty()) {
            return AgentToolResult(errorJson("缺少记忆ID id，请先调用 list_memories 获取目标ID后再试"))
        }
        val memberName = ToolArgs.string(args, "memberName").trim().ifEmpty { null }
        memory.deleteMemory(context.character, id, memberName)
        return AgentToolResult("""{"ok":true,"deleted":true,"id":${quote(id)}}""")
    }
}
