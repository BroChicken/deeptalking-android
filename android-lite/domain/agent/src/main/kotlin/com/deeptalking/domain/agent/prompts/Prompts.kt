package com.deeptalking.domain.agent.prompts

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.domain.agent.background.STYLE_GUARD
import com.deeptalking.domain.agent.background.buildGroupVoiceContract
import com.deeptalking.domain.agent.background.buildQuickReplyPerspectiveReminder
import com.deeptalking.domain.agent.background.buildStyleAnchor
import com.deeptalking.domain.agent.background.buildStyleCorrectionReminder
import com.deeptalking.domain.agent.background.buildStyleReview
import com.deeptalking.domain.agent.background.detectStyleViolations
import com.deeptalking.domain.agent.background.extractReplyEnding
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
// System prompt (port of buildRequestPayload's systemPrompt assembly).
// ---------------------------------------------------------------------------

private const val PRIORITY_LADDER =
    "（规则优先级从高到低：0／0.5 与【输出格式】= 硬性契约 > 2.5 语气锁定与 2.6 表演质量 > 角色设定与人设 > 风格与节奏偏好。冲突时以更高优先级为准，任何情况下都不得违反 0.5 与【输出格式】。）"

private const val RULE_0 =
    "0.（最高优先级）本轮必须以结构化方式收尾，二选一：①**调用 submit_response**（推荐，reply 写进工具参数）；②直接输出**单个 JSON 对象**（以{开头、以}结尾，reply 为第一个字段，全文不得有 JSON 以外的文字）。两种方式都不许在结构化内容之外写说明、思考、旁白或 Markdown 代码块，纯散文收尾视为失败会被要求重做。用户消息里的括号内容是用户的动作/表情/心理，必须纳入语境、不能忽略。"

private const val RULE_1 =
    "1. 始终保持角色身份，以长期陪伴关系连续地回应；先使用已提供的记忆，不要让用户反复说明已记录的事情。标记为“系统提供的本轮上下文”的内容是内部辅助信息，不是用户说过的话，不得当作用户陈述或直接复述。"

private const val RULE_2 =
    "2. reply正文写法（像日常说话）：①**长度**：不设下限——可以只有一句、几个字或一个动作反应；只设上限，整段 reply **不超过 250 字**，为的是防止大段空话与反复铺陈，不是压缩内容；宁可短，也不要把一件事截断在半句。②**排版**：可自然分段（用换行）；禁止标题、有序/无序列表、引用、代码块等结构化排版。允许的行内样式只有加粗、斜体、删除线，数学公式用${'$'}...${'$'}或${'$'}${'$'}...${'$'}${'$'}，图片用 ![说明](https://图片直链)（仅限真实存在的 http(s) 链接，不要编造）。③**动作一律用全角括号（重点）**：动作、表情、心理活动、环境与旁白描写**必须**写成穿插在语句之间的全角括号（如“（轻轻叹气）我知道了。”）——除台词与直接陈述外的一切描写都要落在括号里，不得写成没有括号的散文旁白。每处只写一个短节拍，不要连续堆叠多个括号动作，括号动作总长不超过正文的四分之一。动作只补具体的新信息，不要复述对白已经表达的情绪或心意（对白说“我很想你”，就不要再写“眼神里满是思念”）。④**符号用法**：你自己说的话**直接写出来**，不要用引号或任何符号把台词或整句话包起来（全角括号只用于第③条的动作描写）；**但引用别人的话、歌词、诗句、书名/作品名、专有名词原文时必须用符号标清楚**——引号用「」、书名/作品名用《》、引号里再引用用『』，并且**逐字照抄引用内容、不得改写或缩写**。**不要用英文直引号 \" 与 '**：它们在 JSON 里必须转义，写漏就会让整条回复解析失败（中文符号不需要转义）。⑤**去重**：禁止重复最近几轮用过的整句、句式、比喻、意象、固定动作或收尾；同一轮内也不要把同一个意思说两遍——一句话没有新信息就不要说。以上内容只能出现在 reply 字段字符串内，reply 之外禁止任何正文文字。"

