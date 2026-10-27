package com.deeptalking.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B 站「周边信息」的解析与弹幕聚合。
 *
 * 全部是纯函数：不联网、不依赖登录态，形状直接取自实测响应（评论区 `top_replies`/`replies`、
 * 相关推荐是裸数组、合集列表在 `items_lists` 下、弹幕是 XML）。
 */
class BiliExtrasTest {

    // ------------------------------------------------------------------ 评论区

    @Test
    fun commentsAreParsedFromTopAndHotListsWithoutDuplicates() {
        val root = """
            {"code":0,"data":{
              "top_replies":[
                {"member":{"uname":"甲"},"content":{"message":"置顶：先说结论"},"like":302,"rcount":28}
              ],
              "replies":[
                {"member":{"uname":"甲"},"content":{"message":"置顶：先说结论"},"like":302,"rcount":28},
                {"member":{"uname":"乙"},"content":{"message":"第二条\n有换行"},"like":12,"rcount":0},
                {"member":{"uname":"丙"},"content":{"message":"   "},"like":0,"rcount":0}
              ]
            }}
        """.trimIndent()
        val comments = parseBiliComments(root)
        // 置顶那条在 replies 里也出现了一次，必须去重；纯空白评论丢掉。
        assertEquals(2, comments.size)
        assertEquals("甲", comments[0].user)
        assertEquals("置顶：先说结论", comments[0].text)
        assertEquals(302, comments[0].likes)
        assertEquals(28, comments[0].replies)
        assertEquals("第二条 有换行", comments[1].text)
    }

    @Test
    fun blockedOrEmptyCommentPayloadsDegradeToNothing() {
        assertTrue(parseBiliComments("""{"code":-352,"message":"-352"}""").isEmpty())
        assertTrue(parseBiliComments("""{"code":0,"data":{}}""").isEmpty())
        assertTrue(parseBiliComments("<html>412</html>").isEmpty())
    }

    @Test
    fun commentsAreCappedAtTheRequestedLimit() {
        val items = (1..30).joinToString(",") {
            """{"member":{"uname":"u$it"},"content":{"message":"评论$it"},"like":0}"""
        }
        val root = """{"code":0,"data":{"replies":[$items]}}"""
        assertEquals(20, parseBiliComments(root).size)
        assertEquals(5, parseBiliComments(root, limit = 5).size)
    }

    // -------------------------------------------------------------- 相关推荐

    @Test
    fun relatedIsParsedFromTheTopLevelArray() {
        val root = """
            {"code":0,"data":[
              {"aid":1,"bvid":"BV1AAAAAAAAA","title":"另一个视频","duration":1415,
               "owner":{"mid":9,"name":"某UP"},"stat":{"view":193748}},
              {"aid":2,"title":"没有BV号,应被丢掉","duration":10}
            ]}
        """.trimIndent()
        val related = parseBiliRelated(root)
        assertEquals(1, related.size)
        assertEquals("BV1AAAAAAAAA", related[0].bvid)
        assertEquals("另一个视频", related[0].title)
        assertEquals("某UP", related[0].author)
        assertEquals(193748, related[0].views)
        assertEquals(1415, related[0].durationSec)
    }

    @Test
    fun aRelatedPayloadWrappedInAListIsAlsoAccepted() {
        val root = """{"code":0,"data":{"list":[{"bvid":"BV1BBBBBBBBB","title":"包裹形式"}]}}"""
        assertEquals("包裹形式", parseBiliRelated(root).single().title)
        assertTrue(parseBiliRelated("""{"code":-404,"data":null}""").isEmpty())
    }

    // ------------------------------------------------------------- 合集/系列

    @Test
    fun collectionsAreParsedFromBothSeasonsAndSeriesLists() {
        val root = """
            {"code":0,"data":{"items_lists":{
              "page":{"page_num":1,"page_size":20},
              "seasons_list":[
                {"meta":{"season_id":9384351,"title":"超级中国","name":"合集·超级中国","total":2},
                 "archives":[
                   {"bvid":"BV1ZKn267EY3","title":"上集","duration":1707},
                   {"bvid":"BV1AAAAAAAAA","title":"下集","duration":900}
                 ]}
              ],
              "series_list":[
                {"meta":{"series_id":45123,"name":"随便拍系列","total":7},
                 "archives":[{"bvid":"BV1BBBBBBBBB","title":"第七期","duration":300}]}
              ]
            }}}
        """.trimIndent()
        val collections = parseBiliUpCollections(root)
        assertEquals(2, collections.size)

        val season = collections[0]
        assertEquals(KIND_SEASON, season.kind)
        assertTrue(season.isSeason)
        assertEquals(9384351L, season.id)
        assertEquals("超级中国", season.title)
        assertEquals(2, season.count)
        assertEquals(2, season.preview.size)
        assertEquals("上集", season.preview[0].title)

        // 系列没有 title 字段，要靠 name；编号字段也是 series_id。
        val series = collections[1]
        assertEquals(KIND_SERIES, series.kind)
        assertEquals(45123L, series.id)
        assertEquals("随便拍系列", series.title)
        assertEquals(7, series.count)
        assertEquals(1, series.preview.size)
    }

    @Test
    fun anUpWithoutCollectionsYieldsAnEmptyList() {
        assertTrue(parseBiliUpCollections("""{"code":0,"data":{"items_lists":{}}}""").isEmpty())
        assertTrue(parseBiliUpCollections("""{"code":-412,"message":"请求被拦截"}""").isEmpty())
    }

