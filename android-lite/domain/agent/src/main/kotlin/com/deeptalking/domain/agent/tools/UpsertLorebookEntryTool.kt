package com.deeptalking.domain.agent.tools

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.prompts.trimText
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import java.time.Instant
import java.util.UUID

/**
 * Port of the `upsert_lorebook_entry` branch in tool-execution.js.
 *
 * Upserts into the character's (or a group member's) lorebook and returns the
 * mutated copy via [AgentToolResult.updatedCharacter]. User-written entries
 * (`origin=User`) are protected: their content is never overwritten.
 */
class UpsertLorebookEntryTool : AgentTool {

    override val definition = ToolDefinition(
        name = "upsert_lorebook_entry",
        description = "写入/更新世界书条目——世界层设定的唯一去处（时代与世界观、地点、组织、专有名词、历史、规则、背景事实）。**同一件事物只能有一条**：先对照现有条目，凡名称相近、关键词相同或内容重叠的都算同一条，必须用它的 entryId/name 更新合并，绝不新建近似条目；当你用 entryId/name 明确指定要更新的条目时，content 会**替换**该条目原有内容，因此更新时要写全该条目应有的完整内容（不要只写增量，否则会丢掉原有信息）；未指定 id/name 而由系统按相似度命中时，只会把新句子并入已有内容。只在近期对话里已经出现/确立、且**会反复复用**的这类设定，或用户补充修正了这类设定时才使用；一次性的小事、可从上下文直接看出的细节不要写。**用户手写条目（origin=user）受保护，不得覆盖或改写**；若现有用户条目已覆盖同一设定，不要重复写入。keywords 写剧情里可能出现的称呼（命中才注入）；只有确实需要每轮生效的世界前提/规则才把 alwaysActive 设为 true（常驻条目数量有限，不要滥用）。必须给出 sourceMessageIds 与 evidence 证明该设定已在对话中出现（本轮新编、尚未落库的内容不要写，等它出现在消息里再由整理任务沉淀）；拿不出依据就不要写。",
        parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"条目名（如「赤月王国」「银月商会」）；未给 entryId 时按名字匹配已有条目"},"content":{"type":"string","description":"命中后注入的设定内容，只写该条目本身的信息，不写理由或解释"},"keywords":{"type":"array","items":{"type":"string"},"description":"触发关键词（剧情里可能出现的称呼）；常驻条目可留空"},"alwaysActive":{"type":"boolean","description":"true=常驻（每轮都注入，用于世界前提/规则）；默认 false=只在关键词命中时注入"},"entryId":{"type":"string","description":"可选，明确要更新的条目ID（比按名字匹配更精确）"},"memberName":{"type":"string","description":"可选，群组对话中写入某位成员的私人世界书（不传则写入共享世界书）"},"sourceMessageIds":{"type":"array","items":{"type":"string"},"description":"该设定所在的消息ID（必须是上下文里真实的已存在消息ID）"},"evidence":{"type":"string","description":"逐字摘录或紧扣原文的短句，体现该设定"}},"required":["name","content","sourceMessageIds","evidence"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val name = ToolArgs.string(args, "name").trim().take(AppLimits.Lorebook.NAME_CHARS)
        val content = ToolArgs.string(args, "content").trim().take(AppLimits.Lorebook.CONTENT_CHARS)
        if (name.isEmpty() || content.isEmpty()) {
            return AgentToolResult(errorJson("name 与 content 不能为空"))
        }
        val sourceIds = ToolArgs.strings(args, "sourceMessageIds").map { it.trim() }.filter { it.isNotEmpty() }
        val evidence = ToolArgs.string(args, "evidence").trim()
        if (sourceIds.isEmpty() || evidence.isEmpty()) {
            return AgentToolResult(
                errorJson("sourceMessageIds 必须是上下文里真实存在的消息ID，且 evidence 要能对上原话或明确描述；拿不出依据时不要写世界书（本轮新编、还没出现在消息里的内容也不要写）"),
            )
        }
        val keywords = ToolArgs.strings(args, "keywords")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(AppLimits.Lorebook.KEYWORDS_PER_ENTRY)
        val alwaysActive = ToolArgs.bool(args, "alwaysActive") ?: false
        val entryId = ToolArgs.string(args, "entryId").trim()
        val memberName = ToolArgs.string(args, "memberName").trim()
        val character = context.character

        if (memberName.isNotEmpty() && character.isGroup) {
            val member = character.members.firstOrNull { it.name.equals(memberName, ignoreCase = true) }
                ?: return AgentToolResult(errorJson("找不到成员「$memberName」，无法写入其世界书"))
            val result = upsert(member.lorebook, entryId, name, content, keywords, alwaysActive)
            if (!result.ok || result.entry == null) return AgentToolResult(errorJson(result.reason.ifEmpty { "世界书写入失败" }))
            val updatedMembers = character.members.map { if (it.id == member.id) it.copy(lorebook = result.list) else it }
            return AgentToolResult(
                contentJson = entryJson(result.entry, result.created, result.mergedIntoUser),
                updatedCharacter = character.copy(members = updatedMembers),
            )
        }

        val result = upsert(character.lorebook, entryId, name, content, keywords, alwaysActive)
        if (!result.ok || result.entry == null) return AgentToolResult(errorJson(result.reason.ifEmpty { "世界书写入失败" }))
        return AgentToolResult(
            contentJson = entryJson(result.entry, result.created, result.mergedIntoUser),
            updatedCharacter = character.copy(lorebook = result.list),
        )
    }
}

