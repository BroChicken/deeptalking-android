package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Sticker
import com.deeptalking.core.model.StickerCategory
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.sticker.StickerFetcher
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 发表情包。
 *
 * 两个入口共用一个工具：
 * - 给 `keyword`：从**已有**表情里挑，命中就随机发一张（同一关键词多张不会总发同一张）。
 * - 给 `query`：库里没有合适的，按情境联网找一张，压缩入库后直接发（这里只拿
 *   [StickerFetcher] 返回的 fileRef，抓取与审核都在 app 层）。
 *
 * schema 里内联该角色的关键词枚举（按大类分组写进 description），执行时仍防御性校验。
 */
class SendStickerTool(
    private val stickersEnabled: Boolean = true,
    private val fetcher: StickerFetcher? = null,
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

    override fun isEnabled(context: AgentContext): Boolean =
        stickersEnabled && context.character.stickers.isNotEmpty()

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val keyword = ToolArgs.string(args, "keyword").trim()
        val query = ToolArgs.string(args, "query").trim()
        val stickers = context.character.stickers

        if (keyword.isNotEmpty()) {
            val chosen = pick(stickers, keyword)
            if (chosen != null) return sent(chosen, fetched = false)
        }

        // 库里没有 → 只有给了 query 才联网找一张；找不到就回退成“报可用关键词”。
        val fetch = fetcher
        if (query.isNotEmpty() && fetch != null &&
            stickers.size < com.deeptalking.core.common.AppLimits.Sticker.MAX_PER_CHARACTER
        ) {
            val fetched = runCatching { fetch.fetch(context.character, query) }.getOrNull()
            if (fetched != null) {
                return AgentToolResult(
                    contentJson = """{"ok":true,"keyword":${quote(fetched.keyword)},"source":"web","sent":true}""",
                    stickerFileRef = fetched.fileRef,
                )
            }
        }

        if (stickers.isEmpty()) {
            return AgentToolResult(errorJson("你的表情库还是空的，先用 query 现找一张"))
        }
        val allKeywords = stickers.map { it.keyword }.filter { it.isNotEmpty() }.distinct()
        return AgentToolResult(
            errorJson("没有这张表情；可用关键词：" + allKeywords.joinToString("、")),
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

    private fun sent(sticker: Sticker, fetched: Boolean): AgentToolResult = AgentToolResult(
        contentJson = """{"ok":true,"keyword":${quote(sticker.keyword)},"source":"${if (fetched) "web" else "library"}","sent":true}""",
        stickerFileRef = sticker.fileRef,
    )

    private fun parametersJson(keywords: List<String>): String {
        val parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "已有表情的关键词；优先用它，发送时会在同关键词的多张里随机挑一张。")
                    if (keywords.isNotEmpty()) {
                        put("enum", buildJsonArray { keywords.forEach { add(it) } })
                    }
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "库里没有合适的表情时，写一句具体情境去联网找一张（会入库并直接发出），例如「被无语到」「想贴贴」。")
                })
            })
            put("additionalProperties", false)
        }
        return parameters.toString()
    }

    private companion object {
        const val BASE_DESCRIPTION =
            "给用户发一个表情包（贴图）。想表达情绪时用 keyword 从已有表情里挑一个；" +
                "库里没有合适的，就给 query 描述此刻的情境，系统会联网找一张、加进你的表情库并直接发出。" +
                "贴图会作为你的下一条消息单独发出，不需要再用文字描述它。" +
                "别频繁用（普通闲聊几轮一张就够），严肃或正经的话题别用。"
    }
}
