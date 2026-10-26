package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Sticker
import com.deeptalking.core.model.StickerCategory
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
 * 发表情包（**只发，不找**）。
 *
 * 找图在 `search(platform=sticker)`：搜到、压好、起好关键词并入表情库，再把 keyword 交回来；
 * 这个工具只按 keyword 从**已有**表情里挑一张发出（同关键词多张时随机，不会总发同一张）。
 *
 * 为什么拆开：以前 `keyword`（枚举约束）+ `query` 挤在一个工具里，模型在"库里没有合适的词"
 * 时要自己意识到该换成另一个参数；实测它会瞎填一个不存在的 keyword，或跑去 `web_search`
 * 找图，然后对用户说"加不进去"。拆开后每个工具只有一个职责，未命中时返回的错误直接写明
 * 下一步该调什么。
 */
class SendStickerTool(
    private val stickersEnabled: Boolean = true,
) : AgentTool {

    override val definition = ToolDefinition(
        name = "send_sticker",
        description = BASE_DESCRIPTION,
        parametersJson = parametersJson(keywords = emptyList()),
    )

    override fun definitionFor(context: AgentContext): ToolDefinition {
        val stickers = context.character.stickers
        if (stickers.isEmpty()) return definition
        val groups = StickerCategory.group(stickers)
            .joinToString("\n") { (def, list) ->
                val words = list.map { it.keyword }.filter { it.isNotEmpty() }.distinct()
                if (words.isEmpty()) "" else def.label + "：" + words.joinToString("、")
            }
            .trim()
        if (groups.isEmpty()) return definition
        val description = buildString {
            append(BASE_DESCRIPTION)
            append("\n已有表情（按大类）：\n")
            append(groups)
        }
        val keywords = stickers.map { it.keyword }.filter { it.isNotEmpty() }.distinct()
        return ToolDefinition(
            name = "send_sticker",
            description = description,
            parametersJson = parametersJson(keywords),
        )
    }

    /** 单角色才启用；群组不维护表情库，也没有共享池。 */
    override fun isEnabled(context: AgentContext): Boolean =
        stickersEnabled && !context.character.isGroup

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val keyword = ToolArgs.string(args, "keyword").trim()
        val query = ToolArgs.string(args, "query").trim()
        val stickers = context.character.stickers

        if (keyword.isNotEmpty()) {
            pick(stickers, keyword)?.let { return sent(it) }
        }

        // 找图已经搬到 search(platform=sticker)：老习惯传 query 的调用在这里被明确指路，
        // 而不是静默失败让模型自行发挥。
        if (query.isNotEmpty()) {
            return AgentToolResult(
                contentJson = errorJson(
                    "找表情已移到 search(platform=sticker, query=$query)：先搜进库里，再用返回的 keyword 调 send_sticker。",
                ),
                stickerNotice = "没发出：找表情要用 search(platform=sticker)",
            )
        }

        if (stickers.isEmpty()) {
            return AgentToolResult(
                contentJson = errorJson("你的表情库还是空的：先 search(platform=sticker, query=…) 找一张"),
                stickerNotice = "没发出：表情库是空的",
            )
        }
        val hint = if (keyword.isEmpty()) "缺少 keyword" else "库里没有「$keyword」"
        return AgentToolResult(
            contentJson = errorJson(
                "$hint；先 search(platform=sticker, query=${keyword.ifEmpty { "…" }}) 找进来再发。" +
                    "现有关键词：" + stickers.map { it.keyword }.filter { it.isNotEmpty() }.distinct().joinToString("、"),
            ),
            stickerNotice = "没发出：" + (if (keyword.isEmpty()) "要指定库里已有的关键词" else "库里没有「$keyword」"),
        )
    }

    /** 精确优先，其次双向子串；同一关键词多张时随机，避免总发同一张。 */
    private fun pick(stickers: List<Sticker>, keyword: String): Sticker? {
        val exact = stickers.filter { it.keyword == keyword }
        if (exact.isNotEmpty()) return exact.random()
        val needle = keyword.lowercase()
        val loose = stickers.filter { sticker ->
            val candidate = sticker.keyword.lowercase()
            candidate.isNotEmpty() && (candidate.contains(needle) || needle.contains(candidate))
        }
        return loose.randomOrNull()
    }

    private fun sent(sticker: Sticker): AgentToolResult = AgentToolResult(
        contentJson = """{"ok":true,"keyword":${quote(sticker.keyword)},"source":"library","sent":true}""",
        stickerFileRef = sticker.fileRef,
    )

    private fun parametersJson(keywords: List<String>): String {
        val parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "要发的表情关键词（库里已有的才能发）；同关键词多张时随机挑一张。" +
                            "库里没有合适的就先调 search(platform=sticker, query=情境) 找一张。",
                    )
                    if (keywords.isNotEmpty()) {
                        put("enum", buildJsonArray { keywords.forEach { add(it) } })
                    }
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "已废弃：找图请用 search(platform=sticker, query=…)。这里传了会被拒绝并指路。")
                })
            })
            put("additionalProperties", false)
        }
        return parameters.toString()
    }

    private companion object {
        const val BASE_DESCRIPTION =
            "给用户发一个表情包（贴图）。keyword 填库里已有的关键词；" +
                "库里没有合适的，先调 search(platform=sticker, query=情境) 把它找进库里，再用返回的 keyword 发。" +
                "贴图会作为你的下一条消息单独发出，不需要再用文字描述它。" +
                "别频繁用（普通闲聊几轮一张就够），严肃或正经的话题别用。"
    }
}
