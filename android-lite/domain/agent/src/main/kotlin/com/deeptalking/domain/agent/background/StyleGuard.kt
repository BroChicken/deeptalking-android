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
    const val anchorSamples = 3
    const val reusePhraseChars = 8
    const val critiqueMaxChars = 1600
    const val critiqueMinRatio = 0.5
    const val critiqueMaxRatio = 2.0

    /** 近轮重复检测：看最近几条回复。 */
    const val repeatLookbackReplies = 3

    /** 至少要在几条回复里都出现，才算“模板化重复”。 */
    const val repeatMinReplies = 2

    /** 重复片段的长度范围（连续汉字个数）。2 字太容易误伤，7 字以上几乎不会重复。 */
    const val repeatSpanMin = 3
    const val repeatSpanMax = 6

    /** 一次最多点名几个重复片段。 */
    const val repeatPhraseLimit = 3
}

object QUICK_REPLY_GUARD {
    const val maxChars = 60
    const val mirrorChars = 8
    const val repairMaxChars = 40
    /**
     * How many of the character's own previous replies the `mirrored` check compares
     * against. A quick reply that echoes anything the character just said is the
     * character's line, not the user's — seeing only this turn's reply missed the
     * common case of parroting the turn before.
     */
    const val mirrorLookback = 3
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
    "toneDrift" to "句长过于整齐、缺少长短起伏（读起来像模板，不像人随口说的）",
    "recitedLore" to "复述了世界书或设定条目本身，应当只在需要时自然引用",
)

/**
 * 「只记录、不处置」的正文格式观察项：命中后照样写进 `ChatMessage.styleViolations`（统计与
 * 排查用），但**不**进入文风修订（`critiqueStyle` 会为它多打一次模型调用，而引用保护常把那次
 * 修订整体回退成白跑），也不进"上一轮需要纠正"的提醒。要把它提成"可执行"的问题时，移回
 * [STYLE_VIOLATION_LABELS] 即可（[actionableStyleViolations] 会自动带上它）。
 */
val STYLE_ADVISORY_LABELS: Map<String, String> = mapOf(
    "halfwidthQuote" to "引用别人的话语请用「」『』《》，不要用成对的半角引号（\" \" 或 ' '）——半角引号会和正文的标点、缩写混在一起，读起来分不清哪句是引用",
    "thirdPersonSelf" to "用第一人称说话：直接说\"我\"，不要用第三人称提到自己（如\"小雨皱了皱眉\"），也不要在对白之外写旁白叙述自己——那是在替别人介绍你，不是你本人在说话",
)

/** 只保留"可执行"（[STYLE_VIOLATION_LABELS]）的问题：观察项不得触发文风修订调用。 */
fun actionableStyleViolations(violations: List<String>): List<String> =
    violations.filter { STYLE_VIOLATION_LABELS.containsKey(it) }

