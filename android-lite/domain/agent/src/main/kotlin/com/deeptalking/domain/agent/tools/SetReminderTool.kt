package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Port of the `set_reminder` branch in tool-execution.js.
 *
 * SIMPLIFIED: source evidence is validated against real user messages in the
 * character's history; the full `hasValidUserEvidence` excerpt matching stays
 * in the memory layer. The promise is persisted through [MemoryService.applyTurn].
 */
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
        val key = ToolArgs.string(args, "key").trim().take(100)
        val value = ToolArgs.string(args, "value").trim().take(900)
        if (key.isEmpty() || value.isEmpty()) return AgentToolResult(errorJson("缺少 key 或 value"))
        val evidence = ToolArgs.string(args, "evidence").trim().take(300)
        val sourceIds = ToolArgs.strings(args, "sourceMessageIds").map { it.trim() }.filter { it.isNotEmpty() }
        val knownUserIds = context.character.instant.filter { it.role.name == "User" }.map { it.id }.toSet()
        val validSources = sourceIds.filter { it in knownUserIds }
        if (validSources.isEmpty() || evidence.isEmpty()) {
            return AgentToolResult(
                errorJson("只能记录用户自己明确提出或同意的待办：请附用户原话 sourceMessageIds 与 evidence；若是你要求用户去做的事，请先在回复中征询用户，等用户答应后再记录"),
            )
        }
        val now = Instant.now().toString()
        val dueAt = ToolArgs.string(args, "dueAt").trim().ifEmpty { null }
        val memberName = ToolArgs.string(args, "memberName").trim().ifEmpty { null }
        // Update an existing active promise with the same key rather than inserting a
        // duplicate (legacy set_reminder merges by key).
        val existing = context.character.longTerm.firstOrNull {
            it.category == MemoryCategory.Promises &&
                it.status == PromiseStatus.Active &&
                it.key.equals(key, ignoreCase = true) &&
                (memberName == null || it.memberName.equals(memberName, ignoreCase = true))
        }
        val reminder = LongTermMemory(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            category = MemoryCategory.Promises,
            subject = MemorySubject.User,
            key = key,
            value = value,
            tags = existing?.tags ?: emptyList(),
            importance = existing?.importance ?: 6,
            sourceMessageIds = validSources,
            evidence = evidence,
            eventTime = existing?.eventTime ?: now,
            dueAt = dueAt,
            promisor = "user",
            promisee = "character",
            status = PromiseStatus.Active,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastRecalled = existing?.lastRecalled,
            recallCount = existing?.recallCount ?: 0,
            notifiedAt = null,
        )
        val memberLongTerm = if (memberName != null) mapOf(memberName to listOf(reminder)) else emptyMap()
        val shared = if (memberName != null) emptyList() else listOf(reminder)
        memory.applyTurn(context.character, shortTerm = emptyList(), longTerm = shared, memberLongTerm = memberLongTerm)
        return AgentToolResult(
            """{"ok":true,"key":${quote(key)},"dueAt":${dueAt?.let { quote(it) } ?: "null"}}""",
        )
    }
}
