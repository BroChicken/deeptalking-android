package com.deeptalking.core.network

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一条检索结果：缩略图地址 + 来源页面（溯源/删除用）。 */
data class StickerCandidate(
    val imageUrl: String,
    val pageUrl: String = "",
)

/**
 * 一页检索结果。
 *
 * [blocked] 与 [error] 必须分开：百度的反爬**不是**"没有结果"（HTTP 200 + `{"antiFlag":1}`），
 * 把两者混成空列表会让角色对用户说"搜不到这个主题"，而真正的原因是检索源把请求拦了。
 */
data class StickerSearchPage(
    val candidates: List<StickerCandidate> = emptyList(),
    /** 预热 cookie 并重试一次之后仍被反爬拦截。 */
    val blocked: Boolean = false,
    /** 请求本身失败（超时/非 200），空串表示请求成功。 */
    val error: String = "",
)

/**
 * 主题检索若干张表情：给一句人话（“被无语到”），回来若干张候选图。
 *
 * `page` 从 0 开始，由调用方翻页：百度图片的 `pn=0` 每次返回同一批图，
 * 只取第一页会让同一主题第二次之后全部撞上"库里已有"。
 *
 * 实现放在 `:core:network`（下载、压缩、起名、入库在 `:app`），换站点只改这一层。
 */
fun interface StickerImageSearch {
    suspend fun search(query: String, limit: Int, page: Int): StickerSearchPage
}

/**
 * 素材站/图库/电商的水印图对聊天表情毫无用处，命中域名直接丢。
 * （实测百度图片对「蓝色大肥鱼 表情包」的前几条里就有 699pic/图巨人这类。）
 */
internal val STICKER_HOST_BLOCKLIST: List<String> = listOf(
    "699pic", "nipic", "ibaotu", "588ku", "tujuren", "zcool", "51miz", "quanjing",
    "pngsucai", "sucai", "vcg", "gettyimages", "shutterstock", "dreamstime",
    "zhituwang", "ooopic", "16pic", "bigstock", "taobao", "tmall", "jd.com", "1688",
    "zhanggui", "canva", "adobe", "foter", "pixabay", "freepik",
)

private const val STICKER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

/** 预热地址：只要一个 `BAIDUID` cookie，不涉及登录态。 */
private const val WARMUP_URL = "https://image.baidu.com/"

private const val SEARCH_URL = "https://image.baidu.com/search/acjson"

/**
 * 百度图片的 Cookie 罐子（内存，不落盘）。
 *
 * **没有 `BAIDUID` 就一定被拦**：同一个 IP、同样只带 UA，不带 cookie 时 `acjson`
 * 稳定回 `{"antiFlag":1,"message":"Forbid spider access"}`（HTTP 200）；先 GET 一次
 * `image.baidu.com` 拿到 `Set-Cookie: BAIDUID=…` 再请求就正常返回整页 JSON。
 * OkHttp 不配 `CookieJar` 时既不保存也不回传 cookie，所以这里自带一个。
 */
class BaiduCookieJar : CookieJar {

    private val cookies = ArrayList<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > now) this.cookies += cookie
        }
        // 只有百度的几个 cookie，这里只是防长时间运行意外堆积。
        if (this.cookies.size > MAX_COOKIES) {
            this.cookies.subList(0, this.cookies.size - MAX_COOKIES).clear()
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cookies.filter { it.expiresAt > now && it.matches(url) }
    }

    /** 是否有某个未过期的 cookie（预热是否真的成功）。 */
    @Synchronized
    fun has(name: String): Boolean {
        val now = System.currentTimeMillis()
        return cookies.any { it.name == name && it.expiresAt > now }
    }

    @Synchronized
    fun clear() = cookies.clear()

    private companion object {
        const val MAX_COOKIES = 32
    }
}

/**
 * 百度图片（`image.baidu.com/search/acjson`）：免登录、一条 JSON 就给出
 * 500px 级别的 `middleURL`。**关键词一定要带“表情包”**，否则会按字面理解
 * （实测只搜“蓝色大肥鱼”会返回 2018 年的海洋馆新闻图）。
 *
 * 两条硬约束都是实测出来的：
 * - 请求头**只带 UA**：再带 `Accept: text/html…` 这类浏览器头反而更容易命中反爬；
 * - **必须带 `BAIDUID` cookie**（[BaiduCookieJar] + 一次预热 GET）：不带时同一个 IP
 *   稳定被 `antiFlag` 拦掉，而且是在翻页之前就被拦——翻页修得再好也用不上。
 *   真的还被拦时，会强制重新预热并重试一次，仍失败才回报 [StickerSearchPage.blocked]。
 */