    @Test
    fun collectionEpisodesAreParsedFromArchives() {
        val root = """
            {"code":0,"data":{"archives":[
              {"bvid":"BV1ZKn267EY3","title":"第一集","duration":1707},
              {"bvid":"BV1AAAAAAAAA","title":"第二集","duration":900},
              {"title":"没有BV号"}
            ]}}
        """.trimIndent()
        val episodes = parseBiliCollectionEpisodes(root)
        assertEquals(2, episodes.size)
        assertEquals("第一集", episodes[0].title)
        assertEquals(1707, episodes[0].durationSec)
        assertEquals("BV1AAAAAAAAA", episodes[1].bvid)
    }

    // ----------------------------------------------------------------- 弹幕

    @Test
    fun danmakuXmlIsParsedWithTimestampsAndUnescapedText() {
        val xml = """
            <i>
              <chatserver>chat.bilibili.com</chatserver>
              <maxlimit>3000</maxlimit>
              <d p="53.24000,1,25,16777215,1700000000,0,abc,117865906720113">前面的别走</d>
              <d p="12.5,1,25,16777215,0,0,def,1">a&amp;b&lt;c&gt;d</d>
              <d p="bad,1,25">坏时间</d>
              <d p="61,1,25">   </d>
            </i>
        """.trimIndent()
        val lines = parseBiliDanmakuXml(xml)
        assertEquals(2, lines.size)
        assertEquals(53, lines[0].atSec)
        assertEquals("前面的别走", lines[0].text)
        assertEquals(12, lines[1].atSec)
        assertEquals("a&b<c>d", lines[1].text)
    }

    @Test
    fun numericEntitiesAreDecodedAndBrokenXmlYieldsNothing() {
        assertEquals("中A", unescapeXml("&#20013;&#x41;"))
        assertEquals("原样&保留", unescapeXml("原样&保留"))
        assertTrue(parseBiliDanmakuXml("<i></i>").isEmpty())
        assertTrue(parseBiliDanmakuXml("").isEmpty())
    }

    @Test
    fun repeatedDanmakuRanksFirstAndJunkIsDropped() {
        val lines = listOf(
            BiliDanmakuLine("233", 5),
            BiliDanmakuLine("!!!", 6),
            BiliDanmakuLine("前面的别走", 10),
            BiliDanmakuLine("前面的别走", 20),
            BiliDanmakuLine("前面的别走", 30),
            BiliDanmakuLine("名场面", 61),
            BiliDanmakuLine("名场面", 62),
            BiliDanmakuLine("哈哈", 200),
        )
        val digest = digestBiliDanmaku(lines)
        // total 是原始条数（含被过滤的），模型据此判断"弹幕多不多"。
        assertEquals(8, digest.total)
        assertEquals("前面的别走", digest.top[0].text)
        assertEquals(3, digest.top[0].count)
        assertEquals(10, digest.top[0].atSec)
        assertEquals("名场面", digest.top[1].text)
        assertEquals(2, digest.top[1].count)
        // 纯数字/纯符号不进高频榜。
        assertTrue(digest.top.none { it.text == "233" || it.text == "!!!" })
    }

    @Test
    fun timelineKeepsOneRepresentativeLinePerMinute() {
        val lines = listOf(
            BiliDanmakuLine("开场", 10),
            BiliDanmakuLine("开场", 20),
            BiliDanmakuLine("另一句", 30),
            BiliDanmakuLine("第一分钟", 61),
            BiliDanmakuLine("第三分钟", 200),
        )
        val digest = digestBiliDanmaku(lines, maxTimeline = 8)
        assertEquals(listOf(0, 60, 180), digest.timeline.map { it.atSec })
        // 第 0 分钟里"开场"出现两次，代表句就是它。
        assertEquals("开场", digest.timeline[0].text)
        assertEquals(2, digest.timeline[0].count)
    }

    @Test
    fun aLongVideoTimelineIsSpreadAcrossTheWholeVideo() {
        // 20 分钟各有一条弹幕，只留 4 个桶时必须覆盖开头和结尾，而不是挤在前四分钟。
        val lines = (0 until 20).map { BiliDanmakuLine("第${it}分钟", it * 60 + 5) }
        val digest = digestBiliDanmaku(lines, maxTimeline = 4)
        assertEquals(4, digest.timeline.size)
        assertEquals(0, digest.timeline.first().atSec)
        assertEquals(19 * 60, digest.timeline.last().atSec)
    }

    @Test
    fun danmakuWithNothingUsableIsReportedAsEmptyButCounted() {
        val digest = digestBiliDanmaku(listOf(BiliDanmakuLine("666", 1), BiliDanmakuLine("!!", 2)))
        assertEquals(2, digest.total)
        assertTrue(digest.isEmpty)
        assertTrue(digest.top.isEmpty() && digest.timeline.isEmpty())
        // 完全空输入也不炸。
        assertEquals(0, digestBiliDanmaku(emptyList()).total)
    }

    @Test
    fun capsAreRespected() {
        val lines = (0 until 50).map { BiliDanmakuLine("第${it}句弹幕", it * 61) }
        val digest = digestBiliDanmaku(lines, maxTop = 5, maxTimeline = 3)
        assertEquals(5, digest.top.size)
        assertEquals(3, digest.timeline.size)
        assertNull(digest.top.firstOrNull { it.text.isBlank() })
    }
}
