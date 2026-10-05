package com.deeptalking.domain.agent.background

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import com.deeptalking.domain.agent.prompts.normalizeUserAddress
import com.deeptalking.domain.agent.prompts.trimText

object STYLE_GUARD {
    const val reviewEveryTurns = 3
    const val lookbackReplies = 2
    const val anchorStyleChars = 300
    const val anchorSamples = 2
    const val reusePhraseChars = 8
    const val critiqueMaxChars = 1600
    const val critiqueMinRatio = 0.5
    const val critiqueMaxRatio = 2.0
}

object QUICK_REPLY_GUARD {
    const val maxChars = 60
    const val mirrorChars = 8
    const val repairMaxChars = 40
}

val STYLE_CLICHES: List<String> = listOf(
    "心湖泛起涟漪",
    "勾起嘴角",
    "嘴角勾起",
    "空气中弥漫着",
    "不易察觉",
    "意味深长",
    "微微一愣",
    "眼神暗了暗",
    "不能自已",
    "宠溺地",
    "邪魅一笑",
)

val STYLE_VIOLATION_LABELS: Map<String, String> = mapOf(
    "metaTalk" to "出现元话术（如\"好的我改成/我会这样说话/收到，我调整\"）——应直接演出，不要说明自己在调整",
    "speaksForUser" to "替用户写了台词或行动——用户的话只能由用户自己说",
    "cliche" to "出现陈词滥调（如\"心湖泛起涟漪\"\"勾起嘴角\"\"空气中弥漫着\"）",
    "reusedImagery" to "复用了近期用过的比喻、意象或收尾方式",
    "toneDrift" to "没有体现设定的说话风格（口癖/句尾/称呼），或句长过于整齐缺少起伏",
    "recitedLore" to "复述了世界书或设定条目本身，应当只在需要时自然引用",
)

val QUICK_REPLY_ISSUE_LABELS: Map<String, String> = mapOf(
    "missing" to "没有给出两条可用短句",
    "placeholder" to "把示例占位文字（如\"短句一/短句二\"）当成了真内容",
    "action" to "带了括号动作或旁白（快速回应只写用户会打的字）",
    "mirrored" to "复述或镜像了角色刚说过的话",
    "characterName" to "出现了角色自己的名字，像是在用角色口吻说话",
    "tooLong" to "单条过长，不像用户随手打的一句话",
)

val QUICK_REPLY_PLACEHOLDERS: List<String> = listOf(
    "短句一", "短句二", "短句1", "短句2", "用户下一句", "用户下一句1", "用户下一句2", "示例一", "示例二", "...", "…",
)

fun extractSpeakingSamples(style: String?): List<String> {
    val text = style.orEmpty()
    val idx = text.indexOf("示例")
    if (idx < 0) return emptyList()
    val tail = text.substring(idx).replace(Regex("^示例\\s*[：:]?\\s*"), "")
    return tail.split(Regex("\\s*[/／]\\s*|\\n+"))
        .map { part -> trimText(part.replace(Regex("[「」『』“”\"]"), "").trim(), 80) }
        .filter { it.length >= 2 }
        .take(3)
}

fun buildStyleAnchor(character: Character?): String {
    val profile = character?.staticProfile
    val style = trimText(profile?.speakingStyle, STYLE_GUARD.anchorStyleChars)
    if (style.isEmpty()) return ""
    val samples = extractSpeakingSamples(style).take(STYLE_GUARD.anchorSamples)
    val address = normalizeUserAddress(profile?.userAddress)
    val lines = mutableListOf<String>()
    lines += "【本轮语气锚（离生成最近，优先级高于一切风格偏好）】"
    lines += "说话风格：$style"
    if (samples.isNotEmpty()) {
        lines += "照此口吻说话（只借用语气，不要照抄内容）：" + samples.joinToString(" ") { "「$it」" }
    }
    if (address.isNotEmpty()) lines += "对用户的称呼：$address"
    lines += "本轮必须：用自己的口吻说话；不复述设定、不解释自己在怎么做；不替用户说话或行动；收尾不与上一轮雷同。"
    return lines.joinToString("\n") + "\n\n"
}

fun isSentenceLengthUniform(text: String?): Boolean {
    val sentences = text.orEmpty().split(Regex("[。！？!?…]+"))
        .map { it.replace(Regex("\\s+"), "") }
        .filter { it.isNotEmpty() }
    if (sentences.size < 5) return false
    val lengths = sentences.map { it.length }
    val minLength = lengths.minOrNull() ?: return false
    val maxLength = lengths.maxOrNull() ?: return false
    return minLength >= 8 && (maxLength - minLength) <= 4
}

