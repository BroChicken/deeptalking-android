package com.deeptalking.core.model

import com.deeptalking.core.common.AppLimits

/**
 * 基础设定字段的写作规则，**两条写入路径共用**（`:domain:agent` 的
 * `MemoryToolSupport.cleanFieldValue` 走模型/工具，`:feature:characters` 的
 * `FieldCleaning.cleanFieldValue` 走界面与建卡）。
 *
 * 规则只有两条，但都直接决定"设定会不会越写越长、越来越像 AI 作文"：
 *
 * 1. **只写明确的简短陈述句**。禁止「不是 A 而是 B」这类对比铺陈——它把一句设定写成
 *    两个分句的辩论，既占字数又容易让模型在后续轮次里继续围绕"不是/而是"打转。
 * 2. **按字段长度上限截断**（见 [AppLimits.StaticProfile]）：短字段几十字、叙述字段
 *    一百多字、背景故事放宽到两百多字。截断优先落在句子边界上，避免留下半句话。
 */
object StaticFieldRules {

    /** 「不是…而是…」家族：对比句。命中即拒绝写入，让模型改写成陈述句。 */
    private val CONTRAST_PATTERNS = listOf(
        Regex("不是[^，。；！？\\n]{1,40}，?\\s*而是"),
        Regex("并非[^，。；！？\\n]{1,40}，?\\s*而是"),
        Regex("不在于[^，。；！？\\n]{1,40}，?\\s*而在于"),
        Regex("与其说[^，。；！？\\n]{1,40}，?\\s*不如说"),
    )

    /** 句末标点，用于把截断落在句子边界上。 */
    private const val SENTENCE_END = "。！？；!?;"

    /**
     * 该值是否是对比句。只对基础设定字段生效（其它字段沿用各自原有规则），
     * 命中时返回原因，供工具回一条明确的错误让模型改写；否则返回 null。
     */
    fun contrastReason(key: String, value: String?): String? {
        if (AppLimits.StaticProfile.limitFor(key) == null) return null
        val text = value.orEmpty()
        if (text.isBlank()) return null
        return if (CONTRAST_PATTERNS.any { it.containsMatchIn(text) }) "对比句（不是…而是…）" else null
    }

    /**
     * 按字段上限截断。非基础设定字段原样返回（由调用方用自己的上限）。
     * 超长时优先后退到上限内的最后一个句末标点，找不到才硬截并补省略号。
     */
    fun clamp(key: String, value: String): String {
        val limit = AppLimits.StaticProfile.limitFor(key) ?: return value
        if (value.length <= limit) return value
        val head = value.take(limit)
        val lastEnd = head.indexOfLast { it in SENTENCE_END }
        return if (lastEnd >= 0) head.substring(0, lastEnd + 1) else head.dropLast(1).trimEnd() + "…"
    }
}
