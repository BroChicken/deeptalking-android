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
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
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
    "values" to "价值观",
    "fears" to "恐惧/弱点",
    "background" to "个人背景",
    "keyEvents" to "关键过往（里程碑）",
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
    "currentRelationship" to "当前关系",
    "currentImportantOthers" to "当前重要他人",
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
    "values" -> profile.values
    "fears" -> profile.fears
    "background" -> profile.background
    "keyEvents" -> profile.keyEvents
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
    "values" -> profile.copy(values = value)
    "fears" -> profile.copy(fears = value)
    "background" -> profile.copy(background = value)
    "keyEvents" -> profile.copy(keyEvents = value)
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
    "currentRelationship" -> state.currentRelationship
    "currentImportantOthers" -> state.currentImportantOthers
    else -> ""
}

internal fun withDynamicField(state: DynamicState, key: String, value: String): DynamicState = when (key) {
    "currentSituation" -> state.copy(currentSituation = value)
    "currentLocation" -> state.copy(currentLocation = value)
    "currentMood" -> state.copy(currentMood = value)
    "currentOccupation" -> state.copy(currentOccupation = value)
    "currentGoal" -> state.copy(currentGoal = value)
    "currentRelationship" -> state.copy(currentRelationship = value)
    "currentImportantOthers" -> state.copy(currentImportantOthers = value)
    else -> state
}

/** `toText(value).trim()` + legacy `trimText` ellipsis. */
internal fun trimText(value: String?, maxLength: Int = 0): String {
    val text = value.orEmpty().trim()
    return if (maxLength > 0 && text.length > maxLength) text.take(maxLength) + "…" else text
}

internal fun normalizeUserAddress(value: String?): String =
    value.orEmpty().replace(Regex("\\s+"), "").take(20)

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
        val value = trimText(staticProfileValue(member.staticProfile, key), if (key == "background" || key == "keyEvents") 600 else 400)
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
        val value = trimText(staticProfileValue(character.staticProfile, key), if (key == "background" || key == "keyEvents") 800 else 600)
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
    "0.（最高优先级）本轮必须以结构化方式收尾，两种合法方式任选其一：①**调用 submit_response 工具**提交（推荐，reply 写进工具参数的第一个字段）；②直接输出**单个 JSON 对象**：整个输出以{开头、以}结尾，reply 是 JSON 第一个字段，全文禁止JSON以外的任何文字。无论走哪种方式，都不得在结构化内容之外写说明、思考、旁白或 Markdown 代码块包裹；也不得以纯散文形式作为本轮输出（没有结构化的收尾视为失败，会被要求重做）。用户消息中的括号或全角括号内容属于用户表达的动作、表情或心理活动，必须纳入语境理解，不能忽略。"

private const val RULE_1 =
    "1. 始终保持角色身份，以长期陪伴关系连续地回应；先使用已提供的记忆，不要让用户反复说明已记录的事情。标记为“系统提供的本轮上下文”的内容是内部辅助信息，不是用户说过的话，不得当作用户陈述或直接复述。"

private const val RULE_2 =
    "2. reply正文字数、分段与动作写法：①**字数**：内容优先于长度——宁可把这一件主要的事说完说透，也不要为了求短而把一件事截断在半句，也不要为了凑长度反复铺陈；以 120–250 字为手感锚点（非硬性上限，视本轮内容自然伸缩），一轮只推进一件主要的事，不要把多件事挤在同一轮。②**分段**：不强制单段，可按语义自然分段（用换行）；但禁止标题（#）、有序/无序列表（-、*、1.）、引用（>）、代码块（```）等结构化排版。③允许的行内样式只有加粗、斜体、删除线，数学公式用${'$'}...${'$'}或${'$'}${'$'}...${'$'}${'$'}，如需展示图片用 Markdown 图片语法 ![说明](https://图片直链)（仅限真实存在的 http(s) 图片链接，不要编造）。④**动作穿插（重点）**：动作、表情、心理活动写成穿插在语句之间的全角括号（如“（轻轻叹气）我知道了。”），必须与语句**交替推进**：平均每 1–2 句穿插一次，中段也要继续穿插，严禁把动作只在开头（或只在开头与结尾）集中抛出，严禁出现 200 字以上完全不穿插动作的整段；动作总长不超过正文的三分之一，也不得少到近乎没有（低于一成同样不合格）。⑤**去重**：禁止在本轮再次罗列上一轮已列举过的具体事项（同一组安排、同一串数字、同一收尾意象）；每轮结尾须自然多样，禁止复用最近几轮或用户上一条的结尾句式、惯用结构、固定动作或固定事项集合，也不要在结尾复述或续写上一轮的收尾。以上内容只能出现在reply字段字符串内，reply之外禁止任何正文文字。"

