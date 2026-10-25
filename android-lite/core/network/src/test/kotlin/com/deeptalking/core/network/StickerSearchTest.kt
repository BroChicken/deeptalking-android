package com.deeptalking.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerSearchTest {

    private val json = """
        {"listNum":3,"data":[
          {"middleURL":"https://img2.baidu.com/it/u=1&f=JPEG","fromURL":"http:\/\/www.bilibili.com\/video\/1"},
          {"middleURL":"https://img95.699pic.com/element/1.png","fromURL":"http:\/\/699pic.com\/a"},
          {"middleURL":"https://img0.baidu.com/it/u=2&f=JPEG","fromURL":"http:\/\/weibo.com\/a"}
        ]}
    """.trimIndent()

    @Test
    fun `parses image and page url and drops blocklisted hosts`() {
        val out = parseBaiduImageJson(json, 5)
        assertEquals(2, out.size)
        assertEquals("https://img2.baidu.com/it/u=1&f=JPEG", out[0].imageUrl)
        assertEquals("http://www.bilibili.com/video/1", out[0].pageUrl)
        assertTrue(out.none { it.imageUrl.contains("699pic") })
    }

    @Test
    fun `dedupes and respects the limit`() {
        val duplicated = """
            {"data":[
              {"middleURL":"https://img2.baidu.com/it/u=1","fromURL":"http://a.com"},
              {"middleURL":"https://img2.baidu.com/it/u=1","fromURL":"http://a.com"},
              {"middleURL":"https://img2.baidu.com/it/u=2","fromURL":"http://b.com"},
              {"middleURL":"https://img2.baidu.com/it/u=3","fromURL":"http://c.com"}
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
}
