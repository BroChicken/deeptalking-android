package com.deeptalking.domain.agent.background

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.domain.agent.ResponseParser
import com.deeptalking.domain.agent.prompts.trimText
import com.deeptalking.domain.agent.sessionIdFor
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.domain.memory.getTimeSlot
import com.deeptalking.domain.memory.logicalDay
import com.deeptalking.domain.memory.parseZoned
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

@Serializable
internal data class ExtractSource(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: String? = null,
)

@Serializable
internal data class RecentEventIdentity(
    val eventTime: String? = null,
    val participants: List<String> = emptyList(),
    val location: String = "",
    val content: String = "",
)

@Serializable
internal data class AnalysisInput(
    val id: String,
    val content: String,
    val sourceMessageIds: List<String> = emptyList(),
    val eventTime: String? = null,
)

private data class PreparedPoint(
    val content: String,
    val eventTime: String?,
    val sourceMessageIds: List<String>,
    val firstIndex: Int,
)

private val META_TALK_REGEX = Regex(
    "(好的|收到|明白|知道了)[，,。、]?\\s*(我)?(会|改成|改用|调整|照做|注意|这样(说话|说))|以后我(就)?(用|按)这(种|个)(方式|语气)|我(会)?照你说的(来|说|做)",
)

fun detectStyleViolations(reply: String, character: Character, previousReplies: List<String>): List<String> {
    val text = reply
    val hits = mutableListOf<String>()
    if (text.trim().isEmpty()) return hits
    val address = com.deeptalking.domain.agent.prompts.normalizeUserAddress(character.staticProfile.userAddress)

    if (META_TALK_REGEX.containsMatchIn(text)) hits += "metaTalk"

    val safeAddress = address.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fff]"), "")
    val quotedLine = if (safeAddress.isNotEmpty()) {
        Regex("(^|\\n)\\s*(用户|$safeAddress|你)\\s*[：:]\\s*[「『“\"]")
    } else {
        Regex("(^|\\n)\\s*(用户|你)\\s*[：:]\\s*[「『“\"]")
    }
    if (quotedLine.containsMatchIn(text)) hits += "speaksForUser"

    if (STYLE_CLICHES.any { text.contains(it) }) hits += "cliche"

    val priors = previousReplies.filter { it.isNotEmpty() }
    if (priors.isNotEmpty()) {
        val currentEnding = extractReplyEnding(text)
        if (currentEnding.isNotEmpty() && priors.any { extractReplyEnding(it) == currentEnding }) {
            hits += "reusedImagery"
        } else {
            val names = listOf(address, character.name).filter { it.isNotEmpty() }
            val strippedCurrent = names.fold(text) { value, name -> value.replace(name, " ") }
            val strippedPriors = priors.map { prior -> names.fold(prior) { value, name -> value.replace(name, " ") } }
            if (sharesDistinctivePhrase(strippedCurrent, strippedPriors)) hits += "reusedImagery"
        }
    }

    if (isSentenceLengthUniform(text)) hits += "toneDrift"

    val recitedLore = character.lorebook.any { entry ->
        val name = entry.name.trim()
        if (name.length < 2 || !text.contains(name)) {
            false
        } else {
            val content = entry.content.trim()
            content.length >= 12 && text.contains(content.take(12))
        }
    }
    if (recitedLore) hits += "recitedLore"

    return hits.distinct()
}

