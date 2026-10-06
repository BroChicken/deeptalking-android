package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/** Port of the `web_search` branch in tool-execution.js. */
class WebSearchTool(private val web: WebContentProvider?) : AgentTool {

    override val definition = ToolDefinition(
        name = "web_search",
        description = "搜索实时网页资料，返回实际取得的标题、链接和摘要。失败或无结果时不得编造搜索结果；可请用户提供链接后用 web_fetch 读取。",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"}},"required":["query"],"additionalProperties":false}""",
    )

    // Legacy always registers `web_search`; unavailability is reported at execution.
    override fun isEnabled(context: AgentContext): Boolean = true

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val provider = web ?: return AgentToolResult(errorJson("联网功能不可用"))
        val args = ToolArgs.parse(call.arguments)
        val query = ToolArgs.string(args, "query").trim()
        if (query.isEmpty()) return AgentToolResult(errorJson("缺少搜索关键词 query"))
        return AgentToolResult(provider.searchWeb(query))
    }
}
