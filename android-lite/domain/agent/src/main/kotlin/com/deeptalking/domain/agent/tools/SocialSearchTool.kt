package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/**
 * 单个社区平台的公开检索（免登录）：为了「一个工具 = 一个确定的平台」，本类被实例化三次
 * （`search_video` → B站、`search_music` → 网易云音乐、`search_posts` → 虎扑），而不是让
 * 模型在一个 `social_search` 里传 `platform` 去挑——模型不必记住「netease 才是音乐」这类
 * 隐式映射，工具名与描述直接写明平台。
 *
 * 结果里带链接：网易云歌曲可用 web_fetch 打开看热评，虎扑帖子可用 web_fetch 读正文与回帖。
 */
class SocialSearchTool(
    private val web: WebContentProvider?,
    private val platform: String,
    name: String,
    label: String,
    hint: String,
) : AgentTool {

    override val definition = ToolDefinition(
        name = name,
        description = "在$label 按关键词检索公开内容（免登录）。$hint 失败或无结果时不得编造结果。",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"检索关键词（从用户意图提取）"},"limit":{"type":"integer","minimum":1,"maximum":20,"description":"返回条数，默认 6"}},"required":["query"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val provider = web ?: return AgentToolResult(errorJson("联网功能不可用"))
        val args = ToolArgs.parse(call.arguments)
        val query = ToolArgs.string(args, "query").trim()
        if (query.isEmpty()) return AgentToolResult(errorJson("缺少检索关键词 query"))
        val limit = (ToolArgs.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return AgentToolResult(provider.searchSocial(platform, query, limit))
    }

    private companion object {
        const val DEFAULT_LIMIT = 6
        const val MAX_LIMIT = 20
    }
}
