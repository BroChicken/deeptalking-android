package com.deeptalking.domain.agent.tools

import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.prompts.STATIC_PROFILE_FIELDS
import com.deeptalking.domain.agent.prompts.staticFieldLabel
import com.deeptalking.domain.agent.prompts.withStaticField
import com.deeptalking.domain.memory.hasStaticEditIntent
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
import java.time.ZonedDateTime

/**
 * Port of the `update_character_field` branch in tool-execution.js: sets the
 * requested [STATIC_PROFILE_FIELDS] key on a copy of the character and returns
 * it via [AgentToolResult.updatedCharacter].
 *
 * Group entities keep the legacy [GROUP_SHARED_STATIC_FIELDS] whitelist (empty
 * in the legacy config, so groups reject every static field with the shared-field
 * guidance message rather than the tool being hidden).
 */
class UpdateCharacterFieldTool : AgentTool {

    private val validFields = STATIC_PROFILE_FIELDS.joinToString(",") { "\"${it.first}\"" }

    override val definition = ToolDefinition(
        name = "update_character_field",
        description = "精准修改角色基础设定字段（性别/年龄/种族/外貌特征/性格特征/价值观/恐惧弱点/背景故事/关键过往/说话风格/语言方言/对用户的称呼）。当用户直接要求修改这些设定（如\"把我职业改成教师\"\"我现在是学生了\"\"你性格应该更冷酷些\"\"别叫我用户，叫我明明\"）时，必须调用本工具立即生效；value一律以段落式的陈述句书写（用自然、完整的陈述句写成简短段落，如性格\"性格冷静克制，遇事沉稳。\"），禁止括号注释、理由、整句说明或解释性文字；\"对用户的称呼\"只填一个简短称呼词、不带任何解释。群组实体不维护基础设定字段（世界观并入群组描述），成员的个人字段请在该成员卡片上修改。不要在submit_response的staticFields里重复提交本工具已改的字段。",
        parametersJson = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("field", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { STATIC_PROFILE_FIELDS.forEach { add(it.first) } })
                    put("description", "要修改的字段")
                })
                put("value", buildJsonObject { put("type", "string"); put("description", "字段值：以段落式的陈述句书写（自然、完整的陈述句，简短段落）；禁止括号注释、理由、解释性文字；涉及日期写绝对日期") })
                put("sourceMessageIds", buildJsonObject {
                    put("type", "array"); put("items", buildJsonObject { put("type", "string") })
                    put("description", "引用用户消息ID（用户原话所在消息）")
                })
                put("evidence", buildJsonObject { put("type", "string"); put("description", "逐字摘录用户要求修改的原话") })
                put("timeRef", timeRefSchema())
            })
            put("required", buildJsonArray { add("field"); add("value"); add("sourceMessageIds"); add("evidence") })
        }.toString(),
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val field = ToolArgs.string(args, "field").trim()
        val label = staticFieldLabel(field)
        if (label == null) {
            return AgentToolResult(errorJson("field 必须是基础设定字段之一：" + STATIC_PROFILE_FIELDS.joinToString("、") { it.first }))
        }
        if (context.character.isGroup && field !in GROUP_SHARED_STATIC_FIELDS) {
            val shared = GROUP_SHARED_STATIC_FIELDS.mapNotNull { staticFieldLabel(it) }.joinToString("、")
            return AgentToolResult(errorJson("群组只有公用字段可改：$shared；成员的个人字段请在该成员卡片上修改"))
        }

        val nowZoned = ZonedDateTime.now()
        val resolved = resolveTimeRef(ToolArgs.timeRef(args, "timeRef"), nowZoned)
        val base = resolved?.iso?.let { parseZoned(it) } ?: nowZoned
        val value = MemoryToolSupport.cleanFieldValue(field, parseRelativeText(ToolArgs.string(args, "value"), base))
        if (value.isEmpty()) return AgentToolResult(errorJson("value 为空或全部为说明性文字，请只提供内容本身"))

        val sourceIds = ToolArgs.strings(args, "sourceMessageIds").map { it.trim() }.filter { it.isNotEmpty() }
        val evidence = ToolArgs.string(args, "evidence").trim()
        if (sourceIds.isEmpty() || evidence.isEmpty()) {
            return AgentToolResult(errorJson("sourceMessageIds 必须是真实用户消息ID，且 evidence 逐字摘录用户原话；找不到可引用来源时不修改"))
        }
        // Static fields are only touched when the user explicitly asks for the change.
        val resolvedSources = hasValidUserEvidence(context.character, sourceIds, evidence)
            ?: return AgentToolResult(errorJson("evidence 必须逐字来自 sourceMessageIds 指定的用户消息，找不到用户原话时不修改"))
        if (!hasStaticEditIntent(field, resolvedSources)) {
            return AgentToolResult(errorJson("用户并未明确要求修改「$label」，静态设定不修改"))
        }
        val updated = context.character.copy(
            staticProfile = withStaticField(context.character.staticProfile, field, value),
        )
        return AgentToolResult(
            contentJson = """{"ok":true,"field":${quote(field)},"label":${quote(label)},"value":${quote(value)},"changed":true}""",
            updatedCharacter = updated,
        )
    }

    private companion object {
        /** Legacy `GROUP_SHARED_STATIC_FIELDS` (empty: world lore lives in the group description). */
        val GROUP_SHARED_STATIC_FIELDS: Set<String> = emptySet()
    }
}
