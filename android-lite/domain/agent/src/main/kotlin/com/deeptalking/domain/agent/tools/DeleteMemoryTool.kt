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
        val requestedMember = ToolArgs.string(args, "memberName").trim().ifEmpty { null }
        // Legacy falls back to the shared store when the member name is unknown.
        val memberName = requestedMember?.takeIf { MemoryToolSupport.resolveMember(context.character, it) != null }
        val exists = memory
            .listMemories(context.character, "", "", memberName, Int.MAX_VALUE)
            .any { it.id == id }
        if (!exists) {
            return AgentToolResult(
                """{"ok":false,"deleted":false,"id":${quote(id)},"reason":${quote("未找到该记忆ID，请先调用 list_memories 查询正确ID后再试")}}""",
            )
        }
        val updated = memory.deleteMemory(context.character, id, memberName)
        return AgentToolResult(
            contentJson = """{"ok":true,"deleted":true,"id":${quote(id)}}""",
            updatedCharacter = updated,
        )
    }
}
