package com.deeptalking.domain.agent

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.Role
import com.deeptalking.domain.agent.prompts.STATIC_PROFILE_FIELDS
import com.deeptalking.domain.agent.prompts.staticProfileValue
import com.deeptalking.domain.agent.prompts.withStaticField
import com.deeptalking.domain.agent.tools.MemoryToolSupport
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Single-field revision call (REPLY_PIPELINE_PLAN §12.1#7 / OUTBOUND_CALLS_V2 §三).
 *
 * The old `update_character_field` tool let the main call rewrite a static field. The main
 * call is now plain text, so a dedicated auxiliary call does the rewrite: the client detects
 * an explicit "change this setting" request, asks the model for just the new field content,
 * validates it with the same cleaner/length rules, and patches the character.
 */
internal object FieldRevision {

    private val ALIASES: Map<String, Regex> = mapOf(
        "gender" to Regex("性别|gender", RegexOption.IGNORE_CASE),
        "age" to Regex("年龄|岁数|多大|age", RegexOption.IGNORE_CASE),
        "race" to Regex("种族|race|species", RegexOption.IGNORE_CASE),
        "appearance" to Regex("外貌|长相|头发|衣服|穿着|appearance", RegexOption.IGNORE_CASE),
        "personality" to Regex("性格|personality", RegexOption.IGNORE_CASE),
        "fears" to Regex("恐惧|弱点|害怕|fears", RegexOption.IGNORE_CASE),
        "background" to Regex("背景|经历|过往|background", RegexOption.IGNORE_CASE),
        "speakingStyle" to Regex("说话|语气|口吻|风格|口头禅|speaking.?style", RegexOption.IGNORE_CASE),
        "language" to Regex("语言|方言|说中文|说英文|language", RegexOption.IGNORE_CASE),
        "userAddress" to Regex("叫我|喊我|称呼|别叫|不要叫|address", RegexOption.IGNORE_CASE),
    )

    private val INTENT = Regex(
        "改|修改|设置|设定|换|调整|应该|更|叫我|喊我|别叫|不要叫|称呼|以后用|请用|说话.{0,12}(点|些|一点)|用.{0,8}(说话|语气|口吻|风格)",
    )
    private val DENY = Regex("不要(?:修改|改变|改|换)|别(?:修改|改|换)|do not change|don't change", RegexOption.IGNORE_CASE)

    /** Returns the first static field the user explicitly asks to change, or null. */
    fun detect(userText: String): String? {
        val text = userText.trim()
        if (text.isEmpty() || DENY.containsMatchIn(text) || !INTENT.containsMatchIn(text)) return null
        return ALIASES.entries.firstOrNull { (_, alias) -> alias.containsMatchIn(text) }?.key
    }

    fun label(key: String): String = STATIC_PROFILE_FIELDS.firstOrNull { it.first == key }?.second ?: key

    private const val SYSTEM = "你是角色卡字段修订助手。只输出指定字段改写后的内容，不要解释、不要 JSON、不要引号包裹。"

    fun buildRequest(
        config: AppConfig,
        character: Character,
        key: String,
        instruction: String,
    ): LlmRequest {
        val current = staticProfileValue(character.staticProfile, key)
        val card = buildJsonObject {
            put("name", character.name)
            put("entityType", if (character.isGroup) "group" else "character")
            STATIC_PROFILE_FIELDS.forEach { (field, _) ->
                put(field, staticProfileValue(character.staticProfile, field))
            }
            put("currentSituation", character.dynamicState.currentSituation)
            put("currentLocation", character.dynamicState.currentLocation)
        }
        val limit = AppLimits.StaticProfile.limitFor(key)
        val user = buildString {
            appendLine("字段：${label(key)}（$key）")
            appendLine("当前内容：${current.ifBlank { "（未设置）" }}")
            appendLine("修改要求：$instruction")
            appendLine("角色卡：")
            appendLine(card.toString())
            appendLine()
            append("只输出该字段的新内容。")
            if (limit != null) append("（不超过 $limit 字；写成明确的简短陈述句，不要解释、不要对比句。）")
        }
        return LlmRequest(
            model = config.modelName,
            instructions = SYSTEM,
            input = listOf(com.deeptalking.core.model.ChatMessage(role = Role.User, content = user)),
            temperature = 0.3,
            maxOutputTokens = 512,
            stream = false,
            reasoningEffort = "none",
            apiPlatform = config.apiPlatform,
            sessionId = "deeptalking-field-${character.id}",
            auxiliary = true,
        )
    }

    /** Cleans the model output with the same rules as the old tool; null when unusable. */
    fun clean(key: String, raw: String): String? {
        val value = raw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("「", "」")
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (value.isBlank()) return null
        val cleaned = MemoryToolSupport.cleanFieldValue(key, value)
        return cleaned.takeIf { it.isNotBlank() }
    }

    /** Applies the revised value and stamps `staticFieldMeta`, so auto-fill never overwrites it. */
    fun apply(character: Character, key: String, value: String, now: String): Character {
        val profile = withStaticField(character.staticProfile, key, value)
        if (profile == character.staticProfile) return character
        return character.copy(
            staticProfile = profile,
            staticFieldMeta = character.staticFieldMeta +
                (key to buildJsonObject { put("updatedAt", now) }),
        )
    }

    /** Runs the revision call for one field; null keeps the old value. */
    suspend fun revise(
        llm: LlmBackend,
        config: AppConfig,
        character: Character,
        key: String,
        instruction: String,
    ): String? = runCatching {
        val result = llm.complete(buildRequest(config, character, key, instruction))
        clean(key, result.text)
    }.getOrNull()
}
