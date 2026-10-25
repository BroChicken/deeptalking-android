package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/**
 * Keyword search on one community platform (no login). Replaces the three
 * per-platform tools (`search_video` / `search_music` / `search_posts`): one tool
 * with an explicit `platform` enum, so the parameter is visible in the schema
 * instead of being encoded in the tool name.
 *
 * Results carry links: NetEase songs and Hupu posts can be opened with `web_fetch`.
 */
class SearchTool(private val web: WebContentProvider?) : AgentTool {

    override val definition = ToolDefinition(
        name = "search",
        description = "按关键词搜索指定平台的公开内容（免登录）。platform 取值：bilibili=视频/UP主，netease=歌曲/歌手，hupu=帖子/讨论。返回标题与链接；B站结果另带时长（duration/duration_sec）与播放量，可据此挑一个再用 video_summary 看内容。失败或无结果时不得编造结果。",
        parametersJson = """{"type":"object","properties":{"platform":{"type":"string","enum":["bilibili","netease","hupu"],"description":"bilibili=视频/UP主，netease=歌曲/歌手，hupu=帖子/讨论"},"query":{"type":"string","description":"检索关键词（从用户意图提取）"},"limit":{"type":"integer","minimum":1,"maximum":20,"description":"返回条数，默认 6"}},"required":["platform","query"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val provider = web ?: return AgentToolResult(errorJson("联网功能不可用"))
        val args = ToolArgs.parse(call.arguments)
        val platform = ToolArgs.string(args, "platform").trim().lowercase()
        if (platform !in PLATFORMS) {
            return AgentToolResult(errorJson("platform 只能是 bilibili / netease / hupu"))
        }
        val query = ToolArgs.string(args, "query").trim()
        if (query.isEmpty()) return AgentToolResult(errorJson("缺少检索关键词 query"))
        val limit = (ToolArgs.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return AgentToolResult(provider.searchSocial(platform, query, limit))
    }

    private companion object {
        val PLATFORMS = setOf("bilibili", "netease", "hupu")
        const val DEFAULT_LIMIT = 6
        const val MAX_LIMIT = 20
    }
}
