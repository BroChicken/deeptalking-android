package com.deeptalking.domain.agent.video

import com.deeptalking.core.network.BiliSubtitleLine
import com.deeptalking.core.network.BiliVideoText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块与两种路径的纯逻辑：有字幕走 wrIter/map-reduce，没字幕走 shallow（并明确声明没字幕）、
 * 切块只在行边界断开、超过段数上限就截断。总结内容本身由模型负责，这里只锁"调了几次、喂了什么"。
 */
class VideoSummaryServiceTest {

    private data class Call(val system: String, val user: String, val images: List<String>)

    private class RecordingSummarizer(
        private val reply: (Int) -> String? = { "一句话：讲猫\n要点：\n- 00:10 猫很可爱" },
        /** false 模拟"模型不支持看图"：实现会退回纯文字，并回执 imagesAccepted=false。 */
        private val acceptImages: Boolean = true,
    ) : VideoSummarizer {
        val calls = mutableListOf<Call>()

        override suspend fun complete(system: String, user: String, imageRefs: List<String>): VideoDigest? {
            calls += Call(system, user, imageRefs)
            return reply(calls.size)?.let { VideoDigest(it, imagesAccepted = acceptImages || imageRefs.isEmpty()) }
        }
    }

    private fun line(from: Int, text: String) = BiliSubtitleLine(fromSec = from, text = text)

    private fun videoText(
        lines: List<BiliSubtitleLine>,
        title: String = "猫的一天",
        author: String = "某个UP",
        durationSec: Int = 600,
        desc: String = "记录一只猫的日常",
        source: BiliVideoText.Source = BiliVideoText.Source.Subtitle,
        chapters: List<BiliSubtitleLine> = emptyList(),
    ) = BiliVideoText(
        bvid = "BV1ZKn267EY3",
        title = title,
        author = author,
        durationSec = durationSec,
        cid = 43032511529L,
        upMid = 1L,
        desc = desc,
        source = source,
        lang = if (source == BiliVideoText.Source.Subtitle) "ai-zh" else "",
        lines = lines,
        chapters = chapters,
    )

    @Test
    fun shortTranscriptIsSummedUpInASingleCall() = runBlocking {
        val summarizer = RecordingSummarizer()
        val digest = VideoSummaryService(summarizer).digest(videoText(listOf(line(10, "猫很可爱"))))

        assertEquals(1, summarizer.calls.size)
        val call = summarizer.calls.single()
        assertTrue(call.user.contains("【视频】猫的一天"))
        assertTrue(call.user.contains("UP主：某个UP"))
        assertTrue(call.user.contains("[00:10] 猫很可爱"))
        assertTrue(call.system.contains("只依据字幕"))
        assertEquals("一句话：讲猫\n要点：\n- 00:10 猫很可爱", digest?.text)
    }

    @Test
    fun longTranscriptIsMappedInChunksThenReduced() = runBlocking {
        // 每行约 108 字，130 行 ≈ 14k 字，刚好超过一次补全的阈值，切成两段。
        val lines = (0 until 130).map { line(it * 5, "第${it}句".padEnd(100, '字')) }
        val summarizer = RecordingSummarizer()
        VideoSummaryService(summarizer).digest(videoText(lines))

        assertEquals(3, summarizer.calls.size)
        val mapCalls = summarizer.calls.dropLast(1)
        assertTrue(mapCalls[0].user.contains("第 1/2 段"))
        assertTrue(mapCalls[1].user.contains("第 2/2 段"))
        val reduce = summarizer.calls.last().user
        assertTrue(reduce.contains("【分段要点】"))
        assertTrue(reduce.contains("第 1 段："))
    }

    @Test
    fun aRealisticHalfHourVideoFitsInOneCall() = runBlocking {
        // 实测样本：28 分钟视频 637 句 / 7730 字，加上时间戳前缀约 12.8k 字符。
        val lines = (0 until 637).map { line(it * 2, "一句话十二个字左右的内容") }
        val summarizer = RecordingSummarizer()
        VideoSummaryService(summarizer).digest(videoText(lines, durationSec = 1707))

        assertEquals(1, summarizer.calls.size)
    }