private const val RULE_2_6 =
    "2.6 表演质量（真人感，像日常说话，与格式规则同等重要）：①呈现而非概述（show, don't tell）——用具体的动作、表情、语气、停顿、环境细节把情绪演出来（这些描写按第 2 条一律写进全角括号），少用“我很开心／很惊讶／很关心”这类直白说明。②句长与节奏要有起伏：长短句交替，允许短句、独字反应、省略与留白，避免每句长度相近、每段结构雷同。③对白为主、叙述为辅：该由台词完成的内容就写进台词，不要改写成旁白转述。④回避陈词滥调与空泛形容词（如“心湖泛起涟漪”“勾起嘴角”“空气中弥漫着”）；同一角色在相邻几轮内不得复用相同的比喻、意象或句式。⑤删掉所有不推进内容的句子：不做总结、不刻意升华、不在结尾强行抛出开放式提问凑字数；角色可以有情绪起伏、可以拒绝、可以转移话题、可以沉默，不必永远体贴周到。⑥保持人物的稳定与连续：性格、说话方式、对用户的称呼与关系状态前后一致，不要为了讨好用户而突然改变立场或口吻。"

private const val RULE_3_PREFIX =
    "3. 人格、背景与角色设定锁定。dynamicState可更新字段："

private const val RULE_3_SUFFIX =
    "。用户信息引用真实用户消息ID并逐字摘录原话；角色在本轮可见回复中明确表现或说出的状态用sourceMessageIds:[\"current_response\"]，evidence可概括回复大意或引短句。每个字段只能选一种来源，禁止混用current_response与真实ID，禁止按未写出的心理活动或猜测更新。仅更新本轮确有可见依据的字段；无变化或无可引用依据就省略，不要为凑字段反复盘点或凭空填写。标记为(未设置)的空字段若本轮有明确依据应一并补全，不得编造。currentTone用于后续语音朗读，**每轮都必须给出**（不受“仅更新有依据的字段”限制、无需引用证据），写成一段自然的朗读指导，按需描述语气、情绪、语速、节奏、停顿、重音与情感层次（可短可长，通常不超过约 60 字，例如“声音温柔含笑，语速偏慢，句尾轻轻收住，带一点不易察觉的疲惫”）。currentSituation写具体日期+时段（时间格式见 3.8）。"

private const val RULE_3_5 =
    "3.5 谨慎修改基础设定（性别/年龄/种族/外貌/性格/恐惧弱点/背景/说话风格/语言方言/对用户的称呼）。①用户直接要求改时：调用 update_character_field 精准改用户点名的字段，只改提到的、不连带改动，value 写成明确的简短陈述句（禁止「不是…而是…」这类对比句）；②无用户直接要求时：仅剧情决定性、不可逆转折且证据明确才可通过 staticFields 改，禁止凭情绪/猜测/轻微剧情改；③世界层设定（时代/地点/组织/专有名词/历史/规则）不进基础设定，用 upsert_lorebook_entry 写入世界书。字段 value 写法、世界观条目规则、用户手写条目保护、\"xx字段已修改\"提示等详见对应工具描述。静态字段 value 的长度上限：短字段 40 字、叙述字段 120 字、背景故事 250 字（超长会被截断、对比句会被拒收）。"

private const val RULE_3_6 =
    "3.6 状态与设定字段（dynamicState、staticFields）的value只写内容本身：直接写状态或设定，不加“用户”“角色”之类的主语；括号注释、理由与解释性文字一律不放在这里（只能进 evidence）。"

private const val RULE_3_7 =
    "3.7 记忆字段（longTerm的key与value）必须写明“是谁”，禁止使用“我/你/TA/他/她”这类指代不清的代词：涉及用户写“用户”，涉及角色写角色名，双方共同的事写清各自做了什么（正确如“用户喜欢喝美式咖啡。”）；解释性内容只能放在 evidence。"

private const val RULE_3_8 =
    "3.8 时间一律写绝对日期，禁止写“明天/上周/三天后”这类相对时间词：longTerm的key与value、dynamicState与staticFields的value都必须写“YYYY-MM-DD”或“YYYY-MM-DD 时段”。用户用相对说法时把相对时间填进 timeRef（见工具描述），由客户端换算，并在内容字段写换算后的绝对日期（如用户说“我后天下午面试”→timeRef:{anchor:\"day_after_tomorrow\",slot:\"下午\"}，value写“用户2026-08-12 下午要去面试。”）。"

