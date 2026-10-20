package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.SourceEvidence
import com.deeptalking.core.model.mergeSources
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.prompts.maskUserWord
import com.deeptalking.domain.agent.prompts.trimText
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.domain.memory.hasValidUserEvidence
import com.deeptalking.domain.memory.parseRelativeText
import com.deeptalking.domain.memory.parseZoned
import com.deeptalking.domain.memory.resolveTimeRef
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Correct or delete one existing memory (long-term or short-term) — the merged
 * `update_memory` + `delete_memory`. `op` states the intent, `id` the target; both
 * are required so the model cannot silently fall into a write.
 *
 * `op=update` always needs the user's verbatim `evidence` plus the
 * `sourceMessageIds` it came from (checked client-side; a mismatch changes
 * nothing). A **short-term** entry is recognised by *membership in
 * `character.shortTerm`* (not by an id prefix) and only accepts `value` or
 * `eventTime`; correcting it also folds the new sources into `sourceRoles` /
 * `userEvidence`, otherwise the next evidence check would reject the entry.
 */
class MemoryWriteTool(private val memory: MemoryService) : AgentTool {

    override val definition = ToolDefinition(
        name = "memory_write",
        description = "修改或删除一条已存在的记忆（长期或短期，按 ID）。op=update 按 id 覆盖指定字段，必须附 evidence（用户原话逐字摘录）与 sourceMessageIds（该原话所在消息 ID），对不上时不修改；op=delete 只需要 id。**仅当用户明确否认、纠正，或内容已被推翻/重复时使用**；不要用它新建记忆（新事实由短期记忆整理链路沉淀）。判断短期记忆看它是否在本轮注入的短期摘要窗口里：这类条目 update 只支持 value 与 eventTime，改正后会重新排到队尾等待下一轮整理进长期记忆；长期记忆可改 subject（user=用户 / character=角色自己 / relationship=双方 / world=背景）、value（必须写明主体）、key、importance、promisor/promisee（约定的承诺方/受约方）。群组对话中可传 memberName 指定某位成员的私人记忆。",
        parametersJson = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("op", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { listOf("update", "delete").forEach { add(it) } })
                    put("description", "update=按 id 修改，delete=按 id 删除")
                })
                put("id", buildJsonObject { put("type", "string"); put("description", "要修改/删除的记忆ID（先用 search_memory 检索得到）") })
                put("subject", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { listOf("user", "character", "relationship", "world").forEach { add(it) } })
                    put("description", "可选，修正主体")
                })
                put("key", buildJsonObject { put("type", "string"); put("description", "可选，修正后的记忆标识") })
                put("value", buildJsonObject { put("type", "string"); put("description", "可选，修正后的内容；必须写明主体（用户写\"用户\"，角色写角色名或\"我（角色）\"）") })
                put("importance", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 10); put("description", "可选，修正重要性") })
                put("promisor", buildJsonObject { put("type", "string"); put("description", "可选，约定的承诺方（user / character / relationship / 群组成员名）") })
                put("promisee", buildJsonObject { put("type", "string"); put("description", "可选，约定的受约方（user / character / relationship / 群组成员名）") })
                put("eventTime", buildJsonObject { put("type", "string"); put("description", "可选，修正事件发生时间（ISO格式）") })
                put("dueAt", buildJsonObject { put("type", "string"); put("description", "可选，约定改期时填新的截止时间（ISO格式）") })
                put("evidence", buildJsonObject { put("type", "string"); put("description", "update 时必填：用户原话逐字摘录（长期与短期都要）") })
                put("sourceMessageIds", buildJsonObject {
                    put("type", "array"); put("items", buildJsonObject { put("type", "string") })
                    put("description", "update 时必填：用户纠正原话所在的消息 ID")
                })
                put("memberName", buildJsonObject { put("type", "string"); put("description", "可选，群组中指定某位成员的私人记忆") })
                put("timeRef", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("description", "相对时间词元：anchor=相对基准，offsetDays=天数偏移(-60~60)，weekday=星期，slot=时段，explicit=绝对时间；填了 explicit 则忽略其余")
                    put("properties", buildJsonObject {
                        put("anchor", buildJsonObject { put("type", "string") })
                        put("offsetDays", buildJsonObject { put("type", "integer") })
                        put("weekday", buildJsonObject { put("type", "string") })
                        put("slot", buildJsonObject { put("type", "string") })
                        put("explicit", buildJsonObject { put("type", "string") })
                    })
                })
            })
            put("required", buildJsonArray { add("op"); add("id") })
        }.toString(),
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val op = ToolArgs.string(args, "op").trim().lowercase()
        val id = ToolArgs.string(args, "id").trim()
        if (op != "update" && op != "delete") {
            return AgentToolResult(errorJson("op 只能是 update 或 delete"))
        }
        if (id.isEmpty()) {
            return AgentToolResult(errorJson("缺少记忆ID id，请先调用 search_memory 检索出目标ID"))
        }
        val character = context.character
        val requestedMember = ToolArgs.string(args, "memberName").trim().ifEmpty { null }
        // Legacy falls back to the shared store when the member name is unknown.
        val memberName = requestedMember?.takeIf { MemoryToolSupport.resolveMember(character, it) != null }
        return if (op == "delete") {
            delete(character, id, memberName)
        } else {
            update(args, character, id, memberName)
        }
    }

    // ---- delete -----------------------------------------------------------------------

    private fun delete(
        character: com.deeptalking.core.model.Character,
        id: String,
        memberName: String?,
    ): AgentToolResult {
        // Short-term entries live in the injected window, so a wrong one must be
        // removable directly (member short-term stores are not part of that window).
        // Membership — not an id prefix — decides the store.
        if (memberName == null && character.shortTerm.any { it.id == id }) {
            val updated = character.copy(shortTerm = character.shortTerm.filterNot { it.id == id })
            return AgentToolResult(
                contentJson = """{"ok":true,"deleted":true,"id":${quote(id)},"store":"shortTerm"}""",
                updatedCharacter = updated,
            )
        }
        val exists = memory
            .listMemories(character, "", "", memberName, Int.MAX_VALUE)
            .any { it.id == id }
        if (!exists) {
            return AgentToolResult(
                """{"ok":false,"deleted":false,"id":${quote(id)},"reason":${quote("未找到该记忆ID，请先用 search_memory 查询正确ID后再试")}}""",
            )
        }
        return AgentToolResult(
            contentJson = """{"ok":true,"deleted":true,"id":${quote(id)}}""",
            updatedCharacter = memory.deleteMemory(character, id, memberName),
        )
    }

    // ---- update -----------------------------------------------------------------------

    private fun update(
        args: kotlinx.serialization.json.JsonObject,
        character: com.deeptalking.core.model.Character,
        id: String,
        memberName: String?,
    ): AgentToolResult {
        // Both parts are mandatory for an update: a correction without traceable
        // user words is not a correction.
        val sourceIds = ToolArgs.strings(args, "sourceMessageIds").map { it.trim() }.filter { it.isNotEmpty() }
        val evidence = ToolArgs.string(args, "evidence").trim()
        if (sourceIds.isEmpty() || evidence.isEmpty()) {
            return AgentToolResult(errorJson("update 需要 sourceMessageIds 与 evidence：evidence 逐字摘录用户原话，sourceMessageIds 指向该原话所在消息"))
        }
        if (hasValidUserEvidence(character, sourceIds, evidence) == null) {
            return AgentToolResult(errorJson("evidence 必须是 sourceMessageIds 指定用户消息中的逐字原话；对不上时不修改"))
        }

        val member = memberName?.let { MemoryToolSupport.resolveMember(character, it) }
        val host = if (member != null) MemoryToolSupport.memberAsCharacter(character, member) else character

        // Short-term entries are recognised by membership, never by a `short_` prefix.
        // The window is injected in full, so a wrong entry would otherwise sit in front
        // of the model until it slides out.
        val shortTarget = if (memberName == null) character.shortTerm.firstOrNull { it.id == id } else null
        if (shortTarget != null) {
            return updateShortTerm(args, character, shortTarget, sourceIds, evidence)
        }

        val subjectRaw = ToolArgs.string(args, "subject").trim()
        val subject = parseSubject(subjectRaw)
        if (subjectRaw.isNotEmpty() && subject == null) {
            return AgentToolResult(errorJson("subject 只能是 user / relationship / world / character"))
        }
        val importance = ToolArgs.int(args, "importance")
        val promisor = trimText(ToolArgs.string(args, "promisor"), 40)
        val promisee = trimText(ToolArgs.string(args, "promisee"), 40)

        val now = ZonedDateTime.now()
        val resolved = resolveTimeRef(ToolArgs.timeRef(args, "timeRef"), now)
        val eventTimeRaw = ToolArgs.string(args, "eventTime").trim()
        val dueAtRaw = ToolArgs.string(args, "dueAt").trim()
        val nextEventTime = if (eventTimeRaw.isNotEmpty()) {
            MemoryToolSupport.normalizeTimestamp(eventTimeRaw, null)
        } else {
            resolved?.iso
        }
        val target = memory
            .listMemories(character, "", "", memberName, Int.MAX_VALUE)
            .firstOrNull { it.id == id }
            ?: return AgentToolResult(errorJson("找不到该记忆ID（可能已被删除或合并），请重新检索后再试"))
        // For non-promise categories the legacy code drops the resolved dueAt.
        val nextDueAt = if (target.category == MemoryCategory.Promises) {
            if (dueAtRaw.isNotEmpty()) MemoryToolSupport.normalizeTimestamp(dueAtRaw, null) else resolved?.iso
        } else {
            null
        }
        val timeBase = nextEventTime?.let { parseZoned(it) } ?: now
        val nextValue = trimText(parseRelativeText(ToolArgs.string(args, "value"), timeBase), 900)
        val nextKey = trimText(parseRelativeText(ToolArgs.string(args, "key"), timeBase), 100)

        if (subject == null && nextValue.isEmpty() && nextKey.isEmpty() && importance == null &&
            promisor.isEmpty() && promisee.isEmpty() && nextEventTime == null && nextDueAt == null
        ) {
            return AgentToolResult(errorJson("至少要修改 subject / value / key / importance / promisor / promisee / eventTime / dueAt 之一"))
        }

        val changed = mutableListOf<String>()
        val updatedAt = Instant.now().toString()
        val updatedCharacter = memory.updateMemory(character, id, memberName) { old ->
            // `sourceRoles` is index-aligned with `sourceMessageIds`; align the
            // existing sources first, then append the new (user) ones.
            val merged = mergeSources(
                Triple(old.sourceMessageIds, old.sourceRoles, old.userEvidence),
                Triple(sourceIds, sourceIds.map { "user" }, evidenceList(sourceIds, evidence)),
                limit = 8,
            )
            old.copy(
                subject = if (subject != null) subject.also { changed += "subject" } else old.subject,
                value = if (nextValue.isNotEmpty()) nextValue.also { changed += "value" } else old.value,
                key = if (nextKey.isNotEmpty()) nextKey.also { changed += "key" } else old.key,
                importance = if (importance != null) importance.coerceIn(1, 10).also { changed += "importance" } else old.importance,
                promisor = if (promisor.isNotEmpty()) promisor.also { changed += "promisor" } else old.promisor,
                promisee = if (promisee.isNotEmpty()) promisee.also { changed += "promisee" } else old.promisee,
                eventTime = if (nextEventTime != null) nextEventTime.also { changed += "eventTime" } else old.eventTime,
                dueAt = if (nextDueAt != null) nextDueAt.also { changed += "dueAt" } else old.dueAt,
                evidence = evidence,
                sourceMessageIds = merged.sourceMessageIds,
                sourceRoles = merged.sourceRoles,
                userEvidence = merged.userEvidence,
                updatedAt = updatedAt,
                // A manual correction settles any accumulated conflict and keeps the
                // replaced value in the corrections history (legacy `applyMemoryUpdate`).
                conflicts = emptyList(),
                conflictedAt = null,
                corrections = (old.corrections.takeLast(3) + com.deeptalking.core.model.MemoryCorrection(
                    at = updatedAt,
                    evidence = evidence,
                    // 改写前那条记忆的旧内容（旧 WebView 这里存的是整条字段对象，
                    // 原生只保留"旧值文本"，与 MemoryCorrection.before 的语义一致）。
                    before = old.value,
                )).takeLast(3),
            )
        }

        val finalSubject = subject ?: target.subject
        val finalKey = if (nextKey.isNotEmpty()) nextKey else target.key
        val finalValue = if (nextValue.isNotEmpty()) nextValue else target.value
        val payload = buildJsonObject {
            put("ok", true)
            put("id", id)
            put("category", target.category.legacyKey)
            put("changed", buildJsonArray { changed.forEach { add(it) } })
            put("subject", finalSubject.name.lowercase())
            put("subjectLabel", MemoryToolSupport.actorLabel(host, finalSubject))
            put("key", maskUserWord(host, trimText(finalKey, 100)))
            put("value", maskUserWord(host, trimText(finalValue, 500)))
        }
        return AgentToolResult(payload.toString(), updatedCharacter = updatedCharacter)
    }

    /**
     * A short-term correction: only `value` / `eventTime` are meaningful there. The
     * new sources are merged into `sourceRoles` / `userEvidence` together with the
     * ids, so the entry keeps passing evidence validation after the correction.
     */
    private fun updateShortTerm(
        args: kotlinx.serialization.json.JsonObject,
        character: com.deeptalking.core.model.Character,
        item: com.deeptalking.core.model.ShortTermMemory,
        sourceIds: List<String>,
        evidence: String,
    ): AgentToolResult {
        val unsupported = buildList {
            if (ToolArgs.string(args, "subject").trim().isNotEmpty()) add("subject")
            if (ToolArgs.string(args, "key").trim().isNotEmpty()) add("key")
            if (ToolArgs.int(args, "importance") != null) add("importance")
            if (ToolArgs.string(args, "promisor").trim().isNotEmpty()) add("promisor")
            if (ToolArgs.string(args, "promisee").trim().isNotEmpty()) add("promisee")
            if (ToolArgs.string(args, "dueAt").trim().isNotEmpty()) add("dueAt")
        }
        if (unsupported.isNotEmpty()) {
            return AgentToolResult(
                errorJson("短期记忆只支持修改 value 与 eventTime，不能改 ${unsupported.joinToString(" / ")}"),
            )
        }

        val now = ZonedDateTime.now()
        val resolved = resolveTimeRef(ToolArgs.timeRef(args, "timeRef"), now)
        val eventTimeRaw = ToolArgs.string(args, "eventTime").trim()
        val nextEventTime = if (eventTimeRaw.isNotEmpty()) {
            MemoryToolSupport.normalizeTimestamp(eventTimeRaw, null)
        } else {
            resolved?.iso
        }
        val timeBase = nextEventTime?.let { parseZoned(it) } ?: now
        val nextContent = trimText(parseRelativeText(ToolArgs.string(args, "value"), timeBase), 900)
        if (nextContent.isEmpty() && nextEventTime == null) {
            return AgentToolResult(errorJson("短期记忆至少要修改 value（内容）或 eventTime 之一"))
        }
        val merged = mergeSources(
            Triple(item.sourceMessageIds, item.sourceRoles, item.userEvidence),
            Triple(sourceIds, sourceIds.map { "user" }, evidenceList(sourceIds, evidence)),
            limit = 8,
        )
        val corrected = item.copy(
            content = if (nextContent.isNotEmpty()) nextContent else item.content,
            eventTime = nextEventTime ?: item.eventTime,
            sourceMessageIds = merged.sourceMessageIds,
            sourceRoles = merged.sourceRoles,
            userEvidence = merged.userEvidence,
            // A correction invalidates both consumers: neither has seen this revision.
            revision = item.revision + 1,
        )
        val list = character.shortTerm.filterNot { it.id == item.id } + corrected
        val payload = buildJsonObject {
            put("ok", true)
            put("id", item.id)
            put("store", "shortTerm")
            put("changed", buildJsonArray {
                if (nextContent.isNotEmpty()) add("value")
                if (nextEventTime != null) add("eventTime")
            })
            put("content", maskUserWord(character, trimText(corrected.content, 500)))
            put("eventTime", corrected.eventTime ?: "")
        }
        return AgentToolResult(payload.toString(), updatedCharacter = character.copy(shortTerm = list))
    }

    private fun evidenceList(sourceIds: List<String>, evidence: String): List<SourceEvidence> =
        sourceIds.map { SourceEvidence(sourceMessageId = it, text = evidence) }

    private fun parseSubject(raw: String): MemorySubject? = when (raw.lowercase()) {
        "user" -> MemorySubject.User
        "character" -> MemorySubject.Character
        "relationship" -> MemorySubject.Relationship
        "world" -> MemorySubject.World
        else -> null
    }
}
