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
 * 主题检索一张表情：给一句人话（“被无语到”），回来几张候选图。
 *
 * 实现放在 `:core:network`（`StickerFetcher` 在 `:app` 里下载、压缩、审图、入库），
 * 这样换站点只改这一层。
 */
fun interface StickerImageSearch {
    suspend fun search(query: String, limit: Int): List<StickerCandidate>
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
 */
class BaiduImageSearch(private val client: OkHttpClient = defaultClient()) : StickerImageSearch {

    override suspend fun search(query: String, limit: Int): List<StickerCandidate> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val word = URLEncoder.encode("$q 表情包", "UTF-8").replace("+", "%20")
            val rn = (limit * 3).coerceIn(6, 30)
            val url = "https://image.baidu.com/search/acjson?tn=resultjson_com&pn=0&rn=$rn&word=$word"
            val body = runCatching {
                val request = Request.Builder().url(url).header("User-Agent", STICKER_UA).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) "" else response.body?.string().orEmpty()
                }
            }.getOrNull().orEmpty()
            parseBaiduImageJson(body, limit)
        }
}

/**
 * 从百度图片 JSON 里抠候选。故意不用严格 JSON 解析：这个接口带尾逗号、键值顺序不稳，
 * 逐项正则更耐折腾（`middleURL` 是缩略图，`fromURL` 是来源页，同一项里前后相邻）。
 */
internal fun parseBaiduImageJson(json: String, limit: Int): List<StickerCandidate> {
    if (json.isBlank() || limit <= 0) return emptyList()
    val item = Regex("\"middleURL\":\"([^\"]+)\"([\\s\\S]{0,6000}?)\"fromURL\":\"([^\"]+)\"")
    val seen = HashSet<String>()
    val out = ArrayList<StickerCandidate>()
    for (match in item.findAll(json)) {
        val imageUrl = match.groupValues[1].replace("\\/", "/").trim()
        val pageUrl = match.groupValues[3].replace("\\/", "/").trim()
        if (!imageUrl.startsWith("http")) continue
        if (!seen.add(imageUrl)) continue
        if (isBlocked(imageUrl) || isBlocked(pageUrl)) continue
        out += StickerCandidate(imageUrl = imageUrl, pageUrl = pageUrl)
        if (out.size >= limit) break
    }
    return out
}

private fun isBlocked(url: String): Boolean {
    if (url.isBlank()) return false
    val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
    if (host.isEmpty()) return false
    return STICKER_HOST_BLOCKLIST.any { host.contains(it, ignoreCase = true) }
}
