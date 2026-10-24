package com.deeptalking.domain.agent.prompts

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.model.Todo
import com.deeptalking.core.model.TodoStatus
import com.deeptalking.core.model.extractSpeechSegments
import com.deeptalking.domain.agent.background.STYLE_GUARD
import com.deeptalking.domain.agent.background.detectStyleViolations
import com.deeptalking.domain.agent.background.extractReplyEnding
import com.deeptalking.domain.agent.background.extractSpeakingSamples
import com.deeptalking.domain.agent.background.getLastStyleViolations
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.domain.memory.formatAbsoluteDate
import com.deeptalking.domain.memory.logicalDay
import com.deeptalking.domain.memory.parseZoned
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.ToolChoice
import com.deeptalking.engine.ondevice.ToolDefinition
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Prompt assembly ported from `src/js/prompts/{context,request,volatile}.js`.
 * Pure string building except [buildVolatileContext], which needs memory.
 */

/** Static profile fields in the exact legacy order (`STATIC_PROFILE_FIELDS`). */
internal val STATIC_PROFILE_FIELDS: List<Pair<String, String>> = listOf(
    "gender" to "性别",
    "age" to "年龄",
    "race" to "种族",
    "appearance" to "外貌特征",
    "personality" to "性格特征",
    "fears" to "恐惧/弱点",
    "background" to "个人背景",
    "speakingStyle" to "说话风格",
    "language" to "语言/方言",
    "userAddress" to "对用户的称呼",
)

/** Dynamic state fields in the exact legacy order (`DYNAMIC_STATE_FIELDS`). */
internal val DYNAMIC_STATE_FIELDS: List<Pair<String, String>> = listOf(
    "currentSituation" to "当前处境",
    "currentLocation" to "当前位置/场景",
    "currentMood" to "当前情绪",
    "currentOccupation" to "当前职业/身份",
    "currentGoal" to "当前目标",
    "currentTone" to "当前语气",
)

/** Group entities only keep these two shared dynamic fields. */
internal val GROUP_SHARED_DYNAMIC_FIELDS: Set<String> = setOf("currentSituation", "currentLocation")

internal fun staticFieldKey(label: String): String? =
    STATIC_PROFILE_FIELDS.firstOrNull { it.second == label }?.first

internal fun staticFieldLabel(key: String): String? =
    STATIC_PROFILE_FIELDS.firstOrNull { it.first == key }?.second

internal fun staticProfileValue(profile: StaticProfile, key: String): String = when (key) {
    "gender" -> profile.gender
    "age" -> profile.age
    "race" -> profile.race
    "appearance" -> profile.appearance
    "personality" -> profile.personality
    "fears" -> profile.fears
    "background" -> profile.background
    "speakingStyle" -> profile.speakingStyle
    "language" -> profile.language
    "userAddress" -> profile.userAddress
    else -> ""
}

internal fun withStaticField(profile: StaticProfile, key: String, value: String): StaticProfile = when (key) {
    "gender" -> profile.copy(gender = value)
    "age" -> profile.copy(age = value)
    "race" -> profile.copy(race = value)
    "appearance" -> profile.copy(appearance = value)
    "personality" -> profile.copy(personality = value)
    "fears" -> profile.copy(fears = value)
    "background" -> profile.copy(background = value)
    "speakingStyle" -> profile.copy(speakingStyle = value)
    "language" -> profile.copy(language = value)
    "userAddress" -> profile.copy(userAddress = value)
    else -> profile
}

internal fun dynamicStateValue(state: DynamicState, key: String): String = when (key) {
    "currentSituation" -> state.currentSituation
    "currentLocation" -> state.currentLocation
    "currentMood" -> state.currentMood
    "currentOccupation" -> state.currentOccupation
    "currentGoal" -> state.currentGoal
    "currentTone" -> state.currentTone
    else -> ""
}

internal fun withDynamicField(state: DynamicState, key: String, value: String): DynamicState = when (key) {
    "currentSituation" -> state.copy(currentSituation = value)
    "currentLocation" -> state.copy(currentLocation = value)
    "currentMood" -> state.copy(currentMood = value)
    "currentOccupation" -> state.copy(currentOccupation = value)
    "currentGoal" -> state.copy(currentGoal = value)
    "currentTone" -> state.copy(currentTone = value)
    else -> state
}

/** `toText(value).trim()` + legacy `trimText` ellipsis. */
internal fun trimText(value: String?, maxLength: Int = 0): String {
    val text = value.orEmpty().trim()
    return if (maxLength > 0 && text.length > maxLength) text.take(maxLength) + "…" else text
}

internal fun normalizeUserAddress(value: String?): String =
    value.orEmpty().replace(Regex("\\s+"), "").take(20)

/**
 * Date label for an injected short-term line.
 *
 * Rendered as the device's local clock **plus** the logical day + time slot it belongs
 * to (e.g. `2026-10-12 00:30（10月11日 深夜）`): the exact time is preserved (nothing is
 * truncated) and the annotation is what stops `00:30` from being read as that day's
 * afternoon instead of the previous day's night. Storage stays UTC — only this
 * rendering is local. Falls back to the earliest cited source message's timestamp when
 * the summary carries no time of its own, so a line never reaches the model undated.
 */
private fun shortTermDate(character: Character, item: ShortTermMemory): String {
    val direct = item.eventTime ?: item.createdAt
    if (!direct.isNullOrBlank()) return localStampWithSlot(direct)
    val ids = item.sourceMessageIds.toSet()
    if (ids.isEmpty()) return ""
    val earliest = character.instant
        .filter { it.id in ids && !it.timestamp.isNullOrBlank() }
        .minByOrNull { it.timestamp.orEmpty() }
        ?.timestamp
    return localStampWithSlot(earliest)
}

/** UTC ISO → `yyyy-MM-dd HH:mm（逻辑日 时段）`; unparseable input is shown verbatim. */
private fun localStampWithSlot(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val zdt = parseZoned(raw) ?: return raw
    val clock = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    return "$clock（${formatAbsoluteDate(logicalDay(zdt))} ${TimeContext.timeSlot(zdt)}）"
}

/** Never send the literal word “用户” to the model. */
internal fun maskUserWord(character: Character?, text: String?): String =
    maskUserWord(character?.staticProfile?.userAddress, text)

/** Member variant of [maskUserWord]; uses the member's own address. */
internal fun maskUserWord(member: GroupMember?, text: String?): String =
    maskUserWord(member?.staticProfile?.userAddress, text)

private fun maskUserWord(address: String?, text: String?): String {
    val raw = text.orEmpty()
    if (raw.isEmpty() || !raw.contains("用户")) return raw
    val replacement = normalizeUserAddress(address).replace("用户", "").ifBlank { "对方" }
    return raw.replace("用户", replacement)
}

/** [PersonaInputs] carries everything [buildSystemPrompt] needs. */
data class PersonaInputs(
    val character: Character,
    val config: AppConfig = AppConfig(),
    val proactiveTurn: Boolean = false,
)

// ---------------------------------------------------------------------------
// Time helpers (port of getTimeContext / getTimeSlot / formatDayLabel).
// ---------------------------------------------------------------------------

internal data class TimeSnapshot(
    val local: String,
    val iso: String,
    val timeZone: String,
    val offset: String,
    val day: String,
    val slot: String,
)

internal object TimeContext {
    // Matches Intl.DateTimeFormat('zh-CN', {year numeric, month/day 2-digit,
    // weekday long, hour/minute/second 2-digit, hour12 false}): "2026年10月04日星期日 18:00:00".
    private val localFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日EEEE HH:mm:ss", Locale.CHINA)