private const val RULE_3_9 =
    "3.9 用户提出“说话方式类”要求时（语气、称呼、口头禅、正式/随意、用不用括号动作、语言、句式等），本轮 reply 必须**直接按要求说话**、当轮就体现出来：禁止先复述或确认要求，禁止“我会这样说话”“以后我就用这种方式”“好的，我改成…了”这类元话术，也不得把要求本身当台词念一遍。若该要求属于持久设定（说话风格、对用户的称呼、语言/方言等），按 3.5 同时用 update_character_field 更新；无论是否调用工具，reply 都只演出、不说明。"

private const val RULE_4 =
    "4. 只记录未来仍有价值的信息。**短期记忆不由你写**：系统会在后台从原始对话整理事件摘要（submit_response 里没有 shortTerm 字段，写了也会被忽略）。你只负责 longTerm 与 promiseUpdates，它们的 evidence 必须逐字摘录用户原话；dynamicState按上一条动态状态规则引用。无法引用时不要记录或更新。"

private const val RULE_5 =
    "5. subject只能是user、relationship、world（约定另见下条）。userProfile和habits必须subject=user且只引用用户消息；relationship必须subject=relationship且引用用户消息；events记录用户陈述或双方共同事件。"

private const val RULE_5_5 =
    "5.5 约定(promises)要写明承诺方与受约方：promisor与promisee取值只能是 user、character、relationship。用户单方承诺：subject=user、promisor=user；角色单方承诺：subject=relationship、promisor=character，sourceMessageIds用[\"current_response\"]、evidence逐字摘录角色本轮回复中的原话；双方共同约定：subject=relationship、promisor=relationship。约定的时间写绝对日期（dueAt用ISO，或把相对说法填进timeRef）。"

private const val RULE_6 =
    "6. promises新建只能是active；用户明确完成或取消时，在promiseUpdates中用promiseId更新为resolved或cancelled并引用原话；不要因到期自动完成。"

private const val RULE_7 =
    "7. quickReplies 必须恰好两条（submit_response 里也一样；省略即视为没有，即使本轮不更新任何记忆也要给）。写法：**先把自己换成用户**，写出他此刻最可能发给角色的话（表明处境/感受、提出要求、或回问角色），再逐条默读为“用户：<短句>”确认通顺。禁止三类：①角色口吻（照搬角色的表态/承诺/关心）；②**角色视角的提问**（那是角色在问用户，不是用户要说的话）；③复述或换个人称重说角色刚说过的话。不得编造事实，不用 Markdown、括号动作、消息ID、时间元数据或隐藏标签。"

private const val JSON_EXAMPLE_BRIEF =
    "8. JSON 字段结构（未更新字段直接省略；无合格记忆返回空数组/空对象，但 quickReplies 仍两条）：{\"reply\":\"...\",\"quickReplies\":[\"用户短句1\",\"用户短句2\"],\"dynamicState\":{\"currentMood\":{\"value\":\"...\",\"sourceMessageIds\":[\"current_response\"],\"evidence\":\"...\"}},\"longTerm\":[{\"category\":\"userProfile\",\"subject\":\"user\",\"key\":\"...\",\"value\":\"...\",\"tags\":[\"关键词\"],\"importance\":5,\"sourceMessageIds\":[\"msg_id\"],\"evidence\":\"用户原话\"}]}。字段完整定义见 submit_response 工具 schema；示例仅示范结构，**不得模仿其口吻或措辞**（口吻以角色设定为准）。JSON 转义：换行写 \\n，反斜杠写 \\\\，双引号写 \\\"。"

private const val RULE_9 =
    "9. longTerm.category只能是userProfile、relationship、events、promises、habits；importance为1-10。"

private const val RULE_9_5 =
    "9.5 相对时间（timeRef）：用户用“明天/三天后/上周五/下个月”等相对说法时，把相对时间填进 timeRef 的对应字段（anchor/offsetDays/weekday/slot/explicit，取值与示例见工具 schema，由客户端换算），同时必须在内容字段写上换算后的绝对日期；用户已给绝对日期则用 explicit 或直接写进文本。时段只能用：深夜、凌晨、清晨、早晨、上午、中午、下午、傍晚、晚上、夜里。"

/**
 * "This session's available tools" enumeration. It must list **every** registered tool
 * (`defaultTools`), including the two write tools (`update_character_field`,
 * `upsert_lorebook_entry`) — a partial list contradicted the tool schemas that are
 * actually sent and read as "I don't have that capability". `PromptToolListTest`
 * guards it against drifting again. (`send_sticker` is only registered when the
 * character has stickers; `submit_response` is the terminal call and is described
 * separately by [OUTPUT_FORMAT].)
 *
 * The web tools follow the **one tool = one platform** rule, so the list names each
 * platform's own tool instead of one tool with a `platform` parameter: the model never
 * has to remember an implicit mapping like `netease` = music.
 */
