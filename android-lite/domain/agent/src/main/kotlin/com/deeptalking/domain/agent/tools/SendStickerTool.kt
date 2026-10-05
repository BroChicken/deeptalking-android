package com.deeptalking.domain.agent.tools

import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Port of the `send_sticker` branch in tool-execution.js.
 *
 * The legacy tool description/schema embeds the per-character tag list ([definitionFor]),
 * so the model can only pick a tag the character owns. Execution still validates
 * defensively and reports the available tags on a miss.
 */
class SendStickerTool(private val stickersEnabled: Boolean = true) : AgentTool {

    override val definition = ToolDefinition(
        name = "send_sticker",
        description = "给用户发一个表情包（贴图）。tag 必须是角色已有表情包的标签之一。当你想用表情包/贴图表达情绪时使用；贴图会作为你的下一条消息单独发出，不需要再用文字描述它。",
        parametersJson = """{"type":"object","properties":{"tag":{"type":"string","description":"表情包标签"}},"required":["tag"],"additionalProperties":false}""",
    )

    /** Per-character schema: the `tag` enum mirrors the legacy `buildAgentTools` tag list. */
    fun definitionFor(context: AgentContext): ToolDefinition {
        val tags = context.character.stickers.map { it.tag }.distinct()
        if (tags.isEmpty()) return definition
        val description = "给用户发一个表情包（贴图）。tag 必须从这些可用标签中选择其一：" +
            tags.joinToString("、") +
            "。当你想用表情包/贴图表达情绪时使用；贴图会作为你的下一条消息单独发出，不需要再用文字描述它。"
        val parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("tag", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { tags.forEach { add(it) } })
                    put("description", "表情包标签")
                })
            })
            put("required", buildJsonArray { add("tag") })
            put("additionalProperties", false)
        }
        return ToolDefinition(name = "send_sticker", description = description, parametersJson = parameters.toString())
    }

    override fun isEnabled(context: AgentContext): Boolean =
        stickersEnabled && context.character.stickers.isNotEmpty()

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val tag = ToolArgs.string(args, "tag").trim()
        if (tag.isEmpty()) return AgentToolResult(errorJson("缺少 tag，请从可用标签中选择一个"))
        // Legacy `getStickersByTag`: exact match first, then substring either way.
        val exact = context.character.stickers.filter { it.tag == tag }
        val matches = exact.ifEmpty {
            val needle = tag.lowercase()
            context.character.stickers.filter { sticker ->
                val candidate = sticker.tag.lowercase()
                candidate.isNotEmpty() && (candidate.contains(needle) || needle.contains(candidate))
            }
        }
        if (matches.isEmpty()) {
            val allTags = context.character.stickers.map { it.tag }.distinct()
            return AgentToolResult(errorJson("没有该标签的表情包；可用标签：" + allTags.joinToString("、")))
        }
        val chosen = matches.last()
        return AgentToolResult(
            contentJson = """{"ok":true,"tag":${quote(chosen.tag)},"sent":true}""",
            stickerFileRef = chosen.fileRef,
        )
    }
}