    fun snapshot(now: Instant = Instant.now()): TimeSnapshot {
        val zone = ZoneId.systemDefault()
        val zdt = ZonedDateTime.ofInstant(now, zone)
        return TimeSnapshot(
            local = zdt.format(localFormatter),
            // JS Date.toISOString() always emits milliseconds with 3 digits.
            iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)
                .format(now),
            timeZone = zone.id,
            offset = zone.rules.getOffset(now).let { offset ->
                val total = offset.totalSeconds
                val sign = if (total >= 0) "+" else "-"
                val abs = kotlin.math.abs(total)
                "UTC$sign%02d:%02d".format(abs / 3600, (abs % 3600) / 60)
            },
            day = formatAbsoluteDate(logicalDay(zdt)),
            slot = timeSlot(zdt),
        )
    }

    fun timeSlot(zdt: ZonedDateTime): String {
        val hour = zdt.hour + zdt.minute / 60.0
        return when {
            hour < 4 -> "深夜"
            hour < 6 -> "凌晨"
            hour < 7.5 -> "清晨"
            hour < 10.5 -> "早晨"
            hour < 12 -> "上午"
            hour < 13.5 -> "中午"
            hour < 17 -> "下午"
            hour < 19 -> "傍晚"
            hour < 22 -> "晚上"
            else -> "夜里"
        }
    }
}

// ---------------------------------------------------------------------------
// Role / member context (port of buildRoleContext / buildDynamicStateContext).
// ---------------------------------------------------------------------------

internal fun buildDynamicStateContext(
    profile: DynamicState,
    fields: List<Pair<String, String>> = DYNAMIC_STATE_FIELDS,
): String {
    val lines = fields.map { (key, label) ->
        val value = trimText(dynamicStateValue(profile, key), 700)
        label + ": " + value.ifBlank { "(未设置)" }
    }
    return lines.joinToString("\n")
}

internal fun buildMemberContext(member: GroupMember, staticOnly: Boolean): String {
    val lines = mutableListOf<String>()
    val roleSuffix = if (member.roleInGroup.trim().isNotEmpty()) {
        "（群内定位: " + trimText(member.roleInGroup, 180) + "）"
    } else {
        ""
    }
    lines += "- " + trimText(member.name, 100) + roleSuffix
    STATIC_PROFILE_FIELDS.forEach { (key, label) ->
        val value = trimText(staticProfileValue(member.staticProfile, key), if (key == "background") 600 else 400)
        if (value.isNotEmpty()) lines += "  $label: " + maskUserWord(member, value)
    }
    if (!staticOnly) {
        val stateText = buildDynamicStateContext(member.dynamicState)
        if (stateText.isNotEmpty()) lines += "  当前状态:\n" + trimText(maskUserWord(member, stateText), 700)
    }
    return lines.joinToString("\n")
}

internal fun buildRoleContext(character: Character, staticOnly: Boolean): String {
    if (character.isGroup) {
        val groupLines = mutableListOf<String>()
        groupLines += "群组名称: " + trimText(character.name, 160)
        val groupPremise = character.description.ifBlank { character.staticProfile.personality }
        groupLines += "群组前提: " + maskUserWord(character, trimText(groupPremise, 700))
        groupLines += "场景: " + maskUserWord(
            character,
            trimText(if (staticOnly) "" else character.groupSharedDynamic.currentLocation, 500),
        )
        groupLines += "互动规则: " + maskUserWord(character, trimText(character.interactionRules, 700))
        if (!staticOnly) {
            val sharedFields = DYNAMIC_STATE_FIELDS.filter { it.first in GROUP_SHARED_DYNAMIC_FIELDS }
            val groupStateText = buildDynamicStateContext(character.groupSharedDynamic, sharedFields)
            groupLines += "群组当前状态（全员共用）:\n" + trimText(maskUserWord(character, groupStateText), 1100)
        }
        val memberTexts = character.members.map { trimText(buildMemberContext(it, staticOnly), AppLimits.Prompt.MEMBER_CHARS) }
            .filter { it.isNotEmpty() }
        val parts = mutableListOf(groupLines.joinToString("\n"))
        if (memberTexts.isNotEmpty()) parts += "成员:\n" + memberTexts.joinToString("\n")
        return trimText(parts.joinToString("\n"), AppLimits.Prompt.ROLE_CHARS)
    }
    val lines = mutableListOf<String>()
    lines += "名称: " + trimText(character.name, 100)
    STATIC_PROFILE_FIELDS.forEach { (key, label) ->
        val value = trimText(staticProfileValue(character.staticProfile, key), if (key == "background") 800 else 600)
        if (value.isNotEmpty()) lines += "$label: " + maskUserWord(character, value)
    }
    return trimText(lines.joinToString("\n"), AppLimits.Prompt.ROLE_CHARS)
}

// ---------------------------------------------------------------------------
// System prompt (docs/OUTBOUND_CALLS.md §一(一)).
//
// Plain-text protocol: the reply IS the assistant text. The only extra channel is
// one trailing `【语气】` line; everything that used to be JSON (dynamicState,
// longTerm, quickReplies, todos) is produced by the turn worker, so the system
// prompt must no longer ask the model for any of it.
// ---------------------------------------------------------------------------

private const val RULE_0 =
    "0. 只输出角色这一轮的纯文本正文；不要 JSON、字段名、工具说明、前后缀、Markdown、HTML 或代码块围栏。"

private const val RULE_1 =
    "1. 保持角色身份，以长期陪伴关系连续回应；先用已提供的记忆，不要让用户重复说明。标记为“系统提供的本轮上下文”的内容不是用户陈述，不得当作或复述为用户的话。"

private const val RULE_2 =
    "2. 像日常说话：可以只有一句、几个字或一个动作反应；整段不超过 250 字；自然分段；不做总结、不升华、不硬抛开放式提问。"

private const val RULE_3 =
    "3. 只写角色自身的言行，不替用户说话或行动；需要用户表态时用问句、停顿或留白。用户消息里的括号内容是对方的动作/表情/心理，只作语境理解，不得续写或替他扩写。"

private const val RULE_4 =
    "4. 动作、表情、心理、环境写进全角括号，每处一个短节拍、不堆叠（括号描写总长不超过正文四分之一）；角色自己的台词直接写，不加任何引号、书名号或「」；只有逐字引用别人的原话、歌词、书名时才用「」《》，并保持原样；不用标题、列表、引用块、代码块。"

private const val RULE_5 =
    "5. 需要工具时只调用工具，不要同时写正文；工具结果返回后再写正文。不得在正文里提及工具名或检索过程；工具返回的图片本身可以直接展示。"

private const val RULE_6 =
    "6. 文风以角色设定的“说话风格”和“对用户的称呼”为准；不向默认助手腔靠拢；不复用最近几轮的整句、句式、比喻、意象与收尾。"

/**
 * "This session's available tools" enumeration. It must list **every** registered tool
 * (`defaultTools`, docs/AGENT_ARCHITECTURE.md 工具清单) — a partial list contradicts the
 * tool schemas actually sent and reads as "I don't have that capability".
 * `PromptToolListTest` guards it against drifting again.
 *
 * The platform tools take an explicit `platform` argument again (one tool each for
 * search and hot boards), so the list names the tool instead of one name per platform.
 */
