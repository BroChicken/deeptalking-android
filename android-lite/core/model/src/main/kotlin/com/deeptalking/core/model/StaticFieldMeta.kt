package com.deeptalking.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `staticFieldMeta` 的每字段小账本：除相对时间迁移用的 `updatedAt` 外，另记一个
 * **人工标记**——用户在卡片编辑器里手写过的静态字段。
 *
 * 标记为人工的字段，模型不得自动改写（`submit_response.staticFields` 直接跳过、
 * `update_character_field` 回一条明确原因），只有**本轮用户明确要求**改该字段时才放行；
 * 用户在编辑器里点「让角色接管」即解除该标记，恢复模型自动更新。
 *
 * 旧数据里没有这个键 = 未锁定（默认值兼容）。
 */
private const val MANUAL_KEY = "manual"

/** 该字段是否被用户标为人工（缺失 / 旧数据一律视为 false）。 */
fun isStaticFieldManual(meta: Map<String, JsonElement>, key: String): Boolean {
    val field = meta[key] as? JsonObject ?: return false
    return (field[MANUAL_KEY] as? JsonPrimitive)?.booleanOrNull == true
}

/**
 * 设置 / 清除某字段的人工标记，保留账本里已有的其它键（如 `updatedAt`）。
 * 清除时若账本里已无其它内容则整条删除，避免导出里堆 `"manual":false`。
 */
fun withStaticFieldManual(
    meta: Map<String, JsonElement>,
    key: String,
    manual: Boolean,
): Map<String, JsonElement> {
    val field = (meta[key] as? JsonObject) ?: JsonObject(emptyMap())
    val next = JsonObject(field + (MANUAL_KEY to JsonPrimitive(manual)))
    return if (!manual && next.size == 1) meta - key else meta + (key to next)
}