private const val NAME_SIMILARITY = 0.5
private const val CONTENT_SIMILARITY = 0.45
private const val MERGE_SENTENCE_SIMILARITY = 0.6

private data class UpsertResult(
    val ok: Boolean,
    val reason: String = "",
    val list: List<LorebookEntry> = emptyList(),
    val entry: LorebookEntry? = null,
    val created: Boolean = false,
    val mergedIntoUser: Boolean = false,
)

private fun upsert(
    list: List<LorebookEntry>,
    entryId: String,
    name: String,
    content: String,
    keywords: List<String>,
    alwaysActive: Boolean,
): UpsertResult {
    val exact = findEntry(list, entryId, name)
    if (exact != null) {
        if (exact.origin != LorebookOrigin.Model) {
            return UpsertResult(false, "「${exact.name}」是用户手写条目，不能覆盖或改写；请换一个条目名新增")
        }
        val updated = exact.copy(
            name = exact.name.ifEmpty { name },
            content = content,
            keywords = if (keywords.isNotEmpty()) keywords else exact.keywords,
            alwaysActive = exact.alwaysActive || alwaysActive,
            misses = 0,
            updatedAt = Instant.now().toString(),
        )
        return UpsertResult(true, list = replace(list, exact.id, updated), entry = updated, created = false)
    }

    val similar = list.firstOrNull { entriesSimilar(name, keywords, content, it) }
    if (similar != null) {
        if (similar.origin != LorebookOrigin.Model) {
            // Similar hit is a user entry: do not create a duplicate nor touch its content.
            return UpsertResult(true, list = list, entry = similar, created = false, mergedIntoUser = true)
        }
        val merged = similar.copy(
            content = mergeContent(similar.content, content),
            keywords = (similar.keywords + keywords).distinct().take(AppLimits.Lorebook.KEYWORDS_PER_ENTRY),
            alwaysActive = similar.alwaysActive || alwaysActive,
            misses = 0,
            updatedAt = Instant.now().toString(),
        )
        return UpsertResult(true, list = replace(list, similar.id, merged), entry = merged, created = false)
    }

    if (keywords.isEmpty() && !alwaysActive) {
        return UpsertResult(
            false,
            "世界书条目需要 keywords（命中才注入）或显式 alwaysActive:true（常驻）；请补关键词，或确实属于世界前提时设为常驻",
        )
    }
    if (alwaysActive) {
        val activeCount = list.count { it.origin == LorebookOrigin.Model && it.alwaysActive }
        if (activeCount >= AppLimits.Lorebook.MAX_ALWAYS_ACTIVE) {
            return UpsertResult(
                false,
                "常驻世界书条目已达上限 ${AppLimits.Lorebook.MAX_ALWAYS_ACTIVE} 条；这条请改为关键词条目（补 keywords），或先合并/删除已有常驻条目",
            )
        }
    }
    if (list.size >= AppLimits.Lorebook.ENTRIES) {
        return UpsertResult(false, "世界书条目已达上限 ${AppLimits.Lorebook.ENTRIES} 条")
    }

    val now = Instant.now().toString()
    val entry = LorebookEntry(
        id = "lore_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(8),
        name = name,
        content = content,
        keywords = keywords,
        alwaysActive = alwaysActive,
        origin = LorebookOrigin.Model,
        createdAt = now,
        updatedAt = now,
    )
    return UpsertResult(true, list = (list + entry).take(AppLimits.Lorebook.ENTRIES), entry = entry, created = true)
}

