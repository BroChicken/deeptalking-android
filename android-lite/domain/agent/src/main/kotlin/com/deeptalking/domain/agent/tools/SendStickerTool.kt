package com.deeptalking.domain.agent.tools

import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/**
 * Port of the `send_sticker` branch in tool-execution.js.
 *
 * SIMPLIFIED: the legacy tool description embeds the per-character tag list.
 * [ToolDefinition] is immutable here, so the available tags are validated at
 * execution time and reported back in the error payload instead.
 */
class SendStickerTool(private val stickersEnabled: Boolean = true) : AgentTool {

    override val definition = ToolDefinition(
        name = "send_sticker",
        description = "给用户发一个表情包（贴图）。tag 必须是角色已有表情包的标签之一。当你想用表情包/贴图表达情绪时使用；贴图会作为你的下一条消息单独发出，不需要再用文字描述它。",
        parametersJson = """{"type":"object","properties":{"tag":{"type":"string","description":"表情包标签"}},"required":["tag"],"additionalProperties":false}""",
    )

    override fun isEnabled(context: AgentContext): Boolean =
        stickersEnabled && context.character.stickers.isNotEmpty()

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val tag = ToolArgs.string(args, "tag").trim()
        if (tag.isEmpty()) return AgentToolResult(errorJson("缺少 tag，请从可用标签中选择一个"))
        val matches = context.character.stickers.filter { it.tag == tag }
        if (matches.isEmpty()) {
            val allTags = context.character.stickers.map { it.tag }.distinct()
            return AgentToolResult(errorJson("没有该标签的表情包；可用标签：" + allTags.joinToString("、")))
        }
        val chosen = matches.last()
        return AgentToolResult("""{"ok":true,"tag":${quote(chosen.tag)},"sent":true}""")
    }
}
