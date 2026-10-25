package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.network.BiliStoryboard
import com.deeptalking.core.network.BiliSubtitleLine
import com.deeptalking.core.network.BiliVideoSource
import com.deeptalking.core.network.BiliVideoText
import com.deeptalking.core.network.BiliVideoTextResult
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.defaultTools
import com.deeptalking.domain.agent.video.VideoFrameProvider
import com.deeptalking.domain.agent.video.VideoSummarizer
import com.deeptalking.domain.agent.video.VideoSummaryCache
import com.deeptalking.domain.agent.video.VideoSummaryService
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `video_summary` 的三条输入路径（字幕 / 官方总结 / 只有元信息）、缓存短路、抽帧透传，
 * 以及失败时的"别编造"文案。用假的文本源、假补全、假缓存与假抽帧，全程不联网。
 */
class VideoSummaryToolTest {

    private class FakeSource(
        private val result: BiliVideoTextResult,
        private val storyboard: BiliStoryboard? = null,
    ) : BiliVideoSource {
        val requested = mutableListOf<String>()

        override suspend fun load(bvid: String): BiliVideoTextResult {
            requested += bvid
            return result
        }

        override suspend fun storyboard(bvid: String, cid: Long): BiliStoryboard? = storyboard
    }

    private class RecordingSummarizer(private val reply: String? = "一句话：讲猫\n要点：\n- 00:10 猫很可爱") :
        VideoSummarizer {
        var calls = 0

        override suspend fun complete(system: String, user: String, imageRefs: List<String>): String? {
            calls++
            return reply
        }
    }

    private class FakeCache(private val stored: String? = null) : VideoSummaryCache {
        var written: String? = null
        var writes = 0

        override suspend fun read(bvid: String): String? = stored

        override suspend fun write(bvid: String, payload: String) {
            written = payload
            writes++
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

    private fun metadataText(chapters: List<BiliSubtitleLine> = listOf(BiliSubtitleLine(0, "开场"))) =
        BiliVideoText(
            bvid = "BV1ZKn267EY3",
            title = "猫的一天",
            author = "某个UP",
            durationSec = 754,
            cid = 43032511529L,
            upMid = 1L,
            desc = "记录一只猫的日常",
            source = BiliVideoText.Source.Metadata,
            lang = "",
            chapters = chapters,
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
        assertTrue(result.contentJson.contains("\"has_subtitle\":true"))
        assertTrue(result.contentJson.contains("猫很可爱"))
        assertTrue(result.contentJson.contains("\"duration\":\"12:34\""))
        assertEquals(listOf("BV1ZKn267EY3"), source.requested)
        assertEquals(1, summarizer.calls)
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
    fun aVideoWithoutSubtitlesStillReturnsAShallowSummary() = runBlocking {
        val summarizer = RecordingSummarizer(reply = "一句话：没字幕，看画面像是日常记录")
        val tool = VideoSummaryTool(
            FakeSource(BiliVideoTextResult.Ok(metadataText())),
            VideoSummaryService(summarizer),
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertTrue(result.contentJson.contains("\"source\":\"metadata\""))
        assertTrue(result.contentJson.contains("\"has_subtitle\":false"))
        assertTrue(result.contentJson.contains("没字幕"))
        assertTrue(result.contentJson.contains("不是逐句内容"))
        assertEquals(1, summarizer.calls)
    }

    @Test
    fun framesAreRequestedAndReportedInThePayload() = runBlocking {
        val summarizer = RecordingSummarizer()
        val tool = VideoSummaryTool(
            source = FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            summary = VideoSummaryService(summarizer),
            frames = VideoFrameProvider { _, _ -> listOf("/cache/f0.jpg", "/cache/f1.jpg", "/cache/f2.jpg") },
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"frames\":3"))
    }

    @Test
    fun aCachedSummaryShortCircuitsTheWholePipeline() = runBlocking {
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val cache = FakeCache(stored = """{"ok":true,"bvid":"BV1ZKn267EY3","cached":false,"summary":"旧结果"}""")
        val summarizer = RecordingSummarizer()
        val tool = VideoSummaryTool(source, VideoSummaryService(summarizer), cache)

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"cached\":true"))
        assertTrue(result.contentJson.contains("旧结果"))
        assertTrue(source.requested.isEmpty())
        assertEquals(0, summarizer.calls)
    }

    @Test
    fun aFocusQuestionBypassesTheCacheAndIsStored() = runBlocking {
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val cache = FakeCache(stored = """{"ok":true,"bvid":"BV1ZKn267EY3","cached":false,"summary":"旧结果"}""")
        val tool = VideoSummaryTool(source, VideoSummaryService(RecordingSummarizer()), cache)

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3","focus":"讲了几只猫"}"""), context)

        assertFalse(result.contentJson.contains("旧结果"))
        assertEquals(listOf("BV1ZKn267EY3"), source.requested)
        assertEquals(0, cache.writes) // 带 focus 的结果不覆盖泛总结缓存
    }

    @Test
    fun aFreshSummaryIsWrittenToTheCache() = runBlocking {
        val cache = FakeCache(stored = null)
        val tool = VideoSummaryTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            VideoSummaryService(RecordingSummarizer()),
            cache,
        )

        tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertEquals(1, cache.writes)
        assertTrue(cache.written!!.contains("\"ok\":true"))
    }

    @Test
    fun missingLoginIsExplainedInsteadOfFabricated() = runBlocking {
        val tool = VideoSummaryTool(
            FakeSource(BiliVideoTextResult.Failure("B站视频详情读取失败", needsLogin = true)),
            VideoSummaryService(RecordingSummarizer()),
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("设置页"))
        assertTrue(result.contentJson.contains("不要编造"))
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