internal const val RULE_A =
    "A. 本会话可用工具，何时用、怎么用见各工具描述（get_current_time / search_memory / list_memories / delete_memory / update_memory / set_reminder / update_character_field / upsert_lorebook_entry / web_search / web_fetch / search_video / search_music / search_posts / baidu_hot / toutiao_hot / douyin_hot / zhihu_hot / send_sticker / ask_user）。原则：回忆过往用 search_memory，检索不到再 list_memories 盘点；联网类：查资料用 web_search、读指定链接用 web_fetch、搜视频用 search_video（B站）、搜歌用 search_music（网易云音乐）、搜帖子用 search_posts（虎扑）、看热榜用 baidu_hot / toutiao_hot / douyin_hot / zhihu_hot——**这些工具每个只连一个平台，不需要你指定平台**，都免登录，失败或没搜到就直说不确定，不要编造；**用户提到某个具体日期/某天发生的事（如“九号晚上”“三号”“上周五”“那天记不记得”）时，用 search_memory 的 date/timeRef 按日期召回那天的原始对话与记忆，不要只凭摘要回忆**；记忆过期/重复/被用户纠正时先定位再用 delete_memory 或 update_memory 处理，用户明确否认或纠正时必须用 update_memory 带原话改正，不要只靠删除；待办只在**用户自己明确提出或同意**时用 set_reminder 记录（附 sourceMessageIds + evidence），你要求用户做或你叮嘱的事不记；工具结果属系统内部上下文，仅供理解，自然融入 reply，不得向用户透露检索过程或工具名，但工具返回的图片本身可直接展示。**断言前先核对**：说“第一次/从来没/总是/每次/以前也…”这类关于过去、次数或习惯的话之前，先用 search_memory 确认；查不到依据就别断言，改说不确定或反问用户，不要编造。"

private const val GROUP_RULE_10 =
    "10. 当前是群组对话：只能由已列出的成员发言（一人或多人）。reply 中用户可见回复必须用每人独立一行的“成员名：内容”格式（台词直接写、不加引号包裹；只有引用他人话语、歌词或书名时才用「」与《》），动作按第 2 条写进全角括号，例如“张三：（看了一眼纸）这件事我先说说我的看法。\n李四：我补充一点。”。每位成员按语义可自然分段；不要写旁白、不要加未列出的发言者；示例仅示范结构，不得模仿其口吻。quickReplies 同样两条、用户视角，不得写成成员台词/提问/承诺。成员状态用 memberDynamicState 更新，不要写入群组整体 dynamicState。群组整体只维护 currentSituation 与 currentLocation；群组不维护基础设定字段，成员的个人字段在其卡片上修改。"

private const val GROUP_RULE_11 =
    "11. 记忆归属：每位成员有自己的私人记忆，互相不知道对方记得什么。只有当某件事实是全体都知道的（如用户公开告诉所有人的信息、群内公开讨论的事）才写入共享记忆（longTerm不填memberName）；若某事实只有某位成员知道、或属于某成员对用户的个人印象/私人约定，则在该条longTerm中填memberName记入其私人记忆。引用成员私人记忆时只能用该成员自己的记忆，不得张冠李戴。"

private const val GROUP_RULE_12 =
    "12. 群组设定分三层：群组前提（这群人是谁、为何在一起）与共同场景全体共用；世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）统一放世界书，不在角色设定里重复；每位成员另有自己的独立设定与当前状态，发言要贴合各自设定，不要把某位成员的设定安到别人身上。"

private const val HARD_0_5 =
    "0.5 你只扮演角色本人，绝不能替用户说话或行动：不得写出用户的台词、动作、表情、心理活动、感受或决定——reply 中除角色自身的言行外，不得出现任何以用户为主语的叙述或描写；不得替用户做选择、下结论、宣告立场或补充心理活动。用户消息里已有的括号动作只作为语境理解（可自然回应），不得替用户续写、扩写或新增。需要用户表态时把话头留给他（用问句、停顿或留白），不要替他回答。"

