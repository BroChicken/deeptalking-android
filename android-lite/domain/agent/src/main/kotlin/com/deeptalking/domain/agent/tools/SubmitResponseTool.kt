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
        parametersJson = buildSchema().toString(),
        strict = true,
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult =
        AgentToolResult("""{"ok":true}""")

    private fun buildSchema(): JsonElement {
        val dynField = dynamicStateFieldSchema()
        val dynProps = buildJsonObject {
            dynamicStateKeys().forEach { put(it, dynField) }
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
                            put("description", "用户可见的回复正文，支持Markdown；按语义可自然分段；动作、表情、心理活动用括号穿插在语句之间、与语句交替推进（平均每1–2句一次，不要只在开头或结尾集中出现），动作总长不超过回复一半；口吻一律以角色设定的“说话风格”为准")
                        },
                    )
                    put(
                        "quickReplies",
                        buildJsonObject {
                            put("type", "array")
                            put("minItems", 2)
                            put("maxItems", 2)
                            put("description", "恰好两条\"用户下一句\"的短句：用户视角，是用户可以原样发给角色的话（其中\"我\"只能指用户）。不得是角色的台词、角色的表态或角色对用户的提问，也不得复述角色刚说过的话。写前先把自己换成用户。")
                            put("items", buildJsonObject { put("type", "string") })
                        },
                    )
                    put("shortTerm", shortTermSchema())
                    put("longTerm", longTermSchema())
                    put(
                        "dynamicState",
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", false)
                            put("description", "角色当前动态状态，仅在有明确依据时更新")
                            put("properties", dynProps)
                        },
                    )
                    put(
                        "staticFields",
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", false)
                            put("description", "谨慎修改的基础设定字段（性别/年龄/种族/外貌特征/性格特征/价值观/恐惧弱点/背景故事/关键过往/说话风格/语言方言/对用户的称呼）。用户明确要求修改时必须更新；无用户要求时仅在有决定性剧情依据时更新。")
                            put("properties", staticProps)
                        },
                    )
                    put(
                        "memberDynamicState",
                        buildJsonObject {
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
                        },
                    )
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
                put("timeRef", timeRefSchema())
            },
        )
        put("required", buildJsonArray { add("value") })
    }

    private fun shortTermSchema(): JsonElement = buildJsonObject {
        put("type", "array")
        put("description", "本轮事件流程摘要（对后续几轮有帮助的信息）；content必须写明谁做了什么并写绝对日期，不得写相对时间词；用户用相对时间表述时填timeRef")
        put(
            "items",
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put(
                    "properties",
                    buildJsonObject {
                        put("content", buildJsonObject { put("type", "string") })
                        put("sourceMessageIds", stringArraySchema())
                        put("timeRef", timeRefSchema())
                    },
                )
                put("required", buildJsonArray { add("content") })
            },
        )
    }

    private fun longTermSchema(): JsonElement = buildJsonObject {
        put("type", "array")
        put("description", "未来仍有价值的稳定事实；category 只能是 userProfile|relationship|events|promises|habits；evidence 逐字摘录用户原话；key与value必须写明主体并写绝对日期；约定必须填promisor与promisee；群组对话中若该事实只属于某位成员，填 memberName 记入其私人记忆")
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
                        put("dueAt", buildJsonObject { put("type", "string") })
                        put("promisor", buildJsonObject { put("type", "string") })
                        put("promisee", buildJsonObject { put("type", "string") })
                        put("timeRef", timeRefSchema())
                        put("memberName", buildJsonObject { put("type", "string") })
                    },
                )
                put("required", buildJsonArray { add("category"); add("key"); add("value") })
            },
        )
    }

    // timeRefSchema() is shared; see ToolSchemas.kt.

    private fun stringArraySchema(): JsonElement = buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "string") })
    }

    private fun dynamicStateKeys(): List<String> = listOf(
        "currentSituation", "currentLocation", "currentMood", "currentOccupation",
        "currentGoal", "currentRelationship", "currentImportantOthers",
    )
}
