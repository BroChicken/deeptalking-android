package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/** Port of the `web_fetch` branch in tool-execution.js. */
class WebFetchTool(private val web: WebContentProvider?) : AgentTool {

    override val definition = ToolDefinition(
        name = "web_fetch",
        description = "联网获取内容。三种用法：① 用户给出链接、需要读网页正文时传 url（只传上下文里真实存在的 http(s) 链接，禁止自行编造或拼接 URL）；② 链接是图片（jpg/png/gif/webp 等）时会把图片取回以便你直接查看；③ 用户想找B站/哔哩哔哩视频时传 keyword（关键词从用户意图提取，不要拼搜索网址）。B站视频链接会返回标题、UP主、播放数据、简介与封面图。若网页直连失败或正文为空，会自动改用摘要服务取回标题、描述与主图。",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"要抓取的 http(s) 链接（必须是上下文里真实存在的链接）"},"keyword":{"type":"string","description":"B站视频搜索关键词（从用户意图提取，如\"火影忍者\"）"}},"additionalProperties":false}""",
    )

    // Legacy always registers `web_fetch`; unavailability is reported at execution.
    override fun isEnabled(context: AgentContext): Boolean = true

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val provider = web ?: return AgentToolResult(errorJson("联网功能不可用"))
        val args = ToolArgs.parse(call.arguments)
        val keyword = ToolArgs.string(args, "keyword").trim()
        if (keyword.isNotEmpty()) return AgentToolResult(provider.searchBilibili(keyword))
        val url = ToolArgs.string(args, "url").trim()
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            return AgentToolResult(errorJson("url 必须是 http(s) 链接，或改用 keyword 搜索B站视频"))
        }
        return AgentToolResult(provider.fetch(url))
    }
}
