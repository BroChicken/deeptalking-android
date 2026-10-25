package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.BiliLinks
import com.deeptalking.core.network.BiliVideoSource
import com.deeptalking.core.network.BiliVideoText
import com.deeptalking.core.network.BiliVideoTextResult
import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.core.network.formatBiliClock
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.video.InlineImages
import com.deeptalking.domain.agent.video.VideoFrameProvider
import com.deeptalking.domain.agent.video.VideoSummaryCache
import com.deeptalking.domain.agent.video.VideoSummaryService
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * B 站视频的唯一入口（第 9 个工具）：**搜**和**看**在同一个工具里。
 *
 * 为什么合并且把 B 站从 `search` 里摘出去：用户说“找个讲 X 的视频，说说讲了啥”是一件事的
 * 两步，拆成两个工具只会让模型自己做路由（经常先搜了却忘了总结）。其他平台（网易云/虎扑）
 * 没有“读完再总结”这一步，继续留在 `search`。
 *
 * 参数按"给了什么"分派：给 `url`/BV 号 → 总结；只给 `query` → 搜视频；两个都给时按 url 走。
 *
 * 总结有三条输入路径，**没有一条会以"失败"收场**（除非视频本身取不到）：
 * 1. 逐句字幕（要登录态）→ 分块总结，产出带 `mm:ss` 的要点；
 * 2. B 站官方 AI 总结（要登录态）→ 直接采用，零 LLM 调用；
 * 3. 只有元信息 + 章节 + 画面截图（免登录）→ 说"没字幕，只能看个大概"，禁止编造。
 *
 * 有缓存（默认 7 天）时同一条视频第二次问直接返回上次的结果，不再抓字幕、不再调模型。
 * 字幕原文和截图只留在本机缓存里，不进聊天历史、不进备份。
 */
