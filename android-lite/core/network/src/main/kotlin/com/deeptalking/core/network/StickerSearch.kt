package com.deeptalking.core.network

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** 检索源明确返回了反爬拦截。 */
    val blocked: Boolean = false,
    /** 请求本身失败（超时/非 200），空串表示请求成功。 */
    val error: String = "",
)

/**
 * 主题检索若干张表情：给一句人话（“被无语到”），回来一张候选图。
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

/**
 * 百度图片（`image.baidu.com/search/acjson`）：免登录、一条 JSON 就给出
 * 500px 级别的 `middleURL`。**关键词一定要带“表情包”**，否则会按字面理解
 * （实测只搜“蓝色大肥鱼”会返回 2018 年的海洋馆新闻图）。
 *
 * 请求头**只带 UA**：实测再带 `Accept: text/html...` 这类浏览器头反而会命中反爬
 * （`{"antiFlag":1,"message":"Forbid spider access"}`），只带 UA 才是正常返回。
 */
class BaiduImageSearch(private val client: OkHttpClient = defaultClient()) : StickerImageSearch {

    override suspend fun search(query: String, limit: Int, page: Int): StickerSearchPage =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext StickerSearchPage()
            val word = URLEncoder.encode("$q 表情包", "UTF-8").replace("+", "%20")
            val rn = limit.coerceIn(6, 30)
            val pn = page.coerceAtLeast(0) * rn
            val url = "https://image.baidu.com/search/acjson?tn=resultjson_com&pn=$pn&rn=$rn&word=$word"
            val response = runCatching {
                val request = Request.Builder().url(url).header("User-Agent", STICKER_UA).build()
                client.newCall(request).execute().use { it to (if (it.isSuccessful) it.body?.string().orEmpty() else "") }
            }.getOrNull()
                ?: return@withContext StickerSearchPage(error = "请求失败或超时")
            val (raw, body) = response
            if (!raw.isSuccessful) {
                return@withContext StickerSearchPage(error = "HTTP ${raw.code}")
            }
            if (isAntiBotResponse(body)) return@withContext StickerSearchPage(blocked = true)
            StickerSearchPage(candidates = parseBaiduImageJson(body, limit))
        }
}

/** 百度的反爬响应：`{"antiFlag":1,...}`；空包也按被拦处理（正常返回一定带 `"data"`）。 */
internal fun isAntiBotResponse(body: String): Boolean {
    if (body.isBlank()) return true
    if (body.contains("\"antiFlag\":1") || body.contains("\"antiFlag\": 1")) return true
    return !body.contains("\"data\"")
}

/**
 * 从百度图片 JSON 里抠候选。故意不用严格 JSON 解析：这个接口带尾逗号、键值顺序不稳，
 * 逐项正则更耐折腾（`middleURL` 是缩略图；`fromURL` 是**混淆串**，真实来源页在
 * `fromJumpUrl`，见下）。
 */
internal fun parseBaiduImageJson(json: String, limit: Int): List<StickerCandidate> {
    if (json.isBlank() || limit <= 0) return emptyList()
    // fromURL 是 `ippr_z2C$...` 这类混淆串，不能再当作来源页存进库、也无法用域名黑名单过滤；
    // 紧跟其后的 fromJumpUrl 才是真实页面（部分条目没有，那就留空）。
    val item = Regex(
        "\"middleURL\":\"([^\"]+)\"([\\s\\S]{0,6000}?)\"fromURL\":\"([^\"]+)\"" +
            "(?:[\\s\\S]{0,300}?\"fromJumpUrl\":\"([^\"]+)\")?",
    )
    val seen = HashSet<String>()
    val out = ArrayList<StickerCandidate>()
    for (match in item.findAll(json)) {
        val imageUrl = match.groupValues[1].replace("\\/", "/").trim()
        val pageUrl = match.groupValues[4].replace("\\/", "/").trim().let { if (it.startsWith("http")) it else "" }
        if (!imageUrl.startsWith("http")) continue
        if (!seen.add(imageUrl)) continue
        if (isBlocked(imageUrl) || isBlocked(pageUrl)) continue
        out += StickerCandidate(imageUrl = imageUrl, pageUrl = pageUrl)
        if (out.size >= limit) break
    }
    if (out.isEmpty()) {
        // 没有 fromJumpUrl 的旧格式/变体：退回只认 middleURL（来源页留空，不存混淆串）。
        val imageOnly = Regex("\"middleURL\":\"([^\"]+)\"")
        for (match in imageOnly.findAll(json)) {
            val imageUrl = match.groupValues[1].replace("\\/", "/").trim()
            if (!imageUrl.startsWith("http")) continue
            if (!seen.add(imageUrl)) continue
            if (isBlocked(imageUrl)) continue
            out += StickerCandidate(imageUrl = imageUrl)
            if (out.size >= limit) break
        }
    }
    return out
}

private fun isBlocked(url: String): Boolean {
    if (url.isBlank()) return false
    val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
    if (host.isEmpty()) return false
    return STICKER_HOST_BLOCKLIST.any { host.contains(it, ignoreCase = true) }
}
