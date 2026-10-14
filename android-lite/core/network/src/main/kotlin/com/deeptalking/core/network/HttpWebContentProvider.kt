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
import okhttp3.Dns
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

    override suspend fun searchWeb(query: String, limit: Int): String = withContext(Dispatchers.IO) {
        val text = trimText(query, 200)
        if (text.isEmpty()) {
            return@withContext buildJsonObject {
                put("ok", false)
                put("reason", "缺少搜索关键词 query")
            }.toString()
        }
        val wanted = limit.coerceIn(1, MAX_SEARCH_RESULTS)
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
                val hits = parseBingRss(body, wanted)
                val results = buildJsonArray {
                    hits.forEach { hit ->
                        add(buildJsonObject {
                            put("title", hit.title)
                            put("url", hit.url)
                            put("description", hit.description)
                        })
                    }
                }
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

    override suspend fun searchBilibili(keyword: String, limit: Int): String = withContext(Dispatchers.IO) {
        biliSearchVideos(keyword, limit)
    }

    override suspend fun hotBoard(platform: String, limit: Int): String = withContext(Dispatchers.IO) {
        val name = platform.trim().lowercase(Locale.ROOT)
        if (name !in HOT_PLATFORMS) {
            return@withContext buildJsonObject {
                put("ok", false)
                put("reason", "不支持的平台：只支持 baidu / toutiao / douyin / zhihu")
            }.toString()
        }
        val wanted = limit.coerceIn(1, MAX_HOT_TOPICS)
        try {
            val body = executeGet(hotBoardUrl(name), 15_000L, hotBoardHeaders(name)).use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                res.body?.string().orEmpty()
            }
            val topics = when (name) {
                "baidu" -> parseBaiduBoard(body, wanted)
                "toutiao" -> parseToutiaoBoard(body, wanted)
                "douyin" -> parseDouyinBoard(body, wanted)
                else -> parseZhihuHot(body, wanted)
            }
            if (topics.isEmpty()) {
                return@withContext buildJsonObject {
                    put("ok", false)
                    put("platform", name)
                    put("reason", "没有取到榜单内容（接口可能已变更），不要编造榜单")
                }.toString()
            }
            val items = buildJsonArray {
                topics.forEach { topic ->
                    add(buildJsonObject {
                        put("title", topic.title)
                        if (topic.url.isNotEmpty()) put("url", topic.url)
                        if (topic.hot.isNotEmpty()) put("hot", topic.hot)
                    })
                }
            }
            buildJsonObject {
                put("ok", true)
                put("platform", name)
                put("count", topics.size)
                put("items", items)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", false)
                put("platform", name)
                put("reason", "热榜获取失败：" + (e.message ?: e.toString()))
            }.toString()
        }
    }

    override suspend fun searchSocial(platform: String, query: String, limit: Int): String =
        withContext(Dispatchers.IO) {
            val name = platform.trim().lowercase(Locale.ROOT)
            val text = trimText(query, 120)
            if (name !in SOCIAL_PLATFORMS) {
                return@withContext buildJsonObject {
                    put("ok", false)
                    put("reason", "不支持的平台：只支持 hupu / netease / bilibili")
                }.toString()
            }
            if (text.isEmpty()) {
                return@withContext buildJsonObject {
                    put("ok", false)
                    put("platform", name)
                    put("reason", "缺少检索关键词 query")
                }.toString()
            }
            val wanted = limit.coerceIn(1, MAX_SOCIAL_RESULTS)
            when (name) {
                "bilibili" -> biliSearchVideos(text, wanted)
                "netease" -> neteaseSearchSongs(text, wanted)
                else -> hupuSearchPosts(text, wanted)
            }
        }

    override suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val target = url.trimJs()
        biliRoute(target)?.let { return@withContext it }
        hupuRoute(target)?.let { return@withContext it }
        neteaseRoute(target)?.let { return@withContext it }

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
            if (keyword.isNotEmpty()) return biliSearchVideos(keyword, WebContentProvider.DEFAULT_VIDEO_LIMIT)
        }
        return null
    }

    private fun biliSearchVideos(keyword: String, limit: Int): String {
        val kw = keyword.trimJs()
        val wanted = limit.coerceIn(1, MAX_SOCIAL_RESULTS)
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
            }.take(wanted).toList()
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

    // ------------------------------------------------------------------ 虎扑

    /** Only `bbs.hupu.com/<数字>.html` is a thread; everything else falls back to generic fetch. */
    private fun hupuRoute(url: String): String? {
        if (!HUPU_THREAD_URL.containsMatchIn(url)) return null
        val tid = HUPU_TID.find(url)?.groupValues?.get(1) ?: return null
        return hupuThread(tid)
    }

    private fun hupuThread(tid: String): String {
        return try {
            val html = executeGet("https://bbs.hupu.com/$tid.html", 20_000L, CN_PAGE_HEADERS).use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                res.body?.string().orEmpty()
            }
            val thread = parseHupuThread(html) ?: throw IllegalStateException("页面结构已变更")
            val payload = buildJsonObject {
                put("ok", true)
                put("platform", "hupu")
                put("tid", tid)
                put("url", "https://bbs.hupu.com/$tid.html")
                if (thread.title.isNotEmpty()) put("title", thread.title)
                if (thread.author.isNotEmpty()) put("author", thread.author)
                if (thread.board.isNotEmpty()) put("board", thread.board)
                if (thread.stats.isNotEmpty()) put("stats", thread.stats)
                put("content", trimText(thread.content, 3000))
                if (thread.images.isNotEmpty()) {
                    put("images", buildJsonArray { thread.images.forEach { add(JsonPrimitive(it)) } })
                    put("note", "图片见 images 字段，可在 reply 中用 ![图](URL) 展示")
                }
                if (thread.replies.isNotEmpty()) {
                    put("replies", buildJsonArray {
                        thread.replies.forEach { reply ->
                            add(buildJsonObject {
                                if (reply.author.isNotEmpty()) put("author", reply.author)
                                put("content", trimText(reply.content, 400))
                            })
                        }
                    })
                }
            }
            withPreviewImages(payload.toString(), thread.images, 2)
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", false)
                put("platform", "hupu")
                put("reason", "虎扑帖子读取失败：" + (e.message ?: e.toString()))
            }.toString()
        }
    }

    private fun hupuSearchPosts(keyword: String, limit: Int): String {
        val kw = keyword.trimJs()
        return try {
            val html = executeGet(
                "https://bbs.hupu.com/search?q=" + encodeURIComponent(kw),
                20_000L,
                CN_PAGE_HEADERS,
            ).use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                res.body?.string().orEmpty()
            }
            val hits = parseHupuSearch(html, limit)
            if (hits.isEmpty()) {
                return buildJsonObject {
                    put("ok", false)
                    put("platform", "hupu")
                    put("keyword", kw)
                    put("reason", "没有搜到相关帖子，可换个关键词再试")
                }.toString()
            }
            buildJsonObject {
                put("ok", true)
                put("platform", "hupu")
                put("keyword", kw)
                put("count", hits.size)
                put("results", buildJsonArray {
                    hits.forEach { hit ->
                        add(buildJsonObject {
                            put("title", hit.title)
                            put("url", hit.url)
                            if (hit.description.isNotEmpty()) put("meta", hit.description)
                        })
                    }
                })
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", false)
                put("platform", "hupu")
                put("keyword", kw)
                put("reason", "虎扑搜索失败：" + (e.message ?: e.toString()))
            }.toString()
        }
    }

    // ---------------------------------------------------------------- 网易云

    private fun neteaseRoute(url: String): String? {
        val id = NETEASE_SONG_ID.find(url)?.groupValues?.get(1) ?: return null
        return neteaseSong(id)
    }

    private fun neteaseSong(id: String): String {
        return try {
            val detail = executeGet(
                "https://music.163.com/api/song/detail/?ids=[" + encodeURIComponent(id) + "]",
                15_000L,
                NETEASE_HEADERS,
            ).use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                res.body?.string().orEmpty()
            }
            val song = parseNeteaseSong(detail) ?: throw IllegalStateException("未取到歌曲信息")
            val comments = runCatching { neteaseCommentBody(id) }.getOrNull()
            val payload = buildJsonObject {
                put("ok", true)
                put("platform", "netease")
                put("songId", id)
                put("name", song.name)
                put("artists", song.artists)
                if (song.album.isNotEmpty()) put("album", song.album)
                put("url", "https://music.163.com/song?id=" + id)
                if (song.cover.isNotEmpty()) put("cover", song.cover)
                if (comments != null) {
                    put("commentTotal", comments.total)
                    put("hotComments", buildJsonArray {
                        comments.hot.forEach { comment ->
                            add(buildJsonObject {
                                if (comment.author.isNotEmpty()) put("author", comment.author)
                                put("content", trimText(comment.content, 300))
                                if (comment.liked > 0) put("liked", comment.liked)
                            })
                        }
                    })
                } else {
                    put("commentsUnavailable", true)
                }
                if (song.cover.isNotEmpty()) put("note", "封面图见 cover 字段，可在 reply 中用 ![封面](URL) 展示")
            }
            withPreviewImages(payload.toString(), listOf(song.cover), 1)
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", false)
                put("platform", "netease")
                put("reason", "网易云歌曲读取失败：" + (e.message ?: e.toString()))
            }.toString()
        }
    }

    private fun neteaseCommentBody(id: String): NeteaseComments {
        val body = executeGet(
            "https://music.163.com/api/v1/resource/comments/R_SO_4_" + encodeURIComponent(id) +
                "?limit=" + NETEASE_COMMENT_LIMIT + "&offset=0",
            15_000L,
            NETEASE_HEADERS,
        ).use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            res.body?.string().orEmpty()
        }
        return parseNeteaseComments(body, NETEASE_COMMENT_LIMIT)
    }

    private fun neteaseSearchSongs(keyword: String, limit: Int): String {
        val kw = keyword.trimJs()
        return try {
            val body = executeGet(
                "https://music.163.com/api/search/get?s=" + encodeURIComponent(kw) + "&type=1&limit=" + limit,
                15_000L,
                NETEASE_HEADERS,
            ).use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                res.body?.string().orEmpty()
            }
            val songs = parseNeteaseSearch(body, limit)
            if (songs.isEmpty()) {
                return buildJsonObject {
                    put("ok", false)
                    put("platform", "netease")
                    put("keyword", kw)
                    put("reason", "没有搜到相关歌曲，可换个关键词再试")
                }.toString()
            }
            buildJsonObject {
                put("ok", true)
                put("platform", "netease")
                put("keyword", kw)
                put("count", songs.size)
                put("results", buildJsonArray {
                    songs.forEach { song ->
                        add(buildJsonObject {
                            put("id", song.id)
                            put("name", song.name)
                            put("artists", song.artists)
                            if (song.album.isNotEmpty()) put("album", song.album)
                            put("url", "https://music.163.com/song?id=" + song.id)
                        })
                    }
                })
                put("note", "要听热评可用 web_fetch 打开该歌曲链接")
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", false)
                put("platform", "netease")
                put("keyword", kw)
                put("reason", "网易云搜索失败：" + (e.message ?: e.toString()))
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

fun defaultClient(dns: Dns = Dns.SYSTEM): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(20, TimeUnit.SECONDS)
    .dns(dns)
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

// ======================= 免账号的国内平台源 =======================
//
// 下面这些源都实测过「不需要账号」。接口形态随时可能变，所以每个源都只在
// 自己的分支里抛错、由调用方兜成 {"ok":false,...}，坏一个不影响其它源。

/** Parser entry point for the platform JSON payloads (also used by the parser tests). */
private val PARSER_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private const val MAX_SEARCH_RESULTS = 20
private const val MAX_HOT_TOPICS = 30
private const val MAX_SOCIAL_RESULTS = 20
private const val NETEASE_COMMENT_LIMIT = 20

private val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
private val CN_PAGE_HEADERS = mapOf("User-Agent" to BROWSER_UA)
private val NETEASE_HEADERS = mapOf("User-Agent" to BROWSER_UA, "Referer" to "https://music.163.com/")
private val HOT_PLATFORMS = setOf("baidu", "toutiao", "douyin", "zhihu")
private val SOCIAL_PLATFORMS = setOf("hupu", "netease", "bilibili")
private val HUPU_THREAD_URL = Regex("^https?://bbs\\.hupu\\.com/\\d+\\.html", RegexOption.IGNORE_CASE)
private val HUPU_TID = Regex("bbs\\.hupu\\.com/(\\d+)\\.html", RegexOption.IGNORE_CASE)
private val NETEASE_SONG_ID = Regex("music\\.163\\.com\\S*?[?&]id=(\\d+)", RegexOption.IGNORE_CASE)
private val HUPU_SEARCH_LINK = Regex(
    "<a[^>]*href=\"(https?://bbs\\.hupu\\.com/\\d+\\.html)\"[^>]*>(.*?)</a>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val NEXT_DATA = Regex(
    "<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val IMG_SRC = Regex(
    "<img[^>]*?(?:data-origin|src)=\"(https?://[^\"]+)\"",
    RegexOption.IGNORE_CASE,
)
private val SPAN_TEXT = Regex(
    "<span[^>]*>(.*?)</span>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val SPAN_NUM = Regex(
    "<span class=\"content-wrap-span1\">(.*?)</span>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val DATE_ONLY = Regex("\\d{4}-\\d{2}-\\d{2}")
private val BREADCRUMB_BOARD = Regex("^/\\d+$")
private val ZHIHU_API_QUESTION = Regex("^https?://api\\.zhihu\\.com/questions/(\\d+)", RegexOption.IGNORE_CASE)

private fun hotBoardUrl(platform: String): String = when (platform) {
    "baidu" -> "https://top.baidu.com/api/board?platform=wise&tab=realtime"
    "toutiao" -> "https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc"
    "douyin" -> "https://www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/"
    else -> "https://api.zhihu.com/topstory/hot-lists/total?limit=50"
}

private fun hotBoardHeaders(platform: String): Map<String, String> = when (platform) {
    "zhihu" -> mapOf("User-Agent" to BROWSER_UA, "Referer" to "https://www.zhihu.com/hot")
    "toutiao" -> mapOf("User-Agent" to BROWSER_UA, "Referer" to "https://www.toutiao.com/")
    else -> CN_PAGE_HEADERS
}

/** 一条网页/帖子搜索结果。 */
internal data class WebHit(val title: String, val url: String, val description: String = "")

/** 一条热榜话题；[hot] 是热度的人话写法（拿不到就空）。 */
internal data class BoardTopic(val title: String, val url: String = "", val hot: String = "")

/** 虎扑帖子解析结果。 */
internal data class HupuThread(
    val title: String,
    val author: String,
    val board: String,
    val content: String,
    val stats: String,
    val images: List<String>,
    val replies: List<HupuReply>,
)

internal data class HupuReply(val author: String, val content: String)

internal data class NeteaseSong(
    val id: String,
    val name: String,
    val artists: String,
    val album: String,
    val cover: String,
)

internal data class NeteaseComment(val author: String, val content: String, val liked: Long)

internal data class NeteaseComments(val total: Long, val hot: List<NeteaseComment>)

internal fun parseBingRss(xml: String, limit: Int): List<WebHit> =
    ITEM_RE.findAll(xml).mapNotNull { match ->
        val item = match.groupValues[1]
        val title = trimText(extractTag(item, "title"), 160)
        val url = extractTag(item, "link")
        if (title.isEmpty() || !HTTP_URL.containsMatchIn(url)) return@mapNotNull null
        WebHit(title, url, trimText(extractTag(item, "description"), 400))
    }.take(limit.coerceAtLeast(1)).toList()

/** 百度热搜：`data.cards[].content[].content[]`，每条含 `word` / `url`。 */
internal fun parseBaiduBoard(body: String, limit: Int): List<BoardTopic> {
    val root = body.toJsonObject() ?: return emptyList()
    val cards = ((root["data"] as? JsonObject)?.get("cards") as? JsonArray).orEmpty()
    val out = mutableListOf<BoardTopic>()
    cards.forEach cardLoop@{ card ->
        val groups = ((card as? JsonObject)?.get("content") as? JsonArray).orEmpty()
        groups.forEach groupLoop@{ group ->
            val items = ((group as? JsonObject)?.get("content") as? JsonArray).orEmpty()
            items.forEach itemLoop@{ element ->
                val item = element as? JsonObject ?: return@itemLoop
                val title = item["word"].toText().trimJs()
                if (title.isEmpty()) return@itemLoop
                val hot = item["hotScore"].toText().trimJs().ifEmpty { item["desc"].toText().trimJs() }
                out += BoardTopic(title, item["url"].toText().trimJs(), hot)
            }
        }
    }
    return out.take(limit.coerceAtLeast(1))
}

/** 头条热榜：`data[].Title` / `Url` / `HotValue`。 */
internal fun parseToutiaoBoard(body: String, limit: Int): List<BoardTopic> {
    val root = body.toJsonObject() ?: return emptyList()
    return (root["data"] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val title = item["Title"].toText().trimJs()
        if (title.isEmpty()) return@mapNotNull null
        val url = item["Url"].toText().trimJs()
        BoardTopic(
            title = title,
            url = if (HTTP_URL.containsMatchIn(url)) url else "",
            hot = formatHot(item["HotValue"].toNumberOrZero()),
        )
    }.take(limit.coerceAtLeast(1))
}

/** 抖音热榜：`word_list[].word` / `hot_value`（老接口在根上，新的在 `data` 里）。 */
internal fun parseDouyinBoard(body: String, limit: Int): List<BoardTopic> {
    val root = body.toJsonObject() ?: return emptyList()
    val list = (root["word_list"] as? JsonArray)
        ?: ((root["data"] as? JsonObject)?.get("word_list") as? JsonArray)
        ?: JsonArray(emptyList())
    return list.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val title = item["word"].toText().trimJs()
        if (title.isEmpty()) return@mapNotNull null
        BoardTopic(title, "", formatHot(item["hot_value"].toNumberOrZero()))
    }.take(limit.coerceAtLeast(1))
}

/** 知乎热榜：`data[].target.title` / `target.url` / `detail_text`（如「852 万热度」）。 */
internal fun parseZhihuHot(body: String, limit: Int): List<BoardTopic> {
    val root = body.toJsonObject() ?: return emptyList()
    return (root["data"] as? JsonArray).orEmpty().mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val target = entry["target"] as? JsonObject ?: return@mapNotNull null
        val title = target["title"].toText().trimJs()
        if (title.isEmpty()) return@mapNotNull null
        BoardTopic(title, normalizeZhihuUrl(target["url"].toText()), entry["detail_text"].toText().trimJs())
    }.take(limit.coerceAtLeast(1))
}

/** 热榜给的是 API 链接（`api.zhihu.com/questions/<id>`），换成能打开的网页链接。 */
internal fun normalizeZhihuUrl(url: String): String {
    val text = url.trimJs()
    val match = ZHIHU_API_QUESTION.find(text) ?: return text
    return "https://www.zhihu.com/question/" + match.groupValues[1]
}

/** 虎扑搜索页是服务端直出的 HTML：每个 `content-outline` 块含一条帖子。 */
internal fun parseHupuSearch(html: String, limit: Int): List<WebHit> {
    val wanted = limit.coerceAtLeast(1)
    val out = mutableListOf<WebHit>()
    val chunks = html.split("content-outline").drop(1)
    for (chunk in chunks) {
        val link = HUPU_SEARCH_LINK.find(chunk) ?: continue
        val title = stripHtmlTags(link.groupValues[2]).trimJs()
        if (title.isEmpty()) continue
        val meta = hupuSearchMeta(chunk.substring(link.range.last + 1))
        out += WebHit(title, link.groupValues[1], meta)
        if (out.size >= wanted) break
    }
    return out
}

/**
 * 结果行尾的元信息：日期 + 回复 / 推荐 / 亮评（列名固定，数量对不上就少写几个）。
 * 只取 `<span>` 里的文本，避免把下一个结果块的残标签带进来。
 */
private fun hupuSearchMeta(tail: String): String {
    val spans = SPAN_TEXT.findAll(tail).map { stripHtmlTags(it.groupValues[1]).trimJs() }.toList()
    val date = spans.firstOrNull { DATE_ONLY.containsMatchIn(it) }.orEmpty()
    val numbers = SPAN_NUM.findAll(tail).map { it.groupValues[1].trimJs() }.filter { it.isNotEmpty() }.toList()
    val labels = listOf("回复", "推荐", "亮评")
    return buildList {
        if (date.isNotEmpty()) add(date)
        numbers.forEachIndexed { index, value -> add((labels.getOrNull(index) ?: "数") + " " + value) }
    }.joinToString(" · ")
}

/** 虎扑帖子页把整帖塞在 `<script id="__NEXT_DATA__">` 里。 */
internal fun parseHupuThread(html: String): HupuThread? {
    val raw = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
    val root = raw.toJsonObject() ?: return null
    val pageProps = (root["props"] as? JsonObject)?.get("pageProps") as? JsonObject ?: return null
    val detail = pageProps["detail"] as? JsonObject ?: return null
    val thread = detail["thread"] as? JsonObject ?: return null

    val title = thread["title"].toText().trimJs()
    val contentHtml = thread["content"].toText()
    val content = stripHtmlTags(contentHtml)
    if (title.isEmpty() && content.isEmpty()) return null

    val author = (thread["author"] as? JsonObject)?.let { node ->
        node["puname"].toText().trimJs().ifEmpty { node["nickname"].toText().trimJs() }
    }.orEmpty()
    val crumbs = (detail["breadCrumb"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val board = (crumbs.lastOrNull { BREADCRUMB_BOARD.containsMatchIn(it["url"].toText().trimJs()) }
        ?: crumbs.dropLast(1).lastOrNull())
        ?.get("title").toText().trimJs().orEmpty()
    val stats = listOfNotNull(
        thread["read"].toNumberOrZero().takeIf { it > 0 }?.let { "阅读 $it" },
        thread["replies"].toNumberOrZero().takeIf { it > 0 }?.let { "回复 $it" },
        thread["lights"].toNumberOrZero().takeIf { it > 0 }?.let { "亮评 $it" },
    ).joinToString(" · ")
    val images = IMG_SRC.findAll(contentHtml)
        .map { it.groupValues[1] }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(3)
        .toList()
    val replies = ((detail["replies"] as? JsonObject)?.get("list") as? JsonArray).orEmpty().mapNotNull { element ->
        val reply = element as? JsonObject ?: return@mapNotNull null
        val text = stripHtmlTags(reply["content"].toText())
        if (text.isEmpty()) return@mapNotNull null
        val replyAuthor = (reply["author"] as? JsonObject)?.let { node ->
            node["puname"].toText().trimJs().ifEmpty { node["nickname"].toText().trimJs() }
        }.orEmpty()
        HupuReply(replyAuthor, text)
    }.take(10).toList()

    return HupuThread(title, author, board, content, stats, images, replies)
}

/** 网易云搜索：`result.songs[]`。 */
internal fun parseNeteaseSearch(body: String, limit: Int): List<NeteaseSong> {
    val root = body.toJsonObject() ?: return emptyList()
    val songs = ((root["result"] as? JsonObject)?.get("songs") as? JsonArray).orEmpty()
    return songs.mapNotNull { element ->
        val song = element as? JsonObject ?: return@mapNotNull null
        val id = song["id"].toText().trimJs()
        val name = song["name"].toText().trimJs()
        if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
        NeteaseSong(
            id = id,
            name = name,
            artists = song["artists"].joinNames(),
            album = (song["album"] as? JsonObject)?.get("name").toText().trimJs().orEmpty(),
            cover = "",
        )
    }.take(limit.coerceAtLeast(1))
}

/** 网易云歌曲详情：`songs[0]`。 */
internal fun parseNeteaseSong(body: String): NeteaseSong? {
    val root = body.toJsonObject() ?: return null
    val song = (root["songs"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    val id = song["id"].toText().trimJs()
    val name = song["name"].toText().trimJs()
    if (id.isEmpty() || name.isEmpty()) return null
    val album = song["album"] as? JsonObject
    return NeteaseSong(
        id = id,
        name = name,
        artists = song["artists"].joinNames(),
        album = album?.get("name").toText().trimJs().orEmpty(),
        cover = album?.get("picUrl").toText().trimJs().orEmpty(),
    )
}

/** 网易云评论：`total` + `hotComments`（没有就用最新评论）。 */
internal fun parseNeteaseComments(body: String, limit: Int): NeteaseComments {
    val root = body.toJsonObject() ?: return NeteaseComments(0, emptyList())
    val source = (root["hotComments"] as? JsonArray).orEmpty() + (root["comments"] as? JsonArray).orEmpty()
    val mapped = source.mapNotNull { element ->
        val comment = element as? JsonObject ?: return@mapNotNull null
        val content = comment["content"].toText().trimJs()
        if (content.isEmpty()) return@mapNotNull null
        NeteaseComment(
            author = (comment["user"] as? JsonObject)?.get("nickname").toText().trimJs().orEmpty(),
            content = content,
            liked = comment["likedCount"].toNumberOrZero(),
        )
    }.take(limit.coerceAtLeast(1)).toList()
    return NeteaseComments(root["total"].toNumberOrZero(), mapped)
}

/** 热度数字的人话写法（头条/抖音给的是裸数字）。 */
internal fun formatHot(value: Long): String = when {
    value <= 0 -> ""
    value >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format(Locale.ROOT, "%.1f万", value / 10_000.0)
    else -> value.toString()
}

private fun JsonElement?.joinNames(): String =
    (this as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.get("name").toText().trimJs().ifEmpty { null } }
        .joinToString("、")

private fun String.toJsonObject(): JsonObject? =
    runCatching { PARSER_JSON.parseToJsonElement(this) as? JsonObject }.getOrNull()

/** 文本 + 预览图（与 B 站封面同一形态：模型能直接看到图）。 */
private fun withPreviewImages(text: String, images: List<String>, max: Int): String {
    val wanted = images.filter { it.isNotEmpty() }.distinct().take(max)
    if (wanted.isEmpty()) return text
    return buildJsonArray {
        add(buildJsonObject {
            put("type", "input_text")
            put("text", text)
        })
        wanted.forEach { url ->
            add(buildJsonObject {
                put("type", "input_image")
                put("image_url", url)
                put("detail", "low")
            })
        }
    }.toString()
}