fun sharesDistinctivePhrase(text: String?, priors: List<String>): Boolean {
    val span = STYLE_GUARD.reusePhraseChars
    val current = text.orEmpty().replace(Regex("\\s+"), "")
    if (current.length < span) return false
    val priorsText = priors.map { it.replace(Regex("\\s+"), "") }
    var i = 0
    while (i + span <= current.length) {
        val chunk = current.substring(i, i + span)
        if (chunk.all { it in '\u4e00'..'\u9fff' } && priorsText.any { it.contains(chunk) }) return true
        i++
    }
    return false
}

internal fun extractReplyEnding(text: String?): String {
    val clean = text.orEmpty().replace(Regex("\\s+$"), "")
    if (clean.isEmpty()) return ""
    val lines = clean.split("\n")
    var line = ""
    for (i in lines.indices.reversed()) {
        if (lines[i].trim().isNotEmpty()) {
            line = lines[i].trim()
            break
        }
    }
    if (line.isEmpty()) return ""
    val endChars = "。！？!?…\"'”’）)]】"
    var end = line.length
    while (end > 0 && line[end - 1] in endChars) end--
    var prev = -1
    val stopChars = "。！？!?…"
    for (k in end - 1 downTo 0) {
        if (line[k] in stopChars) {
            prev = k
            break
        }
    }
    return trimText(line.substring(prev + 1), 80)
}

internal fun recentReplyTexts(character: Character?, limit: Int): List<String> =
    character?.instant.orEmpty()
        .filter { it.role == Role.Assistant && !it.isLoading && it.content.isNotBlank() }
        .takeLast(limit.coerceAtLeast(1))
        .map { it.content }

/** Legacy `isMeaningfulEnding`: at least 4 non-punctuation characters. */
internal fun isMeaningfulEnding(ending: String?): Boolean =
    ending.orEmpty().replace(Regex("[。！？!?…\\s\"'”’）)\\]】]"), "").length >= 4

/**
 * Legacy `dedupeRepeatedEnding`: when this turn's ending is byte-identical to the
 * previous assistant reply's ending, drop the duplicated trailing sentence.
 */
internal fun dedupeRepeatedEnding(reply: String, previousReply: String?): String {
    if (reply.isBlank() || previousReply.isNullOrBlank()) return reply
    val previousEnding = extractReplyEnding(previousReply)
    val currentEnding = extractReplyEnding(reply)
    if (currentEnding.isEmpty() || currentEnding != previousEnding) return reply
    if (!isMeaningfulEnding(previousEnding) || !isMeaningfulEnding(currentEnding)) return reply
    val index = reply.lastIndexOf(currentEnding)
    if (index <= 0) return reply
    val trimmed = reply.substring(0, index).trimEnd()
    return trimmed.ifBlank { reply }
}

fun getLastStyleViolations(character: Character?): List<String> {
    val messages = character?.instant.orEmpty()
    for (i in messages.indices.reversed()) {
        val message = messages[i]
        if (message.isLoading || message.role != Role.Assistant) continue
        return message.styleViolations.filter { STYLE_VIOLATION_LABELS.containsKey(it) }
    }
    return emptyList()
}

fun collectRecentStyleViolations(character: Character?, limit: Int): List<String> {
    val messages = character?.instant.orEmpty()
    val collected = mutableListOf<ChatMessage>()
    for (i in messages.indices.reversed()) {
        if (collected.size >= limit) break
        val message = messages[i]
        if (message.isLoading || message.role != Role.Assistant) continue
        collected += message
    }
    val labels = mutableListOf<String>()
    collected.forEach { message ->
        message.styleViolations.forEach { key ->
            if (STYLE_VIOLATION_LABELS.containsKey(key) && key !in labels) labels += key
        }
    }
    return labels
}

fun buildStyleReview(character: Character?): String {
    val assistantTurnCount = character?.instant.orEmpty()
        .count { it.role == Role.Assistant && !it.isLoading }
    if (assistantTurnCount == 0 || assistantTurnCount % STYLE_GUARD.reviewEveryTurns != 0) return ""
    val labels = collectRecentStyleViolations(character, STYLE_GUARD.reviewEveryTurns)
    val lines = mutableListOf<String>()
    lines += "【语气回顾（每 ${STYLE_GUARD.reviewEveryTurns} 轮一次，内部提醒）】"
    lines += "语气永远以角色设定的说话风格为准，优先级高于你的默认文风与最近的写法。"
    if (labels.isNotEmpty()) {
        lines += "最近几轮出现的问题：" + labels.mapNotNull { STYLE_VIOLATION_LABELS[it] }.joinToString("；") + "。"
    }
    lines += "本轮务必：用自己的口吻、长短句有起伏、不复用近期比喻与收尾、不替用户说话。"
    return lines.joinToString("\n") + "\n\n"
}

