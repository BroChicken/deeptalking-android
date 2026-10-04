package com.deeptalking.domain.agent.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared JSON-Schema fragment for `timeRef` (port of `buildTimeRefSchema` in
 * the legacy frontend). Used by submit_response, set_reminder and update_memory
 * so the model sees one consistent relative-time contract.
 */
internal fun timeRefSchema(): JsonElement = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put(
        "description",
        "相对时间词元：用户用\"明天/上周/上个月/三天后\"等相对说法表达时间时必填，客户端会换算成绝对日期。anchor=相对基准，offsetDays=在anchor基础上的天数偏移(-60~60)，weekday=星期，slot=时段，explicit=已经是绝对时间的ISO或YYYY-MM-DD（填了explicit则忽略其余）。content/value内容字段仍须写绝对日期，不得写\"明天\"这类相对时间词。",
    )
    put(
        "properties",
        buildJsonObject {
            put("anchor", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    listOf(
                        "today", "tomorrow", "yesterday", "day_after_tomorrow", "day_before_yesterday",
                        "this_week", "next_week", "last_week", "this_month", "next_month", "last_month",
                        "this_year", "next_year", "last_year",
                    ).forEach { add(it) }
                })
            })
            put("offsetDays", buildJsonObject { put("type", "integer"); put("minimum", -60); put("maximum", 60) })
            put("weekday", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").forEach { add(it) } })
            })
            put("slot", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    listOf("深夜", "凌晨", "清晨", "早晨", "上午", "中午", "下午", "傍晚", "晚上", "夜里").forEach { add(it) }
                })
            })
            put("explicit", buildJsonObject { put("type", "string") })
        },
    )
}
