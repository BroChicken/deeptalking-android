package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.network.BiliSubtitleLine
import com.deeptalking.core.network.BiliVideoSource
import com.deeptalking.core.network.BiliVideoText
import com.deeptalking.core.network.BiliVideoTextResult
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.defaultTools
import com.deeptalking.domain.agent.video.VideoSummarizer
import com.deeptalking.domain.agent.video.VideoSummaryService
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `video_summary` 的两条通路（逐句字幕 → 自建总结、官方总结直接用）以及失败时的"别编造"文案。
 * 用假的文本源与假的补全实现，全程不联网。
 */
class VideoSummaryToolTest {

    private class FakeSource(private val result: BiliVideoTextResult) : BiliVideoSource {
        val requested = mutableListOf<String>()

        override suspend fun load(bvid: String): BiliVideoTextResult {
            requested += bvid
            return result
        }
    }

    private class RecordingSummarizer(private val reply: String? = "一句话：讲猫\n要点：\n- 00:10 猫很可爱") :
        VideoSummarizer {
        var calls = 0

        override suspend fun complete(system: String, user: String): String? {
            calls++
            return reply
        }
    }

    private val context = AgentContext(character = Character(id = "c1", name = "小雨"), activeCharacterId = "c1")

    private fun call(arguments: String) = ToolCall(id = "t1", name = "video_summary", arguments = arguments)

    private fun subtitleText(lines: List<BiliSubtitleLine> = listOf(BiliSubtitleLine(10, "猫很可爱"))) = BiliVideoText(
        bvid = "BV1ZKn267EY3",
        title = "猫的一天",
        author = "某个UP",
        durationSec = 754,
        cid = 43032511529L,
        upMid = 1L,
        desc = "记录一只猫的日常",
        source = BiliVideoText.Source.Subtitle,
        lang = "ai-zh",
        lines = lines,
    )

    private fun officialText() = BiliVideoText(
        bvid = "BV1ZKn267EY3",
        title = "猫的一天",
        author = "某个UP",
        durationSec = 754,
        cid = 43032511529L,
        upMid = 1L,
        desc = "",
        source = BiliVideoText.Source.OfficialSummary,
        lang = "zh",
        lines = listOf(BiliSubtitleLine(0, "开场介绍")),
        officialSummary = "这是官方摘要",
        officialOutline = listOf(BiliSubtitleLine(0, "开场介绍"), BiliSubtitleLine(95, "提出问题")),
    )

    @Test
    fun subtitlePathSummarizesThroughTheService() = runBlocking {
        val summarizer = RecordingSummarizer()
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val tool = VideoSummaryTool(source, VideoSummaryService(summarizer))

        val result = tool.execute(call("""{"url":"https://www.bilibili.com/video/BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertTrue(result.contentJson.contains("\"bvid\":\"BV1ZKn267EY3\""))
        assertTrue(result.contentJson.contains("\"source\":\"subtitle\""))
        assertTrue(result.contentJson.contains("猫很可爱"))
        assertTrue(result.contentJson.contains("\"duration\":\"12:34\""))
        assertEquals(listOf("BV1ZKn267EY3"), source.requested)
        assertEquals(1, summarizer.calls)
    }

    @Test
    fun bareBvIdAndFocusAreAccepted() = runBlocking {
        val summarizer = RecordingSummarizer()
        val tool = VideoSummaryTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            VideoSummaryService(summarizer),
        )

        tool.execute(call("""{"url":"BV1ZKn267EY3","focus":"讲了几只猫"}"""), context)

        assertEquals(1, summarizer.calls)
        assertTrue(tool.definition.parametersJson.contains("\"focus\""))
    }

    @Test
    fun officialSummaryIsUsedWithoutCallingTheModel() = runBlocking {
        val summarizer = RecordingSummarizer()
        val tool = VideoSummaryTool(
            FakeSource(BiliVideoTextResult.Ok(officialText())),
            VideoSummaryService(summarizer),
        )

        val result = tool.execute(call("""{"url":"https://www.bilibili.com/video/BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"source\":\"official\""))
        assertTrue(result.contentJson.contains("这是官方摘要"))
        assertTrue(result.contentJson.contains("- 01:35 提出问题"))
        assertEquals(0, summarizer.calls)
    }

    @Test
    fun missingLoginIsExplainedInsteadOfFabricated() = runBlocking {
        val tool = VideoSummaryTool(
            FakeSource(BiliVideoTextResult.Failure("B站的字幕与官方总结都需要登录态才能取到", needsLogin = true)),
            VideoSummaryService(RecordingSummarizer()),
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("登录态"))
        assertTrue(result.contentJson.contains("不要编造"))
    }

    @Test
    fun aVideoWithoutTextFailsHonestly() = runBlocking {
        val tool = VideoSummaryTool(
            FakeSource(BiliVideoTextResult.Failure("这个视频既没有字幕也没有官方总结")),
            null,
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("既没有字幕"))
        assertFalse(result.contentJson.contains("\"ok\":true"))
    }

    @Test
    fun anArgumentWithoutABvIdIsRejectedBeforeAnyRequest() = runBlocking {
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val tool = VideoSummaryTool(source, VideoSummaryService(RecordingSummarizer()))

        val result = tool.execute(call("""{"url":"https://example.com/watch?v=1"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("BV 号"))
        assertTrue(source.requested.isEmpty())
    }

    @Test
    fun summarizerFailureIsReportedAsAnError() = runBlocking {
        val tool = VideoSummaryTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            VideoSummaryService(RecordingSummarizer(reply = null)),
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("总结失败"))
    }

    @Test
    fun unavailableSourceIsReported() = runBlocking {
        val result = VideoSummaryTool(null).execute(call("""{"url":"BV1ZKn267EY3"}"""), context)
        assertTrue(result.contentJson.contains("视频总结不可用"))
    }

    @Test
    fun theToolIsRegisteredBetweenHotBoardAndMemoryWrite() {
        val tool = VideoSummaryTool(null)
        val names = defaultTools(
            MemoryServiceImpl(),
            web = null,
            stickersEnabled = true,
            videoSummary = tool,
        ).map { it.definition.name }
        assertEquals(
            listOf(
                "search_memory",
                "web_search",
                "web_fetch",
                "search",
                "hot_board",
                "video_summary",
                "memory_write",
                "upsert_lorebook_entry",
                "send_sticker",
            ),
            names,
        )
    }
}
