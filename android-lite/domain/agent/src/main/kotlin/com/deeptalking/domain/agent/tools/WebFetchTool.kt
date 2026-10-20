package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/**
 * Port of the `web_fetch` branch in tool-execution.js.
 *
 * **只读链接，不做平台搜索**：原来的 `keyword` 分支（隐式去 B站搜视频）已移除——按关键词
 * 搜索是 `search` 的职责（platform 见其 schema）。一个工具只做一件事。
 */
class WebFetchTool(private val web: WebContentProvider?) : AgentTool {

    override val definition = ToolDefinition(
        name = "web_fetch",
        description = "读取一个链接的内容。① 用户给出链接、需要读网页正文时传 url（只传上下文里真实存在的 http(s) 链接，禁止自行编造或拼接 URL）；② 链接是图片（jpg/png/gif/webp 等）时会把图片取回以便你直接查看；③ 链接是虎扑帖子或网易云歌曲时，会直接返回帖子正文+回帖、歌曲信息+热评；B站视频链接会返回标题、UP主、播放数据、简介与封面图。若网页直连失败或正文为空，会自动改用摘要服务取回标题、描述与主图。**本工具只读链接；想按关键词搜视频/歌曲/帖子请用 search。**",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"要抓取的 http(s) 链接（必须是上下文里真实存在的链接；虎扑帖子/网易云歌曲/B站视频链接会被专门解析）"}},"required":["url"],"additionalProperties":false}""",
    )

    // Legacy always registers `web_fetch`; unavailability is reported at execution.
    override fun isEnabled(context: AgentContext): Boolean = true

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val provider = web ?: return AgentToolResult(errorJson("联网功能不可用"))
        val args = ToolArgs.parse(call.arguments)
        val url = ToolArgs.string(args, "url").trim()
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            return AgentToolResult(errorJson("url 必须是 http(s) 链接；想按关键词搜索请用 search"))
        }
        return AgentToolResult(provider.fetch(url))
    }
}
