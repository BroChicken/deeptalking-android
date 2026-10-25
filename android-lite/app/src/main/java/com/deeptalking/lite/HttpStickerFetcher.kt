package com.deeptalking.lite

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.data.CharacterRepository
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.Sticker
import com.deeptalking.core.model.StickerCategory
import com.deeptalking.core.network.StickerImageSearch
import com.deeptalking.domain.agent.sticker.FetchedSticker
import com.deeptalking.domain.agent.sticker.StickerFetcher
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 视觉模型对一张候选图的结论：能当表情就带关键词和大类，否则 [keyword] 为空。 */
data class StickerLabel(
    val keyword: String,
    val category: String = StickerCategory.OTHER,
    val accepted: Boolean = true,
)

/**
 * 主题检索 → 直接发：百度找图 → 下载 → 压成 384px/120KB → 视觉审一遍并起名 → 入库。
 *
 * 审核和起名是**同一次**视觉调用（[label]）：既省一次请求，也保证“能发出去的表情”
 * 一定是模型看过的。第一张不合格就看下一张，最多 [AppLimits.Sticker.FETCH_CANDIDATES] 张。
 */
class HttpStickerFetcher(
    private val search: StickerImageSearch,
    private val client: OkHttpClient,
    private val media: MediaStore,
    private val characters: CharacterRepository,
    private val label: suspend (imageRef: String) -> StickerLabel?,
) : StickerFetcher {

    override suspend fun fetch(character: Character, query: String): FetchedSticker? {
        if (character.stickers.size >= AppLimits.Sticker.MAX_PER_CHARACTER) return null
        val q = query.trim().take(40)
        if (q.isEmpty()) return null

        val candidates = runCatching {
            search.search(q, AppLimits.Sticker.FETCH_CANDIDATES * 2)
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) return null

        for (candidate in candidates.take(AppLimits.Sticker.FETCH_CANDIDATES)) {
            val bytes = download(candidate.imageUrl) ?: continue
            val ref = media.saveStickerBytes(bytes) ?: continue
            // 同一张图已经在库里（按内容 hash 命名 → fileRef 相同）就直接放弃，换个来源。
            val live = characters.get(character.id) ?: return null
            if (live.stickers.any { it.fileRef == ref }) continue

            val verdict = runCatching { label(ref) }.getOrNull() ?: continue
            if (!verdict.accepted) continue

            val keyword = sanitizeKeyword(verdict.keyword).ifEmpty { sanitizeKeyword(q) }
            val sticker = Sticker(
                id = UUID.randomUUID().toString(),
                keyword = keyword,
                category = verdict.category.takeIf(StickerCategory::isValid) ?: StickerCategory.OTHER,
                fileRef = ref,
                source = "web",
                origin = candidate.pageUrl,
                createdAt = Instant.now().toString(),
            )
            val written = characters.mutate(character.id) { row ->
                if (row.stickers.size >= AppLimits.Sticker.MAX_PER_CHARACTER) row
                else row.copy(stickers = row.stickers + sticker)
            } ?: return null
            if (written.stickers.none { it.fileRef == ref }) return null
            return FetchedSticker(sticker.keyword, sticker.category, ref)
        }
        return null
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