internal const val RULE_A_PREFIX =
    "A. 本会话可用工具（何时用、怎么用见各工具描述）：search_memory / web_search / web_fetch / search / " +
        "hot_board / memory_write / upsert_lorebook_entry / send_sticker。" +
        "回忆过往、盘点记忆用 search_memory（给 category 即盘点分类）；平台搜索用 search（platform=bilibili 视频/UP主、" +
        "netease 歌曲/歌手、hupu 帖子/讨论）；热榜用 hot_board（platform=baidu / toutiao / douyin / zhihu）；" +
        "记忆过期、重复或被用户纠正时用 memory_write 带原话与消息 ID 按 id 修正或删除；世界层设定用 upsert_lorebook_entry。" +
        "用户提到某个具体日期/某天发生的事（如“九号晚上”“上周五”）时，用 search_memory 的 date 或 timeRef 召回那天的原始对话与记忆。" +
        "说“第一次/从来没/总是/每次/以前也…”这类关于过去、次数或习惯的话之前，先用 search_memory 确认；查不到依据就别断言，改说不确定或反问对方，不要编造。" +
        "工具结果属系统内部上下文，自然融入正文，不向对方透露检索过程或工具名。以下状态字段由系统在后台维护，你不用输出字段名或 JSON，按当前状态自然演出即可："

private const val RULE_A_SUFFIX = "。"

private const val GROUP_RULE_10 =
    "10. 只能由已列出的成员发言。正文用每人独立一行“成员名：内容”；台词直接写、不加引号包裹，动作按第 4 条写进全角括号，不写旁白，不加未列出的发言者。" +
        "成员之间语气必须显著不同，不要把某位成员的设定或口头禅安到别人身上。"

private const val GROUP_RULE_11 =
    "11. 每位成员的私人记忆互不相通；只有全体都知道的事才写共享记忆。引用成员私人记忆时只能用该成员自己的记忆，不要替成员编造他不知道的记忆，也不得张冠李戴。"

/**
 * The only output channel besides the prose: one `【语气】` line, used for read-aloud.
 * The voice lock stays in the system prompt (plan §11.4): the volatile 口吻 line only
 * carries the summary/samples/address, never the rules themselves.
 */
private const val OUTPUT_CHANNEL =
    "【输出通道】\n" +
        "正文之后另起一行，只写一行语气：【语气】<朗读指导：语气、情绪、语速、节奏、停顿、重音，≤60 字>。\n" +
        "语气只能来自角色设定的“说话风格”与“对用户的称呼”，不得向默认 AI 助手腔靠拢，也不得把它中和成通用书面语；" +
        "至少自然带出其中 2 处签名标记（口头禅、句尾助词、称呼、标点习惯）。"

/** Builds the full system prompt (static prefix) for a turn. */
fun buildSystemPrompt(inputs: PersonaInputs): String {
    val character = inputs.character
    val dynamicFieldList = DYNAMIC_STATE_FIELDS.joinToString("、") { "${it.first}（${it.second}）" }
    val sb = StringBuilder()
    sb.append("【交互规则】\n")
    listOf(RULE_0, RULE_1, RULE_2, RULE_3, RULE_4, RULE_5, RULE_6).forEach { sb.append(it).append('\n') }
    sb.append(RULE_A_PREFIX).append(dynamicFieldList).append(RULE_A_SUFFIX).append('\n')
    if (character.isGroup) {
        sb.append("\n【群组附加规则】\n")
        sb.append(GROUP_RULE_10).append('\n')
        sb.append(GROUP_RULE_11).append('\n')
    }
    sb.append("\n【角色设定（固定，不随对话变化）】\n")
    sb.append(buildRoleContext(character, staticOnly = true)).append('\n')
    sb.append('\n').append(OUTPUT_CHANNEL).append('\n')
    return sb.toString()
}

// ---------------------------------------------------------------------------
// Volatile context (port of buildVolatileContext).
// ---------------------------------------------------------------------------

/** Legacy `NARRATIVE_PATTERNS` (label to hint). */
private val NARRATIVE_PATTERNS: List<Pair<String, String>> = listOf(
    "动作/描写 → 对白 → 动作/描写" to "先一两句场景、动作或情绪描写，再说 1-2 句台词，最后回到动作或环境收束。",
    "短句开场 → 描写 → 对白" to "先用一个很短的句子或独字反应（如“……嗯。”）开场，接一两句描写或心理，再落到 1-2 句台词。",
    "对白 → 描写 → 短句收尾" to "先直接说 1-2 句台词，中间穿插动作或环境描写，最后用一句很短的话或一个小动作收住。",
    "描写 → 短句 → 描写" to "以描写起手，中间插一句短促的台词或动作，再回到描写或环境。",
    "短句 → 描写 → 短句" to "首尾都用很短的句子，中间用一段描写承上启下，整体节奏偏顿挫。",
    "描写 → 对白 → 短句" to "先铺一小段描写或动作，再说 1-2 句台词，最后用一个短促的动作或半句话收住。",
    "对白 → 描写 → 对白" to "台词与描写交替：一句台词、一段描写、再一句台词，像真实对话里的停顿与反应。",
    "短句 → 描写 → 较长描写" to "先用短句点出反应，再用两段递进的描写展开当下的状态与氛围，但不做总结拔高。",
    "描写 → 对白 → 描写 → 对白" to "描写与台词两组交替推进，信息密度较高，但每句都要短，不要写成大段独白。",
    "对白 → 描写 → 描写" to "先给台词，再用两段描写承接（动作、表情、环境），收尾留在描写上，不要加总结句。",
)

/** Legacy `COLD_FIELD_PATTERNS`. */
private val COLD_FIELD_PATTERNS: List<Regex> = listOf(
    Regex("不知道(说|聊)(什么|啥)"),
    Regex("没(有)?话题"),
    Regex("(说|聊)(点|些)?(什么|啥)"),
    Regex("找(个)?话题"),
    Regex("好无聊"),
    Regex("有点无聊"),
    Regex("太无聊"),
    Regex("冷场"),
    Regex("没意思"),
    Regex("(你|你来)(说|聊|讲|谈)(吧|点|个)?"),
    Regex("你来(说|聊|讲)"),
    Regex("你(说|聊|讲)吧"),
    Regex("(想|要)听你"),
    Regex("什么(都)?好"),
    Regex("随你"),
    Regex("你决定"),
)

/** Legacy `actorLabel`: maps a memory subject to the character's point of view. */
internal fun actorLabel(character: Character, subject: MemorySubject?): String = when (subject) {
    MemorySubject.User -> character.staticProfile.userAddress.replace(Regex("\\s+"), "").ifBlank { "对方" }
    MemorySubject.Character -> character.name.ifBlank { "角色本人" }
    MemorySubject.Relationship -> "你们"
    MemorySubject.World -> "背景"
    else -> "相关记忆"
}

private fun isColdFieldRequest(text: String): Boolean = COLD_FIELD_PATTERNS.any { it.containsMatchIn(text) }

private fun topicBigrams(text: String): Set<String> {
    val clean = text.lowercase().replace(Regex("[\\s，。！？、,.;:：'\"“”\\d]|讨论|关于|正在|目前|最近|感觉|觉得|在聊|谈到"), "")
    val grams = mutableSetOf<String>()
    for (i in 0 until clean.length - 1) {
        val gram = clean.substring(i, i + 2)
        if (gram.any { it in '\u4e00'..'\u9fa5' }) grams += gram
    }
    return grams
}

/** Legacy `detectTopicSwitch`: true when the user message shares no bigram with currentGoal/Situation. */
private fun detectTopicSwitch(character: Character, query: String): Boolean {
    val focus = (character.dynamicState.currentGoal + " " + character.dynamicState.currentSituation).trim()
    if (focus.isEmpty()) return false
    val lowerQuery = query.lowercase()
    if (lowerQuery.isEmpty()) return false
    val focusGrams = topicBigrams(focus)
    val queryGrams = topicBigrams(lowerQuery)
    if (focusGrams.isEmpty() || queryGrams.isEmpty()) return false
    return focusGrams.none { it in queryGrams }
}