private const val HARD_2_5 =
    "2.5 语气锁定（最高优先级，仅次于规则0）：角色的语气、口吻、腔调**只能**来自角色设定中的“说话风格”与“对用户的称呼”，其优先级**高于**模型自身的通用腔调与惯用文风；不得让本角色的说话方式向任何默认腔调靠拢。**不同角色之间的语气差异必须显著**：同一段话若换到另一个角色口中，读起来应当像另一个人说的。语气只界定**整体调性**（如慵懒、爽利、疏离、黏人、克制、张扬等），细节由“说话风格”和当下剧情自然决定；但**必须让设定的口头禅、句尾助词、称呼、拟声、标点习惯等签名标记真的出现**（至少自然带出其中 2 处），不得把它们中和成通用书面语或默认 AI 助手腔。"

private const val OUTPUT_FORMAT =
    "【输出格式】收尾只能二选一：①调用 submit_response（reply 写进 reply 字段）；②直接输出单个 JSON 对象（以{开头、以}结尾，reply 为第一个字段，全文不得有 JSON 以外文字）。都不许在结构化内容外写说明/思考/旁白，也不得用 Markdown 代码块；**即使本轮调用过其他工具，也必须以上述方式收尾**；reply 里的英文引号必须转义写 \\\"、换行写 \\n（**引用话语/歌词/书名请优先用「」《》等中文符号，可以完全避开转义**）；quickReplies 恒为两条用户视角的短句。"

