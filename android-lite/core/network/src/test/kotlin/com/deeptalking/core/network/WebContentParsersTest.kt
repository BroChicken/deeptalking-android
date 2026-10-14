package com.deeptalking.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests for the login-free CN platform sources. The fixtures below are
 * trimmed copies of real responses (captured 2026-10), so a platform changing
 * its payload shape shows up here instead of only on a device.
 */
class WebContentParsersTest {

    // ------------------------------------------------------------------ 必应

    @Test
    fun bingRssKeepsOnlyHttpResultsAndRespectsTheLimit() {
        val xml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <rss version="2.0"><channel><title>必应：原神</title>
            <item><title>《原神》官方网站</title><link>https://www.yuanshen.com/</link><description>开放世界冒险RPG</description></item>
            <item><title>占位</title><link>javascript:void(0)</link><description>不是链接</description></item>
            <item><title>米游社</title><link>https://www.miyoushe.com/</link><description>&lt;b&gt;社区&lt;/b&gt;</description></item>
            </channel></rss>
        """.trimIndent()

        val all = parseBingRss(xml, 10)
        assertEquals(2, all.size)
        assertEquals("《原神》官方网站", all[0].title)
        assertEquals("https://www.yuanshen.com/", all[0].url)
        assertEquals(1, parseBingRss(xml, 1).size)
    }

    // ------------------------------------------------------------ 热榜（4 家）

    @Test
    fun baiduBoardReadsCardsContentItems() {
        // 真实形态：data.cards[].content[].content[]，每条含 word / url。
        val body = """
            {"success":true,"data":{"cards":[{"component":"tabTextList","content":[{"content":[
              {"isTop":true,"url":"https://m.baidu.com/s?word=A","word":"话题一"},
              {"url":"https://m.baidu.com/s?word=B","word":"话题二","hotScore":"498万"}
            ]}]}]}}
        """.trimIndent()
        val topics = parseBaiduBoard(body, 10)
        assertEquals(2, topics.size)
        assertEquals("话题一", topics[0].title)
        assertEquals("https://m.baidu.com/s?word=A", topics[0].url)
        assertEquals("498万", topics[1].hot)
        assertEquals(1, parseBaiduBoard(body, 1).size)
    }

    @Test
    fun toutiaoBoardReadsTitleUrlAndFormatsHotValue() {
        val body = """
            {"data":[
              {"ClusterId":1,"Title":"中朝新鸭绿江大桥明日开通","Url":"https://www.toutiao.com/trending/1/","HotValue":11207549},
              {"ClusterId":2,"Title":"没有链接的话题","Url":"","HotValue":320}
            ]}
        """.trimIndent()
        val topics = parseToutiaoBoard(body, 10)
        assertEquals(2, topics.size)
        assertEquals("中朝新鸭绿江大桥明日开通", topics[0].title)
        assertEquals("https://www.toutiao.com/trending/1/", topics[0].url)
        assertEquals("1120.8万", topics[0].hot)
        assertEquals("", topics[1].url)
        assertEquals("320", topics[1].hot)
    }

    @Test
    fun douyinBoardReadsWordListAndFormatsHotValue() {
        val body = """
            {"active_time":"2026-10-14 13:44:12","status_code":0,
             "word_list":[{"word":"中朝新鸭绿江大桥将开通","hot_value":11207549,"label":0},{"word":"第二条","hot_value":88}]}
        """.trimIndent()
        val topics = parseDouyinBoard(body, 10)
        assertEquals(2, topics.size)
        assertEquals("中朝新鸭绿江大桥将开通", topics[0].title)
        assertEquals("1120.8万", topics[0].hot)
        assertEquals("88", topics[1].hot)
    }

    @Test
    fun zhihuHotReadsTargetAndDetailText() {
        // 热榜给的是 api 链接，要换成能打开的网页链接。
        val body = """
            {"data":[
              {"target":{"title":"如何看待患者送 1.9 元汽水后举报医院","url":"https://api.zhihu.com/questions/19581624"},
               "detail_text":"852 万热度"}
            ]}
        """.trimIndent()
        val topics = parseZhihuHot(body, 10)
        assertEquals(1, topics.size)
        assertEquals("如何看待患者送 1.9 元汽水后举报医院", topics[0].title)
        assertEquals("https://www.zhihu.com/question/19581624", topics[0].url)
        assertEquals("852 万热度", topics[0].hot)
    }

    // ------------------------------------------------------------------ 虎扑

    @Test
    fun hupuSearchReadsServerRenderedResults() {
        // 真实形态：每个 content-outline 块一条帖子，标题里的 <font> 高亮要去掉。
        val html = """
            <div class="bbs-search-web-content">
            <div class="content-outline"><div class="content-wrap">
              <a class="content-wrap-span" href="https://bbs.hupu.com/642941815.html" target="_blank" rel="noreferrer">「<font color='#c01e2f'>原神</font>」沃喝醉了</a>
              <a class="content-wrap-span" href="https://bbs.hupu.com/788" target="_blank">崩坏3</a>
              <span>2026-10-14</span><span class="content-wrap-span1">0</span><span class="content-wrap-span1">8</span>
            </div></div>
            <div class="content-outline"><div class="content-wrap">
              <a class="content-wrap-span" href="https://bbs.hupu.com/642928930.html" target="_blank">感觉玩<font color='#c01e2f'>原神</font>的人变多了</a>
              <span>2026-10-13</span>
            </div></div>
            </div>
        """.trimIndent()

        val hits = parseHupuSearch(html, 10)
        assertEquals(2, hits.size)
        assertEquals("「原神」沃喝醉了", hits[0].title)
        assertEquals("https://bbs.hupu.com/642941815.html", hits[0].url)
        assertEquals("2026-10-14 · 回复 0 · 推荐 8", hits[0].description)
        // 专区链接（没有 .html）不能当成帖子。
        assertTrue(hits.none { it.url.endsWith("/788") })
        assertEquals(1, parseHupuSearch(html, 1).size)
    }

    @Test
    fun hupuThreadReadsNextDataPayload() {
        val data = """
            {"props":{"pageProps":{"detail":{
              "breadCrumb":[{"title":"社区","url":"/"},{"title":"原神","url":"/706"}],
              "thread":{"tid":"642806812","title":"原神若娜瓦！",
                "content":"\u003cp\u003e素材来源：菠萝哈基蜜\u003c/p\u003e\u003cp\u003e\u003cimg data-origin=\"https://i10.hoopchina.com.cn/a.jpg\"\u003e\u003c/p\u003e",
                "read":12345,"replies":6,"lights":3,
                "author":{"puid":"98267158","puname":"楼主昵称"}},
              "replies":{"count":1,"list":[{"pid":"71511","content":"\u003cp\u003e胡桃：我的烧火棍也是该修理了\u003c/p\u003e",
                "author":{"puname":"回帖人"}}]}
            }}}}
        """.trimIndent()
        val html = "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">$data</script></body></html>"

        val thread = parseHupuThread(html)!!
        assertEquals("原神若娜瓦！", thread.title)
        assertEquals("楼主昵称", thread.author)
        assertEquals("原神", thread.board)
        assertEquals("素材来源：菠萝哈基蜜", thread.content)
        assertTrue(thread.stats.contains("阅读 12345"))
        assertEquals(listOf("https://i10.hoopchina.com.cn/a.jpg"), thread.images)
        assertEquals(1, thread.replies.size)
        assertEquals("回帖人", thread.replies[0].author)
        assertEquals("胡桃：我的烧火棍也是该修理了", thread.replies[0].content)
    }

    @Test
    fun hupuThreadReturnsNullWhenThePageHasNoPayload() {
        assertNull(parseHupuThread("<html><body>页面结构变了</body></html>"))
    }

    // ---------------------------------------------------------------- 网易云

    @Test
    fun neteaseSearchReadsSongs() {
        val body = """
            {"result":{"songCount":2,"songs":[
              {"id":347230,"name":"海阔天空","artists":[{"name":"Beyond"}],"album":{"name":"乐与怒"}},
              {"id":0,"name":""}
            ]},"code":200}
        """.trimIndent()
        val songs = parseNeteaseSearch(body, 10)
        assertEquals(1, songs.size)
        assertEquals("347230", songs[0].id)
        assertEquals("海阔天空", songs[0].name)
        assertEquals("Beyond", songs[0].artists)
        assertEquals("乐与怒", songs[0].album)
    }

    @Test
    fun neteaseSongAndComments() {
        val detail = """
            {"songs":[{"id":347230,"name":"海阔天空","artists":[{"name":"Beyond"}],
              "album":{"name":"乐与怒","picUrl":"https://p1.music.126.net/a.jpg"}}],"code":200}
        """.trimIndent()
        val song = parseNeteaseSong(detail)!!
        assertEquals("海阔天空", song.name)
        assertEquals("Beyond", song.artists)
        assertEquals("https://p1.music.126.net/a.jpg", song.cover)

        val comments = """
            {"total":70427,"more":true,
             "hotComments":[{"user":{"nickname":"冰凍七音"},"content":"有些人死了，他还活着","likedCount":52100}],
             "comments":[{"user":{"nickname":"路人"},"content":"经典","likedCount":3}]}
        """.trimIndent()
        val parsed = parseNeteaseComments(comments, 10)
        assertEquals(70427L, parsed.total)
        assertEquals(2, parsed.hot.size)
        assertEquals("冰凍七音", parsed.hot[0].author)
        assertEquals("有些人死了，他还活着", parsed.hot[0].content)
        assertEquals(52100L, parsed.hot[0].liked)
    }

    // ------------------------------------------------------------------ 通用

    @Test
    fun formatHotUsesFriendlyUnits() {
        assertEquals("", formatHot(0))
        assertEquals("320", formatHot(320))
        assertEquals("1120.8万", formatHot(11_207_549))
        assertEquals("1.2亿", formatHot(123_000_000))
    }

    @Test
    fun brokenPayloadsDegradeToEmptyInsteadOfThrowing() {
        assertEquals(0, parseBaiduBoard("不是 JSON", 10).size)
        assertEquals(0, parseToutiaoBoard("{}", 10).size)
        assertEquals(0, parseDouyinBoard("[]", 10).size)
        assertEquals(0, parseZhihuHot("{\"data\":null}", 10).size)
        assertEquals(0, parseHupuSearch("<html></html>", 10).size)
        assertEquals(0, parseNeteaseSearch("{}", 10).size)
        assertEquals(0, parseNeteaseComments("", 10).hot.size)
    }
}
