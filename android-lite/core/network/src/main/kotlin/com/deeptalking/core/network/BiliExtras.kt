package com.deeptalking.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * B 站视频的四种「周边信息」：评论区、相关推荐、UP 主的合集、弹幕。
 *
 * 它们和字幕/总结是两回事：字幕回答"视频讲了什么"，这些回答"别人怎么评价 / 还有什么可看 /
 * 观众在刷什么"。所以既不能混进摘要，也不能默认抓——都由调用方按需申请（`extras` 参数）。
 */

/** 一条评论（置顶或按点赞排）。 */
data class BiliComment(
    val user: String,
    val text: String,
    val likes: Int,
    val replies: Int,
)

/** 相关推荐里的一个视频。 */
data class BiliRelatedVideo(
    val bvid: String,
    val title: String,
    val author: String,
    val views: Int,
    val durationSec: Int,
)

/** 合集/系列里的一个视频。 */
data class BiliCollectionItem(
    val bvid: String,
    val title: String,
    val durationSec: Int,
)

/**
 * UP 主的一个合集（[KIND_SEASON]，`season_id`）或系列（[KIND_SERIES]，`series_id`）。
 *
 * [count] 是这个合集的**总数**，[preview] 只是接口顺手给的最近几条——两者常常不等，
 * 所以要拿全集必须再用 [id] 查一次 `seasons_archives_list`。
 */
data class BiliCollection(
    val kind: String,
    val id: Long,
    val title: String,
    val count: Int,
    val preview: List<BiliCollectionItem> = emptyList(),
) {
    val isSeason: Boolean get() = kind == KIND_SEASON
}

/** 一条弹幕（解析出来的原子单位，聚合后才是给模型看的 [BiliDanmakuDigest]）。 */
data class BiliDanmakuLine(val text: String, val atSec: Int)

/** 聚合后的一条弹幕：[count] 是相同文本出现次数，[atSec] 是它第一次出现的位置。 */
data class BiliDanmakuHit(val text: String, val count: Int, val atSec: Int)

/**
 * 弹幕的两种视角，**不含原始弹幕流**：
 * - [top]：刷得最多的几句（一个视频几千条弹幕，逐条喂进去又贵又没信息量）；
 * - [timeline]：按分钟分桶、每桶的代表句，用来看"第几分钟观众在刷什么"。
 *
 * [total] 是解析到的原始条数，模型据此判断"弹幕多不多"，也用于区分"真没人发弹幕"和"聚合后为空"。
 */
data class BiliDanmakuDigest(
    val total: Int,
    val top: List<BiliDanmakuHit>,
    val timeline: List<BiliDanmakuHit>,
) {
    val isEmpty: Boolean get() = top.isEmpty() && timeline.isEmpty()
}

const val KIND_SEASON = "season"
const val KIND_SERIES = "series"

// ---------------------------------------------------------------------------- 解析

/**
 * 解析评论区（`x/v2/reply/wbi/main`；旧接口 `x/v2/reply` 形状相同）。
 *
 * 置顶在 `data.top_replies[]`、其余在 `data.replies[]`，**同一条常在两处都出现**，
 * 所以按「用户 + 正文」去重——否则模型会以为有两个人说了同样的话。
 * 正文里的换行压成空格：评论是给模型读的，保留排版只会浪费 token。
 */
internal fun parseBiliComments(rootJson: String, limit: Int = 20): List<BiliComment> {
    val root = runCatching { JSON_FOR_PARSING.parseToJsonElement(rootJson) as? JsonObject }.getOrNull()
        ?: return emptyList()
    val data = root["data"] as? JsonObject ?: return emptyList()
    val seen = LinkedHashMap<String, BiliComment>()
    for (key in COMMENT_LIST_KEYS) {
        val items = data[key] as? JsonArray ?: continue
        for (element in items) {
            val obj = element as? JsonObject ?: continue
            val message = (obj["content"] as? JsonObject)?.get("message").textOrEmpty()
                .replace(WHITESPACE, " ")
                .trim()
            if (message.isEmpty()) continue
            val user = (obj["member"] as? JsonObject)?.get("uname").textOrEmpty().trim()
            val dedupeKey = user + '\u0000' + message
            if (seen.containsKey(dedupeKey)) continue
            seen[dedupeKey] = BiliComment(
                user = user.ifEmpty { "匿名用户" },
                text = message,
                likes = obj["like"].intOrZero(),
                replies = obj["rcount"].intOrZero(),
            )
            if (seen.size >= limit) return seen.values.toList()
        }
    }
    return seen.values.toList()
}

/**
 * 解析相关推荐（`x/web-interface/archive/related`，免登录）。
 * 这个接口的 `data` 本身就是数组；个别版本包成 `data.list[]`，两种都认。
 */
