package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.prompts.trimText
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.domain.memory.hasValidUserEvidence
import com.deeptalking.domain.memory.parseRelativeText
import com.deeptalking.domain.memory.parseZoned
import com.deeptalking.domain.memory.resolveTimeRef
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

/** Port of the `set_reminder` branch in tool-execution.js. */
class SetReminderTool(private val memory: MemoryService) : AgentTool {

    override val definition = ToolDefinition(
        name = "set_reminder",
        description = "记录“需要用户参与”的待办/约定，且**必须由用户自己明确提出或同意**（如用户说“我明天要交报告”“记得提醒我买药”）。调用时必须附用户原话：sourceMessageIds 填用户消息ID、evidence 逐字摘录用户原话。角色要求用户去做的事、角色的建议或叮嘱，一律不得用本工具记录——应先自然征询用户，等用户自己答应后再记。到时间可在对话中温和提起。群组对话中若要记入某位成员的私人记忆可传 memberName。",
        parametersJson = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("key", buildJsonObject { put("type", "string"); put("description", "约定/待办的简短标题") })
                put("value", buildJsonObject { put("type", "string"); put("description", "约定详情，写绝对日期，不要写“明天”这类相对词") })
                put("dueAt", buildJsonObject { put("type", "string"); put("description", "可选，约定的截止时间（ISO格式），如 2026-08-10T09:00:00") })
                put("timeRef", timeRefSchema())
                put("sourceMessageIds", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "用户明确提出该待办的消息ID（必须是真实用户消息）")
                })
                put("evidence", buildJsonObject { put("type", "string"); put("description", "逐字摘录用户提出该待办的原话") })
                put("memberName", buildJsonObject { put("type", "string"); put("description", "可选，群组对话中指定记入某位成员的私人记忆（不传则记入共享记忆）") })
            })
            put("required", buildJsonArray { add("key"); add("value"); add("sourceMessageIds"); add("evidence") })
        }.toString(),
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val reminderKey = trimText(ToolArgs.string(args, "key"), 100)
        val reminderValue = trimText(ToolArgs.string(args, "value"), 900)
        if (reminderKey.isEmpty() || reminderValue.isEmpty()) return AgentToolResult(errorJson("缺少 key 或 value"))

        val evidence = trimText(ToolArgs.string(args, "evidence"), 300)
        val sourceIds = ToolArgs.strings(args, "sourceMessageIds").map { it.trim() }.filter { it.isNotEmpty() }
        val resolvedSources = hasValidUserEvidence(context.character, sourceIds, evidence)
        if (resolvedSources == null) {
            return AgentToolResult(
                errorJson("只能记录用户自己明确提出或同意的待办：请附用户原话 sourceMessageIds 与 evidence；若是你要求用户去做的事，请先在回复中征询用户，等用户答应后再记录"),
            )
        }

        val character = context.character
        val requestedMember = ToolArgs.string(args, "memberName").trim().ifEmpty { null }
        val member = requestedMember?.let { MemoryToolSupport.resolveMember(character, it) }
        val memberName = member?.name

        val now = Instant.now().toString()
        val nowZoned = ZonedDateTime.now()
        val resolved = resolveTimeRef(ToolArgs.timeRef(args, "timeRef"), nowZoned)
        val base = resolved?.iso ?: now
        val baseZoned = parseZoned(base) ?: nowZoned
        val parsedKey = parseRelativeText(reminderKey, baseZoned)
        val parsedValue = parseRelativeText(reminderValue, baseZoned)
        val dueAt = MemoryToolSupport.normalizeTimestamp(ToolArgs.string(args, "dueAt"), null) ?: resolved?.iso

        val store = member?.longTerm ?: character.longTerm
        val existing = store.firstOrNull {
            it.category == MemoryCategory.Promises &&
                it.status == PromiseStatus.Active &&
                it.key.equals(reminderKey, ignoreCase = true)
        }

        val updatedCharacter: com.deeptalking.core.model.Character
        val resultId: String
        if (existing != null) {
            updatedCharacter = memory.updateMemory(character, existing.id, memberName) { old ->
                old.copy(
                    value = reminderValue,
                    dueAt = dueAt ?: old.dueAt,
                    updatedAt = now,
                )
            }
            resultId = existing.id
        } else {
            val reminder = LongTermMemory(
                id = UUID.randomUUID().toString(),
                category = MemoryCategory.Promises,
                subject = MemorySubject.User,
                key = parsedKey,
                value = parsedValue,
                tags = emptyList(),
                importance = 6,
                sourceMessageIds = resolvedSources.map { it.id }.distinct().take(8),
                evidence = evidence,
                eventTime = now,
                dueAt = dueAt,
                promisor = "user",
                promisee = "character",
                status = PromiseStatus.Active,
                createdAt = now,
                updatedAt = now,
                recallCount = 0,
            )
            resultId = reminder.id
            val shared = if (member == null) listOf(reminder) else emptyList()
            val memberLongTerm = if (member != null) mapOf(member.name to listOf(reminder)) else emptyMap()
            updatedCharacter = memory.applyTurn(character, emptyList(), shared, memberLongTerm)
        }

        val dueAtJson: JsonElement = if (dueAt != null) {
            JsonPrimitive(MemoryToolSupport.formatContextTime(dueAt))
        } else {
            JsonNull
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("id", resultId)
            put("key", reminderKey)
            put("dueAt", dueAtJson)
        }
        return AgentToolResult(payload.toString(), updatedCharacter = updatedCharacter)
    }
}