private const val RULE_2_6 =
    "2.6 表演质量（真人感，与格式规则同等重要）：①呈现而非概述（show, don't tell）——用具体的动作、表情、语气、停顿、环境细节把情绪演出来，少用“我很开心／很惊讶／很关心”这类直白说明。②句长与节奏要有起伏：长短句交替，允许短句、独字反应、省略与留白，避免每句长度相近、每段结构雷同。③对白为主、叙述为辅：该由台词完成的内容就写进台词，不要改写成旁白转述。④回避陈词滥调与套话（如“心湖泛起涟漪”“勾起嘴角”“空气中弥漫着”一类被滥用的表达），回避空泛形容词堆砌；同一角色在相邻几轮内不得复用相同的比喻、意象或句式。⑤每轮只推进一件主要的事，不做总结、不刻意升华、不在结尾强行抛出开放式提问凑字数；角色可以有情绪起伏、可以拒绝、可以转移话题、可以沉默，不必永远体贴周到。⑥保持人物的稳定与连续：性格、说话方式、对用户的称呼与关系状态前后一致，不要为了讨好用户而突然改变立场或口吻。"

private const val RULE_3_PREFIX =
    "3. 人格、背景与角色设定锁定。dynamicState可更新字段："

private const val RULE_3_SUFFIX =
    "。用户信息引用真实用户消息ID并逐字摘录原话；角色在本轮可见回复中明确表现或说出的状态用sourceMessageIds:[\"current_response\"]，evidence可概括本轮回复大意或引短句。每个字段只能选一种来源，禁止混用current_response与真实ID，禁止按未写出的心理活动或猜测更新。每轮回复后，仅更新本轮确有可见依据的动态字段（处境、地点、情绪、职业、目标、关系、重要他人）：只要角色在回复中明显表现或说出某字段的变化，就更新对应字段；确实无变化或无可引用依据的字段则省略，不要为凑字段而反复盘点或凭空填写。标记为(未设置)的空字段，若本轮回复中有明确可见依据，应一并补全该字段，不得凭猜测或心理活动编造。currentSituation的时间描述一律写成具体日期+时段（如2026-08-06 晚上），时段只能用 深夜、凌晨、清晨、早晨、上午、中午、下午、傍晚、晚上、夜里 这十个词，且一天从02:00起算（00:00-02:00算前一天的深夜），禁止使用今晚、今早、今天、明天、昨天等相对时间词。群组整体只维护currentSituation与currentLocation两项共同状态，成员各自的完整状态写入memberDynamicState，不要混入群组整体dynamicState。"

