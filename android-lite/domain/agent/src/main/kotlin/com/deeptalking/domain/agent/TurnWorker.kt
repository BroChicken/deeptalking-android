package com.deeptalking.domain.agent

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.Todo
import com.deeptalking.core.model.TodoStatus
import com.deeptalking.domain.agent.prompts.TimeContext
import com.deeptalking.engine.ondevice.LlmRequest
import com.deeptalking.engine.ondevice.LlmResult
import com.deeptalking.engine.ondevice.TokenUsage
import com.deeptalking.engine.ondevice.ToolChoice
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One background turn worker: asks the model for the structured side effects of a turn
 * (`dynamicState`, `quickReplies`, `todos`, `promiseUpdates`) through a single forced
 * `submit_state` function call. The main chat call is plain text and never produces these.
 *
 * Failure is deliberately non-fatal: [Output.ok] is false, the caller keeps the previous
 * state, and only a metric is recorded.
 */
internal object TurnWorker {

    const val TOOL_NAME = "submit_state"

    private val json = Json { isLenient = true; ignoreUnknownKeys = true; explicitNulls = false }

    private const val RAW_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "dynamicState": {
      "type": "object",
      "additionalProperties": false,
      "description": "只给有明确变化或需要补齐的字段，不含朗读语气；群组只写 shared 的 currentSituation/currentLocation",
      "properties": {
        "currentSituation": { "${'$'}field": true },
        "currentLocation": { "${'$'}field": true },
        "currentMood": { "${'$'}field": true },
        "currentOccupation": { "${'$'}field": true },
        "currentGoal": { "${'$'}field": true }
      }
    },
    "memberDynamicState": {
      "type": "array",
      "description": "仅群组：每位成员自己的动态状态（可含各自朗读语气；群组共享状态不含语气）",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "memberName": { "type": "string" },
          "dynamicState": {
            "type": "object",
            "additionalProperties": false,
            "properties": {
              "currentSituation": { "${'$'}field": true },
              "currentLocation": { "${'$'}field": true },
              "currentMood": { "${'$'}field": true },
              "currentOccupation": { "${'$'}field": true },
              "currentGoal": { "${'$'}field": true },
              "currentTone": {
                "type": "string",
                "description": "仅成员：该成员本轮的朗读语气/情绪/语速（如「压低声音、语速偏快」），只描述怎么念，不写事实内容；没有明显变化就省略"
              }
            }
          }
        },
        "required": ["memberName", "dynamicState"]
      }
    },
    "quickReplies": {
      "type": "array",
      "minItems": 2,
      "maxItems": 2,
      "items": { "type": "string" },
      "description": "两条用户视角、可原样发送的短句"
    },
    "todos": {
      "type": "array",
      "description": "本轮新增或变更的待办",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "op": { "type": "string", "enum": ["create", "done", "cancel", "reschedule"] },
          "todoId": { "type": "string", "description": "done/cancel/reschedule 时必填，取自【当前待办】" },
          "title": { "type": "string", "description": "create 时必填：一句话的待办标题" },
          "detail": { "type": "string" },
          "proposer": {
            "type": "string",
            "enum": ["user", "character"],
            "description": "create 时必填：user=用户提出；character=角色提议且用户已明确答应（此时 evidence 必须引用户答应的原话）"
          },
          "dueAt": { "type": "string", "description": "create/reschedule 时必填，ISO 时间" },
          "timeRef": { "type": "object", "additionalProperties": false, "properties": {
            "anchor": { "type": "string" }, "offsetDays": { "type": "integer" },
            "weekday": { "type": "string" }, "slot": { "type": "string" },
            "explicit": { "type": "string" } } },
          "sourceMessageIds": { "type": "array", "items": { "type": "string" } },
          "evidence": {
            "type": "string",
            "description": "必填：用户原句或紧扣原文的概括；完成/取消/改期时要能对上用户确认的原话"
          }
        },
        "required": ["op", "evidence", "sourceMessageIds"]
      }
    },
    "promiseUpdates": {
      "type": "array",
      "description": "用户明确完成或取消的约定",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "promiseId": { "type": "string", "description": "取自【当前约定】" },
          "status": { "type": "string", "enum": ["resolved", "cancelled"] },
          "sourceMessageIds": { "type": "array", "items": { "type": "string" } },
          "evidence": { "type": "string" }
        },
        "required": ["promiseId", "status", "evidence", "sourceMessageIds"]
      }
    }
  },
  "required": ["quickReplies"]
}
"""

    /** The `$field` shorthand used by the design doc, expanded into a real JSON Schema object. */
    private val FIELD_SCHEMA = """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "value": { "type": "string", "description": "只写内容本身的陈述句" },
    "sourceMessageIds": { "type": "array", "items": { "type": "string" } },
    "evidence": { "type": "string", "description": "原句或概括" },
    "timeRef": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "anchor": { "type": "string" },
        "offsetDays": { "type": "integer" },
        "weekday": { "type": "string" },
        "slot": { "type": "string" },
        "explicit": { "type": "string" }
      }
    }
  },
  "required": ["value", "sourceMessageIds", "evidence"]
}
"""

    private val PLACEHOLDER = "{ \"${'$'}field\": true }"

    private val SCHEMA: String = RAW_SCHEMA.replace(PLACEHOLDER, FIELD_SCHEMA)

    val tool: ToolDefinition = ToolDefinition(
        name = TOOL_NAME,
        description = "提交本轮的动态状态、快速回应、待办与约定变更。",
        parametersJson = SCHEMA,
        strict = false,
    )

    private const val SYSTEM = """你是「对话整理助手」。只调用 submit_state，不要输出正文或解释。
