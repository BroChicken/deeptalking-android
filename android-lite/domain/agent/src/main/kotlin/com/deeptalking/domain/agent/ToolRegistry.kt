package com.deeptalking.domain.agent

import com.deeptalking.domain.agent.tools.SendStickerTool
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/**
 * Registry of agent tools. Tools enabled for the current context are exposed
 * to the model in a stable order so the request prefix stays cache-friendly.
 */
class ToolRegistry(private val tools: List<AgentTool>) {

    fun definitions(context: AgentContext): List<ToolDefinition> =
        tools.filter { it.isEnabled(context) }.map { tool ->
            if (tool is SendStickerTool) tool.definitionFor(context) else tool.definition
        }

    suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val tool = tools.firstOrNull { it.definition.name == call.name }
            ?: return AgentToolResult(
                contentJson = """{"ok":false,"reason":"未知工具: ${call.name}"}""",
                isError = true,
            )
        if (!tool.isEnabled(context)) {
            return AgentToolResult(
                contentJson = """{"ok":false,"reason":"工具不可用: ${call.name}"}""",
                isError = true,
            )
        }
        return try {
            tool.execute(call, context)
        } catch (error: Exception) {
            // Legacy `tool-execution.js`: a throwing tool feeds `{ok:false}` back to
            // the model instead of aborting the whole turn.
            AgentToolResult(
                contentJson = """{"ok":false,"reason":"工具执行异常: ${error.message ?: error}"}""",
                isError = true,
            )
        }
    }
}