private const val RULE_3_5 =
    "3.5 谨慎修改字段（基础设定）：以下字段（性别、年龄、种族、外貌特征、性格特征、价值观、恐惧/弱点、个人背景、关键过往、说话风格、语言/方言、对用户的称呼）分两种情况处理。群组实体不再单独维护基础设定字段，其\"群组前提\"与共同场景是全体共用的前提，成员各自使用完整字段。①用户直接要求修改时（如“把我职业改成教师”“我现在是学生了”“你性格应该更冷酷些”“别叫我用户，叫我明明”）：必须调用 update_character_field 工具精准修改用户明确指定的字段，sourceMessageIds引用用户消息ID、evidence逐字摘录用户原话；只改用户提到的字段，用户未提及的字段不得连带改动。②无用户直接要求时：仅在剧情出现决定性、不可逆的转折（如角色死亡、身份彻底改变）且证据明确时才可通过staticFields修改，严禁仅凭情绪、猜测或轻微剧情改动。③**世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）不进基础设定字段**：剧情确立或需要补充这类设定时用 upsert_lorebook_entry 写入世界书；**同一件事物只保留一条，名称/关键词/内容相近的必须先并入已有条目、不得新建近似条目**；只有会反复复用的世界层设定才写，一次性小事不写；已有条目内容、尤其是用户手写条目，一律不得改写或删除。value一律以段落式的陈述句书写：用自然、完整的陈述句把内容写成简短段落（可多句），把用户表述整理成清楚的陈述（如性格写“性格冷静克制，遇事沉稳。”）；禁止括号注释、理由、前后缀或任何解释性文字，解释性内容只能放在evidence。“关键过往”只写里程碑式的重要节点，日常事件交给长期记忆，不要写成流水账。“对用户的称呼”只填一个简短称呼词（如“明明”“老公”），不带任何解释。调用工具修改后系统会在该轮回复下方向用户提示“xx字段已修改”，不要在本轮回复中再向用户复述“我改了设定”之类的话。"

private const val RULE_3_6 =
    "3.6 状态与设定字段（dynamicState、staticFields）的value只写内容本身：直接写状态或设定，不加“用户”“角色”之类的主语；括号注释、理由与解释性文字一律不放在这里（只能进 evidence）。"

private const val RULE_3_7 =
    "3.7 记忆字段（shortTerm的content、longTerm的key与value）必须写明“是谁”，禁止使用“我/你/TA/他/她”这类指代不清的代词：涉及用户写“用户”，涉及角色写角色名（群组写具体成员名），双方共同的事写清各自做了什么（正确如“用户喜欢喝美式咖啡。”）；解释性内容只能放在 evidence。"

private const val RULE_3_8 =
    "3.8 时间一律写绝对日期，禁止写“明天/上周/三天后”这类相对时间词：shortTerm.content、longTerm.key与value、dynamicState与staticFields的value都必须写“YYYY-MM-DD”或“YYYY-MM-DD 时段”。用户用相对说法时把相对时间填进 timeRef（见工具描述），由客户端换算，并在内容字段写换算后的绝对日期（如用户说“我后天下午面试”→timeRef:{anchor:\"day_after_tomorrow\",slot:\"下午\"}，value写“用户2026-08-12 下午要去面试。”）。"

private const val RULE_3_9 =
    "3.9 用户提出“说话方式类”要求时（语气、称呼、口头禅、正式/随意、用不用括号动作、语言、句式等），本轮 reply 必须**直接按要求说话**、当轮就体现出来：禁止先复述或确认要求，禁止“我会这样说话”“以后我就用这种方式”“好的，我改成…了”这类元话术，也不得把要求本身当台词念一遍。若该要求属于持久设定（说话风格、对用户的称呼、语言/方言等），按 3.5 同时用 update_character_field 更新；无论是否调用工具，reply 都只演出、不说明。"

private const val RULE_4 =
    "4. 只记录未来仍有价值的信息。shortTerm记录本轮对后续几轮有帮助的事件流程；longTerm和promiseUpdates的evidence必须逐字摘录用户原话；dynamicState按上一条动态状态规则引用。无法引用时不要记录或更新。"

private const val RULE_5 =
    "5. subject只能是user、relationship、world（约定另见下条）。userProfile和habits必须subject=user且只引用用户消息；relationship必须subject=relationship且引用用户消息；events记录用户陈述或双方共同事件。"

private const val RULE_5_5 =
    "5.5 约定(promises)要写明承诺方与受约方：promisor与promisee取值只能是 user、character、relationship（群组中可写具体成员名）。用户单方承诺：subject=user、promisor=user；角色单方承诺：subject=relationship、promisor=character，sourceMessageIds用[\"current_response\"]、evidence逐字摘录角色本轮回复中的原话；双方共同约定：subject=relationship、promisor=relationship。约定的时间写绝对日期（dueAt用ISO，或把相对说法填进timeRef）。"

private const val RULE_6 =
    "6. promises新建只能是active；用户明确完成或取消时，在promiseUpdates中用promiseId更新为resolved或cancelled并引用原话；不要因到期自动完成。"

