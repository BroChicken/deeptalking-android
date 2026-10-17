package com.deeptalking.domain.agent.tools

import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.prompts.STATIC_PROFILE_FIELDS
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the `submit_response` terminal tool schema (port of
 * `buildSubmitResponseTool` in responses.js) using kotlinx.serialization.
 */
class SubmitResponseTool : AgentTool {

    override val definition = ToolDefinition(
        name = "submit_response",
        description = "结束本轮回复：提交用户可见的回复正文与全部记忆更新。每轮对话结束时必须且只能调用一次此工具（推荐用它收尾，而不是在正文里手写 JSON）；回复正文写入 reply 字段。",
        parametersJson = buildSchema(isGroup = true).toString(),
        strict = true,
    )

    /**
     * Single-character requests get the same schema minus the group-only blocks
     * (`memberDynamicState`, `longTerm[].memberName`, the "群组成员名" party values):
     * a solo character can never use them, and they are the bulk of the schema.
     */
    private val soloDefinition: ToolDefinition by lazy {
        ToolDefinition(
            name = "submit_response",
            description = definition.description,
            parametersJson = buildSchema(isGroup = false).toString(),
            strict = true,
        )
    }

    override fun definitionFor(context: AgentContext): ToolDefinition =
        if (context.character.isGroup) definition else soloDefinition

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult =
        AgentToolResult("""{"ok":true}""")

