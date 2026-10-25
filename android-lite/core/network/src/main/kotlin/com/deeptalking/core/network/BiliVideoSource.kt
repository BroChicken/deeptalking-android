package com.deeptalking.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** 一条带时间轴的字幕行（`from` 秒 + 文本）。 */
data class BiliSubtitleLine(val fromSec: Int, val text: String)

/** 一条可下载的字幕轨（`lan` 形如 `ai-zh` / `zh-CN`，`url` 通常是 `//` 开头的 CDN 地址）。 */
data class BiliSubtitleTrack(val lan: String, val url: String)

/** 官方 AI 总结：一段摘要 + 带时间戳的大纲。 */
data class BiliConclusion(val summary: String, val outline: List<BiliSubtitleLine>)

/**
 * 一个 B 站视频里所有能喂给模型的文本。要么拿到**字幕**（逐句，带时间轴），要么只拿到
 * **官方 AI 总结**（摘要 + 大纲）——两者都取不到时由 [BiliVideoTextResult.Failure] 说明原因。
 */
data class BiliVideoText(
    val bvid: String,
    val title: String,
    val author: String,
    val durationSec: Int,
    val cid: Long,
    val upMid: Long,
    val desc: String,
    val source: Source,
    val lang: String,
    val lines: List<BiliSubtitleLine>,
    val officialSummary: String = "",
    val officialOutline: List<BiliSubtitleLine> = emptyList(),
) {
    enum class Source { Subtitle, OfficialSummary }

    /** 逐句字幕的总字数（官方总结路径下为 0）。 */
    val transcriptChars: Int get() = lines.sumOf { it.text.length }
}

sealed interface BiliVideoTextResult {
    data class Ok(val text: BiliVideoText) : BiliVideoTextResult

    /**
     * [needsLogin] 为 true 表示"配上 Cookie 大概率就能取到"——工具据此让模型说清楚该做什么，
     * 而不是含糊地回一句"没有字幕"。
     */
    data class Failure(val reason: String, val needsLogin: Boolean = false) : BiliVideoTextResult
}

/** 取一个 B 站视频的可总结文本（字幕优先，官方总结兜底）。 */
interface BiliVideoSource {
    suspend fun load(bvid: String): BiliVideoTextResult
}

/**
 * [BiliVideoSource] 的 HTTP 实现。
 *
 * B 站的 AI 字幕与官方总结都**要求登录态**（匿名请求 `x/player/wbi/v2` 的 `subtitles` 恒为空，
 * `view/conclusion/get` 直接 -403），所以 [cookie] 返回用户自己填的 `SESSDATA=...`；
 * 未配置时本类仍会尝试，失败结果里 [BiliVideoTextResult.Failure.needsLogin] 为 true。
 *
 * 风控与时效注意：`wbi_img` 每天轮换（本类按天缓存），字幕 CDN 地址的 `auth_key` 只有
 * 分钟级有效期（必须现取现用、不可落库），高频请求会触发 -412/-799。
 */