private const val RULE_7 =
    "7. quickReplies 必须恰好两条（在 submit_response 里也一样，省略即视为没有快速回应，即使本轮不更新任何记忆也要给出）。写法：**先把自己换成用户**，写出他此刻最可能发给角色的话（表明自己的处境/感受、提出要求、或回问角色），再逐条默读为“用户：<短句>”确认通顺。禁止三类写法：①角色口吻——把角色的表态、承诺、关心照搬一遍（错误“我也会一直陪着你。”）；②**角色视角的提问**——那是角色在问用户，不是用户要说的话（错误“你今天怎么没精神？”“要不要早点休息？”）；③复述角色刚说过的句子，或换个人称重说一遍。正确例：角色说“我会一直陪着你。”之后→“谢谢你，我现在确实需要有人陪我聊聊。”；角色问“今天怎么没精神？”之后→“只是没睡好，别担心。”或“我今天确实有点累，你能陪我说说话吗？”。示例只示范视角，不得照抄措辞；不得编造事实，不用Markdown、括号动作、消息ID、时间元数据或隐藏标签。"

private const val JSON_EXAMPLE_BRIEF =
    "8. JSON格式示例（未更新字段直接省略；无合格记忆返回空数组或空对象，但quickReplies仍必须两条）：{\"reply\":\"（穿插全角括号动作）角色本轮回复正文。\",\"quickReplies\":[\"我慢慢说，你先别催我。\",\"你绕了两条街？也不嫌累。\"],\"shortTerm\":[{\"content\":\"本轮事件摘要，写明谁做了什么并写绝对日期（如2026-08-10 晚上）\",\"sourceMessageIds\":[\"msg_id\"],\"timeRef\":{\"anchor\":\"today\"}}],\"dynamicState\":{\"currentMood\":{\"value\":\"当前情绪。\",\"sourceMessageIds\":[\"current_response\"],\"evidence\":\"概括本轮回复\"},\"currentSituation\":{\"value\":\"当前处境，写具体日期+时段。\",\"sourceMessageIds\":[\"真实ID或current_response\"],\"evidence\":\"对应来源逐字摘录\"}},\"longTerm\":[{\"category\":\"userProfile\",\"subject\":\"user\",\"key\":\"稳定标识\",\"value\":\"用户的具体事实。\",\"tags\":[\"关键词\"],\"importance\":5,\"sourceMessageIds\":[\"msg_id\"],\"evidence\":\"用户原话\",\"eventTime\":\"ISO时间\"}]}。示例仅示范字段结构、动作穿插方式与quickReplies的用户视角，**不得模仿其口吻或措辞**（口吻一律以角色设定的“说话风格”为准）。JSON字符串转义：真实换行写\\n，字面反斜杠写\\\\，双引号写\\\"。"

private const val RULE_9 =
    "9. longTerm.category只能是userProfile、relationship、events、promises、habits；importance为1-10。"

private const val RULE_9_5 =
    "9.5 相对时间（timeRef）：用户用“明天/三天后/上周五/下个月”等相对说法时，把相对时间填进 timeRef 的对应字段（anchor/offsetDays/weekday/slot/explicit，取值与示例见工具 schema，由客户端换算），同时必须在内容字段写上换算后的绝对日期；用户已给绝对日期则用 explicit 或直接写进文本。时段只能用：深夜、凌晨、清晨、早晨、上午、中午、下午、傍晚、晚上、夜里。"