val QUICK_REPLY_ISSUE_LABELS: Map<String, String> = mapOf(
    "missing" to "没有给出两条可用短句",
    "placeholder" to "把示例占位文字（如\"短句一/短句二\"）当成了真内容",
    "action" to "带了括号动作或旁白（快速回应只写用户会打的字）",
    "mirrored" to "复述或镜像了角色刚说过的话（比对本轮回复与角色最近几轮回复）",
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

/** Descriptive part of a speaking style, before the "示例" clause. */
private fun speakingStyleSummary(style: String): String {
    val idx = style.indexOf("示例")
    val head = if (idx >= 0) style.substring(0, idx) else style
    return trimText(head.trim().trimEnd('；', ';', '，', ','), STYLE_GUARD.anchorStyleChars)
}

/**
 * Per-character voice contract, placed last in the volatile context. It names
 * the signature markers (口癖/句尾/称呼/标点) the reply must surface and forbids
 * the model's default assistant voice, so different characters stop sounding
 * alike. A blank speaking style contributes nothing (自动补全字段 later fills it).
 *
 * **未接线**：生产路径不再调用它——尾部现在只发 `Prompts.voiceSpec()` 的声音底色摘要
 * （不发示例台词），通用语域规则在 `RULE_2`（原第 7 条已并入）。保留这里是因为它包含"只借语气、
 * 不要照抄字面"的措辞，可作为以后重写契约的参考；如果决定彻底不用，连同
 * `buildGroupVoiceContract` / `buildStyleReview` / `buildStyleCorrectionReminder` 一起删。
 */
fun buildStyleAnchor(character: Character?): String {
    val profile = character?.staticProfile ?: return ""
    val style = profile.speakingStyle
    if (style.isBlank()) return ""
    val samples = extractSpeakingSamples(style).take(STYLE_GUARD.anchorSamples)
    val summary = speakingStyleSummary(style)
    val address = normalizeUserAddress(profile.userAddress)
    val lines = mutableListOf<String>()
    lines += "【本轮声音契约（离生成最近，优先级高于一切风格偏好）】"
    if (summary.isNotEmpty()) lines += "声音底色：$summary"
    if (samples.isNotEmpty()) {
        lines += "照此口吻说话（只借用语气与用词，不要照抄内容，也不要照搬其中的引号或标点）：" +
            samples.joinToString(" / ")
    }
    if (address.isNotEmpty()) lines += "对用户的称呼：$address"
    lines += "本轮必须：用你自己的口吻说话；至少自然带出你的口头禅、句尾助词、称呼共 2 处，并保持你的标点习惯；" +
        "**禁止通用书面语与默认 AI 助手腔，不得把语气中和成标准普通话**；不复述设定、不解释自己在怎么做；" +
        "不替用户说话或行动；收尾不与上一轮雷同。"
    return lines.joinToString("\n") + "\n\n"
}

/**
 * Voice contract for a group turn: one block per member that actually has a
 * speaking style, so each member's lines keep their own voice. Members with a
 * blank style are skipped (field completion fills them in later); the number of
 * blocks scales with the member count -- there is no cap.
 */
fun buildGroupVoiceContract(character: Character?): String {
    if (character == null || !character.isGroup) return ""
    val blocks = character.members.mapNotNull { member ->
        val style = member.staticProfile.speakingStyle
        if (style.isBlank()) return@mapNotNull null
        val samples = extractSpeakingSamples(style).take(STYLE_GUARD.anchorSamples)
        val summary = speakingStyleSummary(style)
        val address = normalizeUserAddress(member.staticProfile.userAddress)
        val lines = mutableListOf<String>()
        lines += "【成员声音·${member.name.trim()}】"
        if (summary.isNotEmpty()) lines += "声音底色：$summary"
        if (samples.isNotEmpty()) {
            lines += "照此口吻说话（只借用语气与用词，不要照抄内容，也不要照搬其中的引号或标点）：" +
                samples.joinToString(" / ")
        }
        if (address.isNotEmpty()) lines += "对用户的称呼：$address"
        lines += "该成员本轮台词必须体现以上口头禅、句尾助词与称呼，且与其他成员明显不同。"
        lines.joinToString("\n")
    }
    if (blocks.isEmpty()) return ""
    val header = "【群聊声音锁定（离生成最近，优先级高于一切风格偏好）】\n" +
        "群内每位成员的语气必须显著不同——同一句话换到另一个人嘴里要像另一个人说的；" +
        "不得让任何成员的语气向默认 AI 助手腔靠拢，也不得让成员之间互换语气。"
    return header + "\n" + blocks.joinToString("\n") + "\n\n"
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

/**
 * 最近几条回复里反复出现的短片段（3~6 个连续汉字），用来生成【本轮写法】的「本轮别用」。
 *
 * 这是**反模板**的确定性闭环：底层规则要求“近三轮用过的词句不再复用”，这里把真正被
 * 复用的那几段挑出来点名，比抽象地说“别重复”有效。只取连续汉字片段——2 字太容易误伤
 * （“我们”“不知”），7 字以上几乎不会真的重复；[exclude] 里的角色名与称呼一律跳过，
 * 免得把名字当成口头禅。
 *
 * 返回顺序：长片段优先（信息量更大），同长度按出现在几条回复里降序。
 */
fun repeatedPhrasesIn(
    replies: List<String>,
    limit: Int = STYLE_GUARD.repeatPhraseLimit,
    lookback: Int = STYLE_GUARD.repeatLookbackReplies,
    exclude: List<String> = emptyList(),
): List<String> {
    if (limit <= 0) return emptyList()
    val texts = replies.filter { it.isNotBlank() }.takeLast(lookback).map { it.replace(Regex("\\s+"), "") }
    if (texts.size < STYLE_GUARD.repeatMinReplies) return emptyList()
    val excluded = exclude.filter { it.isNotBlank() }
    val counts = mutableMapOf<String, Int>()
    for (span in STYLE_GUARD.repeatSpanMax downTo STYLE_GUARD.repeatSpanMin) {
        val seenIn = mutableMapOf<String, MutableSet<Int>>()
        texts.forEachIndexed { index, text ->
            if (text.length < span) return@forEachIndexed
            for (i in 0..text.length - span) {
                val gram = text.substring(i, i + span)
                if (!gram.all { it in '\u4e00'..'\u9fff' }) continue
                if (excluded.any { it.contains(gram) }) continue
                seenIn.getOrPut(gram) { mutableSetOf() }.add(index)
            }
        }
        seenIn.forEach { (gram, indices) ->
            if (indices.size >= STYLE_GUARD.repeatMinReplies) counts[gram] = indices.size
        }
    }
    if (counts.isEmpty()) return emptyList()
    val ordered = counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.key.length }.thenByDescending { it.value })
        .map { it.key }
    val picked = mutableListOf<String>()
    for (candidate in ordered) {
        if (picked.size >= limit) break
        // 已被更长片段覆盖的短片段不再单独点名（命中“我不知道”后不再报“不知道”）。
        if (picked.any { it.contains(candidate) }) continue
        picked += candidate
    }
    return picked
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
    val labels = collectRecentStyleViolations(character, STYLE_GUARD.lookbackReplies)
    val lines = mutableListOf<String>()
    lines += "【语气回顾（每 ${STYLE_GUARD.reviewEveryTurns} 轮一次，内部提醒）】"
    lines += "语气永远以角色设定的性格与声音底色为准，优先级高于你的默认文风与最近的写法。"
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
 * 成对的半角引号：同一个引号字符出现 ≥2 次。单个不配对的不算（避免 `don't` 这类缩写
 * 只有一个 `'` 时被误报）。成对的半角引号是"引用话语"的写法问题（该用 `「」《》`）；
 * 这里只统计，绝不改写回复。
 */
private fun hasPairedHalfwidthQuotes(text: String): Boolean =
    text.count { it == '"' } >= 2 || text.count { it == '\'' } >= 2

/**
 * 包裹「引用/说明」的成对符号：直角引号、书名号、弯引号，以及圆括号（全角与半角）。
 * 与 [PAIRED_SPANS] 的区别只在于这里还要算上圆括号（舞台说明）。
 */
private val WRAPPED_SPANS = Regex("「[^」]*」|『[^』]*』|《[^》]*》|〈[^〉]*〉|“[^”]*”|‘[^’]*’|（[^）]*）|\\([^)]*\\)")

/**
 * [name] 是否在 [text] 里出现过，且至少有一次出现在所有引号/书名号/括号**之外**。
 *
 * 被包裹的名字是在引用别人的话（`他说「小雨，你先坐」`）或写舞台说明，不算第三人称自称；
 * 只有"光天化日之下"以第三人称提到自己（`小雨把外套搭在椅背上`）才命中。
 *
 * 注意包裹符的集合就是 [WRAPPED_SPANS] 列出的那些（直角引号/书名号/弯引号/圆括号）：
 * 半角 `"` 会"孤立引号吞掉大段正文"，把它算进来反而会掩盖真实命中，所以半角引号只由
 * [hasPairedHalfwidthQuotes] 单独统计，不参与这里的包裹判定。
 */
private fun mentionsNameOutsideWrapping(text: String, name: String): Boolean {
    val spans = WRAPPED_SPANS.findAll(text).map { it.range }.toList()
    var index = text.indexOf(name)
    while (index >= 0) {
        val end = index + name.length - 1
        if (spans.none { index >= it.first && end <= it.last }) return true
        index = text.indexOf(name, index + 1)
    }
    return false
}

/**
 * Client-side style violation detection (port of detectStyleViolations in style.js).
 * Only the high-signal checks are kept; every hit has a low false-positive cost.
 *
 * The reply is **never** rewritten or dropped here: a hit is only recorded on the
 * assistant turn (`styleViolations`) and reported by the review / correction reminder.
 *
 * This is the single implementation — the non-null overload in `BackgroundTasks.kt`
 * delegates to [detectStyleViolationsFor], so both entry points record the same keys.
 */
fun detectStyleViolations(reply: String?, character: Character?, prevReplies: List<String>): List<String> =
    detectStyleViolationsFor(reply, character, prevReplies)

/** Shared body of [detectStyleViolations]. */
internal fun detectStyleViolationsFor(reply: String?, character: Character?, prevReplies: List<String>): List<String> {
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

    // 正文格式（只统计，不改写）：成对的半角引号不算"引用话术"，第三人称自称/旁白
    // 会让角色听起来像是在介绍自己而不是在说话。
    if (hasPairedHalfwidthQuotes(text)) hits += "halfwidthQuote"
    val selfName = character?.name?.trim().orEmpty()
    if (character?.isGroup == false && selfName.length >= 2 &&
        mentionsNameOutsideWrapping(text, selfName)
    ) {
        hits += "thirdPersonSelf"
    }

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

// ---------------------------------------------------------------------------
// 引用保护（文风修订不得改动"内容"）
// ---------------------------------------------------------------------------

/**
 * 「引用的话语」的各种包裹符：中文直角引号、书名号，以及英文/弯引号。
 *
 * 为什么单独抽出来：文风修订（`critiqueStyle`）只该动**风格**，而引用段落、歌词、
 * 书名、专有名词是**内容**——它们被改写就等于篡改了别人的原话或作品名。这里提供
 * 确定性的比对依据，而不是只靠一句"请保留引用"的提示词。
 *
 * 开闭不同的包裹符（「」『』《》〈〉“”‘’）天然成对，一个孤立的开符最多只是配不上，
 * 不会把中间大段正文吞进同一个片段，所以直接用正则取。
 */
private val PAIRED_SPANS = Regex(
    "「[^」]*」|『[^』]*』|《[^》]*》|〈[^〉]*〉|“[^”]*”|‘[^’]*’",
)

/**
 * 开闭同形的引号（`"` 与 `'`）：孤立出现时会把"后面很远的下一个引号"当成闭合，
 * 于是把中间整段正文吞成一个"引用片段"——修一点点就整条回退的误判来源。
 * 这类引号改为：整段只有一对时整对保护，多于一对此才**同一句内**配对（见 [ambiguousQuoteSpans]）。
 */
private const val AMBIGUOUS_QUOTES = "\"'"

/** 句子边界：同形引号不跨句配对。与 [extractReplyEnding] 的句末标点保持一致，外加换行。 */
private const val SENTENCE_TERMINATORS = "。！？!?…\n"

/** 紧跟在句末标点之后、仍属于上一句的收尾符（如 `"早点睡。"` 的闭合引号、`)`、`】`）。 */
private const val TRAILING_CLOSERS = "\"'”’）)】]"

/**
 * 取出同形引号（`"` `'`）的成对片段。
 *
 * - 某字符在整段文本里只出现**两次**：那就是一对完整的引号，允许跨句配对——`他说"我来了。
 *   你等我。"` 整段都是被引用的原话，必须整段保护。
 * - 出现三次及以上：孤立引号会把"后面很远的下一个引号"当成闭合，于是把中间整段正文吞成
 *   一个"引用片段"（修一点点就整条回退的误判来源）。这时退回**同一句内**按出现顺序两两
 *   配对：句子结束时（句末标点后不是收尾符）句内没配上的引号直接作废，绝不跨句吞并。
 *
 * 例：`我回了一句"好。 他说："早点睡。"` —— 前两个引号分别落在两句里（都未配对，作废），
 * 只有同一句内的 `"早点睡。"` 成为保护片段。
 */
private fun ambiguousQuoteSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    val singlePairs = AMBIGUOUS_QUOTES.filter { quote -> text.count { ch -> ch == quote } == 2 }
    for (quote in singlePairs) {
        val first = text.indexOf(quote)
        val last = text.lastIndexOf(quote)
        if (first in 0 until last) spans += first..last
    }
    val open = mutableMapOf<Char, Int>()
    text.forEachIndexed { index, ch ->
        // 整段只有一对的引号已在上面按对处理，不再参与"同句配对"。
        if (ch in singlePairs) return@forEachIndexed
        when {
            ch in AMBIGUOUS_QUOTES -> {
                val start = open.remove(ch)
                if (start == null) open[ch] = index else spans += start..index
            }

            ch in SENTENCE_TERMINATORS && !endsClauseWithTrailingCloser(text, index) -> open.clear()
        }
    }
    return spans
}

/** [index] 处的句末标点后面（跳过连续句末标点）紧跟收尾符时，视为同一句的收尾，不切句。 */
private fun endsClauseWithTrailingCloser(text: String, index: Int): Boolean {
    var next = index + 1
    while (next < text.length && text[next] in SENTENCE_TERMINATORS) next++
    return next < text.length && text[next] in TRAILING_CLOSERS
}

/** 按出现顺序取出 [text] 里所有被引号/书名号包裹的引用段落。 */
fun quotedSpans(text: String?): List<String> {
    val value = text.orEmpty()
    if (value.isEmpty()) return emptyList()
    val spans = mutableListOf<IntRange>()
    PAIRED_SPANS.findAll(value).forEach { spans += it.range }
    spans += ambiguousQuoteSpans(value)
    return spans.sortedBy { it.first }.map { value.substring(it.first, it.last + 1) }
}

/**
 * 修订稿是否**完整保留了**原文里的引用段落与受保护的专有名词。
 *
 * 任一引用段落被改写、增删或换序，或某个专有名词（世界书条目名、角色/成员名、
 * 对用户的称呼）在原文里出现却在修订稿里消失，都判为不合格 —— 调用方据此
 * **整体回退**到原文（不做局部修补：局部修补会留下半截被改写的引用）。
 *
 * 引用片段的切分见 [quotedSpans]：开闭同形的 `"` `'` 在整段只有一对时整对保护，存在多于一对
 * 时才只在同一句内配对——所以原文里一个孤立引号不会把相邻大段正文并成一个"引用"、让极小改写
 * 被误判成整条回退；而真正被引号包住的段落（含跨句引用）一旦被改写或删除，仍然照旧回退。
 */
fun keepsProtectedContent(original: String, revised: String, protectedNames: List<String>): Boolean {
    if (quotedSpans(original) != quotedSpans(revised)) return false
    return protectedNames
        .filter { it.isNotBlank() }
        .all { name -> !original.contains(name) || revised.contains(name) }
}
