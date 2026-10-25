package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.network.BiliStoryboard
import com.deeptalking.core.network.BiliSubtitleLine
import com.deeptalking.core.network.BiliVideoSource
import com.deeptalking.core.network.BiliVideoText
import com.deeptalking.core.network.BiliVideoTextResult
import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.defaultTools
import com.deeptalking.domain.agent.video.InlineImages
import com.deeptalking.domain.agent.video.VideoDigest
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
 * `bilibili_video` 的两个入口（query 搜视频 / url 总结视频）、三条总结路径（字幕 / 官方总结 /
 * 只有元信息）、缓存短路与抽帧透传，以及失败时的"别编造"文案。全程用替身，不联网。
 */
class BilibiliVideoToolTest {

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

    private class RecordingSummarizer(
        private val reply: String? = "一句话：讲猫\n要点：\n- 00:10 猫很可爱",
        /** false 模拟"模型不支持看图"：工具据此不再把截图塞进结果。 */
        private val acceptImages: Boolean = true,
    ) : VideoSummarizer {
        var calls = 0

        override suspend fun complete(system: String, user: String, imageRefs: List<String>): VideoDigest? {
            calls++
            return reply?.let { VideoDigest(it, imagesAccepted = acceptImages || imageRefs.isEmpty()) }
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

    private class RecordingWeb(private val reply: String = """{"ok":true,"results":[]}""") : WebContentProvider {
        val calls = mutableListOf<String>()

        private fun record(call: String): String {
            calls += call
            return reply
        }

        override suspend fun searchWeb(query: String, limit: Int): String = record("searchWeb:$query:$limit")

        override suspend fun fetch(url: String): String = record("fetch:$url")

        override suspend fun searchBilibili(keyword: String, limit: Int): String = record("bilibili:$keyword:$limit")

        override suspend fun hotBoard(platform: String, limit: Int): String = record("hotBoard:$platform:$limit")

        override suspend fun searchSocial(platform: String, query: String, limit: Int): String =
            record("searchSocial:$platform:$query:$limit")
    }

    private val context = AgentContext(character = Character(id = "c1", name = "小雨"), activeCharacterId = "c1")

    private fun call(arguments: String) = ToolCall(id = "t1", name = "bilibili_video", arguments = arguments)

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

    // ------------------------------------------------------------------ 搜视频

    @Test
    fun aQuerySearchesVideosThroughThePlatformProvider() = runBlocking {
        val web = RecordingWeb()
        val tool = BilibiliVideoTool(source = null, search = web)

        val result = tool.execute(call("""{"query":"猫 纪录片","limit":5}"""), context)

        assertEquals(listOf("searchSocial:bilibili:猫 纪录片:5"), web.calls)
        assertTrue(result.contentJson.contains("\"ok\":true"))
    }

    @Test
    fun aUrlWinsWhenBothQueryAndUrlAreGiven() = runBlocking {
        val web = RecordingWeb()
        val summarizer = RecordingSummarizer()
        val tool = BilibiliVideoTool(
            source = FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            search = web,
            summary = VideoSummaryService(summarizer),
        )

        tool.execute(call("""{"query":"猫","url":"BV1ZKn267EY3"}"""), context)

        assertTrue("给了 url 就该总结，不该再去搜", web.calls.isEmpty())
        assertEquals(1, summarizer.calls)
    }

    @Test
    fun neitherQueryNorUrlIsRejectedBeforeAnyRequest() = runBlocking {
        val web = RecordingWeb()
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val tool = BilibiliVideoTool(source = source, search = web)

        val result = tool.execute(call("{}"), context)

        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("至少给一个"))
        assertTrue(web.calls.isEmpty())
        assertTrue(source.requested.isEmpty())
    }

    @Test
    fun searchWithoutAProviderIsReported() = runBlocking {
        val result = BilibiliVideoTool(source = null, search = null)
            .execute(call("""{"query":"猫"}"""), context)
        assertTrue(result.contentJson.contains("联网功能不可用"))
    }

    // -------------------------------------------------------------------- 总结

    @Test
    fun subtitlePathSummarizesThroughTheService() = runBlocking {
        val summarizer = RecordingSummarizer()
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val tool = BilibiliVideoTool(source, summary = VideoSummaryService(summarizer))

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
    fun bareBvIdAndFocusAreAccepted() = runBlocking {
        val summarizer = RecordingSummarizer()
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            summary = VideoSummaryService(summarizer),
        )

        tool.execute(call("""{"url":"BV1ZKn267EY3","focus":"讲了几只猫"}"""), context)

        assertEquals(1, summarizer.calls)
        assertTrue(tool.definition.parametersJson.contains("\"focus\""))
    }

    @Test
    fun officialSummaryIsUsedWithoutCallingTheModel() = runBlocking {
        val summarizer = RecordingSummarizer()
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(officialText())),
            summary = VideoSummaryService(summarizer),
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
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(metadataText())),
            summary = VideoSummaryService(summarizer),
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
        val tool = BilibiliVideoTool(
            source = FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            summary = VideoSummaryService(RecordingSummarizer()),
            frames = VideoFrameProvider { _, _ -> listOf("/cache/f0.jpg", "/cache/f1.jpg", "/cache/f2.jpg") },
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"frames\":3"))
    }

    @Test
    fun aVideoWithoutSubtitlesSendsItsFramesToTheMainModel() = runBlocking {
        val tool = BilibiliVideoTool(
            source = FakeSource(BiliVideoTextResult.Ok(metadataText())),
            summary = VideoSummaryService(RecordingSummarizer(reply = "一句话：没字幕，看画面像是日常记录")),
            frames = VideoFrameProvider { _, _ -> listOf("/cache/f0.jpg", "/cache/f1.jpg", "/cache/f2.jpg") },
            images = InlineImages { ref -> "data:image/jpeg;base64,${ref.hashCode()}" },
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue("无字幕时工具结果应是 input_text + input_image 数组", result.contentJson.startsWith("["))
        assertTrue(result.contentJson.contains("\"type\":\"input_text\""))
        assertEquals(2, Regex("\"type\":\"input_image\"").findAll(result.contentJson).count())
        assertTrue(result.contentJson.contains("\"detail\":\"low\""))
        assertTrue(result.contentJson.contains("没字幕"))
    }

    @Test
    fun aSubtitleSummaryStaysPlainTextEvenWhenFramesExist() = runBlocking {
        val tool = BilibiliVideoTool(
            source = FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            summary = VideoSummaryService(RecordingSummarizer()),
            frames = VideoFrameProvider { _, _ -> listOf("/cache/f0.jpg") },
            images = InlineImages { "data:image/jpeg;base64,AAA" },
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue("有字幕时不该往结果里塞图片", result.contentJson.startsWith("{"))
    }

    @Test
    fun aTextOnlyModelNeverGetsFramesInTheResult() = runBlocking {
        val tool = BilibiliVideoTool(
            source = FakeSource(BiliVideoTextResult.Ok(metadataText())),
            summary = VideoSummaryService(RecordingSummarizer(acceptImages = false)),
            frames = VideoFrameProvider { _, _ -> listOf("/cache/f0.jpg") },
            images = InlineImages { "data:image/jpeg;base64,AAA" },
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        // 纯文本模型收到 input_image 会让下一轮 400，所以这里必须是纯 JSON。
        assertTrue(result.contentJson.startsWith("{"))
        assertTrue(result.contentJson.contains("\"frames\":1"))
    }

    @Test
    fun aCachedSummaryShortCircuitsTheWholePipeline() = runBlocking {
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val cache = FakeCache(stored = """{"ok":true,"bvid":"BV1ZKn267EY3","cached":false,"summary":"旧结果"}""")
        val summarizer = RecordingSummarizer()
        val tool = BilibiliVideoTool(source, summary = VideoSummaryService(summarizer), cache = cache)

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"cached\":true"))
        assertTrue(result.contentJson.contains("旧结果"))
        assertTrue(source.requested.isEmpty())
        assertEquals(0, summarizer.calls)
    }

    @Test
    fun aCachedShallowSummaryComesBackWithoutImages() = runBlocking {
        val cache = FakeCache(stored = """{"ok":true,"bvid":"BV1ZKn267EY3","has_subtitle":false,"cached":false}""")
        val tool = BilibiliVideoTool(
            source = FakeSource(BiliVideoTextResult.Ok(metadataText())),
            summary = VideoSummaryService(RecordingSummarizer()),
            cache = cache,
            frames = VideoFrameProvider { _, _ -> listOf("/cache/f0.jpg") },
            images = InlineImages { "data:image/jpeg;base64,AAA" },
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.startsWith("{"))
        assertTrue(result.contentJson.contains("\"cached\":true"))
    }

    @Test
    fun aFocusQuestionBypassesTheCache() = runBlocking {
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val cache = FakeCache(stored = """{"ok":true,"bvid":"BV1ZKn267EY3","cached":false,"summary":"旧结果"}""")
        val tool = BilibiliVideoTool(source, summary = VideoSummaryService(RecordingSummarizer()), cache = cache)

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3","focus":"讲了几只猫"}"""), context)

        assertFalse(result.contentJson.contains("旧结果"))
        assertEquals(listOf("BV1ZKn267EY3"), source.requested)
        assertEquals(0, cache.writes) // 带 focus 的结果不覆盖泛总结缓存
    }

    @Test
    fun aFreshSummaryIsWrittenToTheCache() = runBlocking {
        val cache = FakeCache(stored = null)
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            summary = VideoSummaryService(RecordingSummarizer()),
            cache = cache,
        )

        tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertEquals(1, cache.writes)
        assertTrue(cache.written!!.contains("\"ok\":true"))
    }

    @Test
    fun missingLoginIsExplainedInsteadOfFabricated() = runBlocking {
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Failure("B站视频详情读取失败", needsLogin = true)),
            summary = VideoSummaryService(RecordingSummarizer()),
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("设置页"))
        assertTrue(result.contentJson.contains("不要编造"))
    }

    @Test
    fun anArgumentWithoutABvIdIsRejectedBeforeAnyRequest() = runBlocking {
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val tool = BilibiliVideoTool(source, summary = VideoSummaryService(RecordingSummarizer()))

        val result = tool.execute(call("""{"url":"https://example.com/watch?v=1"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("BV 号"))
        assertTrue(source.requested.isEmpty())
    }

    @Test
    fun summarizerFailureIsReportedAsAnError() = runBlocking {
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            summary = VideoSummaryService(RecordingSummarizer(reply = null)),
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("总结失败"))
    }

    @Test
    fun unavailableSourceIsReported() = runBlocking {
        val result = BilibiliVideoTool(null).execute(call("""{"url":"BV1ZKn267EY3"}"""), context)
        assertTrue(result.contentJson.contains("视频总结不可用"))
    }

    // -------------------------------------------------------------- registration

    @Test
    fun theToolIsRegisteredBetweenHotBoardAndMemoryWrite() {
        val names = defaultTools(
            MemoryServiceImpl(),
            web = null,
            stickersEnabled = true,
            bilibiliVideo = BilibiliVideoTool(null),
        ).map { it.definition.name }
        assertEquals(
            listOf(
                "search_memory",
                "web_search",
                "web_fetch",
                "search",
                "hot_board",
                "bilibili_video",
                "memory_write",
                "upsert_lorebook_entry",
                "send_sticker",
            ),
            names,
        )
    }
}
