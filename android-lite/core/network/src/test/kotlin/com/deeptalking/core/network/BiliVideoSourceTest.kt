package com.deeptalking.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 纯函数层：WBI 签名的固定向量、字幕/总结解析、轨道选择、时间轴格式。
 *
 * 这些断言挡的是"线上才暴露"的错误：未登录时服务端不校验 `w_rid`，签名写错了本地
 * 也看不出来，只有配上 Cookie 之后才会 403。
 */
class BiliVideoSourceTest {

    // B 站公开文档里的固定向量（img/sub 两张 wbi 图名 → mixin key → w_rid）。
    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun mixinKeyMatchesThePublishedVector() {
        assertEquals("ea1db124af3c7062474693fa704f4ff8", BiliWbi.mixinKey(imgKey, subKey))
    }

    @Test
    fun signedQuerySortsEncodesAndAppendsTheRid() {
        val query = BiliWbi.signedQuery(
            params = mapOf("cid" to "43032511529", "bvid" to "BV1ZKn267EY3"),
            imgKey = imgKey,
            subKey = subKey,
            wts = 1792919039L,
        )
        assertEquals(
            "bvid=BV1ZKn267EY3&cid=43032511529&wts=1792919039" +
                "&w_rid=9a5243d0791431c16f3a4b159ef962c3",
            query,
        )
    }

    @Test
    fun queryValuesArePercentEncodedLikeEncodeUriComponent() {
        assertEquals("a%20b", BiliWbi.encodeQueryValue("a b"))
        assertEquals("%E4%B8%AD%E6%96%87", BiliWbi.encodeQueryValue("中文"))
        // JS 的 encodeURIComponent 不编码这四个符号，B 站实现里会先删掉它们。
        assertEquals("ab", BiliWbi.encodeQueryValue("a!b"))
        assertEquals("-._~", BiliWbi.encodeQueryValue("-._~"))
    }

    @Test
    fun subtitleBodyKeepsTimestampsAndDropsEmptyLines() {
        val body = """
            {"font_size":0.4,"body":[
              {"from":1.28,"to":2.9,"content":"先说结论"},
              {"from":12.5,"to":14.0,"content":"   "},
              {"from":65.0,"to":68.0,"content":"第二段"}
            ]}
        """.trimIndent()
        val lines = parseBiliSubtitleBody(body)
        assertEquals(2, lines.size)
        assertEquals(1, lines[0].fromSec)
        assertEquals("先说结论", lines[0].text)
        assertEquals(65, lines[1].fromSec)
    }

    @Test
    fun subtitleBodyDegradesToEmptyOnBrokenJson() {
        assertTrue(parseBiliSubtitleBody("<html>403</html>").isEmpty())
        assertTrue(parseBiliSubtitleBody("{\"body\":null}").isEmpty())
    }

    @Test
    fun tracksAreParsedAndAiChineseIsPreferred() {
        val root = """
            {"code":0,"data":{"subtitle":{"subtitles":[
              {"lan":"zh-CN","subtitle_url":"//i0.hdslb.com/bfs/subtitle/a.json"},
              {"lan":"ai-zh","subtitle_url":"//aisubtitle.hdslb.com/bfs/ai_subtitle/b.json"}
            ]}}}
        """.trimIndent()
        val tracks = parseBiliSubtitleTracks(root)
        assertEquals(listOf("zh-CN", "ai-zh"), tracks.map { it.lan })
        assertEquals("https://i0.hdslb.com/bfs/subtitle/a.json", tracks[0].url)

        val picked = pickBiliSubtitleTrack(tracks)
        assertEquals("ai-zh", picked?.lan)
        assertEquals("https://aisubtitle.hdslb.com/bfs/ai_subtitle/b.json", picked?.url)
    }

    @Test
    fun tracksFallBackToChineseThenFirstWhenNoAiTrackExists() {
        val zh = listOf(BiliSubtitleTrack("en-US", "https://x/e.json"), BiliSubtitleTrack("zh-Hans", "https://x/z.json"))
        assertEquals("zh-Hans", pickBiliSubtitleTrack(zh)?.lan)
        val onlyEnglish = listOf(BiliSubtitleTrack("en-US", "https://x/e.json"))
        assertEquals("en-US", pickBiliSubtitleTrack(onlyEnglish)?.lan)
        assertNull(pickBiliSubtitleTrack(emptyList()))
    }

    @Test
    fun anonymousPlayerPayloadYieldsNoTracks() {
        val root = """
            {"code":0,"data":{"need_login_subtitle":true,
              "subtitle":{"allow_submit":false,"lan":"","subtitles":[]}}}
        """.trimIndent()
        assertTrue(parseBiliSubtitleTracks(root).isEmpty())
    }

    @Test
    fun conclusionIsParsedIntoSummaryAndTimestampedOutline() {
        val root = """
            {"code":0,"data":{"code":0,"result_type":1,"result":{
              "summary":"这是总摘要",
              "outline":[
                {"title":"开场","part_outline":[{"timestamp":0,"content":"介绍背景"},{"timestamp":95,"content":"提出问题"}]},
                {"title":"结论","part_outline":[{"timestamp":3600,"content":"给出答案"}]}
              ]}}}
        """.trimIndent()
        val conclusion = parseBiliConclusion(root)
        assertEquals("这是总摘要", conclusion?.summary)
        assertEquals(listOf(0, 95, 3600), conclusion?.outline?.map { it.fromSec })
        assertEquals("介绍背景", conclusion?.outline?.first()?.text)
    }

    @Test
    fun conclusionWithoutResultMeansNoSummaryYet() {
        assertNull(parseBiliConclusion("""{"code":0,"data":{"code":1,"result_type":1}}"""))
        assertNull(parseBiliConclusion("""{"code":-403,"message":"访问权限不足"}"""))
    }

    @Test
    fun clockFormattingStaysReadableForLongVideos() {
        assertEquals("00:00", formatBiliClock(0))
        assertEquals("01:15", formatBiliClock(75))
        assertEquals("59:59", formatBiliClock(3599))
        assertEquals("1:01:12", formatBiliClock(3672))
    }

    @Test
    fun searchDurationTextIsTurnedIntoSeconds() {
        assertEquals(16, parseBiliDurationClock("0:16"))
        assertEquals(754, parseBiliDurationClock("12:34"))
        assertEquals(3723, parseBiliDurationClock("1:02:03"))
        assertNull(parseBiliDurationClock(""))
        assertNull(parseBiliDurationClock("未知"))
        assertNull(parseBiliDurationClock("1:2:3:4"))
    }

    @Test
    fun bvidIsExtractedFromLinksAndBareIds() {
        assertEquals(
            "BV1ZKn267EY3",
            BiliLinks.bvidOf("https://www.bilibili.com/video/BV1ZKn267EY3?p=2&t=30"),
        )
        assertEquals("BV1ZKn267EY3", BiliLinks.bvidOf("BV1ZKn267EY3"))
        assertEquals("BV1ZKn267EY3", BiliLinks.bvidOf(" https://b23.tv/BV1ZKn267EY3 "))
        assertNull(BiliLinks.bvidOf("https://www.bilibili.com/video/av12345"))
    }
}