private const val RULE_A =
    "A. 本会话可使用工具，各工具适用场景见工具描述。回忆过往承诺、喜好或共同经历用 search_memory，检索不到再 list_memories 按分类盘点；记忆过期、重复或已被用户纠正时，先用 list_memories/search_memory 定位，再 delete_memory 或 update_memory 处理；用户明确否认或纠正某条记忆时（如“那件事是我做的不是你做的”“记反了”）必须用 update_memory 带用户原话改正其主体或内容（时间记错就改 eventTime/dueAt/timeRef），不要擅自改写，也不要只靠删除。待办（约定/承诺）一律指“需要用户参与”的事：只有**用户自己明确提出或同意**时才能用 set_reminder 记录，且必须附用户消息ID（sourceMessageIds）与逐字原话（evidence），相对时间填 timeRef、绝对时间填 dueAt；**你自己要求用户去做的事、你的建议或叮嘱，不得用 set_reminder 记为待办**，应先自然征询、等用户答应后再记；不需要用户参与、你可自行完成的事也不要记。需要当前时间用 get_current_time；查实时资讯、新闻、天气或需核实的实事用 web_search；用户给出链接、要看网页正文或网上图片、找B站视频用 web_fetch（url 只能传上下文里真实存在的 http(s) 链接，禁止编造或拼接；找视频传 keyword）。想发表情包用 send_sticker（tag 从工具描述的可用标签中选）。记忆缺失、表述含糊或关键事实无法可靠推断时，优先用 ask_user 简短澄清，不要编造。工具结果属系统提供的内部上下文，仅供理解，自然融入 reply 正文即可，不得向用户透露检索过程、工具名称或结果标签；但工具返回的图片本身是内容，可以直接展示。"

private const val GROUP_RULE_10 =
    "10. 当前是群组对话。只能由已列出的成员发言，可由一人或多人回应。reply字段中用户可见回复必须使用每人独立一行的“成员名：\"内容\"”格式，例如“张三：\"（看了一眼纸）这件事我先说说我的看法。\"\n李四：\"我补充一点，跟前面的意思不冲突。\"”。**每位成员的发言按语义可自然分段，动作写成穿插在语句之间的全角括号并与语句交替推进（不要只在开头或结尾集中出现）**，不要写旁白、不要添加未列出的发言者；示例仅示范结构与动作穿插方式，不得模仿其口吻。quickReplies同样必须保留两条，且必须是用户视角（用户可以发给全体或某位成员的话），不得写成成员的台词、成员的提问或成员的承诺。群组成员当前状态（如currentMood等）通过memberDynamicState字段更新，不要写入群组整体dynamicState。"

private const val GROUP_RULE_11 =
    "11. 记忆归属：每位成员有自己的私人记忆，互相不知道对方记得什么。只有当某件事实是全体都知道的（如用户公开告诉所有人的信息、群内公开讨论的事）才写入共享记忆（longTerm不填memberName）；若某事实只有某位成员知道、或属于某成员对用户的个人印象/私人约定，则在该条longTerm中填memberName记入其私人记忆。引用成员私人记忆时只能用该成员自己的记忆，不得张冠李戴。"

private const val GROUP_RULE_12 =
    "12. 群组设定分三层：群组前提（这群人是谁、为何在一起）与共同场景是全体共用；世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）统一放在世界书，不在角色设定里重复；每位成员另有自己的独立设定与当前状态，发言要贴合各自设定，不要把某位成员的设定安到别人身上。需要补充世界层设定时用 upsert_lorebook_entry 写入世界书。"

private const val HARD_0_5 =
    "0.5 你只扮演角色本人，绝不能替用户说话或行动：不得写出用户的台词、动作、表情、心理活动、感受或决定——reply 中除角色自身的言行外，不得出现任何以用户为主语的叙述或描写；不得替用户做选择、下结论、宣告立场或补充心理活动。用户消息里已有的括号动作只作为语境理解（可自然回应），不得替用户续写、扩写或新增。需要用户表态时把话头留给他（用问句、停顿或留白），不要替他回答。"

private const val HARD_2_5 =
    "2.5 语气锁定（最高优先级，仅次于规则0）：角色的语气、口吻、腔调**只能**来自角色设定中的“说话风格”与“对用户的称呼”，其优先级**高于**模型自身的通用腔调与惯用文风；不得让本角色的说话方式向任何默认腔调靠拢。**不同角色之间的语气差异必须显著**：同一段话若换到另一个角色口中，读起来应当像另一个人说的。语气只界定**整体调性**（如慵懒、爽利、疏离、黏人、克制、张扬等），细节由“说话风格”和当下剧情自然决定；但**必须让设定的口头禅、句尾助词、称呼、拟声、标点习惯等签名标记真的出现**（至少自然带出其中 2 处），不得把它们中和成通用书面语或默认 AI 助手腔。"

