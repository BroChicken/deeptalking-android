package com.deeptalking.domain.agent.video

import com.deeptalking.core.network.BiliSubtitleLine
import com.deeptalking.core.network.BiliVideoText
import com.deeptalking.core.network.formatBiliClock

/**
 * 一次非流式补全：`system` + `user` 进，`imageRefs` 是可以给视觉模型看的本地图片引用。
 *
 * 只留这一个方法是有意的——总结服务不该知道模型名、平台、重试策略（那些属于 `:app` 的辅助
 * 调用通道）。图片也一样：调用方给引用，怎么变成 `data:` URI 由实现决定。
 */
fun interface VideoSummarizer {
    suspend fun complete(system: String, user: String, imageRefs: List<String>): VideoDigest?
}

/**
 * 一次总结的结果。
 *
 * [imagesAccepted] 是"这次带图请求是否被模型接受了"的回执：false 表示模型不支持看图（实现已经
 * 退回纯文字重试过）。工具据此决定要不要把截图塞进工具结果——**只有确认模型能看图才塞**，
 * 否则纯文本模型会在下一轮直接 400。没有带图请求时它没有意义，恒为 true。
 *
 * [coveredUntilSec] 是这次真正读到的最后一条字幕时间点：分块有上限时后面的内容**没有看**，
 * 工具据此在结果里写清「已看到 X:XX / 全长 Y:YY」，而不是让模型以为看完了。
 */
data class VideoDigest(
    val text: String,
    val imagesAccepted: Boolean = true,
    val coveredUntilSec: Int? = null,
)

/** 一段被切开的字幕（[index] 从 1 开始，只用于提示词里的"第几段/共几段"）。 */
data class TranscriptChunk(val index: Int, val lines: List<BiliSubtitleLine>)

/**
 * 视频总结的两种路径：
 *
 * - **有逐句字幕** → 分块 map-reduce（短则一次），产出「一句话 + 带 `mm:ss` 的要点」；
 * - **没字幕** → 只用标题/简介/章节标签/画面截图说“大概是什么”，并明确声明没字幕，
 *   绝不假装看过内容。
 *
 * 为什么不让主模型直接读字幕：字幕是**追加式历史**里的一坨文本，会跟着后面每一轮重新计费。
 * 这里把它压成"一句话 + 几条带时间戳的要点"（几百字），再交给角色用自己的口气转述。
 */
class VideoSummaryService(private val summarizer: VideoSummarizer) {

    /**
     * 返回可直接放进工具结果的摘要；失败返回 null（由调用方如实报错，不许编造）。
     * [imageRefs] 是抽到的画面截图，有字幕时用来确认场景，没字幕时是唯一的信息来源。
     */
    suspend fun digest(
        text: BiliVideoText,
        focus: String? = null,
        imageRefs: List<String> = emptyList(),
    ): VideoDigest? {
        if (!text.hasSubtitle) return digestShallow(text, focus, imageRefs)
        val chunks = chunkTranscript(text.lines)
        if (chunks.isEmpty()) return null
        val covered = chunks.lastOrNull()?.lines?.lastOrNull()?.fromSec
        val header = headerOf(text, focus)
        if (chunks.size == 1) {
            val user = header + transcriptBlock(chunks[0].lines) + framesBlock(imageRefs)
            return summarizer.complete(WRITER_SYSTEM + framesRule(imageRefs.size), user, imageRefs)
                ?.normalized(covered)
        }
        val partials = chunks.mapNotNull { chunk ->
            val user = headerOf(text, null) +
                "（第 " + chunk.index + "/" + chunks.size + " 段）\n" +
                transcriptBlock(chunk.lines)
            summarizer.complete(MAP_SYSTEM, user, emptyList())?.normalized()
        }
        if (partials.isEmpty()) return null
        val reduceUser = buildString {
            append(headerOf(text, focus))
            append("【分段要点】\n")
            partials.forEachIndexed { position, partial ->
                append("第 ").append(position + 1).append(" 段：\n").append(partial.text).append('\n')
            }
            append(framesBlock(imageRefs))
        }
        return summarizer.complete(REDUCE_SYSTEM + framesRule(imageRefs.size), reduceUser, imageRefs)
            ?.normalized(covered)
            ?: VideoDigest(
                text = partials.joinToString("\n") { it.text },
                imagesAccepted = imageRefs.isEmpty(),
                coveredUntilSec = covered,
            )
    }

