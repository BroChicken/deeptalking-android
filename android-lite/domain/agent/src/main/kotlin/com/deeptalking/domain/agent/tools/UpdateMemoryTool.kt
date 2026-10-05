package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.MemorySubject
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.memory.MemoryService
import com.deeptalking.domain.memory.hasValidUserEvidence
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Port of the `update_memory` branch in tool-execution.js. */
class UpdateMemoryTool(private val memory: MemoryService) : AgentTool {

    override val definition = ToolDefinition(
        name = "update_memory",
        description = "修正一条已存在的长期记忆（按ID）。仅当用户明确否认或纠正某条记忆时使用，例如\"那件事是我做的不是你做的\"\"记反了\"\"我不是那个意思\"。必须先调用 search_memory 或 list_memories 找到目标ID，再用用户纠正的原话作为 evidence。可改 subject（主体：user=用户 / character=角色自己 / relationship=双方 / world=背景设定）、value（内容，必须写明主体）、key、importance、promisor/promisee（约定的承诺方/受约方）。不要用它新建记忆（新建请用 longTerm 字段或 set_reminder）。",
        parametersJson = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "要修正的记忆ID") })
                put("subject", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { listOf("user", "character", "relationship", "world").forEach { add(it) } })
                    put("description", "可选，修正主体")
                })
                put("value", buildJsonObject { put("type", "string"); put("description", "可选，修正后的内容；必须写明主体（用户写\"用户\"，角色写角色名或\"我（角色）\"）") })
                put("key", buildJsonObject { put("type", "string"); put("description", "可选，修正后的记忆标识") })
                put("importance", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 10); put("description", "可选，修正重要性") })
                put("promisor", buildJsonObject { put("type", "string"); put("description", "可选，约定的承诺方（user / character / relationship / 群组成员名）") })
                put("promisee", buildJsonObject { put("type", "string"); put("description", "可选，约定的受约方（user / character / relationship / 群组成员名）") })
                put("eventTime", buildJsonObject { put("type", "string"); put("description", "可选，修正事件发生时间（ISO格式）") })
                put("dueAt", buildJsonObject { put("type", "string"); put("description", "可选，修正约定的截止时间（ISO格式）") })
                put("timeRef", timeRefSchema())
                put("sourceMessageIds", buildJsonObject {
                    put("type", "array"); put("items", buildJsonObject { put("type", "string") })
                    put("description", "用户纠正原话所在的消息ID")
                })
                put("evidence", buildJsonObject { put("type", "string"); put("description", "逐字摘录用户纠正的原话") })
                put("memberName", buildJsonObject { put("type", "string"); put("description", "可选，群组中指定某位成员的私人记忆") })
            })
            put("required", buildJsonArray { add("id"); add("sourceMessageIds"); add("evidence") })
        }.toString(),
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val id = ToolArgs.string(args, "id").trim()
        if (id.isEmpty()) {
            return AgentToolResult(errorJson("缺少记忆ID id，请先调用 search_memory 或 list_memories 获取目标ID"))
        }
        val sourceIds = ToolArgs.strings(args, "sourceMessageIds").map { it.trim() }.filter { it.isNotEmpty() }
        val evidence = ToolArgs.string(args, "evidence").trim()
        if (sourceIds.isEmpty() || evidence.isEmpty()) {
            return AgentToolResult(errorJson("必须给出 sourceMessageIds 与 evidence（逐字摘录用户纠正的原话）"))
        }
        if (hasValidUserEvidence(context.character, sourceIds, evidence) == null) {
            return AgentToolResult(errorJson("evidence 必须是 sourceMessageIds 指定用户消息中的逐字原话；对不上时不修改"))
        }
        val memberName = ToolArgs.string(args, "memberName").trim().ifEmpty { null }
        val subjectRaw = ToolArgs.string(args, "subject").trim()
        val subject = parseSubject(subjectRaw)
        if (subjectRaw.isNotEmpty() && subject == null) {
            return AgentToolResult(errorJson("subject 只能是 user / relationship / world / character"))
        }
        val value = ToolArgs.string(args, "value").trim()
        val key = ToolArgs.string(args, "key").trim()
        val importance = ToolArgs.int(args, "importance")
        val promisor = ToolArgs.string(args, "promisor").trim()
        val promisee = ToolArgs.string(args, "promisee").trim()
        val eventTime = ToolArgs.string(args, "eventTime").trim()
        val dueAt = ToolArgs.string(args, "dueAt").trim()
        if (subject == null && value.isEmpty() && key.isEmpty() && importance == null &&
            promisor.isEmpty() && promisee.isEmpty() && eventTime.isEmpty() && dueAt.isEmpty()
        ) {
            return AgentToolResult(errorJson("至少要修改 subject / value / key / importance / promisor / promisee / eventTime / dueAt 之一"))
        }

        var found = false
        val updated = memory.updateMemory(context.character, id, memberName) { old ->
            found = true
            old.copy(
                subject = subject ?: old.subject,
                value = value.ifEmpty { old.value },
                key = key.ifEmpty { old.key },
                importance = importance?.coerceIn(1, 10) ?: old.importance,
                promisor = promisor.ifEmpty { old.promisor },
                promisee = promisee.ifEmpty { old.promisee },
                eventTime = eventTime.ifEmpty { old.eventTime },
                dueAt = dueAt.ifEmpty { old.dueAt },
            )
        }
        if (!found) {
            return AgentToolResult(errorJson("找不到该记忆ID（可能已被删除或合并），请重新检索后再试"))
        }
        return AgentToolResult(
            """{"ok":true,"id":${quote(id)},"changed":true,"subject":${quote(subject?.name?.lowercase() ?: "")}}""",
        )
    }

    private fun parseSubject(raw: String): MemorySubject? = when (raw.lowercase()) {
        "user" -> MemorySubject.User
        "character" -> MemorySubject.Character
        "relationship" -> MemorySubject.Relationship
        "world" -> MemorySubject.World
        else -> null
    }
}
