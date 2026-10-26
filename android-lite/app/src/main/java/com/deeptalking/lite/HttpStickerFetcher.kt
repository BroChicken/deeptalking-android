package com.deeptalking.lite

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.data.CharacterRepository
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.Sticker
import com.deeptalking.core.model.StickerCategory
import com.deeptalking.core.network.StickerImageSearch
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
 * 主题检索 → 直接发：百度找图 → 下载 → 压成 384px/120KB → 视觉起名+归类 → 入库。
 *
 * 每一步失败都会变成一句能转述的话（[StickerFetchResult.Failed]），让角色能如实说出
 * “图下载不下来”还是“搜索没有结果”，而不是笼统地说“加不进去”。
 */
class HttpStickerFetcher(
    private val search: StickerImageSearch,
    private val client: OkHttpClient,
    private val media: MediaStore,
    private val characters: CharacterRepository,
    private val label: suspend (imageRef: String) -> StickerLabel?,
) : StickerFetcher {

    override suspend fun fetch(character: Character, query: String): StickerFetchResult {
        val q = query.trim().take(40)
        if (q.isEmpty()) return StickerFetchResult.Failed("检索词是空的，没法找图")

        val candidates = runCatching {
            search.search(q, AppLimits.Sticker.FETCH_CANDIDATES * 2)
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) {
            return StickerFetchResult.Failed("网图搜索没有结果，换个更具体的说法再试")
        }

        var downloaded = 0
        var duplicates = 0
        for (candidate in candidates.take(AppLimits.Sticker.FETCH_CANDIDATES)) {
            val bytes = download(candidate.imageUrl) ?: continue
            downloaded++
            val ref = media.saveStickerBytes(bytes) ?: continue
            // 同一张图已经在库里（按内容 hash 命名 → fileRef 相同）就换下一张。
            val live = characters.get(character.id) ?: return StickerFetchResult.Failed("这个角色已经不在了")
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
                ?: return StickerFetchResult.Failed("表情入库失败")
            if (written.stickers.none { it.fileRef == ref }) {
                return StickerFetchResult.Failed("表情入库失败")
            }
            return StickerFetchResult.Stored(
                FetchedSticker(
                    keyword = sticker.keyword,
                    category = sticker.category,
                    fileRef = ref,
                    total = written.stickers.size,
                ),
            )
        }

        return when {
            downloaded == 0 -> StickerFetchResult.Failed("候选图都下载失败，稍后再试")
            duplicates > 0 -> StickerFetchResult.Failed("搜到的都是库里已有的图，换个说法再试")
            else -> StickerFetchResult.Failed("候选图都没能存下来，稍后再试")
        }
    }

    private suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > AppLimits.Sticker.MAX_FETCH_BYTES) return@use null
                val bytes = body.bytes()
                bytes.takeIf { it.isNotEmpty() && it.size <= AppLimits.Sticker.MAX_FETCH_BYTES }
            }
        }.getOrNull()
    }

    private companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
    }
}

/** 关键词收口：去掉空白与标点、封顶 7 字（防注入与超长 -- 和模型侧同一套规则）。 */
internal fun sanitizeKeyword(raw: String): String {
    val cleaned = raw.trim()
        .replace(Regex("[\\s\\u00a0\\u3000]+"), "")
        .replace(Regex("[。，,、！!？?：:；;（）()\\[\\]「」『』\"'“”‘’…—~]"), "")
    return cleaned.take(AppLimits.Sticker.KEYWORD_MAX)
}
