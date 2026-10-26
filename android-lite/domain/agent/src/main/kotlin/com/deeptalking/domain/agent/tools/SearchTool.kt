package com.deeptalking.domain.agent.tools

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.sticker.StickerFetchResult
import com.deeptalking.domain.agent.sticker.StickerFetcher
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/**
 * 关键词搜索：一个工具 + 显式 `platform` 枚举，参数在 schema 里可见，不再按平台拆成多个工具名。
 *
 * - `netease` / `hupu`：搜歌/搜帖子，结果带链接（可再 `web_fetch` 看正文）。
 * - `sticker`：**找表情包**。按一句情境去网上找图，压好、起好关键词、**放进角色的表情库**，
 *   但不发出去——发由 `send_sticker` 负责（找和发是两件事，见下）。
 *
 * 为什么找图放在这里而不是 `send_sticker`：库里没有合适关键词时，模型以前要在同一个工具里
 * 从 `keyword`（枚举约束）切到 `query`，很容易瞎填一个词或干脆去 `web_search` 找图。
 * 拆开后每个工具只有一个职责，"下一步该调什么"是明确的。
 *
 * B 站视频不在这个工具里：搜视频 + 总结都在 `bilibili_video`。
 */
class SearchTool(
    private val web: WebContentProvider?,
    private val stickerFetcher: StickerFetcher? = null,
    private val stickersEnabled: Boolean = false,
) : AgentTool {

    override val definition = ToolDefinition(
        name = "search",
        description = "按关键词搜索指定平台的公开内容（免登录）。platform 取值：" +
            "netease=歌曲/歌手，hupu=帖子/讨论，sticker=找表情包。" +
            "netease/hupu 返回标题与链接；sticker 会把找到的图压好放进角色的表情库（不发出去），" +
            "返回可用的 keyword，要发再调 send_sticker(keyword=…)。" +
            "**B 站视频用 bilibili_video（它能搜视频、也能总结视频）**。失败或无结果时不得编造结果。",
        parametersJson = """{"type":"object","properties":{"platform":{"type":"string","enum":["netease","hupu","sticker"],"description":"netease=歌曲/歌手，hupu=帖子/讨论，sticker=找表情包"},"query":{"type":"string","description":"检索关键词（从用户意图提取）；sticker 时写一句具体情境或情绪，例如「被无语到」"},"limit":{"type":"integer","minimum":1,"maximum":20,"description":"返回条数，默认 6（netease/hupu）"},"count":{"type":"integer","minimum":1,"maximum":3,"description":"sticker 一次入库几张，默认 1"}},"required":["platform","query"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val platform = ToolArgs.string(args, "platform").trim().lowercase()
        if (platform !in PLATFORMS) {
            return AgentToolResult(errorJson("platform 只能是 netease / hupu / sticker（B 站视频用 bilibili_video）"))
        }
        val query = ToolArgs.string(args, "query").trim()
        if (query.isEmpty()) return AgentToolResult(errorJson("缺少检索关键词 query"))
        if (platform == "sticker") return searchSticker(context, args, query)

        val provider = web ?: return AgentToolResult(errorJson("联网功能不可用"))
        val limit = (ToolArgs.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return AgentToolResult(provider.searchSocial(platform, query, limit))
    }

    /**
     * 找表情：入库后把 keyword 交回模型，由它决定是否以及何时 `send_sticker`。
     *
     * 这里**不设** `stickerFileRef` / `imageFileRef`——搜索不是发送，也不是"贴图到回复里"。
     */
    private suspend fun searchSticker(
        context: AgentContext,
        args: kotlinx.serialization.json.JsonObject,
        query: String,
    ): AgentToolResult {
        val fetcher = stickerFetcher
            ?: return AgentToolResult(
                contentJson = errorJson("表情功能不可用（这台设备没有配置图源）"),
                stickerNotice = "没找到表情：表情功能不可用",
            )
        if (!stickersEnabled || context.character.isGroup) {
            return AgentToolResult(
                contentJson = errorJson("群组没有表情库，不能找表情"),
                stickerNotice = "没找到表情：群组没有表情库",
            )
        }
        val count = (ToolArgs.int(args, "count") ?: 1).coerceIn(1, AppLimits.Sticker.FETCH_MAX_COUNT)
        return when (val outcome = runCatching { fetcher.fetch(context.character, query, count) }.getOrNull()) {
            is StickerFetchResult.Stored -> {
                val added = outcome.stickers
                val keywords = added.map { it.keyword }.filter { it.isNotEmpty() }
                val json = buildString {
                    append("""{"ok":true,"platform":"sticker","added":[""")
                    added.forEachIndexed { index, sticker ->
                        if (index > 0) append(',')
                        append("""{"keyword":${quote(sticker.keyword)},"category":${quote(sticker.category)}}""")
                    }
                    append("],\"total\":").append(outcome.total)
                    append(",\"hint\":")
                    append(quote("已入库（还没发）。要发就调 send_sticker(keyword=\"${keywords.firstOrNull().orEmpty()}\")。"))
                    append('}')
                }
                AgentToolResult(
                    contentJson = json,
                    stickerNotice = "表情库 +${added.size}：${keywords.joinToString("、")}（共 ${outcome.total} 张）",
                )
            }

            is StickerFetchResult.Failed -> AgentToolResult(
                contentJson = errorJson(outcome.reason),
                stickerNotice = "没找到表情：" + outcome.reason,
            )

            null -> AgentToolResult(
                contentJson = errorJson("找图时出错了，稍后再试"),
                stickerNotice = "没找到表情：找图时出错了",
            )
        }
    }

    private companion object {
        val PLATFORMS = setOf("netease", "hupu", "sticker")
        const val DEFAULT_LIMIT = 6
        const val MAX_LIMIT = 20
    }
}
