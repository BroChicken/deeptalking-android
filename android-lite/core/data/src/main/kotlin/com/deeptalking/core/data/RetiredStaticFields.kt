package com.deeptalking.core.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一次性兼容：旧的 1.2.0 字段结构里还有「价值观」（`values`）与「关键过往」（`keyEvents`）
 * 两个字段，它们分别与「性格特征」和「背景故事」重叠（后者只是前者加了时间线），因此已从
 * `StaticProfile` 移除。
 *
 * 但**删字段不能让用户已有的人设凭空消失**：旧行在**首次读取**时把这两个字段的内容并进
 * 幸存字段（性格 ← 价值观、背景 ← 关键过往），并摘掉退休键。读取路径上做这件事意味着
 * 不需要一次性的迁移任务、也不需要"跑没跑过"的标记——行本身在下次写回时自然变成新结构。
 *
 * 走得早也走得便宜：payload 里没有这两个键的字符串时直接原样返回（不解析 JSON）。
 */
internal object RetiredStaticFields {

    private const val VALUES = "values"
    private const val KEY_EVENTS = "keyEvents"
    private const val PERSONALITY = "personality"
    private const val BACKGROUND = "background"

    /** 把 payload 里退休字段的内容并进幸存字段；无可合并内容时原样返回。 */
    fun foldPayload(payload: String): String {
        if (!payload.contains("\"$VALUES\"") && !payload.contains("\"$KEY_EVENTS\"")) return payload
        val root = runCatching { AppJson.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return payload

        var changed = false

        val foldedRoot = (root["staticProfile"] as? JsonObject)?.let { profile ->
            foldProfile(profile)?.also { changed = true }
        }

        val members = (root["members"] as? JsonArray)?.map { element ->
            val member = element as? JsonObject ?: return@map element
            val profile = member["staticProfile"] as? JsonObject ?: return@map element
            val folded = foldProfile(profile) ?: return@map element
            changed = true
            JsonObject(member + ("staticProfile" to folded))
        }

        if (!changed) return payload
        val next = JsonObject(
            root + buildMap {
                if (foldedRoot != null) put("staticProfile", foldedRoot)
                if (members != null) put("members", JsonArray(members))
            },
        )
        return next.toString()
    }

    /** 合并一个 `StaticProfile`；没有退休键时返回 null（表示无需改动）。 */
    private fun foldProfile(profile: JsonObject): JsonObject? {
        if (VALUES !in profile && KEY_EVENTS !in profile) return null
        val values = (profile[VALUES] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val keyEvents = (profile[KEY_EVENTS] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val stripped = JsonObject(profile - VALUES - KEY_EVENTS)
        if (values.isEmpty() && keyEvents.isEmpty()) return stripped

        val personality = (profile[PERSONALITY] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val background = (profile[BACKGROUND] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return JsonObject(
            stripped + mapOf(
                PERSONALITY to JsonPrimitive(mergeInto(personality, values)),
                BACKGROUND to JsonPrimitive(mergeInto(background, keyEvents)),
            ),
        )
    }

    /** 并入时避免重复：新值已包含旧值就保持原样，否则用「；」接在后面。 */
    private fun mergeInto(primary: String, retired: String): String {
        val kept = primary.trim()
        val folded = retired.trim()
        return when {
            folded.isEmpty() -> kept
            kept.isEmpty() -> folded
            kept.contains(folded) -> kept
            else -> "$kept；$folded"
        }
    }
}
