package com.deeptalking.domain.agent

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.FieldEditRegistry
import com.deeptalking.core.model.FieldSection
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.withStaticFieldTimestamps
import com.deeptalking.domain.agent.prompts.STATIC_PROFILE_FIELDS
import com.deeptalking.domain.agent.prompts.staticProfileValue
import com.deeptalking.domain.agent.prompts.withStaticField
import com.deeptalking.domain.agent.tools.MemoryToolSupport
import com.deeptalking.engine.ondevice.LlmBackend
import com.deeptalking.engine.ondevice.LlmRequest
import kotlin.math.abs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Single-field revision call (REPLY_PIPELINE_PLAN §12.1#7 / OUTBOUND_CALLS_V2 §三).
 *
 * The old `update_character_field` tool let the main call rewrite a static field. The main
 * call is now plain text, so a dedicated auxiliary call does the rewrite: the client detects
 * an explicit "change this setting" request, asks the model for just the new field content,
 * validates it with the same cleaner/length rules, and patches the character.
 *
 * Detection is deliberately strict — a silent rewrite is only acceptable when the user
 * literally asks for one. A static field is revised only when the same clause names a field
 * *and* carries an imperative rewrite command (`改成` / `写成` / `设置成` / `叫我` …). Ordinary
 * chat, comparisons and evaluations ("你说话更温柔了") contain no such command and never
 * trigger. Soft nudges (`应该更…`, `…一点`) are also not commands.
 */
internal object FieldRevision {

    /**
     * Field aliases. When one clause names two fields, the alias closest to the rewrite
     * command wins (`把说话语言改成英文` → `language`, not the broader `speakingStyle`);
     * equal distance keeps this declaration order.
     */
    private val ALIASES: Map<String, Regex> = mapOf(
        "gender" to Regex("性别|gender", RegexOption.IGNORE_CASE),
        "age" to Regex("年龄|岁数|age", RegexOption.IGNORE_CASE),
        "race" to Regex("种族|race|species", RegexOption.IGNORE_CASE),
        "appearance" to Regex("外貌|长相|头发|衣服|穿着|appearance", RegexOption.IGNORE_CASE),
        "personality" to Regex("性格|personality", RegexOption.IGNORE_CASE),
        "fears" to Regex("恐惧|弱点|害怕|fears", RegexOption.IGNORE_CASE),
        "background" to Regex("背景|经历|过往|身世|background", RegexOption.IGNORE_CASE),
        "speakingStyle" to Regex("说话风格|说话|语气|口吻|风格|口头禅|speaking.?style", RegexOption.IGNORE_CASE),
        "language" to Regex("语言|方言|language", RegexOption.IGNORE_CASE),
        "userAddress" to Regex("称呼|昵称|叫我|喊我|address", RegexOption.IGNORE_CASE),
    )

    /**
     * Explicit rewrite commands. Bare `改` / `更` / `应该` / `…一点` are intentionally absent:
     * they show up in ordinary chat and must not rewrite anything.
     */
    private val REWRITE = Regex(
        "改成|改为|改做|改称|换掉|换成|换为|设成|设为|设置成|设置为|设定成|设定为|定为|" +
            "写成|写为|写作|改写成|调成|调为|调整成|调整到|修改成|修改为|重写|改写|" +
            "更新成|更新为|变成|改一下|改一改|改改|修改一下|调整一下|改掉",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `以后叫我…` style address commands. The negative lookbehind keeps chat like
     * `你叫我一声` from being read as a rename.
     */
    private val ADDRESS_COMMAND =
        Regex("(?<![你我他她它谁])(?:叫我|喊我|称呼我|管我叫)")

    /** Questions about a field are not instructions to change it. */
    private val QUESTION = Regex("什么|啥|哪个|怎么样|如何")

    /**
     * `不要改…` / `别改…` scope; checked per clause so a later clause can still apply.
     * `别` carries a lookbehind because 性别 / 特别 / 区别 … are field names, not negations.
     */
    private val NEGATED = Regex(
        "(?:不要|不用|不必|不需要|不想|无需|勿|没让你|没有让你|不是让你|甭|(?<![性特区分告类级个])别)" +
            "[^，。,.!！?？；;\\n]{0,6}(?:改|修改|调整|换|动|写|设置|设定|叫|喊)",
    )

    /** Evaluative `变成这样/那样` is not a rewrite command. */
    private val EVALUATIVE = Regex("变(?:成|为)(?:这样|那样|这种|那样儿|如此)")

    /** Clause separators: a rewrite command only scopes to its own clause. */
    private val CLAUSES = Regex("[，。,.!！?？；;\\n、]+")

    /**
     * Returns the single static field the user explicitly asks to change, or null.
     *
     * One field per turn: when the message asks for two different fields the call is skipped
     * instead of silently running (and half-applying) two rewrites.
     */
    fun detect(userText: String): String? {
        val text = userText.trim()
        if (text.isEmpty()) return null
        return CLAUSES.split(text)
            .mapNotNull { clauseField(it.trim()) }
            .distinct()
            .singleOrNull()
    }

    /** The field this clause asks to rewrite, using the alias nearest the command. */
    private fun clauseField(clause: String): String? {
        if (clause.isEmpty()) return null
        if (NEGATED.containsMatchIn(clause) || QUESTION.containsMatchIn(clause)) return null
        if (EVALUATIVE.containsMatchIn(clause)) return null
        val command = REWRITE.find(clause) ?: ADDRESS_COMMAND.find(clause) ?: return null
        return ALIASES.entries
            .mapNotNull { (key, alias) ->
                val match = alias.find(clause) ?: return@mapNotNull null
                key to abs(match.range.last - command.range.first)
            }
            .minByOrNull { it.second }
            ?.first
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

    /**
     * Applies the revised value and stamps `staticFieldMeta`, so auto-fill never overwrites it.
     *
     * A field the user is editing right now in the card editor is left untouched: the
     * revision call ran against a snapshot and its result would discard the text being typed.
     */
    fun apply(character: Character, key: String, value: String, now: String): Character {
        if (FieldEditRegistry.isFocused(character.id, null, FieldSection.Static, key)) return character
        val profile = withStaticField(character.staticProfile, key, value)
        if (profile == character.staticProfile) return character
        return character.copy(
            staticProfile = profile,
            staticFieldMeta = withStaticFieldTimestamps(character.staticFieldMeta, setOf(key), now),
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