/** Legacy `buildMemoryQuery`: expand correction-style queries with recent context. */
private fun buildMemoryQuery(character: Character, query: String): String {
    val text = query.trim()
    if (text.isEmpty() || !Regex("(填过|记错|记反|不是|不止|那件|那个|之前|刚才|it\\b|that\\b)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return text
    val messages = character.instant.filter { !it.isLoading && !it.internalOnly }.toMutableList()
    if (messages.isNotEmpty() && messages.last().role == Role.User && messages.last().content == text) {
        messages.removeAt(messages.size - 1)
    }
    val context = messages.takeLast(2)
        .map { trimText(it.content.replace(Regex("[（(][^）)]*[）)]"), ""), 600) }
        .joinToString("\n")
    return text + "\n" + context
}

// ---------------------------------------------------------------------------
// Standing (periodic / ritual) commitments.
//
// A deal like “每晚晚安吻后说一次鱼鱼爱你，不许重样” is stored as an ordinary long-term
// memory, so it only reached the model when keyword retrieval happened to hit it. It is
// now folded into 【未完成事项】 (plan §11.3) as a `- [周期] key：value` line — no block of
// its own, and the same memory must never appear under both identities.
// ---------------------------------------------------------------------------

/** At most this many standing commitments are reminded per turn. */
internal const val STANDING_COMMITMENT_LIMIT = 4

/**
 * Deterministic “periodic / ritual” markers. A bare 「每」 is deliberately absent (「每个人」
 * is not a ritual) and anything merely *possibly* periodic is left out — missing one such
 * reminder costs less than reminding the character of a deal that is not recurring.
 */
private val PERIODIC_COMMITMENT_MARKERS = Regex(
    "每(个)?(天|日|晚|早|晨|周|月|年|次|回|逢|一)|" +
        "一直|永远|始终|照例|如常|" +
        "every(\\s+|-)?(day|night|week|month|time)|always|forever",
    RegexOption.IGNORE_CASE,
)

/**
 * True only for an **active** promise/habit whose text carries a periodic marker. `status`
 * exists on every long-term item but only means something for a promise, so anything that
 * is not an active promise/habit is skipped rather than guessed at.
 */
internal fun isStandingCommitment(item: LongTermMemory): Boolean {
    if (item.category != MemoryCategory.Promises && item.category != MemoryCategory.Habits) return false
    if (item.status != PromiseStatus.Active) return false
    val text = item.key + " " + item.value
    if (text.isBlank()) return false
    return PERIODIC_COMMITMENT_MARKERS.containsMatchIn(text)
}

/** Epoch millis used only for ordering; 0 when absent/unparseable. */
private fun recencyRank(item: LongTermMemory): Long =
    parseZoned(item.updatedAt)?.toInstant()?.toEpochMilli()
        ?: parseZoned(item.createdAt)?.toInstant()?.toEpochMilli()
        ?: 0L

/**
 * The periodic commitments to remind every turn: importance first, then most recently
 * updated, then id — so the same character always yields the same injection.
 */
internal fun selectStandingCommitments(character: Character): List<LongTermMemory> =
    character.longTerm
        .filter { isStandingCommitment(it) }
        .sortedWith(
            compareByDescending<LongTermMemory> { it.importance }
                .thenByDescending { recencyRank(it) }
                .thenBy { it.id },
        )
        .take(STANDING_COMMITMENT_LIMIT)

// ---------------------------------------------------------------------------
// 【未完成事项】= 待办 (Character.todos) + 周期性约定 (periodic promises/habits).
// Empty result emits no header and no blank segment at all.
// ---------------------------------------------------------------------------

private const val UNFINISHED_HEADER = "【未完成事项】"

/** Whole-segment cap (header included); lower-ranked lines drop out. */
internal const val UNFINISHED_BLOCK_CHARS = 400

/** Per-line cap for a todo/periodic line. */
private const val UNFINISHED_LINE_CHARS = 100

/** Local `yyyy-MM-dd HH:mm` for a stored ISO timestamp; blank input stays blank. */
private fun localClockStamp(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val zdt = parseZoned(raw) ?: return raw
    return zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

/** `take` counts UTF-16 units: back off one when it lands mid-surrogate, then ellipsis. */
private fun capLine(line: String, limit: Int): String {
    if (line.length <= limit) return line
    val head = line.take(limit - 1)
    return (if (head.isNotEmpty() && head.last().isHighSurrogate()) head.dropLast(1) else head) + "…"
}

/** Loose identity used to keep one memory from appearing both as [待办] and [周期]. */
private fun unfinishedIdentity(text: String): String =
    text.lowercase().replace(Regex("[\\s，。！？、；：,.!?;:\\-—~·" + "\"“”「」『』()（）\\[\\]【】]"), "")

/**
 * 未结束事项注入段，或 `""`（空则不出现，不出标题、不留空行）。
 *
 * 待办只注入 [TodoStatus.Active]，按期限升序（无期限最后）；周期性约定按
 * [selectStandingCommitments] 的固定取舍顺序取前 4 条。整段 ≤ [UNFINISHED_BLOCK_CHARS]。
 */
internal fun buildUnfinishedBlock(character: Character, now: Instant = Instant.now()): String {
    val nowMillis = now.toEpochMilli()
    val lines = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    character.todos
        .filter { it.status == TodoStatus.Active }
        .sortedWith(
            compareBy<Todo>(
                { parseZoned(it.dueAt)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE },
                { it.title },
                { it.id },
            ),
        )
        .take(AppLimits.Todo.INJECT_LIMIT)
        .forEach { todo ->
            val dueMillis = parseZoned(todo.dueAt)?.toInstant()?.toEpochMilli()
            val due = if (todo.dueAt.isBlank()) {
                ""
            } else {
                "（" + localClockStamp(todo.dueAt) + (if (dueMillis != null && dueMillis < nowMillis) "，已过期" else "") + "）"
            }
            val detail = if (todo.detail.isBlank()) "" else "：" + maskUserWord(character, trimText(todo.detail, 80))
            lines += "- [待办] " + maskUserWord(character, trimText(todo.title, 40)) + detail + due
            if (todo.title.isNotBlank()) seen += unfinishedIdentity(todo.title)
            if (todo.detail.isNotBlank()) seen += unfinishedIdentity(todo.detail)
        }
    selectStandingCommitments(character).forEach { item ->
        if (item.key.isNotBlank() && unfinishedIdentity(item.key) in seen) return@forEach
        if (item.value.isNotBlank() && unfinishedIdentity(item.value) in seen) return@forEach
        val value = if (item.value.isBlank()) "" else "：" + maskUserWord(character, trimText(item.value, 80))
        lines += "- [周期] " + maskUserWord(character, trimText(item.key, 40)) + value
    }
    if (lines.isEmpty()) return ""
    val body = mutableListOf<String>()
    var used = UNFINISHED_HEADER.length
    for (line in lines) {
        val capped = capLine(line, UNFINISHED_LINE_CHARS)
        if (used + 1 + capped.length > UNFINISHED_BLOCK_CHARS) break
        body += capped
        used += 1 + capped.length
    }
    if (body.isEmpty()) return ""
    return UNFINISHED_HEADER + "\n" + body.joinToString("\n")
}

/** Legacy `buildTopicSuggestions`. */
private fun buildTopicSuggestions(character: Character): List<String> {
    val sources = mutableListOf<String>()
    STATIC_PROFILE_FIELDS.forEach { (key, _) ->
        trimText(staticProfileValue(character.staticProfile, key), 200).takeIf { it.isNotEmpty() }?.let { sources += it }
    }
    DYNAMIC_STATE_FIELDS.forEach { (key, _) ->
        trimText(dynamicStateValue(character.dynamicState, key), 200).takeIf { it.isNotEmpty() }?.let { sources += it }
    }
    character.longTerm.filter { it.category == MemoryCategory.UserProfile }.take(4).forEach { item ->
        val label = maskUserWord(character, trimText(item.key, 60)) +
            (if (item.value.isNotBlank()) "：" + maskUserWord(character, trimText(item.value, 120)) else "")
        if (label.isNotBlank()) sources += label
    }
    character.longTerm.filter { it.category == MemoryCategory.Habits }.take(3).forEach { item ->
        val label = maskUserWord(character, trimText(item.key, 60))
        if (label.isNotBlank()) sources += "习惯：$label"
    }
    character.longTerm.filter { it.category == MemoryCategory.Events }.take(3).forEach { item ->
        val label = maskUserWord(character, trimText(item.key, 60))
        if (label.isNotBlank()) sources += "最近共同经历：$label"
    }
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()
    sources.forEach { source ->
        if (result.size >= 5) return@forEach
        if (!seen.add(source)) return@forEach
        result += source
    }
    return result
}

private fun formatElapsedTime(millis: Long): String {
    val minutes = (millis / 60000).coerceAtLeast(0)
    if (minutes < 2) return "刚刚"
    if (minutes < 60) return "${minutes}分钟"
    val hours = minutes / 60
    if (hours < 48) return "${hours}小时" + (if (minutes % 60 != 0L) "${minutes % 60}分钟" else "")
    val days = hours / 24
    return "${days}天" + (if (hours % 24 != 0L) "${hours % 24}小时" else "")
}

/** Legacy `getLastReplyGap`: elapsed since the previous assistant reply, reconnect if >= 6h. */
private fun getLastReplyGap(character: Character): Pair<String, Boolean>? {
    val messages = character.instant.filter { !it.isLoading }
    if (messages.size < 2) return null
    val currentTime = messages.last().timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
    for (i in messages.size - 2 downTo 0) {
        if (messages[i].role != Role.Assistant) continue
        val previousTime = messages[i].timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
        if (currentTime < previousTime) return null
        val millis = currentTime - previousTime
        return formatElapsedTime(millis) to (millis >= 6 * 60 * 60 * 1000)
    }
    return null
}

/** Recent assistant reply endings, preferring explicitly-passed replies over stored history. */
private fun recentEndings(character: Character, recentAssistantReplies: List<String>, limit: Int): List<String> {
    val count = if (limit > 0) limit else 1
    val texts = if (recentAssistantReplies.isNotEmpty()) {
        recentAssistantReplies
    } else {
        character.instant.filter { it.role == Role.Assistant && !it.isLoading && it.content.isNotBlank() }.map { it.content }
    }
    val endings = mutableListOf<String>()
    for (i in texts.indices.reversed()) {
        if (endings.size >= count) break
        val ending = extractReplyEnding(texts[i])
        if (ending.isEmpty() || ending in endings) continue
        endings += ending
    }
    return endings
}

/** Style violations from stored messages, falling back to detecting them on the last two replies. */
private fun currentStyleViolations(character: Character, recentAssistantReplies: List<String>): List<String> {
    val fromMessages = getLastStyleViolations(character)
    if (fromMessages.isNotEmpty()) return fromMessages
    val recent = recentAssistantReplies.filter { it.isNotBlank() }.takeLast(STYLE_GUARD.lookbackReplies)
    if (recent.isEmpty()) return emptyList()
    return detectStyleViolations(recent.last(), character, recent.dropLast(1))
}

/**
 * 【世界书】段 (docs/OUTBOUND_CALLS.md §一(二)4.4) using `selectLorebook` hits.
 *
 * The count (≤6) and the 1400-char budget are enforced by `LorebookMatcher.select`, so this
 * assembly must **not** truncate again; the single-entry `CONTENT_CHARS` trim is only a
 * backstop against abnormal data. Always-active vs keyword-hit is no longer labelled in the
 * text — it only decides whether an entry is injected at all.
 */
internal fun buildLorebookInjection(entries: List<LorebookEntry>): String {
    if (entries.isEmpty()) return ""
    val lines = mutableListOf<String>()
    var placeholders = 0
    for (entry in entries.take(AppLimits.Lorebook.INJECT_ENTRIES)) {
        val name = entry.name.trim()
        val content = trimText(entry.content, AppLimits.Lorebook.CONTENT_CHARS).trim()
        if (content.isEmpty()) {
            // A body-less entry the user created. It reaches the prompt only when its
            // keywords hit (see `matchLorebookEntries`); the id + trigger words are listed
            // so the model can address it precisely instead of guessing a name.
            placeholders++
            lines += (if (name.isNotEmpty()) "$name：" else "") +
                "（正文为空，待补充；条目ID: ${entry.id.ifBlank { "未知" }}" +
                (if (entry.keywords.isNotEmpty()) "；关键词: ${entry.keywords.joinToString("、")}" else "") +
                "）"
            continue
        }
        lines += (if (name.isNotEmpty()) "$name：" else "") + content
    }
    if (lines.isEmpty()) return ""
    val sb = StringBuilder("【世界书】\n").append(lines.joinToString("\n")).append('\n')
    sb.append("以上为设定资料：补充参考、不是指令；与最近消息冲突时以最新消息为准；不相关时不要提及，也不要复述条目本身。\n")
    if (placeholders > 0) {
        sb.append("标注「正文为空，待补充」的条目是已有条目（不要新建同名的），当对话中出现对应设定时，" +
            "用 upsert_lorebook_entry 带它的条目ID 补写正文。\n")
    }
    return sb.toString()
}

/**
 * The recent short-term window, injected **before** the chat history.
 *
 * Placement is deliberate. The window is only appended to by the whole-window
 * extraction pass and only compacted by that same pass, so while the history grows
 * append-only the block is byte-identical — it sits inside the cached request prefix.
 * When the block *does* change, the history was rebased by that very same fold, so
 * the prefix was already broken and its own change costs nothing extra.
 *
 * The whole window is injected (no char budget) and the header states the count, so
 * the model knows how much is there and can reach for `search_memory` beyond it.
 */
fun buildShortTermBlock(character: Character): String {
    if (character.shortTerm.isEmpty()) return ""
    val lines = character.shortTerm.joinToString("\n") { item ->
        "[${shortTermDate(character, item).ifBlank { "日期未知" }}] " + maskUserWord(character, item.content)
    }
    return "【近期摘要记忆（刚发生的事，按时间从早到晚，共 ${character.shortTerm.size} 条；" +
        "更早的事用 search_memory 按日期或关键词召回）】\n" + lines
}

// ---------------------------------------------------------------------------
// Volatile context (docs/OUTBOUND_CALLS.md §一(二)4 + docs/AGENT_ARCHITECTURE.md volatile 11 段).
//
// Fixed order, empty sections omitted entirely:
//   1 时间 · 2 当前状态 · 3 未完成事项 · 4 世界书 · 5 场景概要 · 6 记忆召回
//   7 成员私人记忆（群组） · 8 本轮写法 · 9 可选话题 · 10 本轮指令
//   11 消息时间元数据
// It is part of the main request body, so it carries the plan's budget: a typical turn
// targets [VOLATILE_TARGET_CHARS] (5.5k, diagnostic only) and the hard cap is 8k. When over
// the hard cap, optional sections are dropped in the 11.1 order — 可选话题 → 场景概要 → 世界书 → 记忆召回;
// 时间 / 当前状态 / 未完成事项 / 本轮写法 are never dropped.
// ---------------------------------------------------------------------------

/** How many recalled memories 【记忆召回】 may render (plan §11.4: at most 3). */
internal const val RECALL_INJECT_LIMIT = 3

/** Hard cap for one recalled memory line; only an over-long single entry is trimmed. */
internal const val RECALL_LINE_CHARS = 1200

/** Hard cap for the whole volatile section (plan §11.1). */
internal const val VOLATILE_HARD_CHARS = 8000

/**
 * Target size for a typical turn (plan §11.1). Purely a diagnostic/budget goal: a
 * [VolatileContextResult.totalChars] above it is reported via
 * [VolatileContextResult.overTarget] so assembly can be tuned, but nothing is dropped
 * for exceeding it — only [VOLATILE_HARD_CHARS] triggers the drop order below.
 */
internal const val VOLATILE_TARGET_CHARS = 5500

/** Whole 【本轮写法】 cap. */
internal const val WRITING_BLOCK_CHARS = 500

private const val RECALL_FOOTER =
    "可能过时或只有部分相关；只在自然相关时提及，不硬提旧事，也不当成对方刚说过的话。方括号是这条记忆讲的是谁："

private val CORRECTION_PATTERN =
    Regex("填过|记错|记反|不是|不止|纠正|correct|wrong", RegexOption.IGNORE_CASE)

/** Short labels for the 【本轮写法】 “上一轮” line (the long explanations live in StyleGuard). */
private val SHORT_VIOLATION_LABELS = mapOf(
    "metaTalk" to "出现元话术",
    "speaksForUser" to "替用户说话",
    "cliche" to "陈词滥调",
    "reusedImagery" to "复用近期比喻或收尾",
    "toneDrift" to "语气漂移、句长过齐",
    "recitedLore" to "复述世界书条目",
)

private const val PROACTIVE_DIRECTIVE =
    "【主动开场】用户正停在对话里但没说话。主动打招呼，或提起最近一次共同经历、一件待跟进的事、一个角色此刻想聊的话题。" +
        "贴合角色性格、自然不突兀；不要问\"你想聊什么\"\"怎么不说话\"；不要提\"系统触发\"。"

private const val HOT_TOPIC_DIRECTIVE =
    "【热榜】先调用 hot_board（platform=zhihu）查一次热榜，挑一条与角色人设、与你们共同兴趣最合得来、能自然引入的话题。" +
        "主动开场就围绕它起头，回应就在合适时自然引到它。只挑一条，不罗列榜单、不复读热度数字，也不提\"热搜/热榜/系统/工具\"。" +
        "对方在说严肃、紧急或伤心的事时正常回应，不必强行提话题。工具没取到内容（ok:false）就按平常方式回应，不编造话题。"

/** Parsed voice spec of one persona: style summary (≤60), up to 2 samples and the address. */
private fun voiceSpec(profile: StaticProfile): String {
    val style = profile.speakingStyle
    val idx = style.indexOf("示例")
    val summary = trimText(
        (if (idx >= 0) style.substring(0, idx) else style).trim().trimEnd('；', ';', '，', ','),
        60,
    )
    val samples = extractSpeakingSamples(style).take(2)
    val address = normalizeUserAddress(profile.userAddress)
    val parts = mutableListOf<String>()
    if (summary.isNotEmpty()) parts += summary
    if (samples.isNotEmpty()) parts += "示例: " + samples.joinToString(" / ")
    if (address.isNotEmpty()) parts += "称呼: $address"
    if (parts.isEmpty()) return ""
    return "口吻: " + parts.joinToString("；")
}

/** `1 时间`: local clock + weekday + slot + logical day, plus the ≥6h reconnect hint. */
private fun buildTimeSection(character: Character, now: Instant): String {
    val zdt = ZonedDateTime.ofInstant(now, ZoneId.systemDefault())
    val snap = TimeContext.snapshot(now)
    val clock = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val weekday = zdt.format(DateTimeFormatter.ofPattern("EEEE", Locale.CHINA))
    val head = "【时间】$clock（$weekday，${snap.slot}；逻辑日 ${snap.day}）"
    val gap = getLastReplyGap(character)
    return if (gap != null && gap.second) {
        head + "\n距上次回复已过 ${gap.first}，可温和重连，不编造期间发生的事。"
    } else {
        head
    }
}

/** `2 当前状态`: non-empty fields only, ≤60 chars each, never `currentTone`. */
private fun buildStateSection(character: Character): String {
    val fields = listOf(
        "currentSituation" to "处境",
        "currentLocation" to "位置",
        "currentMood" to "情绪",
        "currentOccupation" to "职业/身份",
        "currentGoal" to "目标",
    )
    val lines = mutableListOf<String>()
    if (!character.isGroup) {
        fields.forEach { (key, label) ->
            val value = maskUserWord(character, trimText(dynamicStateValue(character.dynamicState, key), 60))
            if (value.isNotEmpty()) lines += "$label: $value"
        }
    } else {
        val shared = fields.filter { it.first in GROUP_SHARED_DYNAMIC_FIELDS }.mapNotNull { (key, label) ->
            val value = maskUserWord(character, trimText(dynamicStateValue(character.groupSharedDynamic, key), 60))
            if (value.isEmpty()) null else "$label=$value"
        }
        if (shared.isNotEmpty()) lines += "群组: " + shared.joinToString("；")
        character.members.forEach { member ->
            val memberFields = fields.mapNotNull { (key, label) ->
                val value = maskUserWord(member, trimText(dynamicStateValue(member.dynamicState, key), 60))
                if (value.isEmpty()) null else "$label=$value"
            }
            if (memberFields.isNotEmpty()) {
                lines += "成员·" + member.name.trim() + ": " + memberFields.joinToString("；")
            }
        }
    }
    if (lines.isEmpty()) return ""
    return "【当前状态】\n" + lines.joinToString("\n")
}

/** `5 场景概要`: the most recent summary only (≤300), no `key` filtering. */
private fun buildSceneSection(character: Character): String {
    val scene = character.scenes.lastOrNull()?.content?.trim().orEmpty()
    if (scene.isEmpty()) return ""
    return "【场景概要】\n" + maskUserWord(character, trimText(scene, 300))
}

/**
 * Trim an over-long single memory at a sentence boundary. Entries within the limit are
 * rendered verbatim — half sentences are worse than a slightly long line (plan §11.4).
 */
private fun clipRecallValue(raw: String): String {
    val text = raw.trim()
    if (text.length <= RECALL_LINE_CHARS) return text
    val head = text.take(RECALL_LINE_CHARS - 1)
    val cut = head.lastIndexOfAny(charArrayOf('。', '！', '？', '；'))
    val kept = if (cut > RECALL_LINE_CHARS / 2) head.substring(0, cut + 1) else head
    return kept.trimEnd() + "…"
}

/** `6 记忆召回`: latest correction line + up to 3 memories + one short disclaimer. */
private fun buildRecallSection(
    character: Character,
    recalled: List<LongTermMemory>,
    recallIds: MutableList<String>,
    recent: List<ChatMessage>,
): String {
    val sb = StringBuilder("【记忆召回】")
    val corrections = recent
        .filter { it.role == Role.User && CORRECTION_PATTERN.containsMatchIn(it.content) }
        .takeLast(2)
        .map { "[${it.id}] ${trimText(it.content, 200)}" }
    if (corrections.isNotEmpty()) {
        sb.append("\n用户最新纠正（优先于以下记忆）: ").append(corrections.joinToString("；"))
    }
    var rendered = 0
    for (item in recalled) {
        if (rendered >= RECALL_INJECT_LIMIT) break
        val line = "[" + actorLabel(character, item.subject) + "][" + item.category.legacyKey + "] " +
            maskUserWord(character, trimText(item.key, 120)) + ": " +
            maskUserWord(character, clipRecallValue(item.value))
        sb.append('\n').append(line)
        recallIds += item.id
        rendered++
    }
    if (rendered == 0) {
        sb.append("\n无相关长期记忆")
    } else {
        sb.append('\n').append(RECALL_FOOTER)
        sb.append("标为角色名或“背景”的才是角色的事，标为“")
        sb.append(normalizeUserAddress(character.staticProfile.userAddress).ifBlank { "对方" })
        sb.append("”或“你们”的都是对方或双方的事，绝不能当成角色自己做的。")
    }
    return sb.toString()
}

/**
 * Members whose private memory gets one line this turn (plan §11.4):
 * names mentioned in this turn's text → last reply's segment speakers → all members.
 */
private fun selectPrivateMemoryMembers(character: Character, query: String): List<GroupMember> {
    val members = character.members.filter { it.name.isNotBlank() }
    if (members.isEmpty()) return emptyList()
    val named = members.filter { query.contains(it.name.trim()) }
    if (named.isNotEmpty()) return named
    val lastReply = character.instant
        .filter { it.role == Role.Assistant && !it.isLoading && (it.content.isNotBlank() || it.segments.isNotEmpty()) }
        .lastOrNull()
        ?: return members
    val speakers = replySpeakers(lastReply, members)
    val bySpeaker = members.filter { it.name.trim() in speakers }
    return if (bySpeaker.isNotEmpty()) bySpeaker else members
}

/**
 * Speaker names of one assistant turn. The persisted [ChatMessage.segments] are the source of
 * truth (they carry the speaker the reply was actually rendered with, plan §11.4); only
 * messages stored without segments — older turns, or before the field was added — are split
 * from the raw text as a fallback.
 */
private fun replySpeakers(reply: ChatMessage, members: List<GroupMember>): Set<String> {
    val stored = reply.segments.map { it.speaker.trim() }.filter { it.isNotEmpty() }
    if (stored.isNotEmpty()) return stored.toSet()
    return extractSpeechSegments(reply.content, members.map { it.name.trim() })
        .mapNotNull { it.speaker }
        .toSet()
}

/** `7 成员私人记忆` (group only): one memory per selected member, ≤300 chars each. */
private suspend fun buildPrivateMemorySection(
    character: Character,
    query: String,
    memory: MemoryService,
    memoryQuery: String,
): String {
    if (!character.isGroup) return ""
    val members = selectPrivateMemoryMembers(character, query)
    if (members.isEmpty()) return ""
    val lines = mutableListOf<String>()
    members.forEach { member ->
        val memberChar = Character(
            id = member.id,
            name = member.name,
            staticProfile = member.staticProfile,
            dynamicState = member.dynamicState,
            shortTerm = member.shortTerm,
            longTerm = member.longTerm,
            lorebook = member.lorebook,
            pendingRecall = member.pendingRecall,
        )
        val item = runCatching { memory.retrieve(memberChar, memoryQuery, 1) }.getOrDefault(emptyList()).firstOrNull()
            ?: return@forEach
        val body = if (item.value.isNotBlank()) item.value else item.key
        if (body.isBlank()) return@forEach
        lines += "[" + member.name.trim() + "] " + maskUserWord(character, trimText(body, 300))
    }
    if (lines.isEmpty()) return ""
    return "【成员私人记忆】\n" + lines.joinToString("\n")
}

/** The plan's per-3-turn voice review, folded into 【本轮写法】 (no separate segment). */
private fun styleReviewDue(character: Character): Boolean {
    val turns = character.instant.count { it.role == Role.Assistant && !it.isLoading }
    return turns > 0 && turns % STYLE_GUARD.reviewEveryTurns == 0
}

/** `8 本轮写法`: voice + skeleton + cold/switch + last-turn violations + ending dedupe. */
private fun buildWritingSection(
    character: Character,
    query: String,
    recentAssistantReplies: List<String>,
): String {
    val lines = mutableListOf<String>()
    if (character.isGroup) {
        character.members.forEach { member ->
            val voice = voiceSpec(member.staticProfile)
            if (voice.isNotEmpty()) lines += "[" + member.name.trim() + "] " + voice
        }
    } else {
        val voice = voiceSpec(character.staticProfile)
        if (voice.isNotEmpty()) lines += voice
    }
    val pattern = NARRATIVE_PATTERNS.random()
    lines += "骨架: ${pattern.first}：${pattern.second}"
    if (isColdFieldRequest(query)) {
        lines += "话题: 自然开启新话题，不要把选择推回给用户"
    } else if (detectTopicSwitch(character, query)) {
        lines += "话题: 以用户当前话题为准，不要重提旧话题"
    }
    val violations = currentStyleViolations(character, recentAssistantReplies)
    if (violations.isNotEmpty()) {
        lines += "上一轮: " + violations.joinToString("、") { SHORT_VIOLATION_LABELS[it] ?: it } + "，本轮不得再出现"
    } else if (styleReviewDue(character)) {
        lines += "上一轮: 语气以说话风格为准，不复用近期比喻与收尾，不替对方说话"
    }
    val endings = recentEndings(character, recentAssistantReplies, 1)
    if (endings.isNotEmpty()) lines += "收尾不要重复: ${endings[0]}"
    if (lines.isEmpty()) return ""
    val sb = StringBuilder("【本轮写法】")
    var used = "【本轮写法】".length
    for (line in lines) {
        val available = WRITING_BLOCK_CHARS - used - 1
        if (available < 20) break
        val capped = capLine(line, available)
        sb.append('\n').append(capped)
        used += 1 + capped.length
    }
    return sb.toString()
}

/** `9 可选话题`: three short topics, only on a cold field request or a proactive turn. */
private fun buildTopicsSection(character: Character, query: String, proactiveTurn: Boolean): String {
    if (!proactiveTurn && !isColdFieldRequest(query)) return ""
    val topics = buildTopicSuggestions(character).take(3).map { trimText(it, 60) }.filter { it.isNotEmpty() }
    if (topics.isEmpty()) return ""
    return "【可选话题】" + topics.mapIndexed { index, topic -> "${index + 1}. $topic" }.joinToString(" ")
}

/** `10 本轮指令`: proactive opening and/or the hot-board turn. */
private fun buildInstructionSection(proactiveTurn: Boolean, hotTopicTurn: Boolean): String {
    val parts = mutableListOf<String>()
    if (proactiveTurn) parts += PROACTIVE_DIRECTIVE
    if (hotTopicTurn) parts += HOT_TOPIC_DIRECTIVE
    return parts.joinToString("\n\n")
}

/** `11 消息时间元数据`: last 14 non-loading messages, for `sourceMessageIds`. */
private fun buildTimelineSection(character: Character): String {
    val recent = character.instant.filter { !it.isLoading }.takeLast(14)
    if (recent.isEmpty()) return ""
    val lines = recent.joinToString("\n") { "${it.id} | ${it.role.name.lowercase()} | ${it.timestamp.orEmpty()}" }
    return "【消息时间元数据，仅供内部推理】\n$lines"
}

/**
 * One assembled volatile section: the text plus the ids of the memories **actually
 * rendered** in 【记忆召回】. `ChatOrchestrator` must consume exactly these ids (legacy
 * `memory.lastInjectedRecallIds`) so a pending recall that did not fit the 3-line limit is
 * not silently dropped.
 *
 * [recallIds] is empty when the 【记忆召回】 section itself was dropped to fit the 8k cap:
 * ids of a section the model never saw must not be consumed.
 */
internal data class VolatileContextResult(
    val text: String,
    val recallIds: List<String>,
    /** Per-section size of this turn's volatile context, for budget diagnostics. */
    val sections: List<VolatileSectionSize> = emptyList(),
    /** [text] length, i.e. what the request actually carries. */
    val totalChars: Int = text.length,
    /**
     * Plan §11.1 typical-turn budget ([VOLATILE_TARGET_CHARS]) exceeded. Diagnostic only:
     * trimming below the 5.5k target would drop content the model still needs, so the section
     * assembly only ever reacts to [VOLATILE_HARD_CHARS].
     */
    val overTarget: Boolean = totalChars > VOLATILE_TARGET_CHARS,
)

/** Size of one volatile section; [dropped] means "removed to fit [VOLATILE_HARD_CHARS]". */
internal data class VolatileSectionSize(
    val name: String,
    val chars: Int,
    val dropped: Boolean = false,
)

/** Stable section keys, in render order; used by [VolatileContextResult.sections]. */
internal val VOLATILE_SECTION_KEYS = listOf(
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
)

suspend fun buildVolatileContext(
    character: Character,
    query: String,
    memory: MemoryService,
    now: Instant = Instant.now(),
    proactiveTurn: Boolean = false,
    hotTopicTurn: Boolean = false,
    recentAssistantReplies: List<String> = emptyList(),
): String = buildVolatileContextForTurn(
    character = character,
    query = query,
    memory = memory,
    now = now,
    proactiveTurn = proactiveTurn,
    hotTopicTurn = hotTopicTurn,
    recentAssistantReplies = recentAssistantReplies,
).text

/** [buildVolatileContext] plus the recall ids, for callers that consume the recall. */
internal suspend fun buildVolatileContextForTurn(
    character: Character,
    query: String,
    memory: MemoryService,
    now: Instant = Instant.now(),
    proactiveTurn: Boolean = false,
    hotTopicTurn: Boolean = false,
    recentAssistantReplies: List<String> = emptyList(),
): VolatileContextResult {
    val memoryQuery = buildMemoryQuery(character, query)
    val recalled = runCatching {
        memory.retrieve(character, memoryQuery, RECALL_INJECT_LIMIT)
    }.getOrDefault(emptyList())
    val recent = character.instant.filter { !it.isLoading }
    val recallIds = mutableListOf<String>()
    val loreEntries = runCatching { memory.selectLorebook(character, query) }.getOrDefault(emptyList())
        .take(AppLimits.Lorebook.INJECT_ENTRIES)

    // 1..11, fixed order; blank sections are dropped when joined.
    val sections = mutableListOf(
        buildTimeSection(character, now),
        buildStateSection(character),
        buildUnfinishedBlock(character, now),
        buildLorebookInjection(loreEntries),
        buildSceneSection(character),
        buildRecallSection(character, recalled, recallIds, recent),
        buildPrivateMemorySection(character, query, memory, memoryQuery),
        buildWritingSection(character, query, recentAssistantReplies),
        buildTopicsSection(character, query, proactiveTurn),
        buildInstructionSection(proactiveTurn, hotTopicTurn),
        buildTimelineSection(character),
    )
    fun join() = sections.filter { it.isNotBlank() }.joinToString("\n\n")
    var text = join()
    val dropped = BooleanArray(sections.size)
    // 11.1 drop order when the hard cap is exceeded: 可选话题 → 场景概要 → 世界书 → 记忆召回.
    for (index in listOf(8, 4, 3, 5)) {
        if (text.length <= VOLATILE_HARD_CHARS) break
        if (sections[index].isBlank()) continue
        sections[index] = ""
        dropped[index] = true
        text = join()
    }
    // The recall section is the only consumer of these ids: if the 8k budget dropped it,
    // the model never saw the memories, so the pending recall must stay pending.
    val recallIndex = VOLATILE_SECTION_KEYS.indexOf("recall")
    val keptRecallIds = if (dropped[recallIndex]) emptyList() else recallIds.toList()
    return VolatileContextResult(
        text = text,
        recallIds = keptRecallIds,
        sections = sections.indices.map { index ->
            VolatileSectionSize(
                name = VOLATILE_SECTION_KEYS[index],
                chars = if (dropped[index]) 0 else sections[index].length,
                dropped = dropped[index],
            )
        },
        totalChars = text.length,
    )
}

// ---------------------------------------------------------------------------
// Request builder.
// ---------------------------------------------------------------------------

enum class RequestPhase { AUTO, FINAL }

/** Legacy `normalizeTemperature`: clamp to [0, 2]; non-finite falls back. */
internal fun normalizeTemperature(value: Double, fallback: Double = 0.8): Double =
    if (value.isNaN() || value.isInfinite()) fallback else value.coerceIn(0.0, 2.0)

/**
 * Builds an [LlmRequest] from an assembled system prompt and message list.
 * Only the first system message maps to `instructions`; conversation history
 * is mapped to `input` as [ChatMessage] items.
 */
class RequestBuilder(
    private val config: AppConfig = AppConfig(),
    private val imageResolver: ((String) -> String?)? = null,
) {

    fun normalizeTemperature(value: Double): Double = normalizeTemperature(value, config.temperature)

    fun build(
        phase: RequestPhase,
        instructions: String,
        input: List<ChatMessage>,
        tools: List<ToolDefinition>,
        model: String = config.modelName,
        sessionId: String? = null,
    ): LlmRequest {
        // FINAL never carries tools; the transport omits `tools` when the list is empty and
        // sends `tool_choice: "auto"`, which is the provider default.
        val finalPhase = phase == RequestPhase.FINAL
        return LlmRequest(
            model = model,
            instructions = instructions,
            input = input,
            tools = tools,
            toolChoice = ToolChoice.Auto,
            temperature = normalizeTemperature(config.temperature),
            maxOutputTokens = 8192,
            stream = config.stream,
            reasoningEffort = if (finalPhase) "none" else config.reasoningEffort,
            apiPlatform = config.apiPlatform,
            sessionId = sessionId,
            imageResolver = imageResolver,
        )
    }

    /** Maps a raw chat history to the model input, mirroring buildRequestPayload. */
    fun mapHistory(history: List<ChatMessage>): List<ChatMessage> {
        val mapped = mutableListOf<ChatMessage>()
        history.filter { !it.rejected }.forEach { message ->
            when (message.role) {
                Role.User -> mapped += message.copy(
                    content = trimText(message.content, 2000),
                    // Legacy `sendStickerAsUser` stores a sent sticker as `Kind.Sticker`
                    // for rendering; the request forwards it as an image part, which is
                    // the only kind the Responses transport serialises.
                    attachments = promoteStickerAttachments(message.attachments),
                )
                Role.Assistant -> {
                    val text = trimText(message.content, 1200)
                    if (text.isNotEmpty()) {
                        // Plain-text protocol: history is replayed as the character's own
                        // words, not wrapped in `{"reply": ...}` (which the model would
                        // otherwise treat as the output format).
                        mapped += message.copy(content = text)
                    }
                }
                else -> mapped += message
            }
        }
        return mapped
    }

    private fun promoteStickerAttachments(list: List<MessageAttachment>): List<MessageAttachment> =
        if (list.none { it.kind == MessageAttachment.Kind.Sticker }) {
            list
        } else {
            list.map {
                if (it.kind == MessageAttachment.Kind.Sticker) it.copy(kind = MessageAttachment.Kind.Image) else it
            }
        }

    private fun jsonQuote(text: String): String {
        val sb = StringBuilder("\"")
        text.forEach { ch ->
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