class HttpBiliVideoSource(
    private val client: OkHttpClient = defaultClient(),
    private val cookie: () -> String? = { null },
) : BiliVideoSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var cachedKeys: WbiKeys? = null

    private data class WbiKeys(val imgKey: String, val subKey: String, val day: String)

    override suspend fun load(bvid: String): BiliVideoTextResult = withContext(Dispatchers.IO) {
        val id = bvid.trim()
        if (id.isEmpty()) return@withContext BiliVideoTextResult.Failure("缺少视频号（BV 号）")
        try {
            val view = getJsonObject("https://api.bilibili.com/x/web-interface/view?bvid=$id")
                ?: return@withContext BiliVideoTextResult.Failure("B站视频详情读取失败（网络不可达或接口已变更）")
            if (view["code"].intOrZero() != 0) {
                return@withContext BiliVideoTextResult.Failure(
                    "B站视频详情返回错误：" + view["message"].textOrEmpty().ifEmpty { "未知原因" },
                )
            }
            val data = view["data"] as? JsonObject
                ?: return@withContext BiliVideoTextResult.Failure("B站视频详情为空")
            val cid = data["cid"].longOrZero().takeIf { it > 0 }
                ?: ((data["pages"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("cid").longOrZero()
                ?: 0L
            val owner = data["owner"] as? JsonObject
            val meta = Meta(
                bvid = data["bvid"].textOrEmpty().ifEmpty { id },
                title = data["title"].textOrEmpty(),
                author = owner?.get("name").textOrEmpty(),
                durationSec = data["duration"].intOrZero(),
                cid = cid,
                upMid = owner?.get("mid").longOrZero() ?: 0L,
                desc = data["desc"].textOrEmpty(),
            )
            val loggedIn = !cookie().isNullOrBlank()

            // 1) 字幕优先：逐句文本能回答"某个细节在第几分钟"，官方总结做不到。
            val tracks = playerTracks(meta.bvid, cid)
            val track = pickBiliSubtitleTrack(tracks)
            if (track != null) {
                val lines = fetchSubtitleLines(track.url)
                if (lines.isNotEmpty()) {
                    return@withContext BiliVideoTextResult.Ok(
                        meta.toText(BiliVideoText.Source.Subtitle, track.lan, lines),
                    )
                }
            }

            // 2) 官方 AI 总结兜底：零 LLM 成本，带 outline 时间戳。
            val conclusion = officialConclusion(meta.bvid, cid, meta.upMid)
            if (conclusion != null && (conclusion.summary.isNotEmpty() || conclusion.outline.isNotEmpty())) {
                return@withContext BiliVideoTextResult.Ok(
                    meta.toText(
                        source = BiliVideoText.Source.OfficialSummary,
                        lang = "zh",
                        lines = conclusion.outline,
                        summary = conclusion.summary,
                    ),
                )
            }

            BiliVideoTextResult.Failure(
                reason = if (loggedIn) {
                    "这个视频既没有字幕也没有官方总结，取不到可以总结的文本"
                } else {
                    "B站的字幕与官方总结都需要登录态才能取到；请在设置里填一次 B 站 Cookie（SESSDATA）"
                },
                needsLogin = !loggedIn,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BiliVideoTextResult.Failure("B站视频文本获取失败：" + (e.message ?: e.toString()))
        }
    }

    private data class Meta(
        val bvid: String,
        val title: String,
        val author: String,
        val durationSec: Int,
        val cid: Long,
        val upMid: Long,
        val desc: String,
    )

    private fun Meta.toText(
        source: BiliVideoText.Source,
        lang: String,
        lines: List<BiliSubtitleLine>,
        summary: String = "",
    ) = BiliVideoText(
        bvid = bvid,
        title = title,
        author = author,
        durationSec = durationSec,
        cid = cid,
        upMid = upMid,
        desc = desc,
        source = source,
        lang = lang,
        lines = lines,
        officialSummary = summary,
        officialOutline = if (source == BiliVideoText.Source.OfficialSummary) lines else emptyList(),
    )

    /** `x/player/wbi/v2` 的字幕轨列表；无登录或无字幕时为空。 */
    private fun playerTracks(bvid: String, cid: Long): List<BiliSubtitleTrack> {
        if (cid <= 0L) return emptyList()
        val keys = wbiKeys() ?: return emptyList()
        val query = BiliWbi.signedQuery(
            params = mapOf("bvid" to bvid, "cid" to cid.toString()),
            imgKey = keys.imgKey,
            subKey = keys.subKey,
            wts = nowSeconds(),
        )
        val root = getJsonObject(
            url = "https://api.bilibili.com/x/player/wbi/v2?$query",
            referer = "https://www.bilibili.com/video/$bvid",
        ) ?: return emptyList()
        if (root["code"].intOrZero() != 0) return emptyList()
        return parseBiliSubtitleTracks(root.toString())
    }

    /** 官方 AI 总结（`x/web-interface/view/conclusion/get`，需登录 + WBI）。 */
    private fun officialConclusion(bvid: String, cid: Long, upMid: Long): BiliConclusion? {
        if (cid <= 0L) return null
        val keys = wbiKeys() ?: return null
        val query = BiliWbi.signedQuery(
            params = mapOf("bvid" to bvid, "cid" to cid.toString(), "up_mid" to upMid.toString()),
            imgKey = keys.imgKey,
            subKey = keys.subKey,
            wts = nowSeconds(),
        )
        val root = getJsonObject(
            url = "https://api.bilibili.com/x/web-interface/view/conclusion/get?$query",
            referer = "https://www.bilibili.com/video/$bvid",
        ) ?: return null
        if (root["code"].intOrZero() != 0) return null
        return parseBiliConclusion(root.toString())
    }

    private fun fetchSubtitleLines(url: String): List<BiliSubtitleLine> {
        val absolute = if (url.startsWith("//")) "https:" + url else url
        val body = getRaw(absolute, referer = "https://www.bilibili.com/") ?: return emptyList()
        return parseBiliSubtitleBody(body)
    }

    private fun wbiKeys(): WbiKeys? {
        val today = runCatching { LocalDate.now().toString() }.getOrDefault("")
        cachedKeys?.takeIf { it.day == today }?.let { return it }
        val root = getJsonObject("https://api.bilibili.com/x/web-interface/nav") ?: return null
        val image = (root["data"] as? JsonObject)?.get("wbi_img") as? JsonObject ?: return null
        val imgKey = wbiImageName(image["img_url"].textOrEmpty())
        val subKey = wbiImageName(image["sub_url"].textOrEmpty())
        if (imgKey.isEmpty() || subKey.isEmpty()) return null
        return WbiKeys(imgKey, subKey, today).also { cachedKeys = it }
    }

    private fun getJsonObject(
        url: String,
        referer: String = "https://www.bilibili.com/",
    ): JsonObject? {
        val body = getRaw(url, referer) ?: return null
        return json.parseToJsonElement(body) as? JsonObject
    }

    private fun getRaw(url: String, referer: String): String? {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", BILI_UA)
            .header("Referer", referer)
        cookie()?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
        val callClient = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        return try {
            callClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private companion object {
        const val BILI_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

/** 从 `https://i0.hdslb.com/bfs/wbi/7cd0….png` 取文件名的十六进制部分。 */
internal fun wbiImageName(url: String): String {
    val name = url.substringAfterLast('/').substringBeforeLast('.')
    return name.filter { it.isLetterOrDigit() }
}

/** 解析字幕 CDN 的 JSON：`{"body":[{"from":1.23,"content":"…"}]}`。 */
internal fun parseBiliSubtitleBody(body: String): List<BiliSubtitleLine> {
    val root = runCatching { JSON_FOR_PARSING.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
    val items = root["body"] as? JsonArray ?: return emptyList()
    return items.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val text = obj["content"].textOrEmpty().trim()
        if (text.isEmpty()) return@mapNotNull null
        BiliSubtitleLine(fromSec = obj["from"].intOrZero(), text = text)
    }
}

/** 解析 `x/player/wbi/v2` 的 `data.subtitle.subtitles`。 */
internal fun parseBiliSubtitleTracks(rootJson: String): List<BiliSubtitleTrack> {
    val root = runCatching { JSON_FOR_PARSING.parseToJsonElement(rootJson) as? JsonObject }.getOrNull() ?: return emptyList()
    val subtitle = (root["data"] as? JsonObject)?.get("subtitle") as? JsonObject ?: return emptyList()
    val items = subtitle["subtitles"] as? JsonArray ?: return emptyList()
    return items.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val url = obj["subtitle_url"].textOrEmpty().trim()
        if (url.isEmpty()) return@mapNotNull null
        BiliSubtitleTrack(
            lan = obj["lan"].textOrEmpty().ifEmpty { "unknown" },
            url = if (url.startsWith("//")) "https:$url" else url,
        )
    }
}

/**
 * AI 中文字幕优先（`ai-zh`），其次任意中文轨，最后退回第一条。
 * AI 字幕通常比 UP 上传的 CC 更完整，且语言与本应用对得上。
 */
internal fun pickBiliSubtitleTrack(tracks: List<BiliSubtitleTrack>): BiliSubtitleTrack? {
    if (tracks.isEmpty()) return null
    tracks.firstOrNull { it.lan.equals("ai-zh", ignoreCase = true) }?.let { return it }
    tracks.firstOrNull { it.lan.startsWith("ai", ignoreCase = true) }?.let { return it }
    tracks.firstOrNull { it.lan.startsWith("zh", ignoreCase = true) }?.let { return it }
    return tracks.first()
}

/**
 * 解析官方 AI 总结：`data.result.summary` + `data.result.outline[].part_outline[]`。
 * `data.code = 1` 表示这个视频还没有总结，返回 null。
 */
internal fun parseBiliConclusion(rootJson: String): BiliConclusion? {
    val root = runCatching { JSON_FOR_PARSING.parseToJsonElement(rootJson) as? JsonObject }.getOrNull() ?: return null
    val data = root["data"] as? JsonObject ?: return null
    if (data.containsKey("code") && data["code"].intOrZero() != 0) return null
    val result = data["result"] as? JsonObject ?: return null
    val summary = result["summary"].textOrEmpty().trim()
    val outline = buildList {
        (result["outline"] as? JsonArray)?.forEach { section ->
            val parts = (section as? JsonObject)?.get("part_outline") as? JsonArray ?: return@forEach
            parts.forEach { part ->
                val obj = part as? JsonObject ?: return@forEach
                val content = obj["content"].textOrEmpty().trim()
                if (content.isNotEmpty()) {
                    add(BiliSubtitleLine(fromSec = obj["timestamp"].intOrZero(), text = content))
                }
            }
        }
    }
    if (summary.isEmpty() && outline.isEmpty()) return null
    return BiliConclusion(summary = summary, outline = outline)
}

/** 秒 → `mm:ss`（超过一小时为 `h:mm:ss`），给模型看的时间轴统一用它。 */
fun formatBiliClock(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/** 从用户给的链接/裸号里取 BV 号（`BV1xx411c7mD`）。 */
object BiliLinks {
    private val BV = Regex("BV[0-9A-Za-z]{10}")

    fun bvidOf(input: String): String? = BV.find(input.trim())?.value
}

/**
 * 解析搜索接口/detail 里的时长文案：`"0:16"` / `"12:34"` / `"1:02:03"` → 秒。
 * 解析不出来返回 null（搜索接口偶尔给空串），调用方就别写这个字段。
 */
fun parseBiliDurationClock(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
    if (numbers.any { it < 0 }) return null
    return when (numbers.size) {
        1 -> numbers[0]
        2 -> numbers[0] * 60 + numbers[1]
        else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
    }
}

private val JSON_FOR_PARSING = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun JsonElement?.textOrEmpty(): String {
    val primitive = this as? JsonPrimitive ?: return ""
    return if (primitive.isString) primitive.content else primitive.content
}

private fun JsonElement?.intOrZero(): Int =
    (this as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() } ?: 0

private fun JsonElement?.longOrZero(): Long =
    (this as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() } ?: 0L