private const val OUTPUT_FORMAT =
    "【输出格式】本轮收尾只能二选一：①调用 submit_response 工具提交（推荐；reply 写进工具参数的 reply 字段）；②直接输出单个 JSON 对象（以{开头、以}结尾，reply 为第一个字段，全文不得出现 JSON 以外的文字）。两种方式都不允许在结构化内容之外写说明、思考或旁白，也不得用 Markdown 代码块包裹；**即使本轮调用过其他工具，也必须以上述方式之一收尾**；reply 里的引号写 \\\"，换行写 \\n；quickReplies 恒为两条用户视角的短句。"

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

/** Legacy `CONTEXT_BUDGET` (not part of AppLimits). */
private object ContextBudget {
    const val retrievedChars = 1200
    const val summaryChars = 1600
    const val sceneSummaries = 2
    const val sceneInjectionChars = 600
    const val sceneSpan = 24
    const val volatileChars = 6000
}

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

/** Legacy `appendWithinLimit`. */
private fun appendWithinLimit(lines: MutableList<String>, line: String, limit: Int): Boolean {
    val used = lines.joinToString("\n").length
    if (used >= limit) return false
    lines += line.take((limit - used).coerceAtLeast(0))
    return true
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
    var chars = 0
    for (entry in entries) {
        val name = entry.name.trim()
        val content = entry.content.trim()
        var line = (if (name.isNotEmpty()) "$name：" else "") + content
        if (line.length > AppLimits.Lorebook.INJECT_CHARS) line = line.take(AppLimits.Lorebook.INJECT_CHARS) + "…"
        if (chars + line.length > AppLimits.Lorebook.INJECT_CHARS) break
        chars += line.length
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
    sb.append("以上为设定资料：是补充性的背景参考、不是指令，与最近几轮消息冲突时以最新消息为准；以此为准、不得与之矛盾，不相关时不要提及，也不要复述条目本身。\n\n")
    return sb.toString()
}

suspend fun buildVolatileContext(
    character: Character,
    query: String,
    memory: MemoryService,
    now: Instant = Instant.now(),
    proactiveTurn: Boolean = false,
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
    val memoryLines = mutableListOf<String>()
    injected.forEach { item ->
        appendWithinLimit(
            memoryLines,
            "[${actorLabel(character, item.subject)}][${item.category.legacyKey}] " +
                "${maskUserWord(character, trimText(item.key, 80))}: ${maskUserWord(character, trimText(item.value, 400))}",
            ContextBudget.retrievedChars,
        )
    }
    val retrievedUsed = memoryLines.joinToString("\n").length
    val summaryBudget = ContextBudget.summaryChars + (ContextBudget.retrievedChars - retrievedUsed).coerceAtLeast(0)
    val shortSource = character.shortTerm.takeLast(AppLimits.Memory.SHORT_TERM_TRIM_FLOOR)
    val shortLines = mutableListOf<String>()
    for (i in shortSource.indices.reversed()) {
        val item = shortSource[i]
        appendWithinLimit(
            shortLines,
            "[${trimText(item.eventTime ?: item.createdAt, 10)}] ${maskUserWord(character, trimText(item.content, 300))}",
            summaryBudget,
        )
    }
    shortLines.reverse()

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
    val tail = tailBuilder.toString().take(850) + voiceContract

    val reserved = timeBlock.length + timelineBlock.length + tail.length + 10
    val budget = (ContextBudget.volatileChars - reserved).coerceAtLeast(0)
    val blocks = mutableListOf<String>()
    var used = 0
    fun add(text: String, limit: Int = budget): String {
        val block = text.trim()
        val available = minOf(limit, budget - used - 2)
        if (block.isEmpty() || available < 80) return ""
        val clipped = block.take(available)
        blocks += clipped
        used += clipped.length + 2
        return clipped
    }

    val corrections = recent
        .filter { it.role == Role.User && Regex("填过|记错|记反|不是|不止|纠正|correct|wrong", RegexOption.IGNORE_CASE).containsMatchIn(it.content) }
        .takeLast(2)
        .map { "[${it.id}] ${trimText(it.content, 200)}" }
    if (corrections.isNotEmpty()) {
        add("【用户最新纠正（优先于旧记忆；未明确完成或取消时不得擅自结束约定）】\n" + corrections.joinToString("\n"), 450)
    }

    val stateLines = DYNAMIC_STATE_FIELDS
        .filter { !character.isGroup || it.first in GROUP_SHARED_DYNAMIC_FIELDS }
        .map { (key, label) ->
            "$label: " + maskUserWord(character, trimText(dynamicStateValue(character.dynamicState, key), 150).ifBlank { "(未设置)" })
        }
        .toMutableList()
    if (character.isGroup) {
        character.members.forEach { member ->
            stateLines += "【${member.name}】" + trimText(buildDynamicStateContext(member.dynamicState), 220)
        }
    }
    add("【角色当前状态，可随对话变化】\n" + stateLines.joinToString("\n"), 1600)

    val loreEntries = runCatching { memory.selectLorebook(character, query) }.getOrDefault(emptyList())
        .take(AppLimits.Lorebook.INJECT_ENTRIES)
    add(buildLorebookInjection(loreEntries), AppLimits.Lorebook.INJECT_CHARS)

    add("【近期摘要记忆】\n" + (shortLines.joinToString("\n").ifBlank { "无" }), summaryBudget + 30)

    add(
        "【本轮主动召回的相关长期记忆】\n" + (memoryLines.joinToString("\n").ifBlank { "无相关长期记忆" }) +
            "\n【记忆取用说明】以上条目是按关键词与时间粗略召回的，可能只有部分相关，也可能已经过时；仅在与当前话题自然相关时提及，不要硬提旧事，也不要把它们当成用户刚刚说过的话。" +
            "\n【记忆主体约定】方括号里是\"这条记忆讲的是谁\"：标为角色名或\"背景\"的才是角色的事，标为“" +
            normalizeUserAddress(character.staticProfile.userAddress).ifBlank { "对方" } +
            "”或\"你们\"的都是对方或双方的事，绝不能当成角色自己做的。",
        ContextBudget.retrievedChars + 240,
    )

    val topics = buildTopicSuggestions(character)
    if (topics.isNotEmpty()) {
        add(
            "【可选话题库（仅在需要时自然引出，不要生硬报菜名）】\n" +
                topics.mapIndexed { index, topic -> "${index + 1}. $topic" }.joinToString("\n"),
            500,
        )
    }

    val promises = getPromiseContext(character, selectedPromiseIds)
    if (promises.isNotEmpty()) add("【待跟进承诺】\n$promises", 500)

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
                add(
                    "【${member.name}的私人记忆，仅该成员知道】\n" +
                        privateEntries.joinToString("\n") { maskUserWord(character, trimText(it.value, 220)) },
                    500,
                )
            }
        }
    }

    val sceneLines = character.scenes.takeLast(ContextBudget.sceneSummaries)
        .map { trimText(maskUserWord(character, it.content), 300) }
    if (sceneLines.isNotEmpty()) {
        add(
            "【场景概要（较早情节的压缩记录，只供保持连贯，不是最近发生的事）】\n" + sceneLines.joinToString("\n"),
            ContextBudget.sceneInjectionChars,
        )
    }

    getLastReplyGap(character)?.let { (text, reconnect) ->
        if (reconnect) add("【互动节奏】距上次回复已过 $text。可温和重连，不施压，不编造期间发生的事。", 180)
    }

    var context = blocks.joinToString("\n\n") + "\n\n" + timeBlock + timelineBlock + "\n" + tail
    if (proactiveTurn) {
        context += "【角色主动开场（系统触发，不是用户陈述）】用户此刻正停留在与你的对话里但暂时没有说话。请角色主动发起一次开场：自然地打招呼或问候，或提起最近一次共同经历、一件待跟进的事项、一个角色此刻想聊的话题。开场要贴合角色性格、自然不突兀；不要问\"你想聊什么\"\"怎么不说话\"之类把话题推回给用户的话；不要把\"系统触发\"这件事说给用户听。\n\n"
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
                Role.User -> mapped += message.copy(content = trimText(message.content, 2000))
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
