package com.deeptalking.lite

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.data.CharacterRepository
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.Sticker
import com.deeptalking.core.model.StickerCategory
import com.deeptalking.core.network.StickerImageSearch
import com.deeptalking.core.network.StickerSearchPage
import com.deeptalking.domain.agent.sticker.FetchedSticker
import com.deeptalking.domain.agent.sticker.StickerFetchResult
import com.deeptalking.domain.agent.sticker.StickerFetcher
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 视觉模型对一张候选图的结论：**只给关键词和大类**。
 *
 * 这里刻意没有“通过/不通过”——审核不是模型的活（检索源 + 域名黑名单在前面把关），
 * 模型只负责起名和归类，失败也不会让这张图作废。
 */
data class StickerLabel(
    val keyword: String,
    val category: String = StickerCategory.OTHER,
)

/**
 * 主题检索 → 入库：百度找图 → 下载 → 压成 384px/120KB → 视觉起名+归类 → 写进表情库。
 *
 * 三条工程约束都是补出来的（原来的实现会让同一主题"加不进去"）：
 * - **翻页**：百度 `pn=0` 每次返回同一批图，全部重复时自动翻到下一页再试一轮，
 *   否则同一主题第四次起永远只看到"库里已有"；
 * - **候选窗口**：每页取 [AppLimits.Sticker.FETCH_CANDIDATES_PER_PAGE] 张，撞库就继续往后，
 *   不再只试 3 张；
 * - **失败可解释**：搜索被反爬、HTTP 码、超体积、解码失败各自成句，带上 `step`
 *   返回给调用方（search / download / save），模型能照实转述。
 *
 * 检索源侧的 `BAIDUID` cookie 预热在 `:core:network` 的 `BaiduImageSearch` 里做——
 * 不带 cookie 时百度在**翻页之前**就整页拦掉，这边翻页再卖力也用不上。
 */
