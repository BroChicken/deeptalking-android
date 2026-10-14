package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/**
 * 国内平台热榜（免登录）：百度热搜 / 今日头条热榜 / 抖音热榜 / 知乎热榜。
 *
 * 用户问「今天有什么热点」「最近大家在聊什么」时用；返回标题、热度、（有的平台）
 * 链接。拿不到就返回失败原因，模型不得编造榜单。
 */
class HotTopicsTool(private val web: WebContentProvider?) : AgentTool {

    override val definition = ToolDefinition(
        name = "hot_topics",
        description = "获取国内平台的热搜/热榜。platform：baidu=百度热搜、toutiao=今日头条热榜、" +
            "douyin=抖音热榜、zhihu=知乎热榜。用户想了解当下热点、最近流行的梗或话题时使用；" +
            "返回按热度排序的标题（部分平台带链接与热度）。失败或无结果时不得编造榜单内容。",
        parametersJson = """{"type":"object","properties":{"platform":{"type":"string","enum":["baidu","toutiao","douyin","zhihu"],"description":"要查询的热榜平台"},"limit":{"type":"integer","minimum":1,"maximum":30,"description":"返回条数，默认 10"}},"required":["platform"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val provider = web ?: return AgentToolResult(errorJson("联网功能不可用"))
        val args = ToolArgs.parse(call.arguments)
        val platform = ToolArgs.string(args, "platform").trim().lowercase()
        if (platform !in PLATFORMS) {
            return AgentToolResult(errorJson("platform 只支持 baidu / toutiao / douyin / zhihu"))
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