fun detectQuickReplyIssues(replies: List<String>, character: Character, replyText: String): List<String> {
    val list = replies.map { it.trim() }.filter { it.isNotEmpty() }
    val hits = mutableListOf<String>()
    if (list.size < 2) hits += "missing"
    val spoken = replyText.replace(Regex("\\s+"), "")
    val charName = character.name.trim()
    list.forEach { line ->
        val compact = line.replace(Regex("\\s+"), "")
        when {
            QUICK_REPLY_PLACEHOLDERS.contains(line) || Regex("^短句[一二1-9]$").matches(line) ->
                hits += "placeholder"

            Regex("^[（(]").containsMatchIn(line) || Regex("[（(][^）)]{1,20}[）)]").containsMatchIn(line) ->
                hits += "action"

            line.length > QUICK_REPLY_GUARD.maxChars ->
                hits += "tooLong"

            charName.length >= 2 && line.contains(charName) ->
                hits += "characterName"

            compact.length >= 4 && spoken.contains(compact) ->
                hits += "mirrored"

            else -> {
                if (compact.length >= QUICK_REPLY_GUARD.mirrorChars) {
                    var i = 0
                    var found = false
                    while (i + QUICK_REPLY_GUARD.mirrorChars <= compact.length && !found) {
                        val chunk = compact.substring(i, i + QUICK_REPLY_GUARD.mirrorChars)
                        if (chunk.all { it in '\u4e00'..'\u9fff' } && spoken.contains(chunk)) found = true
                        i += 2
                    }
                    if (found) hits += "mirrored"
                }
            }
        }
    }
    return hits.distinct()
}