private fun findEntry(list: List<LorebookEntry>, entryId: String, name: String): LorebookEntry? {
    val id = entryId.trim()
    if (id.isNotEmpty()) {
        list.firstOrNull { it.id == id }?.let { return it }
    }
    val wanted = name.trim().lowercase()
    if (wanted.isEmpty()) return null
    return list.firstOrNull { it.name.trim().lowercase() == wanted }
}

private fun replace(list: List<LorebookEntry>, id: String, entry: LorebookEntry): List<LorebookEntry> =
    list.map { if (it.id == id) entry else it }

private fun entriesSimilar(
    candidateName: String,
    candidateKeywords: List<String>,
    candidateContent: String,
    existing: LorebookEntry,
): Boolean {
    val leftName = normalizeLorebookName(candidateName)
    val rightName = normalizeLorebookName(existing.name)
    if (leftName.isNotEmpty() && rightName.isNotEmpty()) {
        if (leftName == rightName) return true
        val nameScore = if (leftName.contains(rightName) || rightName.contains(leftName)) {
            1.0
        } else {
            bigramRatio(lorebookBigrams(leftName), lorebookBigrams(rightName))
        }
        if (nameScore >= NAME_SIMILARITY) return true
    }
    val leftKeywords = candidateKeywords.map(::normalizeLorebookName).filter { it.isNotEmpty() }
    val rightKeywords = existing.keywords.map(::normalizeLorebookName).filter { it.isNotEmpty() }
    if (leftKeywords.isNotEmpty() && leftKeywords.any { it in rightKeywords }) return true
    if (candidateContent.isNotBlank() && existing.content.isNotBlank() &&
        bigramRatio(lorebookBigrams(candidateContent), lorebookBigrams(existing.content)) >= CONTENT_SIMILARITY
    ) {
        return true
    }
    return false
}

private fun normalizeLorebookName(value: String?): String =
    value.orEmpty().trim().lowercase()
        .replace(Regex("[\\s\u3000]"), "")
        .replace(Regex("[「」『』“”‘’\"'《》〈〉（）()\\[\\]【】{}<>]"), "")
        .replace(Regex("[，。！？、；：,.!?;:·—_\\-]"), "")

private fun lorebookBigrams(value: String?): Set<String> {
    val normalized = normalizeLorebookName(value)
    val pairs = mutableSetOf<String>()
    var i = 0
    while (i + 2 <= normalized.length) {
        val pair = normalized.substring(i, i + 2)
        if (pair.length == 2 && pair.all { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }) pairs += pair
        i++
    }
    return pairs
}

private fun bigramRatio(left: Set<String>, right: Set<String>): Double {
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val inter = left.count { it in right }
    val union = left.size + right.size - inter
    return if (union == 0) 0.0 else inter.toDouble() / union
}

private fun splitSentences(text: String): List<String> {
    val delimiters = "。！？!?；;\n"
    val out = mutableListOf<String>()
    val buffer = StringBuilder()
    for (ch in text) {
        buffer.append(ch)
        if (ch in delimiters) {
            val trimmed = buffer.toString().trim()
            if (trimmed.isNotEmpty()) out += trimmed
            buffer.clear()
        }
    }
    if (buffer.toString().trim().isNotEmpty()) out += buffer.toString().trim()
    return out
}

private fun mergeContent(existing: String, incoming: String): String {
    val base = existing.trim()
    val known = splitSentences(base)
    val added = mutableListOf<String>()
    splitSentences(incoming).forEach { sentence ->
        if (sentence.length < 2 || base.contains(sentence)) return@forEach
        val duplicate = (known + added).any {
            bigramRatio(lorebookBigrams(it), lorebookBigrams(sentence)) >= MERGE_SENTENCE_SIMILARITY
        }
        if (!duplicate) added += sentence
    }
    return trimText(base + added.joinToString(""), AppLimits.Lorebook.CONTENT_CHARS)
}

private fun entryJson(entry: LorebookEntry, created: Boolean, mergedIntoUser: Boolean): String {
    val keywords = entry.keywords.joinToString(prefix = "[", postfix = "]") { quote(it) }
    if (mergedIntoUser) {
        val reason = "已有用户手写条目「${entry.name}」覆盖同一设定，未新建重复条目；不要再重复写入"
        return """{"ok":true,"entryId":${quote(entry.id)},"name":${quote(entry.name)},"keywords":$keywords,"created":false,"skipped":true,"reason":${quote(reason)}}"""
    }
    return """{"ok":true,"entryId":${quote(entry.id)},"name":${quote(entry.name)},"alwaysActive":${entry.alwaysActive},"keywords":$keywords,"created":$created,"changed":true}"""
}
