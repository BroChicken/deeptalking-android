package com.deeptalking.domain.agent.video

import com.deeptalking.core.network.BiliSubtitleLine
import com.deeptalking.core.network.BiliVideoText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块与合并的纯逻辑：短字幕一次写完、长字幕先分段再合并、切块只在行边界断开、
 * 超过段数上限就截断。总结内容本身由模型负责，这里只锁"调了几次、喂了什么"。
 */
class VideoSummaryServiceTest {

    private class RecordingSummarizer(
        private val reply: (Int) -> String? = { "一句话：讲猫\n要点：\n- 00:10 猫很可爱" },
    ) : VideoSummarizer {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun complete(system: String, user: String): String? {
            calls += system to user
            return reply(calls.size)
        }
    }

    private fun line(from: Int, text: String) = BiliSubtitleLine(fromSec = from, text = text)

    private fun videoText(
        lines: List<BiliSubtitleLine>,
        title: String = "猫的一天",
        author: String = "某个UP",
        durationSec: Int = 600,
        desc: String = "记录一只猫的日常",
    ) = BiliVideoText(
        bvid = "BV1ZKn267EY3",
        title = title,
        author = author,
        durationSec = durationSec,
        cid = 43032511529L,
        upMid = 1L,
        desc = desc,
        source = BiliVideoText.Source.Subtitle,
        lang = "ai-zh",
        lines = lines,
    )

    @Test
    fun shortTranscriptIsSummedUpInASingleCall() = runBlocking {
        val summarizer = RecordingSummarizer()
        val digest = VideoSummaryService(summarizer).digest(videoText(listOf(line(10, "猫很可爱"))))

        assertEquals(1, summarizer.calls.size)
        val user = summarizer.calls.single().second
        assertTrue(user.contains("【视频】猫的一天"))
        assertTrue(user.contains("UP主：某个UP"))
        assertTrue(user.contains("[00:10] 猫很可爱"))
        assertEquals("一句话：讲猫\n要点：\n- 00:10 猫很可爱", digest)
    }

    @Test
    fun longTranscriptIsMappedInChunksThenReduced() = runBlocking {
        // 每行约 108 字，60 行 ≈ 6500 字，刚好切成两段。
        val lines = (0 until 60).map { line(it * 5, "第${it}句".padEnd(100, '字')) }
        val summarizer = RecordingSummarizer()
        VideoSummaryService(summarizer).digest(videoText(lines))

        assertEquals(3, summarizer.calls.size)
        val mapCalls = summarizer.calls.dropLast(1)
        assertTrue(mapCalls[0].second.contains("第 1/2 段"))
        assertTrue(mapCalls[1].second.contains("第 2/2 段"))
        val reduce = summarizer.calls.last().second
        assertTrue(reduce.contains("【分段要点】"))
        assertTrue(reduce.contains("第 1 段："))
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
    fun emptyTranscriptNeverCallsTheModel() = runBlocking {
        val summarizer = RecordingSummarizer()
        assertNull(VideoSummaryService(summarizer).digest(videoText(emptyList())))
        assertTrue(summarizer.calls.isEmpty())
    }

    @Test
    fun focusIsForwardedToTheModel() = runBlocking {
        val summarizer = RecordingSummarizer()
        VideoSummaryService(summarizer).digest(videoText(listOf(line(5, "提到一本旧书"))), focus = "讲了哪本书")
        assertTrue(summarizer.calls.single().second.contains("【用户特别想知道】讲了哪本书"))
    }
}