class HttpStickerFetcher(
    private val search: StickerImageSearch,
    private val client: OkHttpClient,
    private val media: MediaStore,
    private val characters: CharacterRepository,
    private val label: suspend (imageRef: String) -> StickerLabel?,
) : StickerFetcher {

    private sealed interface Download {
        data class Ok(val bytes: ByteArray) : Download
        data class Err(val reason: String) : Download
    }

    override suspend fun fetch(character: Character, query: String, count: Int): StickerFetchResult {
        val q = query.trim().take(40)
        if (q.isEmpty()) return StickerFetchResult.Failed("检索词是空的，没法找图", "search")
        val want = count.coerceIn(1, AppLimits.Sticker.FETCH_MAX_COUNT)

        val added = mutableListOf<FetchedSticker>()
        var total = character.stickers.size
        var candidatesSeen = 0
        var downloaded = 0
        var duplicates = 0
        var saveFailures = 0
        var lastDownloadError = ""
        var blocked = false
        var searchError = ""

        for (page in 0 until AppLimits.Sticker.FETCH_PAGES) {
            if (added.size >= want) break
            val pageResult = runCatching {
                search.search(q, AppLimits.Sticker.FETCH_CANDIDATES_PER_PAGE, page)
            }.getOrNull() ?: StickerSearchPage(error = "检索请求异常")
            if (pageResult.error.isNotEmpty()) {
                searchError = pageResult.error
                continue
            }
            if (pageResult.blocked) {
                blocked = true
                continue
            }
            candidatesSeen += pageResult.candidates.size
            for (candidate in pageResult.candidates) {
                if (added.size >= want) break
                val bytes = when (val download = download(candidate.imageUrl)) {
                    is Download.Err -> {
                        lastDownloadError = download.reason
                        continue
                    }

                    is Download.Ok -> download.bytes
                }
                downloaded++
                val saved = media.saveStickerBytes(bytes)
                if (saved == null) {
                    saveFailures++
                    continue
                }
                val ref = saved
                val live = characters.get(character.id)
                    ?: return StickerFetchResult.Failed("这个角色已经不在了", "save")
                // 同一张图已经在库里（按内容 hash 命名 → fileRef 相同）就换下一张。
                if (live.stickers.any { it.fileRef == ref }) {
                    duplicates++
                    continue
                }

                // 起名/归类是锦上添花：模型超时、不支持看图、输出不是 JSON 都不影响入库。
                val verdict = runCatching { label(ref) }.getOrNull()
                val keyword = sanitizeKeyword(verdict?.keyword.orEmpty()).ifEmpty { sanitizeKeyword(q) }
                val sticker = Sticker(
                    id = UUID.randomUUID().toString(),
                    keyword = keyword,
                    category = verdict?.category?.takeIf(StickerCategory::isValid) ?: StickerCategory.OTHER,
                    fileRef = ref,
                    source = "web",
                    origin = candidate.pageUrl,
                    createdAt = Instant.now().toString(),
                )
                val written = characters.mutate(character.id) { row -> row.copy(stickers = row.stickers + sticker) }
                    ?: return StickerFetchResult.Failed("表情入库失败", "save")
                if (written.stickers.none { it.fileRef == ref }) {
                    return StickerFetchResult.Failed("表情入库失败", "save")
                }
                total = written.stickers.size
                added += FetchedSticker(keyword = sticker.keyword, category = sticker.category, fileRef = ref)
            }
        }

        if (added.isNotEmpty()) return StickerFetchResult.Stored(added, total)
        return when {
            candidatesSeen == 0 && blocked ->
                StickerFetchResult.Failed("检索源把请求拦了（反爬），过一会儿再试", "search")

            candidatesSeen == 0 && searchError.isNotEmpty() ->
                StickerFetchResult.Failed("网图搜索请求失败（$searchError），稍后再试", "search")

            candidatesSeen == 0 ->
                StickerFetchResult.Failed("网图搜索没有结果，换个更具体的说法再试", "search")

            downloaded == 0 ->
                StickerFetchResult.Failed(
                    "候选图都下载失败" + if (lastDownloadError.isEmpty()) "" else "（$lastDownloadError）",
                    "download",
                )

            duplicates > 0 && duplicates + saveFailures >= candidatesSeen ->
                StickerFetchResult.Failed("搜到的都是库里已有的图，换个说法再试", "dedupe")

            else -> StickerFetchResult.Failed("候选图都没能存下来，稍后再试", "save")
        }
    }

    private suspend fun download(url: String): Download = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use Download.Err("HTTP ${response.code}")
                val body = response.body ?: return@use Download.Err("响应为空")
                if (body.contentLength() > AppLimits.Sticker.MAX_FETCH_BYTES) {
                    return@use Download.Err("图片超过 ${AppLimits.Sticker.MAX_FETCH_BYTES / 1024}KB")
                }
                val bytes = body.bytes()
                when {
                    bytes.isEmpty() -> Download.Err("响应为空")
                    bytes.size > AppLimits.Sticker.MAX_FETCH_BYTES -> Download.Err("图片超体积")
                    else -> Download.Ok(bytes)
                }
            }
        }.getOrElse { Download.Err(it.message ?: "下载异常") }
    }

    private companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        /**
         * 百度自家 CDN（img*.baidu.com）实测不要求 Referer；带上只是为了换检索源/换图床时的兜底。
         */
        const val REFERER = "https://image.baidu.com/"
    }
}

/** 关键词收口：去掉空白与标点、封顶 7 字（防注入与超长 -- 和模型侧同一套规则）。 */
internal fun sanitizeKeyword(raw: String): String {
    val cleaned = raw.trim()
        .replace(Regex("[\\s\\u00a0\\u3000]+"), "")
        .replace(Regex("[。，,、！!？?：:；;（）()\\[\\]「」『』\"'“”‘’…—~]"), "")
    return cleaned.take(AppLimits.Sticker.KEYWORD_MAX)
}