class BackgroundTasks(
    private val llm: LlmBackend,
    private val memory: MemoryService,
    private val model: String = "deepseek-flash",
    private val apiPlatform: String? = null,
) {

    val queue: BackgroundTaskQueue = BackgroundTaskQueue()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    fun detectStyleViolations(
        reply: String,
        character: Character,
        previousReplies: List<String>,
    ): List<String> =
        com.deeptalking.domain.agent.background.detectStyleViolations(reply, character, previousReplies)

    fun detectQuickReplyIssues(
        replies: List<String>,
        character: Character,
        replyText: String,
    ): List<String> =
        com.deeptalking.domain.agent.background.detectQuickReplyIssues(replies, character, replyText)

    suspend fun extractMemory(
        character: Character,
        messages: List<ChatMessage>,
        migration: Boolean = false,
    ): Character {
        val sourceMessages = messages
            .filter { !it.isLoading && it.content.isNotBlank() }
            .take(AppLimits.Memory.SUMMARY_SOURCES)
        if (sourceMessages.isEmpty()) return character

        val recentIdentities = character.shortTerm
            .sortedByDescending { parseZoned(it.eventTime)?.toInstant()?.toEpochMilli() ?: 0L }
            .take(4)
            .map { RecentEventIdentity(eventTime = it.eventTime, content = trimText(it.content, 240)) }

        val sources = sourceMessages.map { message ->
            ExtractSource(
                id = message.id,
                role = message.role.name.lowercase(),
                content = trimText(message.content, 600),
                timestamp = message.timestamp,
            )
        }

        val countClause = if (migration) EXTRACTION_COUNT_UNLIMITED else EXTRACTION_COUNT_LIMITED
        val prompt = EXTRACTION_PROMPT_HEAD + countClause + EXTRACTION_PROMPT_TAIL +
            json.encodeToString(recentIdentities) + "\n对话内容:\n" + json.encodeToString(sources)

        val raw = complete(EXTRACTION_SYSTEM, prompt, temperature = 0.2, maxOutputTokens = 4096, sessionId = sessionIdFor(character))
        if (raw.isBlank()) return character

        val root = ResponseParser.parseJsonLenient(raw)
        var points = (root as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (!migration) points = points.take(15)
        if (points.isEmpty()) return character

        val sourceIdSet = sourceMessages.map { it.id }.toSet()
        val sourceIndexes = sourceMessages.withIndex().associate { it.value.id to it.index }
        val covered = mutableSetOf<String>()
        val prepared = mutableListOf<PreparedPoint>()

        for (point in points) {
            val ids = stringList(point["sourceMessageIds"]).filter { it.isNotBlank() }.distinct()
            val content = point.string("content").orEmpty()
            val eventTime = point.string("eventTime")
            if (trimText(content, 500).isEmpty()) return character
            if (eventIdentity(eventTime).isEmpty() || ids.isEmpty()) return character
            if (ids.any { it !in sourceIdSet }) return character
            val firstIndex = ids.minOf { sourceIndexes[it] ?: Int.MAX_VALUE }
            covered += ids
            prepared += PreparedPoint(content, eventTime, ids, firstIndex)
        }
        if (covered.isEmpty()) return character

        var coveredPrefixLength = 0
        while (coveredPrefixLength < sourceMessages.size && covered.contains(sourceMessages[coveredPrefixLength].id)) {
            coveredPrefixLength++
        }
        val filtered = if (coveredPrefixLength > 0) {
            prepared.filter { it.firstIndex < coveredPrefixLength }
        } else {
            prepared
        }
        if (filtered.isEmpty()) return character

        val nowIso = Instant.now().toString()
        val accepted = mutableListOf<ShortTermMemory>()
        val seenIdentities = mutableSetOf<String>()
        for (point in filtered) {
            var pointTime = normalizeTimestamp(point.eventTime, "")
            if (pointTime.isEmpty()) {
                var latestSourceTime = ""
                point.sourceMessageIds.forEach { id ->
                    val sourceTime = sourceMessages.firstOrNull { it.id == id }?.timestamp.orEmpty()
                    if (sourceTime > latestSourceTime) latestSourceTime = sourceTime
                }
                pointTime = latestSourceTime
            }
            if (pointTime.isEmpty()) pointTime = nowIso
            val content = trimText(point.content, 500)
            val identity = content + "|" + eventIdentity(pointTime)
            if (!seenIdentities.add(identity)) continue
            accepted += ShortTermMemory(
                id = newId("short"),
                content = content,
                sourceMessageIds = point.sourceMessageIds,
                eventTime = pointTime,
                createdAt = nowIso,
            )
        }
        if (accepted.isEmpty()) return character

        var updated = memory.applyTurn(character, shortTerm = accepted, longTerm = emptyList())
        if (updated.shortTerm.size >= AppLimits.Memory.SHORT_TERM) {
            updated = analyzeShortToLongTerm(updated)
        }
        return updated
    }

    suspend fun analyzeShortToLongTerm(character: Character): Character {
        val items = character.shortTerm.take(AppLimits.Memory.ANALYSIS_BATCH)
        if (items.isEmpty()) return character
        val inputs = items.map { item ->
            AnalysisInput(
                id = item.id,
                content = trimText(item.content, 600),
                sourceMessageIds = item.sourceMessageIds,
                eventTime = item.eventTime,
            )
        }
        val prompt = ANALYSIS_PROMPT_HEAD + json.encodeToString(inputs) + ANALYSIS_PROMPT_TAIL
        val raw = complete(ANALYSIS_SYSTEM, prompt, temperature = 0.2, maxOutputTokens = 4096, sessionId = sessionIdFor(character))
        if (raw.isBlank()) return character
        val root = ResponseParser.parseJsonLenient(raw) as? JsonObject ?: return character
        if (root.string("status") != "ok") return character
        val longTermArray = root["longTerm"] as? JsonArray ?: return character
        val parsed = longTermArray.mapNotNull { (it as? JsonObject)?.let(::parseLongTerm) }
        if (parsed.isEmpty()) return character
        return memory.applyTurn(character, shortTerm = emptyList(), longTerm = parsed)
    }

    suspend fun critiqueStyle(character: Character, reply: String): String {
        val original = reply
        if (original.trim().isEmpty()) return original
        val violations = detectStyleViolations(
            original,
            character,
            recentReplyTexts(character, STYLE_GUARD.lookbackReplies),
        )
        if (violations.isEmpty()) return original
        return critiqueWithViolations(character, original, violations)
    }

    private suspend fun critiqueWithViolations(
        character: Character,
        original: String,
        violations: List<String>,
    ): String {
        val rules = violations.joinToString("\n") { "- " + (STYLE_VIOLATION_LABELS[it] ?: it) }
        val prompt = "下面是一段角色扮演回复，以及它违反的文风规则清单。请修订文风。\n" +
            "硬性要求：①只改文风，绝不改动情节、事实、对话含义与人物关系；②保持大体长度与段落数；③不要新增情节要素、不要加解释或旁白；④不得复述规则本身。\n" +
            "违反的规则：\n" + rules + "\n\n" +
            "只返回 JSON：{\"reply\":\"修订后的正文\"}，换行写 \\n，双引号写 \\\"。\n\n原文：\n" + original
        val raw = complete(CRITIQUE_SYSTEM, prompt, temperature = 0.7, maxOutputTokens = 4096, sessionId = sessionIdFor(character))
        if (raw.isBlank()) return original
        val data = ResponseParser.parseJsonLenient(raw) as? JsonObject ?: return original
        val revised = ResponseParser.unescapeLiteralNewlines(data.string("reply").orEmpty()).trim()
        if (revised.isEmpty() || revised == original) return original
        if (revised.length > STYLE_GUARD.critiqueMaxChars) return original
        if (revised.length < original.length * STYLE_GUARD.critiqueMinRatio) return original
        if (revised.length > original.length * STYLE_GUARD.critiqueMaxRatio) return original
        return revised
    }

    suspend fun repairQuickReplies(
        character: Character,
        reply: String,
        userText: String,
        current: List<String>,
    ): List<String> {
        val currentIssues = detectQuickReplyIssues(current, character, reply)
        if (current.size >= 2 && currentIssues.isEmpty()) return current
        val fallback = if (current.size >= 2) current else listOf("嗯", "继续")
        val prompt = buildQuickReplyAsUserPrompt(character, reply)
        val recentUserLines = recentUserTexts(character, userText)
        val userContent = if (recentUserLines.size > 1) {
            "【用户最近说过的话，仅用于参考语气，不要照抄】\n" +
                recentUserLines.dropLast(1).joinToString("\n") + "\n\n" + prompt.second
        } else {
            prompt.second
        }
        val raw = complete(prompt.first, userContent, temperature = 0.8, maxOutputTokens = 1024, sessionId = sessionIdFor(character))
        if (raw.isBlank()) return fallback
        val list = ResponseParser.parseQuickReplyList(ResponseParser.parseJsonLenient(raw))
        if (list.size < 2) return fallback
        if (detectQuickReplyIssues(list, character, reply).isNotEmpty()) return fallback
        return list
    }

    fun scheduleTurn(
        character: Character,
        reply: String,
        userText: String,
        currentQuickReplies: List<String>,
        messages: List<ChatMessage>,
        critiqueEnabled: Boolean,
        quickReplyRepairEnabled: Boolean,
        onCharacterUpdated: (Character) -> Unit,
    ): Job = queue.enqueue {
        var working = character

        val violations = detectStyleViolations(
            reply,
            working,
            recentReplyTexts(working, STYLE_GUARD.lookbackReplies),
        )
        if (critiqueEnabled && violations.isNotEmpty()) {
            val revised = critiqueWithViolations(working, reply, violations)
            working = updateReplyMessage(working, reply) { message ->
                message.copy(content = revised.ifEmpty { message.content }, styleViolations = violations)
            }
        }

        val issues = detectQuickReplyIssues(currentQuickReplies, working, reply)
        if (quickReplyRepairEnabled && (currentQuickReplies.size < 2 || issues.isNotEmpty())) {
            repairQuickReplies(working, reply, userText, currentQuickReplies)
            working = updateReplyMessage(working, reply) { it.copy(quickReplyIssues = issues) }
        }

        val extracted = extractMemory(working, messages)
        if (extracted != working) working = extracted

        // Consolidate short-term events into long-term memories (legacy analyzeShortToLongTerm).
        val analyzed = analyzeShortToLongTerm(working)
        if (analyzed != working) working = analyzed

        if (working != character) onCharacterUpdated(working)
    }

    private fun updateReplyMessage(
        character: Character,
        reply: String,
        transform: (ChatMessage) -> ChatMessage,
    ): Character {
        val index = character.instant.indexOfLast { it.role == Role.Assistant && !it.isLoading }
        if (index < 0) return character
        if (character.instant[index].content != reply) return character
        val updated = character.instant.toMutableList()
        updated[index] = transform(updated[index])
        return character.copy(instant = updated)
    }

    private fun recentUserTexts(character: Character, userText: String): List<String> {
        val fromInstant = character.instant
            .filter { it.role == Role.User && !it.isLoading && it.content.isNotBlank() }
            .takeLast(3)
            .map { trimText(it.content, 80) }
            .filter { it.isNotEmpty() }
        if (fromInstant.isNotEmpty()) return fromInstant
        val trimmed = trimText(userText, 80)
        return if (trimmed.isNotEmpty()) listOf(trimmed) else emptyList()
    }

    private fun buildQuickReplyAsUserPrompt(character: Character, replyText: String): Pair<String, String> {
        val address = com.deeptalking.domain.agent.prompts.normalizeUserAddress(character.staticProfile.userAddress)
        val charName = character.name.ifBlank { "对方" }
        val system = "你就是这位用户本人，正在手机上和「$charName」聊天。只输出用户此刻最可能打出的两句话，不要扮演$charName，不要写旁白或动作，不要解释。"
        val user = "「$charName」刚对你说：\n" + trimText(replyText, 800) + "\n\n" +
            "请写出你（用户" + (if (address.isNotEmpty()) "，对方平时叫你“$address”" else "") + "）此刻最可能发给他的两句话：\n" +
            "①每句都是用户可以原样发送的消息，是\"我\"（用户自己）的立场、感受、提问或要求；\n" +
            "②不得是${charName}会说的话，不得是把${charName}刚说的话换个人称复述一遍；\n" +
            "③每条不超过 ${QUICK_REPLY_GUARD.repairMaxChars} 字，不用括号动作、不用 Markdown。\n" +
            "只返回 JSON：{\"quickReplies\":[\"句子一\",\"句子二\"]}"
        return system to user
    }

    private suspend fun complete(
        system: String,
        user: String,
        temperature: Double,
        maxOutputTokens: Int,
        sessionId: String? = null,
    ): String {
        val request = LlmRequest(
            model = model,
            instructions = system,
            input = listOf(ChatMessage(role = Role.User, content = user)),
            temperature = temperature,
            maxOutputTokens = maxOutputTokens,
            stream = false,
            reasoningEffort = "none",
            apiPlatform = apiPlatform,
            sessionId = sessionId ?: "deeptalking-general",
        )
        return try {
            llm.complete(request).text
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ""
        }
    }

    private fun parseLongTerm(obj: JsonObject): LongTermMemory {
        val category = when (obj.string("category").lowercase()) {
            "userprofile" -> MemoryCategory.UserProfile
            "relationship" -> MemoryCategory.Relationship
            "promises" -> MemoryCategory.Promises
            "habits" -> MemoryCategory.Habits
            else -> MemoryCategory.Events
        }
        val subject = when (obj.string("subject").lowercase()) {
            "user" -> MemorySubject.User
            "relationship" -> MemorySubject.Relationship
            "world" -> MemorySubject.World
            "character" -> MemorySubject.Character
            else -> MemorySubject.Legacy
        }
        val status = when (obj.string("status").lowercase()) {
            "resolved" -> PromiseStatus.Resolved
            "cancelled" -> PromiseStatus.Cancelled
            else -> PromiseStatus.Active
        }
        return LongTermMemory(
            category = category,
            subject = subject,
            key = obj.string("key"),
            value = obj.string("value"),
            tags = stringList(obj["tags"]),
            importance = (obj["importance"] as? JsonPrimitive)?.intOrNull ?: 0,
            sourceMessageIds = stringList(obj["sourceMessageIds"]),
            evidence = obj.string("evidence"),
            eventTime = obj.string("eventTime").takeIf { it.isNotBlank() },
            dueAt = obj.string("dueAt").takeIf { it.isNotBlank() },
            promisor = obj.string("promisor").takeIf { it.isNotBlank() },
            promisee = obj.string("promisee").takeIf { it.isNotBlank() },
            status = status,
        )
    }

    private fun stringList(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun eventIdentity(eventTime: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val zoned = parseZoned(eventTime, zone) ?: return ""
        val day = logicalDay(zoned)
        return "${day.year}年${day.monthValue}月${day.dayOfMonth}日[${getTimeSlot(zoned.toLocalTime())}]"
    }

    private fun normalizeTimestamp(value: String?, fallback: String): String {
        if (value.isNullOrEmpty()) return fallback
        val zoned = parseZoned(value) ?: return fallback
        return zoned.toInstant().toString()
    }

    private fun newId(prefix: String): String =
        prefix + "_" + Instant.now().toEpochMilli() + "_" + Random.nextInt(100000, 999999)

    private companion object {
        const val EXTRACTION_SYSTEM = "你是一个信息提取助手。只返回JSON数组，不要其他文字。"
        const val ANALYSIS_SYSTEM = "你是一个记忆分析助手。只返回JSON，不要其他文字。"
        const val CRITIQUE_SYSTEM = "你是文风校对助手。只返回JSON。"

        const val EXTRACTION_COUNT_LIMITED = "返回JSON数组，最多十五个元素。"
        const val EXTRACTION_COUNT_UNLIMITED = "不限事件数量，尽可能完整覆盖本批全部消息。"

        const val EXTRACTION_PROMPT_HEAD =
            "分析以下带消息ID和时间戳的即时对话，压缩为事件流程摘要。事件按“日期+时段”划分：时段只有深夜、凌晨、清晨、早晨、上午、中午、下午、傍晚、晚上、夜里十种，且一天从02:00起算（00:00-02:00算前一天的深夜）；同一天同一时段的多个事情视为同一事件，按时间线集中叙述（如“2026-08-01 下午在家先…晚上…”）。同一时段也可能有不同事件，按事实或持续话题区分，不得把无关事件强行合并；同一事件内用时间线连接各阶段，保留仍然有效的关键细节、结果与未决事项。**只保留会影响后续剧情的内容**：谁做了什么、学到或决定了什么，地点/物品/伤势/关系的变化，新的发现或线索，以及仍未解决的目标、承诺、威胁与期限；一次性的寒暄、当下的情绪起伏、闲聊过程一律不记。**每条content不超过120字**，用平实的叙述句写成，禁止文学化描写、比喻、形容词铺陈与对白原文。eventTime写该事件的真实ISO时间，participants为参与人列表，location写地点（没有明确地点写“未说明”）。若内容是“最近短期事件”中某事件的延续，必须原样复用该事件的eventTime、participants和location，并把旧摘要与本批新进展重新组织成一段完整的更新后摘要；系统保留已有摘要并追加新事实，因此不能只返回新增片段：旧摘要中**仍然有效**的信息（未过期的约定、未解决的事、仍然成立的关系与状态）必须原样保留，只丢弃已经过时或已被推翻的部分。content按时间和因果顺序书写，不要逐字摘录。content必须写明是谁做的：涉及用户写“用户”，涉及角色写角色名（群组写具体成员名），双方共同的事写清各自做了什么，禁止使用“我、你、TA、他、她”这类指代不清的代词。content中禁止使用“今天、昨天、明天、今晚、今晚、刚刚、刚才、现在、最近、这几天”等相对时间词，一律使用具体日期（如“8月1日下午”）或事件本身描述。每条sourceMessageIds只能引用本批输入消息ID，旧摘要的来源由系统保留。必须覆盖全部输入消息：从第一条到最后一个已处理消息之间的每个消息ID都必须至少出现在一个事件的sourceMessageIds中，不能跳过中间消息，不得遗漏。"

        const val EXTRACTION_PROMPT_TAIL =
            "返回JSON数组：[{\"content\":\"一段完整的更新后事件流程摘要\",\"eventTime\":\"ISO时间\",\"participants\":[\"用户\",\"角色名\"],\"location\":\"地点或未说明\",\"sourceMessageIds\":[\"msg_id\"]}]。\n最近短期事件:\n"

        const val ANALYSIS_PROMPT_HEAD =
            "分析以下带短期ID、来源消息ID、userEvidence及时间/人物/地点三要素的事件流程摘要，提取未来仍有价值的稳定事实。**价值判据**：只有能跨轮复用、会影响后续对话或关系、或用户明确表达过的信息才记（长期偏好、重要人物与关系、承诺与约定、反复出现的习惯、持续的情节线）；一次性的寒暄客套、当下的情绪起伏、可以随口重说的闲聊、纯场景描写一律不记。**证据不足宁可不记**，不要为了产出而脑补或推演。必须检查全部输入条目，并在analyzedShortTermIds中原样返回全部输入的短期ID；不得遗漏、增加或重复。合并重复或冲突条目；事件、承诺保留必要日期；普通寒暄不要进入长期记忆。每条长期记忆必须在sourceShortTermIds中列出它实际使用的短期条目id；每个列出的短期条目都必须至少贡献一个sourceMessageIds中的消息ID，否则不要列出该短期ID。sourceMessageIds只选择直接支持该事实的消息，不要复制短期条目的全部来源；userProfile和habits只能选择用户消息。evidence只能逐字引用被选择消息对应的userEvidence.text，不能引用摘要或改写。没有可引用的userEvidence时不要输出该条。events按“日期+时段+事实主题”区分：同一天同一时段（深夜/凌晨/清晨/早晨/上午/中午/下午/傍晚/晚上/夜里，一天从02:00起算）的短期事件合并为一条长期事件，同一时段的独立事实不得互相覆盖，key须标明稳定事实主题；合并时eventTime取最早的ISO时间，participants取并集，location取最新（无明确地点写“未说明”）。剧情弧线：若若干短期事件属于同一持续情节或话题线（同一人物线、同一持续事件、同一反复出现的话题），除按时间合并外，还应在其中一条事件条目上标注arcOf（该情节的持续话题或人物线名称，稳定可复用）与arcStage（只能是起始/发展/转折/现状之一，按情节推进阶段标注），arcOf命名一旦确定就保持稳定——同一情节线不得每轮改名或另起新名，续写时沿用已有名称；并将value整合成按时间顺序、带情绪起伏的叙事摘要；同一arcOf只允许一条带弧线标注的条目，其余同线条目按普通事件输出。无论是否提取出长期记忆，成功完成分析都必须返回status:\"ok\"；没有长期价值时仍需返回完整analyzedShortTermIds和空longTerm。\n分类规则：userProfile/habits仅限用户事实，subject=user；relationship仅限双方关系，subject=relationship；events只记录用户陈述或共同事件；promises只记录用户明确承诺或双方明确约定。promises在此处只能新建为active；完成或取消由主对话的promiseUpdates按承诺ID处理。存在明确期限才填写dueAt。\n条目:\n"

        const val ANALYSIS_PROMPT_TAIL =
            "\n\n返回JSON格式:\n{\"status\":\"ok\",\"analyzedShortTermIds\":[\"全部输入的short_id\"],\"longTerm\": [{\"category\": \"...\", \"subject\": \"user|relationship|world\", \"key\": \"稳定且可复用的标识\", \"value\": \"...\", \"tags\": [...], \"importance\": 1-10, \"sourceShortTermIds\":[\"short_id\"], \"sourceMessageIds\": [\"msg_id\"], \"evidence\": \"被选择用户消息中的逐字原话\", \"eventTime\": \"ISO时间\", \"participants\":[\"参与者\"],\"location\":\"地点或未说明\",\"status\": \"active\", \"dueAt\": \"ISO时间\", \"arcOf\": \"可选，持续情节线名称\", \"arcStage\": \"可选，起始/发展/转折/现状\"}]}\ncategory可选: userProfile, relationship, events, promises, habits。value同样必须写明主体：涉及用户写“用户”，涉及角色写角色名（群组写具体成员名），禁止“我/你/TA”这类指代不清的代词。key与value中的时间一律写绝对日期（YYYY-MM-DD 或 YYYY-MM-DD 时段），禁止“明天/明晚/上周/上个月/三天后”这类相对时间词。这里只提取用户的约定（promises 的 promisor=user、promisor与promisee只能填 user 或 character）；角色单方承诺由主对话记录，不在此处提取。"
    }
}
