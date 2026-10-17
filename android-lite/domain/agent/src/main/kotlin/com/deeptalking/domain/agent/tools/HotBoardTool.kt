package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/**
 * 单个平台的国内热榜（免登录）。
 *
 * **一个工具只对应一个平台**：模型不再传 `platform` 去挑榜单，工具名本身就说明去哪儿
 * （`baidu_hot` / `toutiao_hot` / `douyin_hot` / `zhihu_hot`），从根上消除了
 * 「得记住 baidu=百度 才不会查错榜」这类隐式映射——选错平台这件事变得不可能发生。
 *
 * 用户问「今天有什么热点」「最近大家在聊什么」时用；返回标题、热度、（有的平台）链接。
 * 拿不到就返回失败原因，模型不得编造榜单。
 */
class HotBoardTool(
    private val web: WebContentProvider?,
    private val platform: String,
    name: String,
    label: String,
) : AgentTool {

    override val definition = ToolDefinition(
        name = name,
        description = "获取$label（免登录）。用户想了解当下热点、最近流行的梗或话题时使用；" +
            "返回按热度排序的标题（部分平台带链接与热度）。失败或无结果时不得编造榜单内容。",
        parametersJson = """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":30,"description":"返回条数，默认 10"}},"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val provider = web ?: return AgentToolResult(errorJson("联网功能不可用"))
        val args = ToolArgs.parse(call.arguments)
        val limit = (ToolArgs.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return AgentToolResult(provider.hotBoard(platform, limit))
    }

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
    }
}