class BaiduImageSearch(
    client: OkHttpClient = defaultClient(),
    private val cookies: BaiduCookieJar = BaiduCookieJar(),
) : StickerImageSearch {

    /** 自己的一份 client：只给这个检索源挂 cookie 罐，不污染其它出站请求。 */
    private val http: OkHttpClient = client.newBuilder().cookieJar(cookies).build()

    private val warmLock = Mutex()

    @Volatile
    private var warmed = false

    override suspend fun search(query: String, limit: Int, page: Int): StickerSearchPage =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext StickerSearchPage()
            val word = URLEncoder.encode("$q 表情包", "UTF-8").replace("+", "%20")
            val rn = limit.coerceIn(6, 30)
            val pn = page.coerceAtLeast(0) * rn
            val url = "$SEARCH_URL?tn=resultjson_com&pn=$pn&rn=$rn&word=$word"

            warmUp()
            var fetched = get(url)
            if (fetched.error.isNotEmpty()) {
                return@withContext StickerSearchPage(error = fetched.error)
            }
            if (isAntiBotResponse(fetched.body)) {
                // cookie 过期或被换掉：强制重来一次；预热本身失败也没关系，下面照实报告被拦。
                warmUp(force = true)
                fetched = get(url)
                if (fetched.error.isNotEmpty()) {
                    return@withContext StickerSearchPage(error = fetched.error)
                }
            }
            if (isAntiBotResponse(fetched.body)) return@withContext StickerSearchPage(blocked = true)
            StickerSearchPage(candidates = parseBaiduImageJson(fetched.body, limit))
        }

    /**
     * 预热一次拿 `BAIDUID`，进程内最多一次；[force] 用于被拦之后重来。
     * 预热失败（断网/超时）不抛错，只是让后面的请求照实报"被拦"。
     */
    private suspend fun warmUp(force: Boolean = false) {
        if (!force && warmed && cookies.has("BAIDUID")) return
        warmLock.withLock {
            if (!force && warmed && cookies.has("BAIDUID")) return
            // 被拦时先丢掉旧 cookie 再拿一次：带着旧的去预热，服务端多半不会再发新的。
            if (force) cookies.clear()
            warmUpOnce()
            warmed = cookies.has("BAIDUID")
        }
    }

    private fun warmUpOnce() {
        runCatching {
            val request = Request.Builder().url(WARMUP_URL).header("User-Agent", STICKER_UA).build()
            // 只要响应头里的 Set-Cookie，body 不读。
            http.newCall(request).execute().use { }
        }
    }

    private data class Fetched(val body: String = "", val error: String = "")

    private fun get(url: String): Fetched = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", STICKER_UA).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use Fetched(error = "HTTP ${response.code}")
            Fetched(body = response.body?.string().orEmpty())
        }
    }.getOrElse { Fetched(error = it.message ?: "请求失败或超时") }
}

/** 百度的反爬响应：`{"antiFlag":1,...}`；空包也按被拦处理（正常返回一定带 `"data"`）。 */
internal fun isAntiBotResponse(body: String): Boolean {
    if (body.isBlank()) return true
    if (body.contains("\"antiFlag\":1") || body.contains("\"antiFlag\": 1")) return true
    return !body.contains("\"data\"")
}

/**
 * 从百度图片 JSON 里抠候选。故意不用严格 JSON 解析：这个接口带尾逗号、键值顺序不稳，
 * 按 `middleURL` 逐项切块更耐折腾。
 *
 * 来源页不能取 `fromURL`——它是 `ippr_z2C$…` 混淆串（当前接口里 `fromJumpUrl` 多数也是同值混淆串）。
 * 真实页面在同一条的 `replaceUrl[].FromURL`；没有就退到 `fromURLHost`，再没有就留空。
 */
internal fun parseBaiduImageJson(json: String, limit: Int): List<StickerCandidate> {
    if (json.isBlank() || limit <= 0) return emptyList()
    val items = Regex("\"middleURL\":\"([^\"]*)\"").findAll(json).toList()
    val out = ArrayList<StickerCandidate>()
    val seen = HashSet<String>()
    for ((index, match) in items.withIndex()) {
        if (out.size >= limit) break
        val imageUrl = match.groupValues[1].replace("\\/", "/").trim()
        if (!imageUrl.startsWith("http")) continue
        // 这一项后续字段所在区间：到下一个 middleURL 为止（每项几百字符，封顶 4000 防跑飞）。
        val blockStart = match.range.last + 1
        val blockEnd = items.getOrNull(index + 1)?.range?.first ?: json.length
        val pageUrl = sourcePageUrl(json.substring(blockStart, minOf(blockEnd, blockStart + 4000)))
        if (!seen.add(imageUrl)) continue
        if (isBlocked(imageUrl) || isBlocked(pageUrl)) continue
        out += StickerCandidate(imageUrl = imageUrl, pageUrl = pageUrl)
    }
    return out
}

private val REAL_PAGE_URL = Regex("\"FromURL\":\"(http[^\"]*)\"")
private val JUMP_URL = Regex("\"fromJumpUrl\":\"([^\"]*)\"")
private val SOURCE_HOST = Regex("\"fromURLHost\":\"([^\"]*)\"")

/**
 * 一条结果的来源页，按可信度取：
 * 1. `replaceUrl[].FromURL` —— 真 URL（当前数据里真实页面在这里）；
 * 2. `fromJumpUrl` —— 新版接口多数也是 `ippr_` 混淆串，只有以 http 开头才用；
 * 3. `fromURLHost` —— 只有域名没有路径；偶尔是「全网资源」这种占位文字，含点才当域名。
 */
internal fun sourcePageUrl(block: String): String {
    REAL_PAGE_URL.find(block)?.groupValues?.get(1)?.let { return it.replace("\\/", "/") }
    JUMP_URL.find(block)?.groupValues?.get(1)?.replace("\\/", "/")?.let {
        if (it.startsWith("http")) return it
    }
    SOURCE_HOST.find(block)?.groupValues?.get(1)?.trim()?.let { host ->
        if (host.contains('.') && !host.contains(' ')) return "https://$host"
    }
    return ""
}

private fun isBlocked(url: String): Boolean {
    if (url.isBlank()) return false
    val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
    if (host.isEmpty()) return false
    return STICKER_HOST_BLOCKLIST.any { host.contains(it, ignoreCase = true) }
}
