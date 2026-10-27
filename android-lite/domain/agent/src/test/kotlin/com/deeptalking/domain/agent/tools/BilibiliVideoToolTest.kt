package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.network.BiliCollection
import com.deeptalking.core.network.BiliCollectionItem
import com.deeptalking.core.network.BiliComment
import com.deeptalking.core.network.BiliDanmakuDigest
import com.deeptalking.core.network.BiliDanmakuHit
import com.deeptalking.core.network.BiliPage
import com.deeptalking.core.network.BiliRelatedVideo
import com.deeptalking.core.network.BiliSeason
import com.deeptalking.core.network.BiliSeasonEpisode
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
        private val comments: List<BiliComment>? = null,
        private val related: List<BiliRelatedVideo>? = null,
        private val collections: List<BiliCollection>? = null,
        private val episodes: List<BiliCollectionItem>? = null,
        private val danmaku: BiliDanmakuDigest? = null,
    ) : BiliVideoSource {
        val requested = mutableListOf<String>()
        val requestedPages = mutableListOf<Int>()

        /** 记录"要过哪些 extras + 带的 season_id"，用来断言"不带 extras 就不发请求"。 */
        val extrasAsked = mutableListOf<String>()

        override suspend fun load(bvid: String, page: Int): BiliVideoTextResult {
            requested += bvid
            requestedPages += page
            return result
        }

        override suspend fun storyboard(bvid: String, cid: Long): BiliStoryboard? = storyboard

        override suspend fun comments(bvid: String, aid: Long): List<BiliComment>? {
            extrasAsked += "comments:$aid"
            return comments
        }

        override suspend fun related(bvid: String): List<BiliRelatedVideo>? {
            extrasAsked += "related"
            return related
        }

        override suspend fun upCollections(upMid: Long): List<BiliCollection>? {
            extrasAsked += "series:$upMid"
            return collections
        }

        override suspend fun collectionEpisodes(upMid: Long, collectionId: Long): List<BiliCollectionItem>? {
            extrasAsked += "episodes:$collectionId"
            return episodes
        }

        override suspend fun danmaku(bvid: String, cid: Long): BiliDanmakuDigest? {
            extrasAsked += "danmaku:$cid"
            return danmaku
        }
    }

    private class RecordingSummarizer(
        private val reply: String? = "一句话：讲猫\n整体脉络：先讲猫。\n关键节点：\n- 00:10 猫很可爱",
        /** false 模拟"模型不支持看图"：工具据此不再把截图塞进结果。 */
        private val acceptImages: Boolean = true,
    ) : VideoSummarizer {
        var calls = 0
        val users = mutableListOf<String>()

        override suspend fun complete(system: String, user: String, imageRefs: List<String>): VideoDigest? {
            calls++
            users += user
            return reply?.let { VideoDigest(it, imagesAccepted = acceptImages || imageRefs.isEmpty()) }
        }
    }

    /** 可记录 `frame_at` 的抽帧替身（匿名 lambda 只能实现 `frames`）。 */
    private class FakeFrames(
        private val refs: List<String> = emptyList(),
        private val frame: String? = null,
    ) : VideoFrameProvider {
        var frameAsked: Int? = null

        override suspend fun frames(text: BiliVideoText, storyboard: BiliStoryboard?): List<String> = refs

        override suspend fun frameAt(text: BiliVideoText, storyboard: BiliStoryboard?, seconds: Int): String? {
            frameAsked = seconds
            return frame
        }
    }

    private class FakeCache(private val stored: String? = null) : VideoSummaryCache {
        val reads = mutableListOf<String>()
        val writtenKeys = mutableListOf<String>()
        var written: String? = null
        var writes = 0

        override suspend fun read(bvid: String): String? {
            reads += bvid
            return stored
        }

        override suspend fun write(bvid: String, payload: String) {
            writtenKeys += bvid
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
        upMid = 16555335L,
        // aid 才是评论区的 oid（117482388065032），和 cid 完全是两个数——取错就抓不到评论。
        aid = 117482388065032L,
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
        // 3 张候选帧均匀挑（上限 4），不是"只挑开头两张"。
        assertEquals(3, Regex("\"type\":\"input_image\"").findAll(result.contentJson).count())
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

    // -------------------------------------------------- 看到哪了 / 继续看 / 贴帧

    @Test
    fun aShortVideoThatWasReadToTheEndDoesNotAskToContinue() = runBlocking {
        val lines = listOf(BiliSubtitleLine(10, "开场"), BiliSubtitleLine(700, "结尾"))
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText(lines))),
            summary = VideoSummaryService(RecordingSummarizer()),
        )

        // 时长 754 秒、最后一行在 700 秒 → 只剩 54 秒没读，不值得再提"继续"。
        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"mode\":\"summary\""))
        assertTrue(result.contentJson.contains("\"can_continue\":false"))
        assertFalse(result.contentJson.contains("带 from="))
    }

    @Test
    fun anUnfinishedVideoTellsTheModelToContinueWithFrom() = runBlocking {
        val lines = listOf(BiliSubtitleLine(0, "开场"), BiliSubtitleLine(600, "讲到一半"))
        val text = subtitleText(lines).copy(durationSec = 1800)
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(text)),
            summary = VideoSummaryService(RecordingSummarizer()),
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context)

        assertTrue(result.contentJson.contains("\"can_continue\":true"))
        assertTrue(result.contentJson.contains("\"covered_until\":\"10:00\""))
        assertTrue(result.contentJson.contains("\"unread_sec\":1200"))
        assertTrue(result.contentJson.contains("带 from=10:00"))
    }

    @Test
    fun fromSendsOnlyTheLaterLinesToTheSummarizer() = runBlocking {
        val lines = listOf(
            BiliSubtitleLine(10, "开场白"),
            BiliSubtitleLine(300, "中段内容"),
            BiliSubtitleLine(700, "最后一段"),
        )
        val summarizer = RecordingSummarizer()
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText(lines))),
            summary = VideoSummaryService(summarizer),
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3","from":"10:05"}"""), context)

        assertEquals(1, summarizer.calls)
        assertFalse("600 秒之前的内容不该再喂一遍", summarizer.users.single().contains("开场白"))
        assertFalse(summarizer.users.single().contains("中段内容"))
        assertTrue(summarizer.users.single().contains("最后一段"))
        assertTrue(result.contentJson.contains("\"from\":\"10:05\""))
    }

    @Test
    fun continuingFromAMinuteBypassesTheCacheAndDoesNotOverwriteIt() = runBlocking {
        val cache = FakeCache(stored = """{"ok":true,"cached":false,"summary":"旧结果"}""")
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val tool = BilibiliVideoTool(
            source,
            summary = VideoSummaryService(RecordingSummarizer()),
            cache = cache,
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3","from":"5:00"}"""), context)

        assertFalse(result.contentJson.contains("旧结果"))
        assertEquals(listOf("BV1ZKn267EY3"), source.requested)
        assertEquals("续读的结果不覆盖从头看的泛总结缓存", 0, cache.writes)
    }

    @Test
    fun refreshForcesAFreshLookEvenWhenCached() = runBlocking {
        val cache = FakeCache(stored = """{"ok":true,"cached":false,"summary":"旧结果"}""")
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val tool = BilibiliVideoTool(
            source,
            summary = VideoSummaryService(RecordingSummarizer()),
            cache = cache,
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3","refresh":true}"""), context)

        assertFalse(result.contentJson.contains("旧结果"))
        assertEquals(listOf("BV1ZKn267EY3"), source.requested)
    }

    @Test
    fun frameAtPutsOneFrameOfTheVideoIntoTheReply() = runBlocking {
        val frames = FakeFrames(refs = listOf("/cache/f0.jpg"), frame = "/cache/f7.jpg")
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            summary = VideoSummaryService(RecordingSummarizer()),
            frames = frames,
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3","frame_at":"3:20"}"""), context)

        assertEquals(200, frames.frameAsked)
        assertEquals("/cache/f7.jpg", result.imageFileRef)
        assertTrue(result.contentJson.contains("\"frame_at\":\"03:20\""))
        assertTrue(result.contentJson.contains("\"frame_attached\":true"))
        assertTrue(result.contentJson.contains("作为附图贴出"))
    }

    @Test
    fun aFrameRequestWithoutAScreenshotWallIsReportedPlainly() = runBlocking {
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            summary = VideoSummaryService(RecordingSummarizer()),
            frames = FakeFrames(refs = emptyList(), frame = null),
        )

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3","frame_at":"1:00"}"""), context)

        assertEquals(null, result.imageFileRef)
        assertTrue(result.contentJson.contains("\"frame_attached\":false"))
        assertTrue(result.contentJson.contains("截图墙"))
    }

    @Test
    fun aBrokenTimeArgumentIsRejectedBeforeAnyRequest() = runBlocking {
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val tool = BilibiliVideoTool(source, summary = VideoSummaryService(RecordingSummarizer()))

        val result = tool.execute(call("""{"url":"BV1ZKn267EY3","frame_at":"不久之前"}"""), context)

        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("frame_at"))
        assertTrue(source.requested.isEmpty())
    }

    // ------------------------------------------------------- 分P / 合集(系列) / 分段

    private fun multiPartText() = subtitleText().copy(
        page = 2,
        pages = listOf(
            BiliPage(1, 43032511529L, "上集：清晨", 300),
            BiliPage(2, 43032511530L, "下集：黄昏", 454),
        ),
        chapters = listOf(BiliSubtitleLine(0, "开场"), BiliSubtitleLine(120, "矛盾的起点")),
    )

    private fun seasonText() = multiPartText().copy(
        season = BiliSeason(
            "猫猫宇宙",
            listOf(
                BiliSeasonEpisode(1, "BV1ZKn267EY3", "第一集 猫的清晨", 754),
                BiliSeasonEpisode(2, "BV1AAAAAAAAA", "第二集 猫的黄昏", 640),
            ),
        ),
    )

    @Test
    fun thePayloadCarriesPagesChaptersAndSeason() = runBlocking {
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(seasonText())),
            summary = VideoSummaryService(RecordingSummarizer()),
        )

        val body = tool.execute(call("""{"url":"BV1ZKn267EY3","p":2}"""), context).contentJson

        assertTrue("当前第几P：$body", body.contains("\"page\":2"))
        assertTrue(body.contains("\"page_count\":2"))
        assertTrue(body.contains("\"pages\""))
        assertTrue(body.contains("下集：黄昏"))
        assertTrue("分段：$body", body.contains("\"chapters\""))
        assertTrue(body.contains("矛盾的起点"))
        assertTrue("合集：$body", body.contains("\"season\""))
        assertTrue(body.contains("猫猫宇宙"))
        assertEquals("第一集在合集里的序号", true, body.contains("\"current\":1"))
        // note 要教模型怎么用这些结构，否则它会把列表当正文念出来。
        assertTrue(body.contains("分P"))
    }

    @Test
    fun explicitPageAndUrlPageAreBothHonoured() = runBlocking {
        val byArg = FakeSource(BiliVideoTextResult.Ok(multiPartText()))
        BilibiliVideoTool(byArg, summary = VideoSummaryService(RecordingSummarizer()))
            .execute(call("""{"url":"BV1ZKn267EY3","p":3}"""), context)
        assertEquals(listOf(3), byArg.requestedPages)

        val byUrl = FakeSource(BiliVideoTextResult.Ok(multiPartText()))
        BilibiliVideoTool(byUrl, summary = VideoSummaryService(RecordingSummarizer()))
            .execute(call("""{"url":"https://www.bilibili.com/video/BV1ZKn267EY3?p=4"}"""), context)
        assertEquals(listOf(4), byUrl.requestedPages)

        val plain = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        BilibiliVideoTool(plain, summary = VideoSummaryService(RecordingSummarizer()))
            .execute(call("""{"url":"BV1ZKn267EY3"}"""), context)
        assertEquals(listOf(1), plain.requestedPages)
    }

    @Test
    fun eachPartKeepsItsOwnCacheEntry() = runBlocking {
        val partTwo = FakeCache()
        BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(multiPartText())),
            summary = VideoSummaryService(RecordingSummarizer()),
            cache = partTwo,
        ).execute(call("""{"url":"BV1ZKn267EY3","p":2}"""), context)
        assertEquals(listOf("BV1ZKn267EY3#p2"), partTwo.reads)
        assertEquals(listOf("BV1ZKn267EY3#p2"), partTwo.writtenKeys)

        // 第 1 P 沿用裸 BV 号做键：历史缓存继续有效。
        val partOne = FakeCache()
        BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            summary = VideoSummaryService(RecordingSummarizer()),
            cache = partOne,
        ).execute(call("""{"url":"BV1ZKn267EY3"}"""), context)
        assertEquals(listOf("BV1ZKn267EY3"), partOne.writtenKeys)
    }

    // ------------------------------------------------------- 周边信息（extras）

    private fun hotComments() = listOf(
        BiliComment("小紫", "置顶：这期讲得真清楚", 302, 28),
        BiliComment("路人", "一口气看完", 41, 0),
    )

    private fun relatedVideos() = listOf(
        BiliRelatedVideo("BV1AAAAAAAAA", "另一个视频", "某UP", 193748, 1415),
    )

    private fun upCollections() = listOf(
        BiliCollection(
            kind = "season",
            id = 9384351L,
            title = "超级中国",
            count = 2,
            preview = listOf(BiliCollectionItem("BV1ZKn267EY3", "上集", 1707)),
        ),
        BiliCollection(kind = "series", id = 45123L, title = "随手拍系列", count = 7),
    )

    private fun collectionItems() = listOf(
        BiliCollectionItem("BV1ZKn267EY3", "上集", 1707),
        BiliCollectionItem("BV1CCCCCCCCC", "下集", 900),
    )

    private fun danmakuDigest() = BiliDanmakuDigest(
        total = 35,
        top = listOf(BiliDanmakuHit("前面的别走", 6, 10)),
        timeline = listOf(BiliDanmakuHit("开始了", 2, 0), BiliDanmakuHit("结束了", 1, 60)),
    )

    @Test
    fun extrasAreNotFetchedUnlessAsked() = runBlocking {
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()))
        val tool = BilibiliVideoTool(source, summary = VideoSummaryService(RecordingSummarizer()))

        val body = tool.execute(call("""{"url":"BV1ZKn267EY3"}"""), context).contentJson

        assertTrue("不带 extras 就一个额外请求都不该发：${source.extrasAsked}", source.extrasAsked.isEmpty())
        listOf("comments", "related", "series", "danmaku", "extras_failed").forEach { field ->
            assertFalse("默认结果里不该出现 $field：$body", body.contains("\"$field\""))
        }
    }

    @Test
    fun everyRequestedExtraComesBackInItsOwnField() = runBlocking {
        val source = FakeSource(
            result = BiliVideoTextResult.Ok(subtitleText()),
            comments = hotComments(),
            related = relatedVideos(),
            collections = upCollections(),
            episodes = collectionItems(),
            danmaku = danmakuDigest(),
        )
        val tool = BilibiliVideoTool(source, summary = VideoSummaryService(RecordingSummarizer()))

        val body = tool.execute(
            call("""{"url":"BV1ZKn267EY3","extras":["comments","related","series","danmaku"]}"""),
            context,
        ).contentJson

        assertTrue("评论：$body", body.contains("\"comments\":["))
        assertTrue(body.contains("置顶：这期讲得真清楚"))
        assertTrue(body.contains("\"likes\":302"))
        assertTrue("相关：$body", body.contains("\"related\":["))
        assertTrue(body.contains("另一个视频"))
        assertTrue(body.contains("\"views\":193748"))
        assertTrue("合集：$body", body.contains("\"series\":{"))
        assertTrue(body.contains("超级中国"))
        assertTrue(body.contains("随手拍系列"))
        assertTrue("弹幕：$body", body.contains("\"danmaku\":{"))
        assertTrue(body.contains("\"total\":35"))
        assertTrue(body.contains("前面的别走"))
        assertTrue("时间线：$body", body.contains("\"timeline\":"))
        // 评论按 aid 取、弹幕按 cid 取，两者不能搞混。
        assertTrue("评论 oid 必须是 aid：${source.extrasAsked}", source.extrasAsked.contains("comments:117482388065032"))
        assertTrue(source.extrasAsked.contains("danmaku:43032511529"))
        assertTrue(source.extrasAsked.contains("series:16555335"))
    }

    @Test
    fun commentCountAndTextLengthAreCapped() = runBlocking {
        val many = (1..12).map { index ->
            BiliComment("用户$index", "很长的评论".repeat(30), 100 - index, 0)
        }
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText()), comments = many),
            summary = VideoSummaryService(RecordingSummarizer()),
        )

        val body = tool.execute(call("""{"url":"BV1ZKn267EY3","extras":["comments"]}"""), context).contentJson

        assertEquals("评论最多 8 条", 8, Regex("\"user\":").findAll(body).count())
        assertTrue("长评论要截断", body.contains("…"))
        assertFalse("第 9 条之后的评论不该出现", body.contains("用户9"))
    }

    @Test
    fun anExtraThatCannotBeFetchedIsReportedInsteadOfInvented() = runBlocking {
        // 评论接口被 -352 风控、弹幕接口连不上：两个都得如实写进 extras_failed。
        val tool = BilibiliVideoTool(
            FakeSource(BiliVideoTextResult.Ok(subtitleText())),
            summary = VideoSummaryService(RecordingSummarizer()),
        )

        val body = tool.execute(
            call("""{"url":"BV1ZKn267EY3","extras":["comments","danmaku"]}"""),
            context,
        ).contentJson

        assertTrue("失败清单：$body", body.contains("\"extras_failed\":["))
        assertTrue(body.contains("\"name\":\"comments\""))
        assertTrue(body.contains("\"name\":\"danmaku\""))
        assertFalse("抓不到就不该有 comments 字段", body.contains("\"comments\":["))
        assertTrue("note 要拦住编造：$body", body.contains("不要编造"))
        assertTrue("摘要本身不能因为 extras 失败而失败", body.contains("\"ok\":true"))
    }

    @Test
    fun extrasBypassTheCacheOnBothReadsAndWrites() = runBlocking {
        val cache = FakeCache(stored = """{"ok":true,"cached":false,"summary":"旧结果"}""")
        val source = FakeSource(BiliVideoTextResult.Ok(subtitleText()), comments = hotComments())
        val tool = BilibiliVideoTool(source, summary = VideoSummaryService(RecordingSummarizer()), cache = cache)

        val body = tool.execute(call("""{"url":"BV1ZKn267EY3","extras":["comments"]}"""), context).contentJson

        assertTrue("评论是时效内容，不能读 7 天缓存", cache.reads.isEmpty())
        assertTrue("也不能把评论写进缓存", cache.writes == 0)
        assertFalse(body.contains("旧结果"))
        assertTrue(body.contains("\"comments\":["))
        assertEquals(listOf("BV1ZKn267EY3"), source.requested)
    }

    @Test
    fun aSeasonIdFetchesThatCollectionsFullEpisodeList() = runBlocking {
        val byId = FakeSource(
            BiliVideoTextResult.Ok(subtitleText()),
            collections = upCollections(),
            episodes = collectionItems(),
        )
        val body = BilibiliVideoTool(byId, summary = VideoSummaryService(RecordingSummarizer()))
            .execute(call("""{"url":"BV1ZKn267EY3","extras":["series"],"season_id":45123}"""), context)
            .contentJson

        assertTrue("带 season_id 就要取那个系列：${byId.extrasAsked}", byId.extrasAsked.contains("episodes:45123"))
        assertTrue(body.contains("\"collection_id\":45123"))
        assertTrue(body.contains("下集"))
        assertTrue(body.contains("\"episodes\":["))

        // 没指定合集时取第一个合集（让模型先看到"合集里长什么样"），不能什么都不取。
        val auto = FakeSource(
            BiliVideoTextResult.Ok(subtitleText()),
            collections = upCollections(),
            episodes = collectionItems(),
        )
        BilibiliVideoTool(auto, summary = VideoSummaryService(RecordingSummarizer()))
            .execute(call("""{"url":"BV1ZKn267EY3","extras":["series"]}"""), context)
        assertTrue(auto.extrasAsked.contains("episodes:9384351"))
    }

    @Test
    fun chineseExtraNamesAndUnknownOnesAreHandled() = runBlocking {
        val source = FakeSource(
            BiliVideoTextResult.Ok(subtitleText()),
            comments = hotComments(),
            danmaku = danmakuDigest(),
        )
        val tool = BilibiliVideoTool(source, summary = VideoSummaryService(RecordingSummarizer()))

        val body = tool.execute(call("""{"url":"BV1ZKn267EY3","extras":["评论","弹幕","不存在"]}"""), context)
            .contentJson

        assertTrue(body.contains("\"comments\":["))
        assertTrue(body.contains("\"danmaku\":{"))
        // 认不出的名字静默忽略，不该把整次调用判成失败。
        assertTrue(body.contains("\"ok\":true"))
        assertEquals(2, source.extrasAsked.size)
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
