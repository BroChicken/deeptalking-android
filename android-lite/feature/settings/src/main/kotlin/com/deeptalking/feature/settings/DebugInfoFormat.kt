package com.deeptalking.feature.settings

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.RequestMetric
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

private val TASK_LABELS = mapOf(
    "chat.main" to "主聊天",
    "chat.worker" to "后台处理",
    "chat" to "主聊天（旧）",
    "extraction" to "短期记忆生成",
    "analysis" to "长期记忆生成",
    "lorebook" to "世界书整理",
    "scene" to "场景概要",
    "auxiliary" to "辅助调用",
    "memory.extraction" to "短期记忆生成（旧）",
    "memory.analysis" to "长期记忆生成（旧）",
    "memory.scene" to "场景概要（旧）",
)

private val STATUS_LABELS = mapOf(
    "completed" to "完成",
    "failed" to "失败",
    "note" to "说明",
)

private val PREFIX_LABELS = mapOf(
    "first-request" to "首次请求",
    "instructions-changed" to "系统提示变化",
    "tools-changed" to "工具定义变化",
    "history-rebased" to "历史重排",
    "history-extended" to "历史延长",
)

private val SECTION_LABELS = mapOf(
    "time" to "时间",
    "state" to "当前状态",
    "unfinished" to "未完成事项",
    "lorebook" to "世界书",
    "scene" to "场景概要",
    "recall" to "记忆召回",
    "privateMemory" to "成员私人记忆",
    "writing" to "本轮写法",
    "topics" to "可选话题",
    "instruction" to "本轮指令",
    "timeline" to "消息时间",
    "static" to "静态设定",
    "history" to "历史",
    "tools" to "工具定义",
)

private val SECTION_ORDER = listOf(
    "time",
    "state",
    "unfinished",
    "lorebook",
    "scene",
    "recall",
    "privateMemory",
    "writing",
    "topics",
    "instruction",
    "timeline",
    "static",
    "history",
    "tools",
)

private val REASONING_LABELS = mapOf(
    "none" to "无",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "max" to "最高",
)

private val PLATFORM_LABELS = mapOf(
    "deepseek" to "DeepSeek",
    "opencode" to "OpenCode",
    "custom" to "自定义",
)

/** Human-readable debug report used by the Settings page. */
internal fun formatDebugInfo(config: AppConfig, metrics: List<RequestMetric>): List<String> = buildList {
    formatLastReply(config.lastReplyDebug)?.let { add(it) }
    if (metrics.isNotEmpty()) {
        val rows = metrics.takeLast(12)
        add("【最近请求与缓存统计（最近 ${rows.size} 条）】")
        rows.forEachIndexed { index, metric ->
            add(formatMetric(index + 1, metric))
        }
    }
}

private fun formatLastReply(raw: String): String? {
    if (raw.isBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return "【最近一次回应】\n数据格式无法解析"
    val lines = mutableListOf("【最近一次回应】")
    obj["at"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { lines += "时间：$it" }
    val cfg = obj["config"] as? JsonObject
    cfg?.get("model")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { lines += "模型：$it" }
    cfg?.get("reasoning")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
        lines += "思考强度：${REASONING_LABELS[it] ?: it}"
    }
    cfg?.get("stream")?.jsonPrimitive?.booleanOrNull?.let {
        lines += "流式：${if (it) "是" else "否"}"
    }
    obj["durationMs"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }?.let {
        lines += "耗时：${duration(it)}"
    }
    val quickReplies = obj["quickReplies"] as? JsonArray
    if (quickReplies != null && quickReplies.isNotEmpty()) {
        lines += "快速回应：${quickReplies.joinStrings(" / ")}"
    }
    return lines.joinToString("\n")
}

private fun formatMetric(index: Int, metric: RequestMetric): String {
    val lines = mutableListOf(
        "$index. ${metric.at.ifBlank { "时间未知" }} · ${taskLabel(metric.taskType)} · ${statusLabel(metric.status)}",
    )
    if (metric.phase.isNotBlank()) lines += "原因：${metric.phase}"
    metric.round?.let { lines += "轮次：第 $it 轮" }
    val modelAndPlatform = listOfNotNull(
        metric.model.takeIf { it.isNotBlank() }?.let { "模型：$it" },
        metric.platform.takeIf { it.isNotBlank() }?.let { "平台：${PLATFORM_LABELS[it] ?: it}" },
    )
    if (modelAndPlatform.isNotEmpty()) lines += modelAndPlatform.joinToString(" · ")
    metric.durationMs?.let { lines += "耗时：${duration(it)}" }
    lines += "输入：${tokens(metric.inputTokens)} · 输出：${tokens(metric.outputTokens)}"
    lines += "缓存：命中 ${tokens(metric.hitTokens)} · 未命中 ${tokens(metric.missTokens)} · 命中率 ${rate(metric.hitRate)}"
    if (metric.contextChars != null || metric.inputChars != null) {
        lines += "上下文：${chars(metric.contextChars)} · 输入字符：${chars(metric.inputChars)}"
    }
    metric.prefixChange?.takeIf { it.isNotBlank() }?.let {
        lines += "前缀变化：${PREFIX_LABELS[it] ?: it}"
    }
    metric.commonHistoryMessages?.let { lines += "共同历史：$it 条" }
    lines += sections(metric.sectionChars)
    return lines.joinToString("\n")
}

private fun sections(value: Map<String, Int>?): String {
    if (value.isNullOrEmpty()) return "分段：未记录"
    val ordered = SECTION_ORDER.mapNotNull { key -> value[key]?.let { key to it } } +
        value.filterKeys { it !in SECTION_ORDER }
            .toSortedMap()
            .map { it.key to it.value }
    val total = value.values.sum()
    return "分段：${ordered.joinToString(" · ") { (key, count) -> "${SECTION_LABELS[key] ?: key} ${count}" }} · 总计 $total 字符"
}

private fun taskLabel(value: String): String = TASK_LABELS[value] ?: value.ifBlank { "未知" }

private fun statusLabel(value: String): String = STATUS_LABELS[value] ?: value.ifBlank { "未知" }

private fun tokens(value: Int?): String = value?.let(::compact) ?: "未返回"

private fun chars(value: Int?): String = value?.let { "$it 字符" } ?: "未记录"

private fun rate(value: Double?): String =
    value?.let { "${Math.round(it * 100.0).toInt()}%" } ?: "未返回"

private fun duration(value: Long): String =
    if (value >= 1_000) String.format(Locale.US, "%.1f 秒", value / 1_000.0) else "$value 毫秒"

private fun compact(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun JsonArray.joinStrings(separator: String): String =
    mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { text -> text.isNotBlank() } }
        .joinToString(separator)