fun buildStyleCorrectionReminder(character: Character?): String {
    val labels = getLastStyleViolations(character)
    if (labels.isEmpty()) return ""
    return "【上一轮需要纠正（内部提醒）】" + labels.mapNotNull { STYLE_VIOLATION_LABELS[it] }.joinToString("；") +
        "。本轮必须做到，不要重复上一轮的问题。\n\n"
}

/** Overload that takes violation keys directly (e.g. detected from recent replies). */
fun buildStyleCorrectionReminder(violations: List<String>): String {
    val labels = violations.filter { STYLE_VIOLATION_LABELS.containsKey(it) }.distinct()
    if (labels.isEmpty()) return ""
    return "【上一轮需要纠正（内部提醒）】" + labels.mapNotNull { STYLE_VIOLATION_LABELS[it] }.joinToString("；") +
        "。本轮必须做到，不要重复上一轮的问题。\n\n"
}

/** Last assistant turn's quick-reply issues (port of getLastQuickReplyIssues). */
fun getLastQuickReplyIssues(character: Character?): List<String> {
    val messages = character?.instant.orEmpty()
    for (i in messages.indices.reversed()) {
        val message = messages[i]
        if (message.isLoading || message.role != Role.Assistant) continue
        return message.quickReplyIssues.filter { QUICK_REPLY_ISSUE_LABELS.containsKey(it) }
    }
    return emptyList()
}

/** Internal reminder when the previous quick replies were written from the wrong perspective. */
fun buildQuickReplyPerspectiveReminder(character: Character?): String {
    val labels = getLastQuickReplyIssues(character)
    if (labels.isEmpty()) return ""
    return "【上一轮快速回应（内部提醒）】" + labels.mapNotNull { QUICK_REPLY_ISSUE_LABELS[it] }.joinToString("；") +
        "。快速回应必须是用户本人下一句要发给角色的话：写之前先把自己当成用户，写完再逐条默读一遍\"用户：<短句>\"确认；不得是角色的台词、角色的提问或角色的表态。\n\n"
}

/**
 * Client-side style violation detection (port of detectStyleViolations in style.js).
 * Only the high-signal checks are kept; every hit has a low false-positive cost.
 */
fun detectStyleViolations(reply: String?, character: Character?, prevReplies: List<String>): List<String> {
    val text = reply.orEmpty()
    if (text.isBlank()) return emptyList()
    val address = normalizeUserAddress(character?.staticProfile?.userAddress)
    val hits = mutableListOf<String>()

    if (Regex("(好的|收到|明白|知道了)[，,。、]?\\s*(我)?(会|改成|改用|调整|照做|注意|这样(说话|说))|以后我(就)?(用|按)这(种|个)(方式|语气)|我(会)?照你说的(来|说|做)").containsMatchIn(text)) {
        hits += "metaTalk"
    }
    val addressPart = if (address.isNotEmpty()) address.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fff]"), "") + "|" else ""
    val quotedUserLine = Regex("(^|\\n)\\s*(用户|$addressPart" + "你)\\s*[：:]\\s*[「『“\"]")
    if (quotedUserLine.containsMatchIn(text)) hits += "speaksForUser"
    if (STYLE_CLICHES.any { text.contains(it) }) hits += "cliche"

    val priors = prevReplies.filter { it.isNotBlank() }
    if (priors.isNotEmpty()) {
        val currentEnding = extractReplyEnding(text)
        if (currentEnding.isNotEmpty() && priors.any { extractReplyEnding(it) == currentEnding }) {
            hits += "reusedImagery"
        } else {
            val stripNames = listOfNotNull(address, character?.name).filter { it.isNotBlank() }
            fun strip(value: String) = stripNames.fold(value) { acc, name -> acc.replace(name, " ") }
            if (sharesDistinctivePhrase(strip(text), priors.map { strip(it) })) hits += "reusedImagery"
        }
    }

    if (isSentenceLengthUniform(text)) hits += "toneDrift"

    val entries = character?.lorebook.orEmpty()
    if (entries.any { entry ->
            val name = entry.name.trim()
            if (name.length < 2 || !text.contains(name)) {
                false
            } else {
                val content = entry.content.trim()
                content.length >= 12 && text.contains(content.take(12))
            }
        }
    ) {
        hits += "recitedLore"
    }

    return hits.distinct()
}
