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
 * B 站的「截图墙」（`x/player/videoshot`）：整段视频被切成 [gridWidth]×[gridHeight] 帧一张的
 * 拼图，每 [frameWidth]×[frameHeight] 像素一帧，[timestamps] 是每一帧对应的秒数。
 *
 * **这个接口不需要登录**，是本 App 唯一的"抽帧"来源：下载拼图后在本地裁出几帧，就能让视觉
 * 模型看一眼画面（比下载视频流再解码便宜两个数量级）。
 */
data class BiliStoryboard(
    val gridWidth: Int,
    val gridHeight: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val images: List<String>,
    val timestamps: List<Int>,
) {
    val framesPerImage: Int get() = (gridWidth * gridHeight).coerceAtLeast(1)

    val frameCount: Int get() = minOf(timestamps.size, images.size * framesPerImage)

    /** 第 [frame] 帧在拼图里的位置；越界返回 null。 */
    fun locationOf(frame: Int): FrameLocation? {
        if (frame < 0 || frame >= frameCount) return null
        val imageIndex = frame / framesPerImage
        val cell = frame % framesPerImage
        if (imageIndex >= images.size) return null
        return FrameLocation(
            imageIndex = imageIndex,
            column = cell % gridWidth,
            row = cell / gridWidth,
        )
    }
}

data class FrameLocation(val imageIndex: Int, val column: Int, val row: Int)

/** 一个 B 站视频里所有能喂给模型的文本。 */
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
    /** 逐句字幕；[Source.Metadata] 时为空。 */
    val lines: List<BiliSubtitleLine> = emptyList(),
    /** B 站官方 AI 总结正文（[Source.OfficialSummary] 时有值）。 */
    val officialSummary: String = "",
    val officialOutline: List<BiliSubtitleLine> = emptyList(),
    /** `view_points` 的章节标签（不需要登录就能拿到），无字幕时它是唯一的"结构"信息。 */
    val chapters: List<BiliSubtitleLine> = emptyList(),
) {
    enum class Source { Subtitle, OfficialSummary, Metadata }

    /** 只有逐句字幕才算"看得见文字内容"；官方总结是二手摘要，Metadata 什么都没有。 */
    val hasSubtitle: Boolean get() = source == Source.Subtitle && lines.isNotEmpty()

    val transcriptChars: Int get() = lines.sumOf { it.text.length }
}

sealed interface BiliVideoTextResult {
    data class Ok(val text: BiliVideoText) : BiliVideoTextResult

    /**
     * 只有**连视频元信息都取不到**时才走到这里：没字幕不是失败，见 [BiliVideoText.Source.Metadata]。
     * [needsLogin] 为 true 表示"配上 Cookie 可能拿到字幕"。
     */
    data class Failure(val reason: String, val needsLogin: Boolean = false) : BiliVideoTextResult
}

/** 取一个 B 站视频的可总结材料（字幕 / 官方总结 / 只有元信息 + 章节）。 */
interface BiliVideoSource {
    suspend fun load(bvid: String): BiliVideoTextResult

    /** 截图墙（抽帧用）；接口不可用或没有截图时返回 null。默认不支持，便于测试替身。 */
    suspend fun storyboard(bvid: String, cid: Long): BiliStoryboard? = null
}

