package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/**
 * Hot lists of one platform (no login). Replaces the four per-platform tools
 * (`baidu_hot` / `toutiao_hot` / `douyin_hot` / `zhihu_hot`) with a single tool
 * whose `platform` enum names the board.
 *
 * Used when the user asks "今天有什么热点" / "最近大家在聊什么"; returns titles by
 * heat (some platforms add links/heat scores). When it fails, return the reason —
 * never invent a board.
 */
class HotBoardTool(private val web: WebContentProvider?) : AgentTool {

    override val definition = ToolDefinition(
        name = "hot_board",
        description = "获取指定平台热榜（免登录）。platform 取值：baidu、toutiao、douyin、zhihu。返回按热度排序的标题（部分平台带链接与热度）；用户想了解当下热点、最近流行的梗或话题时使用。失败或无结果时不得编造榜单内容。",
        parametersJson = """{"type":"object","properties":{"platform":{"type":"string","enum":["baidu","toutiao","douyin","zhihu"],"description":"热榜平台"},"limit":{"type":"integer","minimum":1,"maximum":30,"description":"返回条数，默认 10"}},"required":["platform"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val provider = web ?: return AgentToolResult(errorJson("联网功能不可用"))
        val args = ToolArgs.parse(call.arguments)
        val platform = ToolArgs.string(args, "platform").trim().lowercase()
        if (platform !in PLATFORMS) {
            return AgentToolResult(errorJson("platform 只能是 baidu / toutiao / douyin / zhihu"))
        }
        val limit = (ToolArgs.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return AgentToolResult(provider.hotBoard(platform, limit))
    }

    private companion object {
        val PLATFORMS = setOf("baidu", "toutiao", "douyin", "zhihu")
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
    }
}