任务：
1. 提取本轮有明确变化的动态字段（不含朗读语气）；
2. 生成两条用户视角的快速回应；
3. 维护待办与约定：新建、完成、取消、改期。
规则：
- 只写本轮有明确依据的内容；没有变化就省略，不凭空补全。
- 每个字段必须有 sourceMessageIds 与 evidence；角色本轮言行用 ["current_response"]，evidence 引原句或概括。
- currentSituation 写具体日期 + 时段，以【时间基准】的逻辑日为准。
- 快速回应必须恰好两条，是用户此刻可能原样发给角色的话；不得是角色口吻、角色提问或复述角色刚说过的话；不写 Markdown、括号动作、消息 ID。
- 待办：只在用户提出，或角色提出且用户明确答应后建立；角色单方面说自己要做什么不算。新建必须有明确日期，日期要能在用户原话里定位（用户发起），或在角色上一轮提议 + 用户回应里定位（角色发起）；用户没回应、转移话题或拒绝都不建。角色发起填 proposer=character 并附用户答应的原话，用户发起填 proposer=user。完成、取消、改期必须引用用户原话，按【当前待办】的 ID 指认；同一件事不要重复新建。
- 约定：只有用户明确完成或取消时才更新 promiseUpdates，按【当前约定】的 ID；到期不自动完成。"""

    fun buildRequest(
        config: AppConfig,
        character: Character,
        userText: String,
        reply: String,
        replyId: String,
        proactive: Boolean,
        now: java.time.Instant,
    ): LlmRequest {
        val time = TimeContext.snapshot(now)
        // Groups keep their shared state in `groupSharedDynamic`; single characters use
        // `dynamicState`. The worker must see the same source the volatile context shows.
        val currentState = if (character.isGroup) character.groupSharedDynamic else character.dynamicState
        val state = buildString {
            appendLine("【当前状态】")
            appendLine("处境: ${currentState.currentSituation.ifBlank { "(未设置)" }}")
            appendLine("位置: ${currentState.currentLocation.ifBlank { "(未设置)" }}")
            appendLine("情绪: ${currentState.currentMood.ifBlank { "(未设置)" }}")
            appendLine("职业/身份: ${currentState.currentOccupation.ifBlank { "(未设置)" }}")
            appendLine("目标: ${currentState.currentGoal.ifBlank { "(未设置)" }}")
        }
        val todos = character.todos.filter { it.status == TodoStatus.Active }.joinToString("\n") {
            "- [ID:${it.id}] ${it.title}｜期限：${it.dueAt.ifBlank { "未设置" }}｜状态：进行中"
        }.ifBlank { "- （无）" }
        val promises = character.longTerm
            .filter { it.category == MemoryCategory.Promises && it.status == PromiseStatus.Active }
            .joinToString("\n") {
                "- [ID:${it.id}] ${it.key}: ${it.value}｜承诺方：${it.promisor.orEmpty().ifBlank { "未说明" }}｜期限：${it.dueAt.orEmpty().ifBlank { "未设置" }}"
            }.ifBlank { "- （无）" }
        val recent = character.instant
            .filter { !it.isLoading && !it.internalOnly && it.content.isNotBlank() }
            .takeLast(6)
            .joinToString("\n") { message ->
                val who = if (message.role == Role.User) "用户" else character.name
                "$who: ${message.content.take(500)}"
            }
        val input = buildString {
            appendLine(state.trimEnd())
            appendLine()
            appendLine("【当前待办】")
            appendLine(todos)
            appendLine()
            appendLine("【当前约定】")
            appendLine(promises)
            appendLine()
            if (character.workerIssues.isNotEmpty()) {
                appendLine("【上轮整理未落地】")
                character.workerIssues.take(8).forEach { appendLine("- $it") }
                appendLine("这些是本地上轮拒绝的条目。本轮若仍成立，请重新提交并附正确证据；不成立就忽略，不要编造证据。")
                appendLine()
            }
            if (proactive) {
                appendLine("【本轮无用户消息（角色主动开场）】")
            } else {
                val userId = character.instant.lastOrNull { it.role == Role.User && !it.isLoading }?.id.orEmpty()
                appendLine("【本轮用户消息（id: $userId）】")
                appendLine(userText.ifBlank { "（无文本）" })
            }
            appendLine()
            appendLine("【角色回复（id: $replyId）】")
            appendLine(reply)
            appendLine()
            appendLine("【时间基准】")
            appendLine("${time.local} / ${time.timeZone} (${time.offset}) / ${time.iso} / 逻辑日 ${time.day} ${time.slot}")
            appendLine()
            appendLine("【最近对话（仅用于理解指代，不抄写）】")
            appendLine(recent.ifBlank { "（无）" })
        }
        return LlmRequest(
            model = config.modelName,
            instructions = SYSTEM,
            input = listOf(
                com.deeptalking.core.model.ChatMessage(
                    role = Role.User,
                    content = input,
                ),
            ),
            tools = listOf(tool),
            toolChoice = ToolChoice.Function(TOOL_NAME),
            temperature = 0.2,
            maxOutputTokens = MAX_OUTPUT_TOKENS,
            stream = false,
            reasoningEffort = "none",
            apiPlatform = config.apiPlatform,
            sessionId = "deeptalking-worker-${character.id}",
            auxiliary = true,
        )
    }

    /** Parses `submit_state` arguments; falls back to a JSON object in the text. */
    fun parse(result: LlmResult): Output {
        val arguments = result.toolCalls.firstOrNull { it.name == TOOL_NAME }?.arguments
            ?: result.text.takeIf { it.trimStart().startsWith("{") }
        val root = arguments?.let {
            runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        } ?: return Output(ok = false, usage = result.usage)
        // The contract is exactly [QUICK_REPLY_COUNT] user-voice lines. Counts that break it
        // are handled by an explicit rule (never silently truncated without a trace):
        // too many keeps the first two, too few rejects the whole list as unusable so the
        // caller's repair/fallback path regenerates it.
        val parsedReplies = (root["quickReplies"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            ?.distinct()
            .orEmpty()
        val quickReplies = when {
            parsedReplies.size == QUICK_REPLY_COUNT -> parsedReplies
            parsedReplies.size > QUICK_REPLY_COUNT -> parsedReplies.take(QUICK_REPLY_COUNT)
            else -> emptyList()
        }
        val quickReplyWarning = when {
            parsedReplies.size > QUICK_REPLY_COUNT ->
                "quickReplies 返回 ${parsedReplies.size} 条，超过 $QUICK_REPLY_COUNT 条，只保留前两条"
            parsedReplies.size < QUICK_REPLY_COUNT ->
                "quickReplies 只有 ${parsedReplies.size} 条（要求 $QUICK_REPLY_COUNT 条），弃用并由修复流程重建"
            else -> null
        }
        return Output(
            dynamicState = root["dynamicState"] as? JsonObject,
            memberDynamicState = root["memberDynamicState"] as? JsonArray,
            quickReplies = quickReplies,
            todos = root["todos"] as? JsonArray,
            promiseUpdates = root["promiseUpdates"] as? JsonArray,
            ok = true,
            usage = result.usage,
            warnings = listOfNotNull(quickReplyWarning),
        )
    }

    /**
     * One `memberDynamicState` element: the member's name plus its state object, untouched.
     *
     * `currentTone` is a legitimate **member-only** field (each member reads their own lines
     * with their own delivery), so unlike the character-level `dynamicState` it must not be
     * filtered out by the caller. Malformed elements are skipped instead of failing the parse,
     * matching the previous per-element tolerance.
     */
    internal fun memberStates(output: Output): List<Pair<String, JsonObject>> =
        output.memberDynamicState?.mapNotNull { memberStateOf(it) }.orEmpty()

    /** [memberStates] for a raw element; null when the name or state object is missing. */
    internal fun memberStateOf(element: JsonElement): Pair<String, JsonObject>? {
        val obj = element as? JsonObject ?: return null
        val name = (obj["memberName"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val state = obj["dynamicState"] as? JsonObject ?: return null
        if (name.isEmpty() || state.isEmpty()) return null
        return name to state
    }

    data class Output(
        val dynamicState: JsonObject? = null,
        val memberDynamicState: JsonArray? = null,
        val quickReplies: List<String> = emptyList(),
        val todos: JsonArray? = null,
        val promiseUpdates: JsonArray? = null,
        val ok: Boolean = false,
        val usage: TokenUsage? = null,
        /**
         * Non-fatal contract violations (currently `quickReplies` count). The parse still
         * succeeds; callers may log these instead of guessing why a field was dropped.
         */
        val warnings: List<String> = emptyList(),
    )

    /** Marker used by tests/tools: the per-request output budget is shared with auxiliary calls. */
    const val MAX_OUTPUT_TOKENS = AppLimits.Api.AUXILIARY_MAX_OUTPUT_TOKENS

    /** The worker contract asks for exactly two user-voice quick replies. */
    const val QUICK_REPLY_COUNT = 2
}