    private fun VideoDigest.normalized(coveredUntilSec: Int? = null): VideoDigest? =
        text.trim().takeIf { it.isNotEmpty() }
            ?.let { VideoDigest(it, imagesAccepted, this.coveredUntilSec ?: coveredUntilSec) }

    /** 没字幕：只给元信息 + 章节 + 画面，要求明确声明"没字幕、只能看个大概"。 */
    private suspend fun digestShallow(
        text: BiliVideoText,
        focus: String?,
        imageRefs: List<String>,
    ): VideoDigest? {
        val user = buildString {
            append(headerOf(text, focus))
            if (text.chapters.isNotEmpty()) {
                append("【章节标签】\n")
                text.chapters.forEach { chapter ->
                    append("- ").append(formatBiliClock(chapter.fromSec)).append(' ').append(chapter.text).append('\n')
                }
            }
            append("【说明】这个视频取不到字幕（也没有站方 AI 总结），只能靠简介、章节和画面截图判断。\n")
            append(framesBlock(imageRefs))
        }
        return summarizer.complete(
            system = SHALLOW_SYSTEM + framesRule(imageRefs.size),
            user = user,
            imageRefs = imageRefs,
        )?.normalized()
    }

    private fun headerOf(text: BiliVideoText, focus: String?): String = buildString {
        append("【视频】").append(text.title)
        if (text.author.isNotEmpty()) append("　UP主：").append(text.author)
        append("　时长：").append(formatBiliClock(text.durationSec)).append('\n')
        val desc = text.desc.trim().replace(Regex("\\s+"), " ").take(200)
        if (desc.isNotEmpty()) append("【简介】").append(desc).append('\n')
        val wanted = focus?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted != null) append("【用户特别想知道】").append(wanted).append('\n')
    }

    private fun transcriptBlock(lines: List<BiliSubtitleLine>): String = buildString {
        append("【字幕】\n")
        lines.forEach { line ->
            append('[').append(formatBiliClock(line.fromSec)).append("] ").append(line.text).append('\n')
        }
    }

    private fun framesBlock(imageRefs: List<String>): String =
        if (imageRefs.isEmpty()) "" else "【画面】另附 ${imageRefs.size} 张视频截图，按时间顺序排列。\n"

    private fun framesRule(count: Int): String =
        if (count == 0) "" else
            "\n有附带画面截图时：只用它确认“画面在拍什么/是什么形式”（真人、动画、录屏、板书…），" +
                "不要凭画面推测台词、数据或结论。"

    companion object {
        /**
         * 单次补全能吃下的字幕字数上限。
         *
         * 实测：28 分钟的中文视频（AI 字幕 637 句 / 7730 字）加上逐行 `[mm:ss]` 前缀后约
         * 12.8k 字符，一次补全完全吃得下（约 9k~13k 输入 token）——所以阈值取 14000，让
         * 半小时以内的视频都只调一次模型，而不是分两段再合并（少 2 次往返、少一半延迟）。
         */
        const val CHUNK_CHARS = 14000

        /** 最多几段（配合 [CHUNK_CHARS] 约覆盖 8 万~11 万字的超长视频）。 */
        const val MAX_CHUNKS = 8

        /**
         * 按行切块，**在行边界断开**（不切开一句话）。
         *
         * 超过 [maxChunks] 的部分仍然会被丢弃，但**丢弃不再静默**：调用方拿到最后一行的时间点
         * （[VideoDigest.coveredUntilSec]），在工具结果里写明"已看到 X:XX"，模型据此告诉用户
         * 后面还没看，并可以带 `from` 从那个时间点继续。
         */
        fun chunkTranscript(
            lines: List<BiliSubtitleLine>,
            chunkChars: Int = CHUNK_CHARS,
            maxChunks: Int = MAX_CHUNKS,
        ): List<TranscriptChunk> {
            if (lines.isEmpty()) return emptyList()
            val chunks = mutableListOf<TranscriptChunk>()
            var current = mutableListOf<BiliSubtitleLine>()
            var currentChars = 0
            for (line in lines) {
                if (chunks.size >= maxChunks) break
                val length = line.text.length + 8
                if (current.isNotEmpty() && currentChars + length > chunkChars) {
                    chunks += TranscriptChunk(chunks.size + 1, current)
                    if (chunks.size >= maxChunks) {
                        current = mutableListOf()
                        break
                    }
                    current = mutableListOf()
                    currentChars = 0
                }
                current += line
                currentChars += length
            }
            if (current.isNotEmpty() && chunks.size < maxChunks) {
                chunks += TranscriptChunk(chunks.size + 1, current)
            }
            return chunks
        }

        private const val WRITER_SYSTEM =
            "你在把一段视频字幕整理成要点，供另一个人向用户转述。\n" +
                "只依据字幕：字幕里没有的信息一律不写，不补充背景知识，不评价视频好坏，不推测作者动机。\n" +
                "输出格式（不要多余的开头结尾）：\n" +
                "一句话：<不超过 40 字，说清这个视频到底讲了什么>\n" +
                "整体脉络：<3~5 句，按时间顺序说清它怎么展开、讲到哪、最后得出什么结论；写走向，不要罗列细节>\n" +
                "关键节点：\n" +
                "- mm:ss <一条具体信息，不超过 40 字>\n" +
                "关键节点 2 到 6 条，时间戳必须取自字幕里出现过的标记。"

        private const val MAP_SYSTEM =
            "你在把视频字幕的其中一段整理成要点，供后续合并成整条视频的脉络。\n" +
                "只依据本段字幕，不补充外部知识，不写引言和结论。\n" +
                "第一行固定写「本段：<一句话概括这一段在讲什么，不超过 30 字>」，然后每行一条要点，格式：" +
                "- mm:ss 内容（时间戳取自本段字幕标记，内容不超过 40 字），要点 2 到 5 条。"

        private const val REDUCE_SYSTEM =
            "你在把一个视频几段字幕的要点合并成最终结果，供另一个人向用户转述。\n" +
                "只用给出的要点，不新增信息。\n" +
                "输出格式：\n" +
                "一句话：<不超过 40 字，说清整条视频讲了什么>\n" +
                "整体脉络：<3~5 句，按时间顺序说清整条视频怎么展开、最后得出什么结论；不要罗列细节>\n" +
                "关键节点：\n" +
                "- mm:ss <不超过 40 字>\n" +
                "关键节点 3 到 8 条，按时间顺序，去掉重复和琐碎细节。"

        private const val SHALLOW_SYSTEM =
            "这个视频**没有字幕**，你只能看到：标题、简介、章节标签和几张画面截图。\n" +
                "你要做的是“看个大概”，不是总结内容：说清楚这是什么类型的内容、分了哪几段、画面是什么形式。\n" +
                "严禁编造具体观点、结论、数据、人名或台词；不要假装看过。\n" +
                "输出格式（不要多余的开头结尾）：\n" +
                "一句话：<不超过 40 字，必须是“没字幕，看画面像是…”这种带保留的说法>\n" +
                "整体脉络：<2~4 句，按时间顺序说清它大概怎么展开；只写简介/章节/画面能支撑的东西>\n" +
                "关键节点：\n" +
                "- <不超过 40 字>\n" +
                "关键节点 2 到 4 条。"
    }
}
