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
                contentJson = """{"ok":false,"reason":"unknown tool: ${call.name}"}""",
                isError = true,
            )
        if (!tool.isEnabled(context)) {
            return AgentToolResult(
                contentJson = """{"ok":false,"reason":"tool disabled: ${call.name}"}""",
                isError = true,
            )
        }
        return tool.execute(call, context)
    }
}
