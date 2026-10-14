package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/**
 * 国内社区平台的公开检索（免登录）：虎扑帖子、网易云音乐歌曲、B 站视频。
 *
 * 网易云结果里带歌曲 id/链接，要看热评再用 web_fetch 打开那个链接；虎扑结果里的
 * 链接同理（web_fetch 会返回帖子正文与回帖）。
 */
class SocialSearchTool(private val web: WebContentProvider?) : AgentTool {

    override val definition = ToolDefinition(
        name = "social_search",
        description = "在国内社区平台按关键词检索公开内容（免登录）。platform：hupu=虎扑（帖子）、" +
            "netease=网易云音乐（歌曲）、bilibili=B站（视频）。用户想「搜一下虎扑/网易云/B站上有什么」、" +
            "找某首歌或某个视频时使用。返回标题与链接；网易云返回歌曲 id，可用 web_fetch 打开歌曲链接看热评，" +
            "虎扑链接用 web_fetch 可读正文与回帖。失败或无结果时不得编造结果。",
        parametersJson = """{"type":"object","properties":{"platform":{"type":"string","enum":["hupu","netease","bilibili"],"description":"要检索的平台"},"query":{"type":"string","description":"检索关键词（从用户意图提取，如「原神」「海阔天空」）"},"limit":{"type":"integer","minimum":1,"maximum":20,"description":"返回条数，默认 6"}},"required":["platform","query"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val provider = web ?: return AgentToolResult(errorJson("联网功能不可用"))
        val args = ToolArgs.parse(call.arguments)
        val platform = ToolArgs.string(args, "platform").trim().lowercase()
        if (platform !in PLATFORMS) {
            return AgentToolResult(errorJson("platform 只支持 hupu / netease / bilibili"))
        }
        val query = ToolArgs.string(args, "query").trim()
        if (query.isEmpty()) return AgentToolResult(errorJson("缺少检索关键词 query"))
        val limit = (ToolArgs.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return AgentToolResult(provider.searchSocial(platform, query, limit))
    }

    private companion object {
        val PLATFORMS = setOf("hupu", "netease", "bilibili")
        const val DEFAULT_LIMIT = 6
        const val MAX_LIMIT = 20
    }
}