internal fun parseBiliRelated(rootJson: String, limit: Int = 20): List<BiliRelatedVideo> {
    val root = runCatching { JSON_FOR_PARSING.parseToJsonElement(rootJson) as? JsonObject }.getOrNull()
        ?: return emptyList()
    val data = root["data"] ?: return emptyList()
    val items = data as? JsonArray ?: ((data as? JsonObject)?.get("list") as? JsonArray)
    ?: return emptyList()
    return items.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val bvid = obj["bvid"].textOrEmpty().trim()
        if (bvid.isEmpty()) return@mapNotNull null
        val title = obj["title"].textOrEmpty().trim()
        if (title.isEmpty()) return@mapNotNull null
        BiliRelatedVideo(
            bvid = bvid,
            title = title,
            author = (obj["owner"] as? JsonObject)?.get("name").textOrEmpty().trim(),
            views = (obj["stat"] as? JsonObject)?.get("view").intOrZero() ?: 0,
            durationSec = obj["duration"].intOrZero(),
        )
    }.take(limit)
}

/**
 * 解析 UP 主的合集/系列总览（`x/polymer/web-space/seasons_series_list`，免登录）。
 *
 * 两种条目共用 `items_lists` 下的两个数组：`seasons_list[]`（合集）与 `series_list[]`（系列）。
 * 编号字段不同（`meta.season_id` / `meta.series_id`），标题字段同样各有一套（`title` / `name`），
 * 所以两样都要试；`archives[]` 是接口顺带返回的最近几条，当作预览，不代表全集。
 */
internal fun parseBiliUpCollections(rootJson: String, limit: Int = 20): List<BiliCollection> {
    val root = runCatching { JSON_FOR_PARSING.parseToJsonElement(rootJson) as? JsonObject }.getOrNull()
        ?: return emptyList()
    val lists = (root["data"] as? JsonObject)?.get("items_lists") as? JsonObject ?: return emptyList()
    val out = mutableListOf<BiliCollection>()
    for ((key, kind) in COLLECTION_LISTS) {
        val items = lists[key] as? JsonArray ?: continue
        for (element in items) {
            val obj = element as? JsonObject ?: continue
            val meta = obj["meta"] as? JsonObject ?: continue
            val id = if (kind == KIND_SEASON) meta["season_id"].longOrZero() else meta["series_id"].longOrZero()
            if (id <= 0L) continue
            val title = meta["title"].textOrEmpty().trim()
                .ifEmpty { meta["name"].textOrEmpty().trim() }
            if (title.isEmpty()) continue
            val preview = parseCollectionArchives(obj["archives"] as? JsonArray)
            val count = meta["total"].intOrZero().takeIf { it > 0 } ?: preview.size
            out += BiliCollection(kind = kind, id = id, title = title, count = count, preview = preview)
            if (out.size >= limit) return out
        }
    }
    return out
}

/**
 * 解析某个合集/系列的全部分集（`x/polymer/web-space/seasons_archives_list`）。
 * 排序交给接口的 `sort_reverse`，这里保持原始顺序。
 */
internal fun parseBiliCollectionEpisodes(rootJson: String, limit: Int = 50): List<BiliCollectionItem> {
    val root = runCatching { JSON_FOR_PARSING.parseToJsonElement(rootJson) as? JsonObject }.getOrNull()
        ?: return emptyList()
    val data = root["data"] as? JsonObject ?: return emptyList()
    return parseCollectionArchives(data["archives"] as? JsonArray).take(limit)
}

private fun parseCollectionArchives(items: JsonArray?): List<BiliCollectionItem> {
    if (items == null) return emptyList()
    return items.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val bvid = obj["bvid"].textOrEmpty().trim()
        if (bvid.isEmpty()) return@mapNotNull null
        BiliCollectionItem(
            bvid = bvid,
            title = obj["title"].textOrEmpty().trim(),
            durationSec = obj["duration"].intOrZero(),
        )
    }
}

/**
 * 解析弹幕 XML（`https://comment.bilibili.com/<cid>.xml`，免登录）。
 *
 * 每条形如 `<d p="53.24000,1,25,16777215,1700000000,0,abc,123">文本</d>`，
 * `p` 的第一段是**秒数（浮点）**；文本经过 XML 转义，要解回来。
 */
internal fun parseBiliDanmakuXml(xml: String, limit: Int = 20_000): List<BiliDanmakuLine> {
    val out = ArrayList<BiliDanmakuLine>(minOf(xml.length / 80, 1024))
    for (match in DANMAKU_TAG.findAll(xml)) {
        val atSec = match.groupValues[1].substringBefore(',').trim().toDoubleOrNull()?.toInt() ?: continue
        val text = unescapeXml(match.groupValues[2]).replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) continue
        out += BiliDanmakuLine(text = text, atSec = atSec.coerceAtLeast(0))
        if (out.size >= limit) break
    }
    return out
}

