package com.deeptalking.domain.agent.video

import com.deeptalking.core.network.BiliSubtitleLine
import com.deeptalking.core.network.BiliVideoText
import com.deeptalking.core.network.formatBiliClock

/**
 * 一次非流式补全：`system` + `user` 进、纯文本出。
 *
 * 只留这一个方法是有意的——总结服务不该知道模型名、平台、重试策略（那些属于 `:app` 的
 * 辅助调用通道），测试里给个记录参数的实现就能验证分块与合并逻辑。
 */
fun interface VideoSummarizer {
    suspend fun complete(system: String, user: String): String?
}

/** 一段被切开的字幕（[index] 从 1 开始，只用于提示词里的"第几段/共几段"）。 */
data class TranscriptChunk(val index: Int, val lines: List<BiliSubtitleLine>)

/**
 * 长视频的 map-reduce 总结：字幕 ≤ [CHUNK_CHARS] 时一次写完；更长时先分段提要点、再合并。
 *
 * 为什么不让主模型直接读字幕：字幕是**追加式历史**里的一坨文本，会跟着后面每一轮重新计费。
 * 这里把它压成"一句话 + 几条带时间戳的要点"（几百字），再交给角色用自己的口气转述。
 */
class VideoSummaryService(private val summarizer: VideoSummarizer) {

    /** 返回可直接放进工具结果的摘要文本；失败返回 null（由调用方如实报错，不许编造）。 */
    suspend fun digest(text: BiliVideoText, focus: String? = null): String? {
        val chunks = chunkTranscript(text.lines)
        if (chunks.isEmpty()) return null
        val header = headerOf(text, focus)
        if (chunks.size == 1) {
            val user = header + transcriptBlock(chunks[0].lines)
            return summarizer.complete(WRITER_SYSTEM, user)?.trim()?.takeIf { it.isNotEmpty() }
        }
        val partials = chunks.mapNotNull { chunk ->
            val user = headerOf(text, null) +
                "（第 " + chunk.index + "/" + chunks.size + " 段）\n" +
                transcriptBlock(chunk.lines)
            summarizer.complete(MAP_SYSTEM, user)?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (partials.isEmpty()) return null
        val reduceUser = buildString {
            append(headerOf(text, focus))
            append("【分段要点】\n")
            partials.forEachIndexed { position, partial ->
                append("第 ").append(position + 1).append(" 段：\n").append(partial).append('\n')
            }
        }
        return summarizer.complete(REDUCE_SYSTEM, reduceUser)?.trim()?.takeIf { it.isNotEmpty() }
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

    companion object {
        /**
         * 单次补全能吃下的字幕字数上限。
         *
         * 实测：28 分钟的中文视频（AI 字幕 637 句 / 7730 字）加上逐行 `[mm:ss]` 前缀后约
         * 12.8k 字符，一次补全完全吃得下（约 9k~13k 输入 token）——所以阈值取 14000，让
         * 半小时以内的视频都只调一次模型，而不是分两段再合并（少 2 次往返、少一半延迟）。
         */
        const val CHUNK_CHARS = 14000

        /** 最多几段（配合 [CHUNK_CHARS] 约覆盖 2 万~4 万字的超长视频）：再多就不值得等了。 */
        const val MAX_CHUNKS = 4

        /**
         * 按行切块，**在行边界断开**（不切开一句话），最多 [MAX_CHUNKS] 段——超出的部分丢弃，
         * 但摘要头部已经写了视频总时长，用户追问后段时模型可以如实说"我只看了一部分"。
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
            "你在把一段视频字幕整理成要点，供另一个人转述给用户。\n" +
                "只依据字幕：字幕里没有的信息一律不写，不补充背景知识，不评价视频好坏，不推测作者动机。\n" +
                "输出格式（不要多余的开头结尾）：\n" +
                "一句话：<不超过 50 字，说清这个视频到底讲了什么>\n" +
                "要点：\n" +
                "- mm:ss <一条具体信息，不超过 40 字>\n" +
                "要点 3 到 6 条，时间戳必须取自字幕里出现过的标记。"

        private const val MAP_SYSTEM =
            "你在把视频字幕的其中一段整理成要点，供后续合并。\n" +
                "只依据本段字幕，不补充外部知识，不写引言和结论。\n" +
                "每行一条，格式：- mm:ss 内容（时间戳取自本段字幕标记，内容不超过 40 字），最多 6 条。"

        private const val REDUCE_SYSTEM =
            "你在把一个视频几段字幕的要点合并成最终要点，供另一个人转述给用户。\n" +
                "只用给出的要点，不新增信息。\n" +
                "输出格式：\n" +
                "一句话：<不超过 50 字>\n" +
                "要点：\n" +
                "- mm:ss <不超过 40 字>\n" +
                "最终 4 到 8 条，按时间顺序，去掉重复和琐碎细节。"
    }
}
