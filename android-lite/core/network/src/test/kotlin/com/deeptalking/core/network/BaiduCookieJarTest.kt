package com.deeptalking.core.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百度图片必须带 `BAIDUID`，不带就稳定回 `antiFlag`（实测同一个 IP 的对照结果），
 * 所以这个罐子直接决定「能不能加进表情库」，不是可有可无的工具类。
 */
class BaiduCookieJarTest {

    private val warmup = "https://image.baidu.com/".toHttpUrl()
    private val search = "https://image.baidu.com/search/acjson?pn=0&rn=8".toHttpUrl()

    private fun cookie(header: String, url: String = "https://image.baidu.com/") =
        Cookie.parse(url.toHttpUrl(), header)!!

    @Test
    fun `replays the baidu cookie on the search request`() {
        val jar = BaiduCookieJar()
        assertFalse(jar.has("BAIDUID"))
        jar.saveFromResponse(warmup, listOf(cookie("BAIDUID=abc; Domain=.baidu.com; Path=/")))
        assertTrue(jar.has("BAIDUID"))
        assertEquals(listOf("BAIDUID"), jar.loadForRequest(search).map { it.name })
    }

    @Test
    fun `drops expired cookies`() {
        val jar = BaiduCookieJar()
        jar.saveFromResponse(warmup, listOf(cookie("BAIDUID=abc; Domain=.baidu.com; Path=/; Max-Age=0")))
        assertFalse(jar.has("BAIDUID"))
        assertTrue(jar.loadForRequest(search).isEmpty())
    }

    @Test
    fun `does not leak baidu cookies to other hosts`() {
        val jar = BaiduCookieJar()
        jar.saveFromResponse(warmup, listOf(cookie("BAIDUID=abc; Domain=.baidu.com; Path=/")))
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
        // 同域其它主机要带上：预热在 image.baidu.com，图片 CDN 在 img*.baidu.com。
        assertEquals(1, jar.loadForRequest("https://img1.baidu.com/it/u=1".toHttpUrl()).size)
    }

    @Test
    fun `replaces a cookie with the same name instead of stacking`() {
        val jar = BaiduCookieJar()
        jar.saveFromResponse(warmup, listOf(cookie("BAIDUID=old; Domain=.baidu.com; Path=/")))
        jar.saveFromResponse(warmup, listOf(cookie("BAIDUID=new; Domain=.baidu.com; Path=/")))
        assertEquals(listOf("new"), jar.loadForRequest(search).map { it.value })
    }

    @Test
    fun `empty Set-Cookie does not blow away what is already stored`() {
        val jar = BaiduCookieJar()
        jar.saveFromResponse(warmup, listOf(cookie("BAIDUID=abc; Domain=.baidu.com; Path=/")))
        jar.saveFromResponse(warmup, emptyList())
        assertTrue(jar.has("BAIDUID"))
    }
}
