package com.deeptalking.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerSearchTest {

    private val json = """
        {"listNum":3,"data":[
          {"middleURL":"https://img2.baidu.com/it/u=1&f=JPEG","fromURL":"ippr_z2C_obfuscated","fromJumpUrl":"http:\/\/www.bilibili.com\/video\/1"},
          {"middleURL":"https://img95.699pic.com/element/1.png","fromURL":"ippr_z2C_obfuscated","fromJumpUrl":"http:\/\/699pic.com\/a"},
          {"middleURL":"https://img0.baidu.com/it/u=2&f=JPEG","fromURL":"ippr_z2C_obfuscated"}
        ]}
    """.trimIndent()

    @Test
    fun `parses image url and the real page url from fromJumpUrl`() {
        val out = parseBaiduImageJson(json, 5)
        assertEquals(2, out.size)
        assertEquals("https://img2.baidu.com/it/u=1&f=JPEG", out[0].imageUrl)
        // fromURL 是混淆串，不能当来源页；真实页面在 fromJumpUrl。
        assertEquals("http://www.bilibili.com/video/1", out[0].pageUrl)
        // 这条没有 fromJumpUrl：图片照收，来源页留空（绝不存混淆串）。
        assertEquals("", out[1].pageUrl)
        assertTrue(out.none { it.pageUrl.contains("ippr_") })
    }

    @Test
    fun `drops hosts on the watermark blocklist, including the source page`() {
        val blockedPage = """
            {"data":[
              {"middleURL":"https://img0.baidu.com/it/u=7&f=JPEG","fromURL":"x","fromJumpUrl":"http:\/\/699pic.com\/a"}
            ]}
        """.trimIndent()
        assertTrue(parseBaiduImageJson(blockedPage, 5).isEmpty())
        assertTrue(parseBaiduImageJson(json, 5).none { it.imageUrl.contains("699pic") })
    }

    @Test
    fun `dedupes and respects the limit`() {
        val duplicated = """
            {"data":[
              {"middleURL":"https://img2.baidu.com/it/u=1","fromURL":"a","fromJumpUrl":"http://a.com"},
              {"middleURL":"https://img2.baidu.com/it/u=1","fromURL":"a","fromJumpUrl":"http://a.com"},
              {"middleURL":"https://img2.baidu.com/it/u=2","fromURL":"b","fromJumpUrl":"http://b.com"},
              {"middleURL":"https://img2.baidu.com/it/u=3","fromURL":"c","fromJumpUrl":"http://c.com"}
            ]}
        """.trimIndent()
        // 重复项按首次出现去重，再按顺序截到 limit。
        assertEquals(listOf("u=1", "u=2"), parseBaiduImageJson(duplicated, 2).map { it.imageUrl.takeLast(3) })
        assertEquals(3, parseBaiduImageJson(duplicated, 10).size)
    }

    @Test
    fun `blank json yields nothing`() {
        assertTrue(parseBaiduImageJson("", 5).isEmpty())
        assertTrue(parseBaiduImageJson(json, 0).isEmpty())
    }

    @Test
    fun `the anti-bot payload is reported as blocked instead of empty results`() {
        // 百度反爬是 HTTP 200 + 这个 JSON；当成"没搜到"会让角色对用户说错话。
        assertTrue(isAntiBotResponse("""{"antiFlag":1,"message":"Forbid spider access"}"""))
        assertTrue(isAntiBotResponse(""))
        assertFalse(isAntiBotResponse(json))
    }
}
