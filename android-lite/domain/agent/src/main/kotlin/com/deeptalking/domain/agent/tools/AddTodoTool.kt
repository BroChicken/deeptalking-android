package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.Todo
import com.deeptalking.core.model.TodoProposer
import com.deeptalking.core.model.TodoStatus
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.prompts.trimText
import com.deeptalking.domain.memory.TodoPolicy
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
import java.util.UUID

/**
 * 新增一条**独立**的待办事项。
 *
 * 与长期记忆完全解耦：它不写 `longTerm`，也从不读它。准入三条件（见 `TodoPolicy`）：
 * ①对话里出现明确期限；②由用户或角色提出；③双方同意（用户发起→用户原话；角色发起→用户应答原话）。
 * 期限定位不到、或用户没应答，一律拒绝并回报原因，绝不静默记下。
 */
class AddTodoTool : AgentTool {

    override val definition = ToolDefinition(
        name = "add_todo",
        description = "新增一条待办事项（与长期记忆无关，单独一份清单）。**必须同时满足三件事**：①对话里已经出现**明确的日期/时间**（“明天”“下周三”“十号晚八点”“2026-08-10”，不能是“以后/有空/最近”）；②这条待办是**用户提出**的、或**你（角色）提出**的；③**双方同意**——用户发起时以用户原话为准；你发起时必须由**你自己结合上下文判断用户真的答应了**（不是被提了一嘴、也不是婉拒/敷衍），并把用户**回应的原话**填进 sourceMessageIds 与 evidence。是否同意由你判断，不要只盯着字面关键词；但用户**明确拒绝/取消**过的，一律不要记。期限必须能在对话原话里定位，工具会拒绝核对不了的期限。到时间可在对话里自然提起，不要点明“这是待办”。",
        parametersJson = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("title", buildJsonObject { put("type", "string"); put("description", "待办的简短标题（如“交报告”）") })
                put("detail", buildJsonObject { put("type", "string"); put("description", "待办详情，写绝对日期，不要写“明天”这类相对词") })
                put("dueAt", buildJsonObject { put("type", "string"); put("description", "约定的期限（ISO格式，如 2026-08-10T09:00:00）；也可用 timeRef 表达相对时间") })
                put("timeRef", timeRefSchema())
                put("proposer", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("user"); add("character") })
                    put("description", "谁提出的：user=用户提出；character=你提出的、且用户已应答同意")
                })
                put("sourceMessageIds", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "用户原话所在消息ID（角色发起时填用户应答同意的消息ID）")
                })
                put("evidence", buildJsonObject {
                    put("type", "string")
                    put("description", "逐字摘录用户原话（用户发起=用户提出的话；角色发起=用户同意的话）")
                })
            })
            put(
                "required",
                buildJsonArray { add("title"); add("detail"); add("proposer"); add("sourceMessageIds"); add("evidence") },
            )
        }.toString(),
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val title = trimText(ToolArgs.string(args, "title"), 100)
        val detail = trimText(ToolArgs.string(args, "detail"), 900)
        if (title.isEmpty() || detail.isEmpty()) return AgentToolResult(errorJson("缺少 title 或 detail"))

        val proposer = when (ToolArgs.string(args, "proposer").trim().lowercase()) {
            "character" -> TodoProposer.Character
            else -> TodoProposer.User
        }
        val evidence = trimText(ToolArgs.string(args, "evidence"), 300)
        val sourceIds = ToolArgs.strings(args, "sourceMessageIds").map { it.trim() }.filter { it.isNotEmpty() }

        val character = context.character
        val now = Instant.now().toString()
        val nowZoned = ZonedDateTime.now()
        val resolved = resolveTimeRef(ToolArgs.timeRef(args, "timeRef"), nowZoned)
        val base = resolved?.iso ?: now
        val baseZoned = parseZoned(base) ?: nowZoned
        val dueAt = MemoryToolSupport.normalizeTimestamp(ToolArgs.string(args, "dueAt"), null) ?: resolved?.iso

        val decision = TodoPolicy.validateTodoCreation(
            character = character,
            proposer = proposer,
            sourceIds = sourceIds,
            evidence = evidence,
            dueAtIso = dueAt,
        )
        if (decision is TodoPolicy.Creation.Rejected) return AgentToolResult(errorJson(decision.reason))
        val accepted = decision as TodoPolicy.Creation.Accepted

        val parsedTitle = parseRelativeText(title, baseZoned)
        val parsedDetail = parseRelativeText(detail, baseZoned)
        val sourceIdList = accepted.sources.map { it.id }.distinct().take(8)

        val existingIndex = character.todos.indexOfFirst {
            it.status == TodoStatus.Active && it.title.equals(parsedTitle, ignoreCase = true)
        }
        val updatedTodos = if (existingIndex >= 0) {
            val old = character.todos[existingIndex]
            character.todos.toMutableList().also {
                it[existingIndex] = old.copy(
                    detail = parsedDetail,
                    dueAt = dueAt.orEmpty(),
                    proposer = proposer,
                    sourceMessageIds = sourceIdList,
                    evidence = evidence,
                    updatedAt = now,
                )
            }
        } else {
            character.todos + Todo(
                id = UUID.randomUUID().toString(),
                title = parsedTitle,
                detail = parsedDetail,
                dueAt = dueAt.orEmpty(),
                status = TodoStatus.Active,
                proposer = proposer,
                sourceMessageIds = sourceIdList,
                evidence = evidence,
                createdAt = now,
                updatedAt = now,
            )
        }
        val resultId = if (existingIndex >= 0) character.todos[existingIndex].id else updatedTodos.last().id
        val updatedCharacter: Character = character.copy(todos = updatedTodos)

        val payload = buildJsonObject {
            put("ok", true)
            put("id", resultId)
            put("title", parsedTitle)
            put("dueAt", MemoryToolSupport.formatContextTime(dueAt))
        }
        return AgentToolResult(payload.toString(), updatedCharacter = updatedCharacter)
    }
}