    private fun buildSchema(isGroup: Boolean): JsonElement {
        val dynField = dynamicStateFieldSchema()
        val dynProps = buildJsonObject {
            dynamicStateKeys().forEach { key ->
                put(key, if (key == "currentTone") currentToneFieldSchema() else dynField)
            }
        }
        val staticProps = buildJsonObject {
            STATIC_PROFILE_FIELDS.forEach { (key, _) -> put(key, dynField) }
        }
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "properties",
                buildJsonObject {
                    put(
                        "reply",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "用户可见的回复正文（写法见规则 2：长度、排版、全角括号动作、引号与引用、去重）")
                        },
                    )
                    put(
                        "quickReplies",
                        buildJsonObject {
                            put("type", "array")
                            put("minItems", 2)
                            put("maxItems", 2)
                            put("description", "恰好两条用户视角的短句（三类禁止与新句要求见规则 7）")
                            put("items", buildJsonObject { put("type", "string") })
                        },
                    )
                    put("longTerm", longTermSchema(isGroup))
                    put(
                        "dynamicState",
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", false)
                            put("description", "角色当前动态状态：仅在有明确依据时更新对应字段；currentTone 例外，每轮都必须给出")
                            put("properties", dynProps)
                            put("required", buildJsonArray { add("currentTone") })
                        },
                    )
                    put(
                        "staticFields",
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", false)
                            put("description", "谨慎修改的基础设定字段（字段清单、长度上限与句式要求见规则 3.5）")
                            put("properties", staticProps)
                        },
                    )
                    if (isGroup) put("memberDynamicState", memberDynamicStateSchema(dynProps))
                    put(
                        "promiseUpdates",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "用户明确完成或取消的承诺")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "object")
                                    put("additionalProperties", false)
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("promiseId", buildJsonObject { put("type", "string") })
                                            put(
                                                "status",
                                                buildJsonObject {
                                                    put("type", "string")
                                                    put("enum", buildJsonArray { add("resolved"); add("cancelled") })
                                                },
                                            )
                                            put("sourceMessageIds", stringArraySchema())
                                            put("evidence", buildJsonObject { put("type", "string") })
                                        },
                                    )
                                    put("required", buildJsonArray { add("promiseId"); add("status") })
                                },
                            )
                        },
                    )
                    put(
                        "recall",
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", false)
                            put("description", "主动召回记忆的请求")
                            put(
                                "properties",
                                buildJsonObject {
                                    put("category", buildJsonObject { put("type", "string") })
                                    put("tags", buildJsonObject {
                                        put("type", "array")
                                        put("items", buildJsonObject { put("type", "string") })
                                    })
                                },
                            )
                        },
                    )
                },
            )
            put("required", buildJsonArray { add("reply"); add("quickReplies") })
        }
    }

    private fun dynamicStateFieldSchema(): JsonElement = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "字段值：只写内容本身，以段落式的陈述句书写（自然、完整的陈述句，简短段落）；禁止括号注释、理由、解释性文字")
                    },
                )
                put("sourceMessageIds", stringArraySchema())
                put("evidence", buildJsonObject { put("type", "string") })
                put("timeRef", timeRefPointerSchema())
            },
        )
        put("required", buildJsonArray { add("value") })
    }

    /** `currentTone` is the read-aloud delivery instruction, always required and evidence-free. */
    private fun currentToneFieldSchema(): JsonElement = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "本轮朗读播报指令（每轮必填、免证据；写法见规则 3）")
                    },
                )
            },
        )
        put("required", buildJsonArray { add("value") })
    }

    private fun longTermSchema(isGroup: Boolean): JsonElement = buildJsonObject {
        val partyValues = if (isGroup) {
            "user（用户）/character（角色自己）/relationship（双方）/群组成员名"
        } else {
            "user（用户）/character（角色自己）/relationship（双方）"
        }
        put("type", "array")
        put("description", "未来仍有价值的稳定事实（判据见规则 4/5/9；各字段写法见本对象字段说明）")
        put(
            "items",
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "category",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("userProfile"); add("relationship"); add("events"); add("promises"); add("habits")
                                })
                            },
                        )
                        put(
                            "subject",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { add("user"); add("relationship"); add("world") })
                            },
                        )
                        put("key", buildJsonObject { put("type", "string") })
                        put("value", buildJsonObject { put("type", "string") })
                        put("tags", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                        put("importance", buildJsonObject {
                            put("type", "integer"); put("minimum", 1); put("maximum", 10)
                        })
                        put("sourceMessageIds", stringArraySchema())
                        put("evidence", buildJsonObject { put("type", "string") })
                        put("eventTime", buildJsonObject { put("type", "string") })
                        put("dueAt", buildJsonObject {
                            put("type", "string")
                            put("description", "可选，约定的截止时间（ISO格式）")
                        })
                        put("promisor", buildJsonObject {
                            put("type", "string")
                            put("description", "可选但约定必填，承诺方：$partyValues")
                        })
                        put("promisee", buildJsonObject {
                            put("type", "string")
                            put("description", "可选但约定必填，受约方：$partyValues")
                        })
                        put("timeRef", timeRefSchema())
                        if (isGroup) {
                            put("memberName", buildJsonObject {
                                put("type", "string")
                                put("description", "可选，群组对话中指定记入某位成员的私人记忆（不传则记入群组共享记忆）")
                            })
                        }
                    },
                )
                put("required", buildJsonArray { add("category"); add("key"); add("value") })
            },
        )
    }

    // timeRefSchema() is the single canonical definition (used by longTermSchema and
    // set_reminder); every other site uses the compact timeRefPointerSchema().

    /** Group-only: per-member dynamic state; reuses the same field shape as `dynamicState`. */
    private fun memberDynamicStateSchema(dynProps: JsonElement): JsonElement = buildJsonObject {
        put("type", "array")
        put("description", "群组成员动态状态（仅群组对话使用）")
        put(
            "items",
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put(
                    "properties",
                    buildJsonObject {
                        put("memberName", buildJsonObject { put("type", "string") })
                        put(
                            "dynamicState",
                            buildJsonObject {
                                put("type", "object")
                                put("additionalProperties", false)
                                put("properties", dynProps)
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add("memberName") })
            },
        )
    }

    private fun stringArraySchema(): JsonElement = buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "string") })
    }

    private fun dynamicStateKeys(): List<String> = listOf(
        "currentSituation", "currentLocation", "currentMood", "currentOccupation",
        "currentGoal", "currentTone",
    )
}