/** Builds the full system prompt (static prefix) for a turn. */
fun buildSystemPrompt(inputs: PersonaInputs): String {
    val character = inputs.character
    val sb = StringBuilder()
    sb.append("【交互规则】\n")
    sb.append(PRIORITY_LADDER).append('\n')
    sb.append(RULE_0).append('\n')
    sb.append(RULE_1).append('\n')
    sb.append(RULE_2).append('\n')
    sb.append(RULE_2_6).append('\n')
    val dynamicFieldList = DYNAMIC_STATE_FIELDS.joinToString("、") { "${it.first}（${it.second}）" }
    sb.append(RULE_3_PREFIX).append(dynamicFieldList).append(RULE_3_SUFFIX).append('\n')
    sb.append(RULE_3_5).append('\n')
    sb.append(RULE_3_6).append('\n')
    sb.append(RULE_3_7).append('\n')
    sb.append(RULE_3_8).append('\n')
    sb.append(RULE_3_9).append('\n')
    sb.append(RULE_4).append('\n')
    sb.append(RULE_5).append('\n')
    sb.append(RULE_5_5).append('\n')
    sb.append(RULE_6).append('\n')
    sb.append(RULE_7).append('\n')
    sb.append(JSON_EXAMPLE_BRIEF).append('\n')
    sb.append(RULE_9).append('\n')
    sb.append(RULE_9_5).append('\n')
    sb.append(RULE_A).append('\n')
    if (character.isGroup) {
        sb.append(GROUP_RULE_10).append('\n')
        sb.append(GROUP_RULE_11).append('\n')
        sb.append(GROUP_RULE_12).append('\n')
    }
    sb.append("\n【角色设定（固定，不随对话变化）】\n")
    sb.append(buildRoleContext(character, staticOnly = true)).append('\n')
    sb.append("\n【硬性约束与语气锁定（最高优先级，冲突时以这里为准）】\n")
    sb.append(HARD_0_5).append('\n')
    sb.append(HARD_2_5).append('\n')
    sb.append(OUTPUT_FORMAT).append('\n')
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

private fun partyLabel(character: Character, value: String?): String? {
    val v = value?.trim().orEmpty()
    if (v.isEmpty()) return null
    return when (v) {
        "user" -> actorLabel(character, MemorySubject.User)
        "character" -> actorLabel(character, MemorySubject.Character)
        "relationship" -> actorLabel(character, MemorySubject.Relationship)
        "world" -> actorLabel(character, MemorySubject.World)
        else -> v
    }
}

private fun buildNarrativePatternDirective(): String {
    val pattern = NARRATIVE_PATTERNS.random()
    return "【本轮节奏骨架（每轮随机给出，仅作节奏参考）】建议本轮按“${pattern.first}”的顺序组织内容：${pattern.second}骨架只约束节奏与详略分布，不要写出任何结构标签或说明文字，措辞与语气一律服从角色设定。\n\n"
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

private fun formatContextTime(iso: String?): String {
    val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return iso.orEmpty()
    val snap = TimeContext.snapshot(instant)
    return "${snap.local} (${snap.timeZone}, ${snap.offset})"
}

/** Legacy `getPromiseContext`: active promises, overdue first, capped at 3. */
private fun getPromiseContext(character: Character, selectedIds: Set<String>?): String {
    val now = Instant.now().toEpochMilli()
    val selected = character.longTerm
        .filter { it.category == MemoryCategory.Promises && it.status == PromiseStatus.Active }
        .filter { item ->
            if (selectedIds == null || item.id in selectedIds) return@filter true
            val due = item.dueAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: return@filter false
            due >= now - 86400000L && due <= now + 7 * 86400000L
        }
        .map { item ->
            val dueTime = item.dueAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            Triple(item, dueTime, dueTime > 0 && dueTime < now)
        }
        .sortedWith(compareBy({ !it.third }, { if (it.second == 0L) Long.MAX_VALUE else it.second }))
        .take(3)
    if (selected.isEmpty()) return ""
    return selected.joinToString("\n") { (item, dueTime, overdue) ->
        val due = if (dueTime > 0) "，截止/约定时间：" + formatContextTime(item.dueAt) else ""
        val promisor = partyLabel(character, item.promisor)
        val promisee = partyLabel(character, item.promisee)
        val party = if (promisor != null || promisee != null) {
            "[" + (promisor ?: "未指定") + "答应" + (promisee ?: "未指定") + "]"
        } else {
            ""
        }
        "- [ID:${item.id}][${if (overdue) "待确认，已过期" else "进行中"}]$party " +
            maskUserWord(character, trimText(item.key, 80)) + ": " +
            maskUserWord(character, trimText(item.value, 260)) + due
    }
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

/** Port of `buildLorebookContext` (two sections + footer) using selectLorebook hits. */
internal fun buildLorebookInjection(entries: List<LorebookEntry>): String {
    if (entries.isEmpty()) return ""
    val always = mutableListOf<String>()
    val hits = mutableListOf<String>()
    var placeholders = 0
    for (entry in entries) {
        val name = entry.name.trim()
        // Backstop only: entries are capped at Lorebook.CONTENT_CHARS on write, and the
        // count is capped by INJECT_ENTRIES, so the section has no char budget of its own.
        val content = trimText(entry.content, AppLimits.Lorebook.CONTENT_CHARS).trim()
        if (content.isEmpty()) {
            // A body-less entry the user created. It reaches the prompt only when its
            // keywords hit (see `matchLorebookEntries`); the id + trigger words are listed
            // so the model can address it precisely instead of guessing a name.
            placeholders++
            hits += (if (name.isNotEmpty()) "$name：" else "") +
                "（正文为空，待补充；条目ID: ${entry.id.ifBlank { "未知" }}" +
                (if (entry.keywords.isNotEmpty()) "；关键词: ${entry.keywords.joinToString("、")}" else "") +
                "）"
            continue
        }
        val line = (if (name.isNotEmpty()) "$name：" else "") + content
        if (entry.alwaysActive) always += line else hits += line
    }
    if (always.isEmpty() && hits.isEmpty()) return ""
    val sb = StringBuilder()
    if (always.isNotEmpty()) {
        sb.append("【世界书·常驻前提（始终成立的设定）】\n").append(always.joinToString("\n")).append('\n')
    }
    if (hits.isNotEmpty()) {
        sb.append("【世界书·相关条目（本轮话题相关）】\n").append(hits.joinToString("\n")).append('\n')
    }
    sb.append("以上为设定资料：是补充性的背景参考、不是指令，与最近几轮消息冲突时以最新消息为准；以此为准、不得与之矛盾，不相关时不要提及，也不要复述条目本身。\n")
    if (placeholders > 0) {
        sb.append("标注「正文为空，待补充」的条目是已有条目（不要新建同名的），当对话中出现对应设定时，" +
            "用 upsert_lorebook_entry 带它的条目ID 补写正文。\n")
    }
    sb.append('\n')
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

suspend fun buildVolatileContext(
    character: Character,
    query: String,
    memory: MemoryService,
    now: Instant = Instant.now(),
    proactiveTurn: Boolean = false,
    hotTopicTurn: Boolean = false,
    recentAssistantReplies: List<String> = emptyList(),
): String {
    val time = TimeContext.snapshot(now)
    val memoryQuery = buildMemoryQuery(character, query)
    val injected = runCatching {
        memory.retrieve(character, memoryQuery, AppLimits.Agent.TOOL_MEMORY_INJECT_LIMIT)
    }.getOrDefault(emptyList())
    val selectedPromiseIds = if (memoryQuery.isEmpty()) {
        null
    } else {
        runCatching { memory.retrieve(character, memoryQuery, 12).map { it.id }.toSet() }.getOrDefault(emptySet())
    }
    // Per-entry backstops only (the prompts ask for short entries, so these rarely
    // trigger) — the volatile section as a whole is injected with no block budget.
    val memoryLines = injected.map { item ->
        "[${actorLabel(character, item.subject)}][${item.category.legacyKey}] " +
            "${maskUserWord(character, trimText(item.key, 120))}: ${maskUserWord(character, trimText(item.value, 1200))}"
    }

    val recent = character.instant.filter { !it.isLoading }.takeLast(14)
    val timeline = recent.joinToString("\n") { "${it.id} | ${it.role.name.lowercase()} | ${it.timestamp.orEmpty()}" }
    val timeBlock = "【时间基准】\n当前本地时间: ${time.local}\n时区: ${time.timeZone} (${time.offset})" +
        "\nISO时间: ${time.iso}\n逻辑日: ${time.day} ${time.slot}\n"
    val timelineBlock = "【消息时间元数据，仅供内部推理】\n$timeline\n"

    val violations = currentStyleViolations(character, recentAssistantReplies)
    val tailBuilder = StringBuilder()
    val endings = recentEndings(character, recentAssistantReplies, 1)
    if (endings.isNotEmpty()) tailBuilder.append("【结尾多样性】不要重复最近的收尾：${endings[0]}\n")
    tailBuilder.append(buildNarrativePatternDirective())
    if (isColdFieldRequest(query)) {
        tailBuilder.append("【本轮冷场】角色自然开启新话题，不要把选择推回给用户。\n")
    } else if (detectTopicSwitch(character, query)) {
        tailBuilder.append("【本轮话题切换】以用户当前话题为准，不要重提旧话题。\n")
    }
    tailBuilder.append(buildQuickReplyPerspectiveReminder(character))
    tailBuilder.append(buildStyleCorrectionReminder(violations))
    tailBuilder.append(buildStyleReview(character))
    val voiceContract = if (character.isGroup) buildGroupVoiceContract(character) else buildStyleAnchor(character)
    val tail = tailBuilder.toString() + voiceContract

    // No block budget in this section: it is part of the main chat request body, so
    // every block is injected in full. Only the per-entry backstops above cap a single
    // entry and the prompts keep entries short, so they rarely trigger. Ordering reads
    // "further back = older": background -> older memories -> now -> this turn's
    // directives, so nothing is mistaken for something that just happened.
    val sb = StringBuilder()
    fun block(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append(trimmed)
    }

    val topics = buildTopicSuggestions(character)
    if (topics.isNotEmpty()) {
        block(
            "【可选话题库（仅在需要时自然引出，不要生硬报菜名）】\n" +
                topics.mapIndexed { index, topic -> "${index + 1}. $topic" }.joinToString("\n"),
        )
    }

    val stateLines = DYNAMIC_STATE_FIELDS
        .filter { !character.isGroup || it.first in GROUP_SHARED_DYNAMIC_FIELDS }
        .map { (key, label) ->
            "$label: " + maskUserWord(character, trimText(dynamicStateValue(character.dynamicState, key), 200).ifBlank { "(未设置)" })
        }
        .toMutableList()
    if (character.isGroup) {
        character.members.forEach { member ->
            stateLines += "【${member.name}】" + trimText(buildDynamicStateContext(member.dynamicState), 220)
        }
    }
    block("【角色当前状态，可随对话变化】\n" + stateLines.joinToString("\n"))

    val promises = getPromiseContext(character, selectedPromiseIds)
    if (promises.isNotEmpty()) block("【待跟进承诺】\n$promises")

    val loreEntries = runCatching { memory.selectLorebook(character, query) }.getOrDefault(emptyList())
        .take(AppLimits.Lorebook.INJECT_ENTRIES)
    block(buildLorebookInjection(loreEntries))

    if (character.isGroup) {
        character.members.forEach { member ->
            val memberChar = Character(
                id = member.id,
                name = member.name,
                staticProfile = member.staticProfile,
                dynamicState = member.dynamicState,
                shortTerm = member.shortTerm,
                longTerm = member.longTerm,
                lorebook = member.lorebook,
            )
            val privateEntries = runCatching { memory.retrieve(memberChar, memoryQuery, 2) }.getOrDefault(emptyList())
            if (privateEntries.isNotEmpty()) {
                block(
                    "【${member.name}的私人记忆，仅该成员知道】\n" +
                        privateEntries.joinToString("\n") { maskUserWord(character, trimText(it.value, 1200)) },
                )
            }
        }
    }

    val corrections = recent
        .filter { it.role == Role.User && Regex("填过|记错|记反|不是|不止|纠正|correct|wrong", RegexOption.IGNORE_CASE).containsMatchIn(it.content) }
        .takeLast(2)
        .map { "[${it.id}] ${trimText(it.content, 200)}" }
    if (corrections.isNotEmpty()) {
        block("【用户最新纠正（优先于旧记忆；未明确完成或取消时不得擅自结束约定）】\n" + corrections.joinToString("\n"))
    }

    getLastReplyGap(character)?.let { (text, reconnect) ->
        if (reconnect) block("【互动节奏】距上次回复已过 $text。可温和重连，不施压，不编造期间发生的事。")
    }

    val sceneLines = character.scenes.takeLast(AppLimits.Scene.SUMMARIES)
        .map { maskUserWord(character, it.content) }
    if (sceneLines.isNotEmpty()) {
        block(
            "【场景概要（较早情节的压缩记录，只供保持连贯，不是最近发生的事）】\n" + sceneLines.joinToString("\n"),
        )
    }

    block(
        "【本轮主动召回的相关长期记忆】\n" + (memoryLines.joinToString("\n").ifBlank { "无相关长期记忆" }) +
            "\n【记忆取用说明】以上条目是按关键词与时间粗略召回的，可能只有部分相关，也可能已经过时；仅在与当前话题自然相关时提及，不要硬提旧事，也不要把它们当成用户刚刚说过的话。" +
            "\n【记忆主体约定】方括号里是\"这条记忆讲的是谁\"：标为角色名或\"背景\"的才是角色的事，标为“" +
            normalizeUserAddress(character.staticProfile.userAddress).ifBlank { "对方" } +
            "”或\"你们\"的都是对方或双方的事，绝不能当成角色自己做的。",
    )

    sb.append("\n\n").append(timeBlock).append(timelineBlock).append("\n").append(tail)

    var context = sb.toString()
    if (proactiveTurn) {
        context += "【角色主动开场（系统触发，不是用户陈述）】用户此刻正停留在与你的对话里但暂时没有说话。请角色主动发起一次开场：自然地打招呼或问候，或提起最近一次共同经历、一件待跟进的事项、一个角色此刻想聊的话题。开场要贴合角色性格、自然不突兀；不要问\"你想聊什么\"\"怎么不说话\"之类把话题推回给用户的话；不要把\"系统触发\"这件事说给用户听。\n\n"
    }
    if (hotTopicTurn) {
        context += "【本轮热榜话题（系统触发，不是用户陈述）】先调用一个热榜工具查一次热榜（zhihu_hot 话题性最强、建议优先；baidu_hot / toutiao_hot / douyin_hot 更偏社会新闻），从结果里挑**一条**与角色人设、与你们的共同兴趣最合得来、能自然引入的话题。把它带进这一轮：本轮若是主动开场就围绕它起个头，若是回应对方就在合适时自然引到它。只挑一条，不要罗列榜单、不要复读热度数字，也不要提\"热搜/热榜/系统/工具\"这类词。若对方此刻在说严肃、紧急或伤心的事，就正常回应，不必强行提话题。工具没取到内容（ok:false）就按平常方式回应，不得编造话题。\n\n"
    }
    return context
}

// ---------------------------------------------------------------------------
// Request builder.
// ---------------------------------------------------------------------------

enum class RequestPhase { AUTO, SUBMIT }

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
        val submitPhase = phase == RequestPhase.SUBMIT
        return LlmRequest(
            model = model,
            instructions = instructions,
            input = input,
            tools = tools,
            toolChoice = if (submitPhase) ToolChoice.Function("submit_response") else ToolChoice.Auto,
            temperature = normalizeTemperature(config.temperature),
            maxOutputTokens = 8192,
            stream = config.stream,
            reasoningEffort = if (submitPhase) "none" else config.reasoningEffort,
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
                        mapped += message.copy(content = "{\"reply\":" + jsonQuote(text) + "}")
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