    @Test
    fun chunksBreakOnLineBoundariesAndRespectTheCap() {
        val lines = (0 until 6).map { line(it, "x".repeat(30)) }
        val one = VideoSummaryService.chunkTranscript(lines, chunkChars = 40, maxChunks = 10)
        assertEquals(6, one.size)
        assertTrue(one.all { it.lines.size == 1 })

        val capped = VideoSummaryService.chunkTranscript(lines, chunkChars = 40, maxChunks = 2)
        assertEquals(2, capped.size)
        assertEquals(listOf(1, 2), capped.map { it.index })
    }

    @Test
    fun aFailedCallYieldsNullInsteadOfAFabricatedDigest() = runBlocking {
        val summarizer = RecordingSummarizer(reply = { null })
        assertNull(VideoSummaryService(summarizer).digest(videoText(listOf(line(1, "内容")))))
    }

    @Test
    fun aTextWithoutLinesFallsBackToTheShallowPrompt() = runBlocking {
        val summarizer = RecordingSummarizer()
        // 字幕源但一行都没有 = 实际没取到字幕，走 shallow，而不是"返回 null 让工具报错"。
        VideoSummaryService(summarizer).digest(
            videoText(emptyList(), source = BiliVideoText.Source.Subtitle),
        )
        assertEquals(1, summarizer.calls.size)
        assertTrue(summarizer.calls.single().system.contains("没有字幕"))
    }

    @Test
    fun focusIsForwardedToTheModel() = runBlocking {
        val summarizer = RecordingSummarizer()
        VideoSummaryService(summarizer).digest(videoText(listOf(line(5, "提到一本旧书"))), focus = "讲了哪本书")
        assertTrue(summarizer.calls.single().user.contains("【用户特别想知道】讲了哪本书"))
    }

    @Test
    fun framesAreAttachedAndTheSystemPromptWarnsAgainstGuessingFromThem() = runBlocking {
        val summarizer = RecordingSummarizer()
        VideoSummaryService(summarizer).digest(
            videoText(listOf(line(10, "猫很可爱"))),
            imageRefs = listOf("/tmp/f0.jpg", "/tmp/f1.jpg"),
        )

        val call = summarizer.calls.single()
        assertEquals(2, call.images.size)
        assertTrue(call.user.contains("另附 2 张视频截图"))
        assertTrue(call.system.contains("不要凭画面推测台词"))
    }

    // ------------------------------------------------------------- 没字幕的路径

    @Test
    fun aVideoWithoutSubtitlesIsSummarisedFromMetadataPlusFrames() = runBlocking {
        val summarizer = RecordingSummarizer()
        val digest = VideoSummaryService(summarizer).digest(
            text = videoText(
                lines = emptyList(),
                source = BiliVideoText.Source.Metadata,
                chapters = listOf(BiliSubtitleLine(0, "开场"), BiliSubtitleLine(95, "第一部分")),
            ),
            imageRefs = listOf("/tmp/f0.jpg", "/tmp/f1.jpg", "/tmp/f2.jpg"),
        )

        val call = summarizer.calls.single()
        assertTrue(call.system.contains("没有字幕"))
        assertTrue(call.system.contains("严禁编造"))
        assertTrue(call.user.contains("【章节标签】"))
        assertTrue(call.user.contains("- 01:35 第一部分"))
        assertEquals(3, call.images.size)
        assertEquals("一句话：讲猫\n要点：\n- 00:10 猫很可爱", digest?.text)
    }

    @Test
    fun rejectionOfImagesIsReportedBackToTheCaller() = runBlocking {
        val summarizer = RecordingSummarizer(acceptImages = false)
        val digest = VideoSummaryService(summarizer).digest(
            videoText(lines = emptyList(), source = BiliVideoText.Source.Metadata),
            imageRefs = listOf("/tmp/f0.jpg"),
        )
        // 文本照常拿到，但回执标明"图没被接受"，工具据此不会把截图塞给主模型。
        assertEquals("一句话：讲猫\n要点：\n- 00:10 猫很可爱", digest?.text)
        assertEquals(false, digest?.imagesAccepted)
    }

    @Test
    fun aMetadataOnlyVideoWithNoFramesStillGetsAPrompt() = runBlocking {
        val summarizer = RecordingSummarizer()
        VideoSummaryService(summarizer).digest(
            videoText(lines = emptyList(), source = BiliVideoText.Source.Metadata),
        )
        assertTrue(summarizer.calls.single().images.isEmpty())
        assertTrue(summarizer.calls.single().user.contains("取不到字幕"))
    }
}
