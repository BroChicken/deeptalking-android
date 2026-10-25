package com.deeptalking.domain.agent.tools

import com.deeptalking.core.network.BiliLinks
import com.deeptalking.core.network.BiliVideoSource
import com.deeptalking.core.network.BiliVideoText
import com.deeptalking.core.network.BiliVideoTextResult
import com.deeptalking.core.network.formatBiliClock
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.video.VideoSummaryService
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 总结一个 B 站视频（第 9 个工具）。
 *
 * 文本来源只有两条，都够不到时**必须如实报错**：`search` 能搜到视频，但"这个视频讲了什么"
 * 只能来自字幕或 B 站官方 AI 总结（两者都要登录态，见 `HttpBiliVideoSource`）。
 * 所以本工具的价值有一半是**挡住幻觉**——它把"没有字幕"翻译成模型能照着说的一句话，
 * 而不是让模型凭标题编内容。
 *
 * 字幕文本不落库、不进历史：这里只把 [VideoSummaryService] 压出来的几百字摘要交给模型。
 */
class VideoSummaryTool(
    private val source: BiliVideoSource?,
    private val summary: VideoSummaryService? = null,
) : AgentTool {

    override val definition = ToolDefinition(
        name = "video_summary",
        description = "总结一个 B 站视频讲了什么（用户发来 B 站链接，或你先用 search 搜到视频再挑一个）。" +
            "返回视频标题、UP主、时长与带时间戳的要点；传 focus 可只关注某个问题。" +
            "只依据视频字幕或 B 站官方 AI 总结，取不到文本时会返回失败原因（例如需要用户先在设置里登录 B 站）——" +
            "此时不得凭标题或封面编造视频内容，应把原因转述给用户。",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"B 站视频链接或 BV 号（必须来自上下文里真实存在的视频，禁止编造）"},"focus":{"type":"string","description":"用户特别想了解的点，如“结论是什么”“讲了哪几本书”（可选）"}},"required":["url"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val provider = source ?: return AgentToolResult(errorJson("视频总结不可用（联网功能未启用）"))
        val args = ToolArgs.parse(call.arguments)
        val raw = ToolArgs.string(args, "url").trim()
        val bvid = BiliLinks.bvidOf(raw)
            ?: return AgentToolResult(errorJson("需要一个 B 站视频链接或 BV 号（当前参数里没有可识别的 BV 号）"))
        val focus = ToolArgs.string(args, "focus").trim().ifEmpty { null }
        return when (val result = provider.load(bvid)) {
            is BiliVideoTextResult.Failure -> AgentToolResult(errorJson(failureReason(result)))
            is BiliVideoTextResult.Ok -> AgentToolResult(render(result.text, focus))
        }
    }

    private fun failureReason(result: BiliVideoTextResult.Failure): String = buildString {
        append(result.reason)
        if (result.needsLogin) {
            append("。可以请用户在设置页填一次 B 站 Cookie（只存在本机），不要编造这个视频的内容")
        } else {
            append("。不要编造视频内容，把这句话如实转述给用户")
        }
    }

    /** 官方总结省一次 LLM 调用；只有拿到逐句字幕时才走自建总结（能回答 focus）。 */
    private suspend fun render(text: BiliVideoText, focus: String?): String {
        if (text.source == BiliVideoText.Source.OfficialSummary) {
            return payload(text, "official", officialText(text))
        }
        val service = summary ?: return errorJson("总结服务不可用")
        val digest = service.digest(text, focus) ?: return errorJson("取到了字幕但总结失败，可稍后再试")
        return payload(text, "subtitle", digest)
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

    private fun payload(text: BiliVideoText, source: String, digest: String): String {
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
            put("summary", digest)
            put("note", "时间戳可跳转：https://www.bilibili.com/video/" + text.bvid + "?t=<秒>；" +
                "只依据" + (if (source == "official") "B站官方 AI 总结" else "视频字幕") + "整理，字幕里没有的别补充")
        }.toString()
    }
}
