package com.deeptalking.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Native port of the legacy `src/js/api/web-content.js` web tooling: Bing RSS
 * search, bilibili search/detail, and direct/Microlink page fetching. JSON
 * string shapes match the legacy JS byte-for-byte where the model consumes them.
 */
class HttpWebContentProvider(
    private val client: OkHttpClient = defaultClient(),
) : WebContentProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun searchWeb(query: String): String = withContext(Dispatchers.IO) {
        val text = trimText(query, 200)
        if (text.isEmpty()) {
            return@withContext buildJsonObject {
                put("ok", false)
                put("reason", "缺少搜索关键词 query")
            }.toString()
        }
        try {
            val response = executeGet(
                "https://www.bing.com/search?format=rss&q=" + encodeURIComponent(text),
                15_000L,
            )
            response.use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                val body = res.body?.string().orEmpty()
                if (PARSER_ERROR.containsMatchIn(body) || HTML_DOC.containsMatchIn(body)) {
                    throw IllegalStateException("搜索响应不是 RSS")
                }
                val items = ITEM_RE.findAll(body).mapNotNull { match ->
                    val item = match.groupValues[1]
                    val title = trimText(extractTag(item, "title"), 160)
                    val url = extractTag(item, "link")
                    if (title.isEmpty() || !HTTP_URL.containsMatchIn(url)) return@mapNotNull null
                    buildJsonObject {
                        put("title", title)
                        put("url", url)
                        put("description", trimText(extractTag(item, "description"), 400))
                    }
                }.take(6).toList()
                val results = buildJsonArray { items.forEach { add(it) } }
                val ok = results.isNotEmpty()
                buildJsonObject {
                    put("ok", ok)
                    put("query", text)
                    put("results", results)
                    if (!ok) put("reason", "没有取得搜索结果，不得编造，可请用户提供链接")
                }.toString()
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", false)
                put("query", text)
                put(
                    "reason",
                    "搜索服务不可用：" + (e.message ?: e.toString()) + "。可请用户提供链接，不得声称已搜索成功",
                )
            }.toString()
        }
    }

    override suspend fun searchBilibili(keyword: String): String = withContext(Dispatchers.IO) {
        biliSearchVideos(keyword)
    }

    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val target = url.trimJs()
        biliRoute(target)?.let { return@withContext it }

        val looksImage = IMAGE_URL.containsMatchIn(target)
        try {
            val response = executeGet(target, 20_000L)
            response.use { res ->
                val contentType = res.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
                if (res.isSuccessful && contentType.startsWith("image/")) {
                    return@use imageToolResult(target)
                }
                if (res.isSuccessful) {
                    val text = res.body?.string().orEmpty()
                    val readable = htmlToReadableText(text)
                    if (readable.length >= 80) {
                        return@use buildJsonObject {
                            put("ok", true)
                            put("url", target)
                            put("content", trimText(readable, 6000))
                        }.toString()
                    }
                    if (looksImage) return@use imageToolResult(target)
                    return@use fetchViaMicrolink(target)
                }
                if (looksImage) return@use imageToolResult(target)
                return@use fetchViaMicrolink(target)
            }
        } catch (e: Exception) {
            if (looksImage) imageToolResult(target) else fetchViaMicrolink(target)
        }
    }

    private fun biliRoute(url: String): String? {
        if (B23_URL.containsMatchIn(url)) {
            val id = resolveBiliShortLink(url)
            if (id != null) return biliVideoDetail(id)
        }
        extractBiliVideoId(url)?.let { return biliVideoDetail(it) }
        if (BILI_SEARCH_URL.containsMatchIn(url) || BILI_SEARCH_PATH.containsMatchIn(url)) {
            val keyword = extractBiliSearchKeyword(url)
            if (keyword.isNotEmpty()) return biliSearchVideos(keyword)
        }
        return null
    }

    private fun biliSearchVideos(keyword: String): String {
        val kw = keyword.trimJs()
        if (kw.isEmpty()) {
            return buildJsonObject {
                put("ok", false)
                put("platform", "bilibili")
                put("reason", "缺少搜索关键词")
            }.toString()
        }
        return try {
            val data = fetchBiliJson("/x/web-interface/search/all/v2", mapOf("keyword" to kw, "page" to "1"))
            val sections = (data as? JsonObject)?.get("result") as? JsonArray
            val videoSection = sections?.firstOrNull {
                (it as? JsonObject)?.get("result_type").toText() == "video"
            } as? JsonObject
            val source = (videoSection?.get("data") as? JsonArray).orEmpty()
            val mapped = source.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val bvid = item["bvid"].toText()
                if (bvid.isEmpty()) return@mapNotNull null
                buildJsonObject {
                    put("bvid", bvid)
                    put("title", stripHtmlTags(item["title"].toText()))
                    put("author", item["author"].toText())
                    val play = item["play"]
                    put("play", if (play == null || play is JsonNull) JsonPrimitive(0) else play)
                    put("duration", item["duration"].toText())
                    put("url", "https://www.bilibili.com/video/$bvid")
                    put("cover", normalizeBiliPic(item["pic"].toText()))
                }
            }.take(8).toList()
            if (mapped.isEmpty()) {
                return buildJsonObject {
                    put("ok", false)
                    put("platform", "bilibili")
                    put("keyword", kw)
                    put("reason", "没有搜到相关视频，可换个关键词再试")
                }.toString()
            }
            val results = buildJsonArray { mapped.forEach { add(it) } }
            buildJsonObject {
                put("ok", true)
                put("platform", "bilibili")
                put("keyword", kw)
                put("count", mapped.size)
                put("results", results)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", false)
                put("platform", "bilibili")
                put("keyword", kw)
                put("reason", "B站搜索失败：" + (e.message ?: e.toString()))
            }.toString()
        }
    }

    private fun biliVideoDetail(id: BiliId): String {
        return try {
            val params = if (!id.bvid.isNullOrEmpty()) {
                mapOf("bvid" to id.bvid)
            } else {
                mapOf("aid" to (id.aid ?: ""))
            }
            val data = fetchBiliJson("/x/web-interface/view", params) as? JsonObject
                ?: throw IllegalStateException("未返回视频数据")
            val cover = normalizeBiliPic(data["pic"].toText())
            val bvid = data["bvid"].toText()
            val payload = buildJsonObject {
                put("ok", true)
                put("platform", "bilibili")
                put("bvid", bvid)
                put("title", data["title"].toText())
                put("author", (data["owner"] as? JsonObject)?.get("name").toText())
                put("duration", data["duration"].toNumberOrZero())
                put("play", (data["stat"] as? JsonObject)?.get("view").toNumberOrZero())
                put("like", (data["stat"] as? JsonObject)?.get("like").toNumberOrZero())
                put("danmaku", (data["stat"] as? JsonObject)?.get("danmaku").toNumberOrZero())
                put("desc", trimText(data["desc"].toText(), 800))
                put("cover", cover)
                put("url", "https://www.bilibili.com/video/" + bvid)
                if (cover.isNotEmpty()) {
                    put("note", "封面图见 cover 字段，可在 reply 中用 ![封面](URL) 展示")
                }
            }
            val text = payload.toString()
            if (cover.isNotEmpty()) {
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", text)
                    })
                    add(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", cover)
                        put("detail", "low")
                    })
                }.toString()
            } else {
                text
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", false)
                put("platform", "bilibili")
                put("reason", "B站视频读取失败：" + (e.message ?: e.toString()))
            }.toString()
        }
    }

    private fun fetchViaMicrolink(url: String): String {
        return try {
            val response = executeGet(
                "https://api.microlink.io/?url=" + encodeURIComponent(url),
                12_000L,
            )
            response.use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                val body = res.body?.string().orEmpty()
                val root = json.parseToJsonElement(body) as? JsonObject
                val data = root?.get("data") as? JsonObject ?: throw IllegalStateException("未返回摘要数据")
                val image = (data["image"] as? JsonObject)?.get("url").toText()
                val title = data["title"].toText()
                val description = trimText(data["description"].toText(), 600)
                if (title.isEmpty() && description.isEmpty() && image.isEmpty()) {
                    return@use buildJsonObject {
                        put("ok", false)
                        put("url", url)
                        put("reason", "无法获取可读内容（直连与摘要服务均失败）")
                    }.toString()
                }
                val payload = buildJsonObject {
                    put("ok", true)
                    put("via", "microlink")
                    put("url", data["url"].toText(url))
                    put("title", title)
                    put("description", description)
                    put("publisher", data["publisher"].toText())
                    put("author", data["author"].toText())
                    put("date", data["date"].toText())
                    put("image", image)
                    if (image.isNotEmpty()) {
                        put("note", "主图见 image 字段，可在 reply 中用 ![图](URL) 展示")
                    }
                }
                val text = payload.toString()
                if (image.isNotEmpty()) {
                    buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", text)
                        })
                        add(buildJsonObject {
                            put("type", "input_image")
                            put("image_url", image)
                            put("detail", "low")
                        })
                    }.toString()
                } else {
                    text
                }
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", false)
                put("url", url)
                put(
                    "reason",
                    "抓取失败（直连与摘要服务均不可用）：" + (e.message ?: e.toString()),
                )
            }.toString()
        }
    }

    private fun fetchBiliJson(path: String, params: Map<String, String>): JsonElement {
        val query = params.entries.joinToString("&") {
            encodeURIComponent(it.key) + "=" + encodeURIComponent(it.value)
        }
        val url = "https://api.bilibili.com" + path + if (query.isNotEmpty()) "?$query" else ""
        val response = executeGet(url, 15_000L, mapOf("Referer" to "https://www.bilibili.com/"))
        return response.use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            val body = res.body?.string().orEmpty()
            val root = json.parseToJsonElement(body)
            val obj = root as? JsonObject
            if (obj == null) {
                throw IllegalStateException("接口返回异常（code ）")
            }
            val codeElement = obj["code"]
            val codeZero = when {
                codeElement == null -> false
                codeElement is JsonNull -> true
                else -> (codeElement as? JsonPrimitive)?.content?.toDoubleOrNull() == 0.0
            }
            if (!codeZero) {
                val message = obj["message"].toText("接口返回异常")
                throw IllegalStateException(message + "（code " + obj["code"].toText() + "）")
            }
            obj["data"] ?: JsonNull
        }
    }

    private fun resolveBiliShortLink(url: String): BiliId? {
        return try {
            val response = executeGet(url, 15_000L)
            response.use { res ->
                val direct = extractBiliVideoId(res.request.url.toString())
                if (direct != null) {
                    direct
                } else {
                    val html = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                    val match = BV10.find(html)
                    if (match != null) BiliId(bvid = match.groupValues[1]) else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun executeGet(
        url: String,
        timeoutMs: Long,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val callClient = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return callClient.newCall(builder.build()).execute()
    }
}

fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

private data class BiliId(
    val bvid: String? = null,
    val aid: String? = null,
)

private fun imageToolResult(url: String): String = buildJsonArray {
    add(buildJsonObject {
        put("type", "input_text")
        put(
            "text",
            "已获取图片。图片URL: " + url + "（如需展示给用户，可在 reply 中用 ![说明](" + url + ") 直接贴出）",
        )
    })
    add(buildJsonObject {
        put("type", "input_image")
        put("image_url", url)
        put("detail", "auto")
    })
}.toString()

private fun normalizeBiliPic(url: String): String {
    val value = url.trimJs()
    if (value.isEmpty()) return ""
    if (value.startsWith("//")) return "https:$value"
    return value.replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
}

private fun extractBiliVideoId(url: String): BiliId? {
    VIDEO_BV.find(url)?.let { return BiliId(bvid = it.groupValues[1]) }
    VIDEO_AV.find(url)?.let { return BiliId(aid = it.groupValues[1]) }
    return null
}

private fun extractBiliSearchKeyword(url: String): String {
    val match = KEYWORD_RE.find(url) ?: return ""
    return try {
        decodeURIComponent(match.groupValues[1]).trimJs()
    } catch (e: Exception) {
        match.groupValues[1].trimJs()
    }
}

private fun stripHtmlTags(value: String): String =
    WS_RUN.replace(decodeBasicHtmlEntities(TAG_RE.replace(value, "")), " ").trimJs()

private fun htmlToReadableText(html: String): String {
    var text = SCRIPT_RE.replace(html, " ")
    text = STYLE_RE.replace(text, " ")
    text = NOSCRIPT_RE.replace(text, " ")
    text = HTML_TAG_RE.replace(text, " ")
    text = decodeBasicHtmlEntities(text)
    text = SPACE_RUN.replace(text, " ")
    text = NEWLINE3.replace(text, "\n\n")
    return text.trimJs()
}

private fun decodeBasicHtmlEntities(value: String): String {
    var text = value
    text = text.replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
    text = text.replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
    text = text.replace(Regex("&lt;", RegexOption.IGNORE_CASE), "<")
    text = text.replace(Regex("&gt;", RegexOption.IGNORE_CASE), ">")
    text = text.replace(Regex("&quot;", RegexOption.IGNORE_CASE), "\"")
    text = text.replace(Regex("&#39;", RegexOption.IGNORE_CASE), "'")
    return text
}

private fun decodeXmlEntities(value: String): String = ENTITY_RE.replace(value) { match ->
    when (val entity = match.groupValues[1].lowercase(Locale.ROOT)) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> when {
            entity.startsWith("#x") -> decodeCodePoint(entity.drop(2), 16) ?: match.value
            entity.startsWith("#") -> decodeCodePoint(entity.drop(1), 10) ?: match.value
            else -> match.value
        }
    }
}

private fun decodeCodePoint(raw: String, radix: Int): String? {
    val code = raw.toIntOrNull(radix) ?: return null
    if (!Character.isValidCodePoint(code)) return null
    return String(Character.toChars(code))
}

private fun extractTag(xml: String, tag: String): String {
    val regex = Regex("<$tag\\b[^>]*>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val match = regex.find(xml) ?: return ""
    val content = match.groupValues[1]
    val cdata = CDATA_RE.find(content)
    val decoded = if (cdata != null) cdata.groupValues[1] else decodeXmlEntities(content)
    return decoded.trimJs()
}

private fun trimText(value: String, maxLength: Int): String {
    val text = value.trimJs()
    return if (maxLength > 0 && text.length > maxLength) text.substring(0, maxLength) + "…" else text
}

private fun String.trimJs(): String = trim { it.isWhitespace() || it.code == 0x00A0 || it == '\uFEFF' }

private fun JsonElement?.toText(fallback: String = ""): String {
    val element = this ?: return fallback
    return when (element) {
        is JsonNull -> fallback
        is JsonPrimitive -> element.content
        else -> element.toString()
    }
}

private fun JsonElement?.toNumberOrZero(): Long {
    val primitive = this as? JsonPrimitive ?: return 0L
    return primitive.content.toDoubleOrNull()?.toLong() ?: 0L
}

private fun encodeURIComponent(value: String): String {
    val hex = "0123456789ABCDEF"
    val out = StringBuilder()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
            char == '-' || char == '_' || char == '.' || char == '!' ||
            char == '~' || char == '*' || char == '\'' || char == '(' || char == ')'
        ) {
            out.append(char)
        } else {
            out.append('%').append(hex[(code shr 4) and 0xF]).append(hex[code and 0xF])
        }
    }
    return out.toString()
}

private fun decodeURIComponent(value: String): String {
    val out = StringBuilder()
    val bytes = ByteArrayOutputStream()
    fun flush() {
        if (bytes.size() > 0) {
            out.append(String(bytes.toByteArray(), Charsets.UTF_8))
            bytes.reset()
        }
    }
    var i = 0
    while (i < value.length) {
        val char = value[i]
        if (char == '%' && i + 2 < value.length) {
            val code = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (code != null) {
                bytes.write(code)
                i += 3
                continue
            }
        }
        flush()
        out.append(char)
        i++
    }
    flush()
    return out.toString()
}

private val ITEM_RE = Regex(
    "<item\\b[^>]*>(.*?)</item>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val PARSER_ERROR = Regex("<parsererror", RegexOption.IGNORE_CASE)
private val HTML_DOC = Regex("<(?:!doctype\\s+html|html)\\b", RegexOption.IGNORE_CASE)
private val HTTP_URL = Regex("^https?://", RegexOption.IGNORE_CASE)
private val IMAGE_URL = Regex("\\.(png|jpe?g|gif|webp|bmp|svg)(\\?|#|\$)", RegexOption.IGNORE_CASE)
private val B23_URL = Regex("^https?://(www\\.)?b23\\.tv/", RegexOption.IGNORE_CASE)
private val BILI_SEARCH_URL = Regex("search\\.bilibili\\.com", RegexOption.IGNORE_CASE)
private val BILI_SEARCH_PATH = Regex("bilibili\\.com/.*/search", RegexOption.IGNORE_CASE)
private val VIDEO_BV = Regex("/video/(BV[0-9A-Za-z]+)", RegexOption.IGNORE_CASE)
private val VIDEO_AV = Regex("/video/av(\\d+)", RegexOption.IGNORE_CASE)
private val BV10 = Regex("(BV[0-9A-Za-z]{10})")
private val KEYWORD_RE = Regex("[?&]keyword=([^&#]+)")
private val TAG_RE = Regex("<[^>]*>")
private val HTML_TAG_RE = Regex("<[^>]+>")
private val SCRIPT_RE = Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
private val STYLE_RE = Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
private val NOSCRIPT_RE = Regex("<noscript[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE)
private val CDATA_RE = Regex("^\\s*<!\\[CDATA\\[(.*?)\\]\\]>\\s*$", RegexOption.DOT_MATCHES_ALL)
private val ENTITY_RE = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
private val SPACE_RUN = Regex("[ \\t\\r\\f\\v]+")
private val NEWLINE3 = Regex("\\n{3,}")
private val WS_RUN = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")