class BilibiliVideoTool(
    private val source: BiliVideoSource?,
    /** 搜索走平台搜索（`WebContentProvider.searchSocial`），与总结共用同一个网络层。 */
    private val search: WebContentProvider? = null,
    private val summary: VideoSummaryService? = null,
    private val cache: VideoSummaryCache? = null,
    private val frames: VideoFrameProvider? = null,
    /** 无字幕时把 1~2 张截图直接回给主模型（能识图的模型可以自己看画面）。 */
    private val images: InlineImages? = null,
) : AgentTool {

    override val definition = ToolDefinition(
        name = "bilibili_video",
        description = "B 站视频：按关键词搜视频，或总结一个视频讲了什么。① 给 query → 搜视频，返回 BV 号、标题、" +
            "UP主、时长、播放量、简介与封面，可据此挑一个；② 给 url（B 站链接或 BV 号）→ 总结它，返回是否有字幕、" +
            "带时间戳的要点，传 focus 可只关注某个问题。两个都给时按 url 总结。总结只依据字幕/站方 AI 总结/画面截图；" +
            "没字幕时会标明“只能看个大概”，此时不得当成逐句事实，也不要凭标题编造。",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"检索关键词（搜视频时用，从用户意图提取）"},"url":{"type":"string","description":"B 站视频链接或 BV 号（总结时用；必须来自上下文里真实存在的视频，禁止编造）"},"focus":{"type":"string","description":"总结时用户特别想了解的点，如“结论是什么”（可选）"},"limit":{"type":"integer","minimum":1,"maximum":20,"description":"搜索条数，默认 6（总结时忽略）"}},"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val rawUrl = ToolArgs.string(args, "url").trim()
        val query = ToolArgs.string(args, "query").trim()
        val focus = ToolArgs.string(args, "focus").trim().ifEmpty { null }
        if (rawUrl.isNotEmpty()) return AgentToolResult(summarize(rawUrl, focus))
        if (query.isNotEmpty()) {
            val limit = (ToolArgs.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
            return AgentToolResult(searchVideos(query, limit))
        }
        return AgentToolResult(errorJson("需要 query（搜视频）或 url（总结视频）至少给一个"))
    }

    // ------------------------------------------------------------------ 搜视频

    private suspend fun searchVideos(query: String, limit: Int): String {
        val provider = search ?: return errorJson("联网功能不可用")
        return provider.searchSocial("bilibili", query, limit)
    }

    // ------------------------------------------------------------------ 总结

    private suspend fun summarize(rawUrl: String, focus: String?): String {
        val provider = source ?: return errorJson("B 站视频总结不可用（联网功能未启用）")
        val bvid = BiliLinks.bvidOf(rawUrl)
            ?: return errorJson("需要一个 B 站视频链接或 BV 号（当前参数里没有可识别的 BV 号）")

        // 只缓存"泛总结"：带 focus 的问题每次都得重新问，否则会拿旧答案敷衍新问题。
        if (focus == null) {
            cache?.read(bvid)?.let { cached ->
                return withCached(cached)
            }
        }

        return when (val result = provider.load(bvid)) {
            is BiliVideoTextResult.Failure -> errorJson(failureReason(result))
            is BiliVideoTextResult.Ok -> render(provider, result.text, focus)
        }
    }

    private suspend fun render(
        provider: BiliVideoSource,
        text: BiliVideoText,
        focus: String?,
    ): String {
        val storyboard = runCatching { provider.storyboard(text.bvid, text.cid) }.getOrNull()
        val imageRefs = frames?.frames(text, storyboard).orEmpty()

        if (text.source == BiliVideoText.Source.OfficialSummary) {
            return payload(text, source = "official", digest = officialText(text), frameCount = imageRefs.size)
        }
        val service = summary ?: return errorJson("总结服务不可用")
        val digest = service.digest(text, focus, imageRefs)
            ?: return errorJson("取到了视频信息但总结失败，可稍后再试")
        val payload = payload(
            text = text,
            source = if (text.hasSubtitle) "subtitle" else "metadata",
            digest = digest.text,
            frameCount = imageRefs.size,
        )
        if (focus == null) runCatching { cache?.write(text.bvid, payload) }
        return attachFrames(payload, text, imageRefs, digest.imagesAccepted)
    }

    /**
     * 无字幕时把 1~2 张截图作为 `input_image` 一并回给主模型：这时候画面本身就是证据，
     * 让能识图的模型自己看一遍，比只给它一段"看画面像是…"的二手描述更靠谱。
     *
     * 三条闸门缺一不可：**没字幕**（有字幕时文字信息足够，图片纯占上下文）、**模型确实能看图**
     * （[imagesAccepted]，纯文本模型收到 `input_image` 会让下一轮直接 400）、**缓存里只存纯文字**
     * （命中缓存时不带 base64，避免历史里堆积几十 KB 的图片数据）。
     */
    private fun attachFrames(
        payload: String,
        text: BiliVideoText,
        imageRefs: List<String>,
        imagesAccepted: Boolean,
    ): String {
        if (text.hasSubtitle || imageRefs.isEmpty() || !imagesAccepted) return payload
        val inliner = images ?: return payload
        val uris = imageRefs.take(MAX_RESULT_FRAMES).mapNotNull { ref ->
            runCatching { inliner.dataUri(ref) }.getOrNull()?.takeIf { it.startsWith("data:") }
        }
        if (uris.isEmpty()) return payload
        return buildJsonArray {
            add(buildJsonObject {
                put("type", "input_text")
                put("text", payload)
            })
            uris.forEach { uri ->
                add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", uri)
                    put("detail", "low")
                })
            }
        }.toString()
    }

    private fun failureReason(result: BiliVideoTextResult.Failure): String = buildString {
        append(result.reason)
        if (result.needsLogin) {
            append("。可以请用户在设置页填一次 B 站 Cookie（只存在本机），不要编造这个视频的内容")
        } else {
            append("。不要编造视频内容，把这句话如实转述给用户")
        }
    }

    private fun officialText(text: BiliVideoText): String = buildString {
        if (text.officialSummary.isNotEmpty()) append(text.officialSummary)
        if (text.officialOutline.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append("要点：\n")
            text.officialOutline.forEach { line ->
                append("- ").append(formatBiliClock(line.fromSec)).append(' ').append(line.text).append('\n')
            }
        }
    }.trim()

    private fun payload(
        text: BiliVideoText,
        source: String,
        digest: String,
        frameCount: Int,
    ): String {
        val seconds = text.durationSec
        return buildJsonObject {
            put("ok", true)
            put("platform", "bilibili")
            put("bvid", text.bvid)
            put("title", text.title)
            put("author", text.author)
            put("duration", formatBiliClock(seconds))
            put("duration_sec", seconds)
            put("source", source)
            put("has_subtitle", text.hasSubtitle)
            put("frames", frameCount)
            put("cached", false)
            put("summary", digest)
            put("note", noteFor(source, text.bvid))
        }.toString()
    }

    private fun noteFor(source: String, bvid: String): String = when (source) {
        "official" -> "这是 B 站官方 AI 总结，不是逐句字幕；时间戳可跳转：https://www.bilibili.com/video/$bvid?t=<秒>"
        "metadata" -> "这个视频没有可用字幕，以上只是根据简介、章节和画面截图看的大概，不是逐句内容；" +
            "转述时要说清“没字幕”，不要当成看过全部内容"
        else -> "时间戳可跳转：https://www.bilibili.com/video/$bvid?t=<秒>；只依据视频字幕整理，字幕里没有的别补充"
    }

    /** 命中缓存时把 `cached` 翻成 true，保留原来的其它字段。 */
    private fun withCached(payload: String): String = runCatching {
        val obj = JSON.parseToJsonElement(payload).jsonObject
        JsonObject(obj + ("cached" to JsonPrimitive(true))).toString()
    }.getOrDefault(payload)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 回给主模型的截图张数上限：低清图约 85 token/张，两张就够说明"画面在做什么"。 */
        const val MAX_RESULT_FRAMES = 2

        const val DEFAULT_LIMIT = 6
        const val MAX_LIMIT = 20
    }
}
