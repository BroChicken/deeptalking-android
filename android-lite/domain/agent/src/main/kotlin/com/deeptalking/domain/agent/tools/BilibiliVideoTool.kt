package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.BiliLinks
import com.deeptalking.core.network.BiliVideoSource
import com.deeptalking.core.network.BiliVideoText
import com.deeptalking.core.network.BiliVideoTextResult
import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.core.network.formatBiliClock
import com.deeptalking.core.network.parseBiliDurationClock
import com.deeptalking.core.network.spreadPick
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
 * 1. 逐句字幕（要登录态）→ 分块 map-reduce，产出「一句话 + 整体脉络 + 带 `mm:ss` 的关键节点」；
 * 2. B 站官方 AI 总结（要登录态）→ 直接采用，零 LLM 调用；
 * 3. 只有元信息 + 章节 + 画面截图（免登录）→ 说"没字幕，只能看个大概"，禁止编造。
 *
 * 结果里一定写清**看到哪了**（`mode` / `covered_until` / `unread_sec`）：分块有上限、超长视频
 * 后段没看时，模型据此带 `from` 续读，而不是拿同一份摘要反复糊弄。用户想看图就带 `frame_at`，
 * 取那一帧作为附图贴进回复。
 */
class BilibiliVideoTool(
    private val source: BiliVideoSource?,
    /** 搜索走平台搜索（`WebContentProvider.searchSocial`），与总结共用同一个网络层。 */
    private val search: WebContentProvider? = null,
    private val summary: VideoSummaryService? = null,
    private val cache: VideoSummaryCache? = null,
    private val frames: VideoFrameProvider? = null,
    /** 无字幕时把截图直接回给主模型（能识图的模型可以自己看画面）。 */
    private val images: InlineImages? = null,
) : AgentTool {

    override val definition = ToolDefinition(
        name = "bilibili_video",
        description = "B 站视频：按关键词搜视频，或总结一个视频讲了什么。① 给 query → 搜视频，返回 BV 号、标题、" +
            "UP主、时长、播放量、简介与封面，可据此挑一个；② 给 url（B 站链接或 BV 号）→ 总结它，返回一句话、" +
            "整体脉络和带时间戳的关键节点，并写明看到哪了（mode=summary 是摘要、shallow 是没字幕只能看个大概）；" +
            "③ 用户说“继续 / 后面呢”时带 from=<上次的 covered_until> 再调一次，别重复总结同一段；" +
            "④ 用户想看某一刻的画面就带 frame_at（如 3:20），那一帧会作为附图贴进你的回复。" +
            "总结只依据字幕/站方 AI 总结/画面截图；没字幕时不得当成逐句事实，也不要凭标题编造。",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"检索关键词（搜视频时用，从用户意图提取）"},"url":{"type":"string","description":"B 站视频链接或 BV 号（总结时用；必须来自上下文里真实存在的视频，禁止编造）"},"focus":{"type":"string","description":"总结时用户特别想了解的点，如“结论是什么”（可选）"},"from":{"type":"string","description":"从第几分钟开始看（如 12:30 或 750 秒）；用于视频太长只看了一段之后继续看后段（可选）"},"frame_at":{"type":"string","description":"把视频某一刻的画面贴进回复（如 3:20）；只取一张，会作为附图发出（可选）"},"refresh":{"type":"boolean","description":"true = 忽略本地缓存重新看一遍（可选）"},"limit":{"type":"integer","minimum":1,"maximum":20,"description":"搜索条数，默认 6（总结时忽略）"}},"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val rawUrl = ToolArgs.string(args, "url").trim()
        val query = ToolArgs.string(args, "query").trim()
        val focus = ToolArgs.string(args, "focus").trim().ifEmpty { null }
        val fromRaw = ToolArgs.string(args, "from").trim()
        val frameRaw = ToolArgs.string(args, "frame_at").trim()
        val refresh = ToolArgs.bool(args, "refresh") ?: false

        if (rawUrl.isNotEmpty()) {
            val fromSec = if (fromRaw.isEmpty()) {
                0
            } else {
                parseBiliDurationClock(fromRaw)
                    ?: return AgentToolResult(errorJson("from 要写成 mm:ss 或秒数，例如 12:30"))
            }
            val frameAt = if (frameRaw.isEmpty()) {
                null
            } else {
                parseBiliDurationClock(frameRaw)
                    ?: return AgentToolResult(errorJson("frame_at 要写成 mm:ss 或秒数，例如 3:20"))
            }
            return summarize(rawUrl, focus, fromSec, frameAt, refresh)
        }
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

    private suspend fun summarize(
        rawUrl: String,
        focus: String?,
        fromSec: Int,
        frameAtSec: Int?,
        refresh: Boolean,
    ): AgentToolResult {
        val provider = source ?: return AgentToolResult(errorJson("B 站视频总结不可用（联网功能未启用）"))
        val bvid = BiliLinks.bvidOf(rawUrl)
            ?: return AgentToolResult(errorJson("需要一个 B 站视频链接或 BV 号（当前参数里没有可识别的 BV 号）"))

        // 只缓存"从头看的泛总结"：带 focus / from 的问题每次都得重新问，
        // 否则“继续看”会命中上一次的同一份摘要（这就是用户觉得它在敷衍的直接原因）。
        if (focus == null && fromSec == 0 && !refresh) {
            cache?.read(bvid)?.let { cached -> return AgentToolResult(withCached(cached)) }
        }

        return when (val result = provider.load(bvid)) {
            is BiliVideoTextResult.Failure -> AgentToolResult(errorJson(failureReason(result)))
            is BiliVideoTextResult.Ok -> render(provider, result.text, focus, fromSec, frameAtSec)
        }
    }

    private suspend fun render(
        provider: BiliVideoSource,
        text: BiliVideoText,
        focus: String?,
        fromSec: Int,
        frameAtSec: Int?,
    ): AgentToolResult {
        val storyboard = runCatching { provider.storyboard(text.bvid, text.cid) }.getOrNull()
        val imageRefs = frames?.frames(text, storyboard).orEmpty()
        val frameRef = frameAtSec?.let { seconds ->
            runCatching { frames?.frameAt(text, storyboard, seconds) }.getOrNull()
        }

        // “从 12:30 继续看”：只把后段字幕喂给总结，避免又从头讲一遍。
        val sliced = if (fromSec > 0 && text.hasSubtitle) {
            text.copy(lines = text.lines.filter { it.fromSec >= fromSec })
        } else {
            text
        }

        if (text.source == BiliVideoText.Source.OfficialSummary) {
            val payload = payload(
                text = text,
                source = "official",
                digest = officialText(text),
                frameCount = imageRefs.size,
                mode = "summary",
                fromSec = fromSec,
                coveredUntilSec = null,
                frameAtSec = frameAtSec,
                frameAttached = frameRef != null,
            )
            return AgentToolResult(contentJson = payload, imageFileRef = frameRef)
        }

        val service = summary ?: return AgentToolResult(errorJson("总结服务不可用"))
        val digest = service.digest(sliced, focus, imageRefs)
            ?: return AgentToolResult(errorJson("取到了视频信息但总结失败，可稍后再试"))
        val payload = payload(
            text = text,
            source = if (text.hasSubtitle) "subtitle" else "metadata",
            digest = digest.text,
            frameCount = imageRefs.size,
            mode = if (text.hasSubtitle) "summary" else "shallow",
            fromSec = fromSec,
            coveredUntilSec = digest.coveredUntilSec,
            frameAtSec = frameAtSec,
            frameAttached = frameRef != null,
        )
        // 缓存里只放纯文字结果（不带 base64 截图），命中时再补上"已缓存"标记。
        if (focus == null && fromSec == 0) runCatching { cache?.write(text.bvid, payload) }
        return AgentToolResult(
            contentJson = attachFrames(payload, text, imageRefs, digest.imagesAccepted),
            imageFileRef = frameRef,
        )
    }

    /**
     * 无字幕时把截图作为 `input_image` 一并回给主模型：这时候画面本身就是证据。
     *
     * 三条闸门缺一不可：**没字幕**（有字幕时文字信息足够，图片纯占上下文）、**模型确实能看图**
     * （[imagesAccepted]，纯文本模型收到 `input_image` 会让下一轮直接 400）、**缓存里只存纯文字**
     * （命中缓存时不带 base64，避免历史里堆积几十 KB 的图片数据）。
     *
     * 截图是**均匀挑**的（[spreadPick]），不是"时间最靠前的两张"——后者等于只给开头。
     */
    private fun attachFrames(
        payload: String,
        text: BiliVideoText,
        imageRefs: List<String>,
        imagesAccepted: Boolean,
    ): String {
        if (text.hasSubtitle || imageRefs.isEmpty() || !imagesAccepted) return payload
        val inliner = images ?: return payload
        val picked = spreadPick(imageRefs, MAX_RESULT_FRAMES)
        val uris = picked.mapNotNull { ref ->
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
            append("关键节点：\n")
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
        mode: String,
        fromSec: Int,
        coveredUntilSec: Int?,
        frameAtSec: Int?,
        frameAttached: Boolean,
    ): String {
        // 官方总结没有逐句字幕，"看到哪"就用大纲最后一条；字幕路径用真正读到的最后一行。
        val covered = coveredUntilSec
            ?: if (text.hasSubtitle) text.lines.lastOrNull()?.fromSec else null
        val unread = covered?.let { (text.durationSec - it).coerceAtLeast(0) }
        return buildJsonObject {
            put("ok", true)
            put("platform", "bilibili")
            put("bvid", text.bvid)
            put("title", text.title)
            if (text.author.isNotEmpty()) put("author", text.author)
            put("duration", formatBiliClock(text.durationSec))
            put("duration_sec", text.durationSec)
            put("source", source)
            // summary=摘要、shallow=没字幕只能看个大概。模型据此决定怎么说，不许把摘要说成全集。
            put("mode", mode)
            put("has_subtitle", text.hasSubtitle)
            put("frames", frameCount)
            put("cached", false)
            if (fromSec > 0) put("from", formatBiliClock(fromSec))
            if (covered != null) put("covered_until", formatBiliClock(covered))
            if (unread != null) put("unread_sec", unread)
            put("can_continue", (unread ?: 0) > CONTINUE_THRESHOLD_SEC)
            if (frameAtSec != null) {
                put("frame_at", formatBiliClock(frameAtSec))
                put("frame_attached", frameAttached)
            }
            put("summary", digest)
            put(
                "note",
                noteFor(
                    source = source,
                    mode = mode,
                    bvid = text.bvid,
                    durationSec = text.durationSec,
                    covered = covered,
                    unread = unread,
                    frameAt = frameAtSec,
                    frameAttached = frameAttached,
                ),
            )
        }.toString()
    }

    private fun noteFor(
        source: String,
        mode: String,
        bvid: String,
        durationSec: Int,
        covered: Int?,
        unread: Int?,
        frameAt: Int?,
        frameAttached: Boolean,
    ): String = buildString {
        append(
            when (source) {
                "official" ->
                    "这是 B 站官方 AI 总结，不是逐句字幕；时间戳可跳转：https://www.bilibili.com/video/$bvid?t=<秒>"
                "metadata" ->
                    "这个视频没有可用字幕（未登录 B 站时拿不到 AI 字幕），以上只是根据简介、章节和画面截图看的大概，" +
                        "不是逐句内容；转述时要说清“没字幕”，不要当成看过全部内容"
                else ->
                    "只依据视频字幕整理，字幕里没有的别补充；时间戳可跳转：https://www.bilibili.com/video/$bvid?t=<秒>"
            },
        )
        if (mode == "summary") {
            append("。这是**摘要**，不是全文：用户要更细的原话时，说明这是摘要，不要把摘要当成逐字内容")
        }
        val remaining = unread ?: 0
        if (remaining > CONTINUE_THRESHOLD_SEC) {
            val mark = covered?.let(::formatBiliClock).orEmpty()
            append("。已看到 ").append(mark).append("，全长 ").append(formatBiliClock(durationSec))
            append("，后面还没看；用户说“继续 / 后面呢”时带 from=").append(mark).append(" 再调一次，别重复总结同一段")
        }
        if (frameAt != null) {
            if (frameAttached) {
                append("。已经把 ").append(formatBiliClock(frameAt)).append(" 这一帧作为附图贴出，正文里不用再描述图片")
            } else {
                append("。这个视频没有可用的截图墙，那一帧取不到")
            }
        }
    }

    /** 命中缓存时把 `cached` 翻成 true，保留原来的其它字段。 */
    private fun withCached(payload: String): String = runCatching {
        val obj = JSON.parseToJsonElement(payload).jsonObject
        JsonObject(obj + ("cached" to JsonPrimitive(true))).toString()
    }.getOrDefault(payload)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 回给主模型的截图张数上限：均匀挑，低清图约 85 token/张。 */
        const val MAX_RESULT_FRAMES = 4

        /** 后段还剩多久才算"没看完"，值得提示模型续读（少于 1 分钟就当看完了）。 */
        const val CONTINUE_THRESHOLD_SEC = 60

        const val DEFAULT_LIMIT = 6
        const val MAX_LIMIT = 20
    }
}