/**
 * 把弹幕压成「高频 + 时间线」两个列表。
 *
 * 不做全文透传的理由很直接：一个热门视频几万条弹幕，逐条喂给模型既烧 token 又没有任何信息量
 * —— 真正有用的是"大家在刷什么梗"和"第几分钟在刷"。
 *
 * - [top]：相同文本计数后按次数降序，同次数时先出现的排前面；
 * - [timeline]：按分钟分桶，每桶取出现最多的那句，桶数超过 [maxTimeline] 时**均匀挑**
 *   （不是取前几个），否则一小时视频的时间线会全挤在开头十分钟。
 */
fun digestBiliDanmaku(
    lines: List<BiliDanmakuLine>,
    maxTop: Int = 12,
    maxTimeline: Int = 8,
): BiliDanmakuDigest {
    val usable = lines.filter { usableDanmakuText(it.text) }
    if (usable.isEmpty()) return BiliDanmakuDigest(total = lines.size, top = emptyList(), timeline = emptyList())

    val counts = LinkedHashMap<String, BiliDanmakuHit>()
    for (line in usable) {
        val existing = counts[line.text]
        counts[line.text] = if (existing == null) {
            BiliDanmakuHit(text = line.text, count = 1, atSec = line.atSec)
        } else {
            existing.copy(count = existing.count + 1)
        }
    }
    val top = counts.values
        .sortedWith(compareByDescending<BiliDanmakuHit> { it.count }.thenBy { it.atSec })
        .take(maxTop.coerceAtLeast(0))

    val buckets = usable.groupBy { it.atSec / 60 }.toSortedMap()
    val perBucket = buckets.map { (minute, bucket) ->
        val firstSeen = bucket.groupBy { it.text }.mapValues { (_, same) -> same.first().atSec }
        val bucketCounts = bucket.groupingBy { it.text }.eachCount()
        val best = bucketCounts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { firstSeen[it.key] ?: Int.MAX_VALUE },
            )
            .first()
        BiliDanmakuHit(text = best.key, count = best.value, atSec = minute * 60)
    }
    val timeline = if (perBucket.size <= maxTimeline) {
        perBucket
    } else {
        spreadIndices(perBucket.size, maxTimeline).map { perBucket[it] }
    }
    return BiliDanmakuDigest(total = lines.size, top = top, timeline = timeline)
}

/**
 * 一条弹幕值不值得占一格上下文：2~24 字、且至少含一个字母（中文也算字母）。
 *
 * 挡掉的是 `233`、`666`、`!!!`、`??` 这类纯数字/纯符号——它们在弹幕里占比极高，
 * 但完全说不出视频内容。
 */
private fun usableDanmakuText(raw: String): Boolean {
    val text = raw.trim()
    if (text.length < 2 || text.length > 24) return false
    return text.any { it.isLetter() }
}

/** 等距取 [count] 个下标（含首尾、去重）：时间线要覆盖整段视频，不是开头几分钟。 */
private fun spreadIndices(total: Int, count: Int): List<Int> {
    if (count <= 0 || total <= 0) return emptyList()
    if (total <= count) return (0 until total).toList()
    if (count == 1) return listOf(total / 2)
    return (0 until count)
        .map { step -> (step.toLong() * (total - 1) / (count - 1)).toInt() }
        .distinct()
}

/** 解开 XML 里用到的 5 个命名实体 + 十进制/十六进制字符引用。 */
internal fun unescapeXml(text: String): String {
    if (!text.contains('&')) return text
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        if (ch == '&') {
            val end = text.indexOf(';', startIndex = index + 1)
            if (end in (index + 2)..(index + 12)) {
                val entity = text.substring(index + 1, end)
                val decoded = when {
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.substring(2).toIntOrNull(16)?.let(::codePointToString)
                    entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePointToString)
                    else -> null
                }
                if (decoded != null) {
                    out.append(decoded)
                    index = end + 1
                    continue
                }
            }
        }
        out.append(ch)
        index++
    }
    return out.toString()
}

private fun codePointToString(codePoint: Int): String? =
    if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else null

private val DANMAKU_TAG = Regex("<d\\s+p=\"([^\"]*)\"[^>]*>(.*?)</d>", RegexOption.DOT_MATCHES_ALL)
private val WHITESPACE = Regex("\\s+")
private val COMMENT_LIST_KEYS = listOf("top_replies", "replies", "top")
private val COLLECTION_LISTS = listOf("seasons_list" to KIND_SEASON, "series_list" to KIND_SERIES)