/**
 * [BiliVideoSource] 的 HTTP 实现。
 *
 * B 站的 AI 字幕与官方总结都**要求登录态**（匿名请求 `x/player/wbi/v2` 的 `subtitles` 恒为空、
 * `view/conclusion/get` 直接 -403），所以 [cookie] 返回用户自己填的 `SESSDATA=...`。
 * 但 **`x/player/videoshot`（截图墙）和 `view`（元信息）、`view_points`（章节）都不需要登录**，
 * 所以"没有 Cookie"只会让总结从"逐句"降级为"看画面 + 简介/章节"，而不是直接失败。
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

            // 1) 字幕优先：逐句文本能回答"某个细节在第几分钟"，官方总结做不到。
            val player = playerPayload(meta.bvid, cid)
            val chapters = parseBiliViewPoints(player.orEmpty())
            val track = pickBiliSubtitleTrack(parseBiliSubtitleTracks(player.orEmpty()))
            if (track != null) {
                val lines = fetchSubtitleLines(track.url)
                if (lines.isNotEmpty()) {
                    return@withContext BiliVideoTextResult.Ok(
                        meta.toText(BiliVideoText.Source.Subtitle, track.lan, lines, chapters = chapters),
                    )
                }
            }

            // 2) 官方 AI 总结：零 LLM 成本，带 outline 时间戳。
            val conclusion = officialConclusion(meta.bvid, cid, meta.upMid)
            if (conclusion != null) {
                return@withContext BiliVideoTextResult.Ok(
                    meta.toText(
                        source = BiliVideoText.Source.OfficialSummary,
                        lang = "zh",
                        lines = conclusion.outline,
                        summary = conclusion.summary,
                        chapters = chapters,
                    ),
                )
            }

            // 3) 什么都没有（大概率是没登录）：仍然带着元信息 + 章节回去，让上层"看画面说个大概"，
            //    而不是把整次调用判成失败。
            BiliVideoTextResult.Ok(
                meta.toText(BiliVideoText.Source.Metadata, lang = "", chapters = chapters),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BiliVideoTextResult.Failure("B站视频文本获取失败：" + (e.message ?: e.toString()))
        }
    }

    override suspend fun storyboard(bvid: String, cid: Long): BiliStoryboard? = withContext(Dispatchers.IO) {
        if (cid <= 0L) return@withContext null
        val root = getJsonObject(
            url = "https://api.bilibili.com/x/player/videoshot?bvid=$bvid&cid=$cid&index=1",
            referer = "https://www.bilibili.com/video/$bvid",
        ) ?: return@withContext null
        if (root["code"].intOrZero() != 0) return@withContext null
        parseBiliStoryboard(root.toString())
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
        lines: List<BiliSubtitleLine> = emptyList(),
        summary: String = "",
        chapters: List<BiliSubtitleLine> = emptyList(),
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
        chapters = chapters,
    )

    /**
     * `x/player/wbi/v2` 的原始响应；没有 cid 或签名 key 取不到时返回 null。
     * 字幕轨、`need_login_subtitle`、`view_points` 章节都在这里面。
     */
    private fun playerPayload(bvid: String, cid: Long): String? {
        if (cid <= 0L) return null
        val keys = wbiKeys() ?: return null
        val query = BiliWbi.signedQuery(
            params = mapOf("bvid" to bvid, "cid" to cid.toString()),
            imgKey = keys.imgKey,
            subKey = keys.subKey,
            wts = nowSeconds(),
        )
        val root = getJsonObject(
            url = "https://api.bilibili.com/x/player/wbi/v2?$query",
            referer = "https://www.bilibili.com/video/$bvid",
        ) ?: return null
        if (root["code"].intOrZero() != 0) return null
        return root.toString()
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
        return runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
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
 * 解析 `x/player/wbi/v2` 的 `view_points` 章节标签（不需要登录）。
 * 只有少数视频有，内容通常是 4~18 字的短标签，但足以说明"视频分几段、每段讲什么方向"。
 */
internal fun parseBiliViewPoints(rootJson: String): List<BiliSubtitleLine> {
    val root = runCatching { JSON_FOR_PARSING.parseToJsonElement(rootJson) as? JsonObject }.getOrNull() ?: return emptyList()
    val items = (root["data"] as? JsonObject)?.get("view_points") as? JsonArray ?: return emptyList()
    return items.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val content = obj["content"].textOrEmpty().trim()
        if (content.isEmpty()) return@mapNotNull null
        BiliSubtitleLine(fromSec = obj["from"].intOrZero(), text = content)
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

/** 解析 `x/player/videoshot` 的截图墙。 */
internal fun parseBiliStoryboard(rootJson: String): BiliStoryboard? {
    val root = runCatching { JSON_FOR_PARSING.parseToJsonElement(rootJson) as? JsonObject }.getOrNull() ?: return null
    val data = root["data"] as? JsonObject ?: return null
    val gridWidth = data["img_x_len"].intOrZero()
    val gridHeight = data["img_y_len"].intOrZero()
    val frameWidth = data["img_x_size"].intOrZero()
    val frameHeight = data["img_y_size"].intOrZero()
    val images = (data["image"] as? JsonArray)?.mapNotNull { element ->
        val url = element.textOrEmpty().trim()
        if (url.isEmpty()) null else if (url.startsWith("//")) "https:$url" else url
    }.orEmpty()
    val timestamps = (data["index"] as? JsonArray)?.map { it.intOrZero() }.orEmpty()
    if (gridWidth <= 0 || gridHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
    if (images.isEmpty() || timestamps.isEmpty()) return null
    return BiliStoryboard(
        gridWidth = gridWidth,
        gridHeight = gridHeight,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        images = images,
        timestamps = timestamps,
    )
}

/**
 * 从截图墙里均匀挑 [count] 帧（按帧号升序、去重）。挑帧而不是整墙喂给模型：一帧 480×270 的
 * JPEG 已经够看清"画面里在干嘛"，4~6 帧就能覆盖开场/中段/结尾的视觉变化。
 */
fun pickStoryboardFrames(storyboard: BiliStoryboard, count: Int): List<Int> {
    val total = storyboard.frameCount
    val wanted = count.coerceAtLeast(1)
    if (total <= 0) return emptyList()
    if (total <= wanted) return (0 until total).toList()
    if (wanted == 1) return listOf(total / 2)
    val picked = LinkedHashSet<Int>()
    for (step in 0 until wanted) {
        picked += (step.toLong() * (total - 1) / (wanted - 1)).toInt()
    }
    return picked.sorted()
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
    return primitive.content
}

private fun JsonElement?.intOrZero(): Int =
    (this as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() } ?: 0

private fun JsonElement?.longOrZero(): Long =
    (this as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() } ?: 0L
