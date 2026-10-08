package com.deeptalking.feature.characters

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.StaticProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Parsing/validation helpers backing the character feature parity port:
 * one-sentence generation (`src/js/characters/generation.js`), member parsing,
 * avatar descriptions (`src/js/characters/avatar.js`) and member field fill
 * (`src/js/ui/character-editor.js`).
 *
 * The network call itself stays in the `:app` module; callers reconstruct the
 * richer payload from the returned raw JSON using the helpers here.
 */
object CharacterParity {

    const val CHARACTER_QUALITY_RULE =
        "人设质量要求：①不得使用陈词滥调的名字或模板化人设（如“艾尔德里亚”式的套路奇幻名、“高冷大小姐”“温柔邻家女孩”这类通用模板）；" +
            "②personality 写具体的行为倾向（遇到事情会怎么做、在意什么），不要堆砌形容词；" +
            "③background 只写 3-5 条会影响当下互动的要点，不要写编年史。"

    const val SPEAKING_STYLE_SAMPLES_RULE =
        "speakingStyle 必须写成“整体调性描述；示例：<台词1> / <台词2> / <台词3>”：三条示例台词必须是该角色真的会说的口语短句，" +
            "要体现口头禅、句尾助词、标点习惯与对用户的称呼，三条之间差异明显（能看出是同一个人、但场景不同）；" +
            "**禁止换行，三条之间只能用 \" / \" 分隔**，整个字段不超过 200 字。"

    private val DYNAMIC_FIELD_KEYS = setOf(
        "currentSituation", "currentLocation", "currentMood", "currentOccupation",
        "currentGoal", "currentRelationship", "currentImportantOthers",
    )

    private val GROUP_SHARED_DYNAMIC_FIELDS = setOf("currentSituation", "currentLocation")

    data class GeneratedDraft(
        val isGroup: Boolean,
        val name: String,
        val emoji: String,
        val personality: String,
        val background: String,
        val description: String,
        val scene: String,
        val interactionRules: String,
        val staticProfile: StaticProfile,
        val dynamicState: DynamicState,
        val lorebook: List<LorebookEntry>,
        val members: List<GroupMember>,
    )

    // ---------------------------------------------------------------- prompts

    /** Full one-sentence generation prompt (legacy `quickGenerateCharacter`). */
    fun buildQuickGeneratePrompt(desc: String, isGroup: Boolean): String =
        if (isGroup) GROUP_PROMPT_PREFIX + CHARACTER_QUALITY_RULE + SPEAKING_STYLE_SAMPLES_RULE + "\n描述: " + desc
        else CHARACTER_PROMPT_PREFIX + CHARACTER_QUALITY_RULE + SPEAKING_STYLE_SAMPLES_RULE + "\n描述: " + desc

    private val GROUP_PROMPT_PREFIX = buildString {
        append("根据以下描述生成一个角色群组。只返回JSON：")
        append("{\"entityType\":\"group\",\"groupInfo\":{\"name\":\"群组名\",\"avatar\":\"emoji\",\"description\":\"群组前提（这群人是谁、为什么在一起，1-2 句；不要写世界观/时代/地点/组织等世界层设定）\",\"scene\":\"场景\",\"interactionRules\":\"成员互动规则\"},")
        append("\"lorebook\":[{\"name\":\"条目名（地点/组织/专有名词/规则等）\",\"keywords\":[\"触发词\"],\"content\":\"命中后注入的世界层设定\",\"alwaysActive\":false}],")
        append("\"members\":[{\"name\":\"成员名\",\"avatar\":\"emoji\",\"gender\":\"性别\",\"age\":\"年龄\",\"race\":\"种族\",\"appearance\":\"外貌\",\"personality\":\"性格\",\"values\":\"价值观\",\"fears\":\"恐惧或弱点\",\"background\":\"背景\",\"keyEvents\":\"关键过往（里程碑）\",\"speakingStyle\":\"说话风格\",\"language\":\"语言\",\"userAddress\":\"该成员对用户的称呼（一个短称呼词）\",\"roleInGroup\":\"群内定位\",\"dynamicState\":{\"currentSituation\":\"当前处境\",\"currentLocation\":\"当前位置\",\"currentMood\":\"当前情绪\",\"currentOccupation\":\"当前职业/身份\",\"currentGoal\":\"当前目标\",\"currentRelationship\":\"当前关系\",\"currentImportantOthers\":\"当前重要他人\"}}]}。")
        append("群组的 description/scene 是全体成员共用的前提，必须填写；**世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）一律写进 lorebook（2-4 条），绝不能塞进 description 或成员的 background**；")
        append("lorebook 条目要能被日后复用，keywords 写剧情里可能出现的称呼（常驻内容把 alwaysActive 设为 true），content 只写该条目本身的信息。")
        append("至少生成两名成员；每名成员必须尽可能填满所有字段，不能只返回名称和性格；成员之间的说话方式必须显著不同（看台词就能分辨是谁）。")
    }

    private val CHARACTER_PROMPT_PREFIX = buildString {
        append("根据以下描述，生成一个角色设定。返回JSON格式，包含这些字段: entityType, name, avatar(emoji), gender, age, race, appearance, personality, values, fears, background(角色个人经历，不要写世界观), keyEvents, speakingStyle, language, userAddress(角色对用户的称呼，只填一个短称呼词), ")
        append("dynamicState(对象，包含 currentSituation、currentLocation、currentMood、currentOccupation、currentGoal、currentRelationship、currentImportantOthers), ")
        append("lorebook(数组，2-4 条世界层设定条目，每条 {\"name\":\"条目名\",\"keywords\":[\"触发词\"],\"content\":\"命中后注入的世界层设定\",\"alwaysActive\":false})。")
        append("**世界层设定（时代/世界观、地点、组织、专有名词、历史、规则）一律写进 lorebook，不要写进 background。**")
    }

    // ---------------------------------------------------------------- parsing

    /** Parses the full generated character/group payload, or null when malformed. */
    fun parseGeneratedDraft(raw: String): GeneratedDraft? {
        val json = extractJsonObject(raw) ?: return null
        val groupInfo = json["groupInfo"] as? JsonObject
        val membersArray = (json["members"] as? JsonArray) ?: (json["additionalMembers"] as? JsonArray)
        val isGroup = groupInfo != null || membersArray != null || json.str("entityType").equals("group", ignoreCase = true)

        val profile = parseStaticProfile(json)
        val dynamic = parseDynamicState(json["dynamicState"] as? JsonObject)
        val lorebook = dedupeGeneratedLorebook(parseGeneratedLorebook(json["lorebook"]))
        val members = (membersArray ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonObject)?.let(::parseMember) }
            .filter { it.name.isNotBlank() }

        return if (isGroup) {
            GeneratedDraft(
                isGroup = true,
                name = groupInfo?.str("name").orEmpty().ifBlank { json.str("name") },
                emoji = groupInfo?.str("avatar").orEmpty().ifBlank { "👥" },
                personality = groupInfo?.str("description").orEmpty().ifBlank { json.str("description") },
                background = groupInfo?.str("scene").orEmpty(),
                description = groupInfo?.str("description").orEmpty().ifBlank { json.str("description") },
                scene = groupInfo?.str("scene").orEmpty(),
                interactionRules = groupInfo?.str("interactionRules").orEmpty().ifBlank { json.str("interactionRules") },
                staticProfile = profile,
                dynamicState = dynamic,
                lorebook = lorebook,
                members = members,
            )
        } else {
            GeneratedDraft(
                isGroup = false,
                name = json.str("name"),
                emoji = json.str("avatar").ifBlank { "👤" },
                personality = profile.personality,
                background = profile.background,
                description = "",
                scene = "",
                interactionRules = "",
                staticProfile = profile,
                dynamicState = dynamic,
                lorebook = lorebook,
                members = emptyList(),
            )
        }
    }

    /** Full member parse from a group generation/upgrade payload. */
    fun parseGroupMembersJson(raw: String): List<GroupMember> {
        val json = extractJsonObject(raw) ?: return emptyList()
        val array = (json["members"] as? JsonArray) ?: (json["additionalMembers"] as? JsonArray) ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::parseMember) }.filter { it.name.isNotBlank() }
    }

    /** Legacy `parseGroupMembers` line format: `名称｜性格简述` per line. */
    fun parseGroupMembersText(text: String): List<GroupMember> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('｜', '|').map { it.trim() }
                val name = parts.getOrNull(0).orEmpty()
                if (name.isBlank()) null
                else GroupMember(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    emoji = "👤",
                    staticProfile = StaticProfile(personality = parts.getOrNull(1).orEmpty()),
                )
            }

    /** Legacy `buildAvatarDescription` for a plain character. */
    @Suppress("UNUSED_PARAMETER")
    fun buildAvatarDescription(target: Character, parent: Character? = null): String =
        avatarDescription(target.name, target.staticProfile, null)

    /** Legacy `buildAvatarDescription` for a group member (includes `roleInGroup`). */
    @Suppress("UNUSED_PARAMETER")
    fun buildAvatarDescription(member: GroupMember, parent: Character? = null): String =
        avatarDescription(member.name, member.staticProfile, member.roleInGroup)

    private fun avatarDescription(name: String, profile: StaticProfile, roleInGroup: String?): String {
        val parts = mutableListOf(name, profile.appearance, profile.personality)
        if (!roleInGroup.isNullOrBlank()) parts += roleInGroup
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("，").ifBlank { "一个神秘角色" }
    }

    // ------------------------------------------------------- member fill apply

    /** Legacy `fillGroupMemberFields` payload application: only fills empty fields. */
    fun applyMemberFillPayload(
        member: GroupMember,
        raw: String,
        base: ZonedDateTime,
        zone: ZoneId = base.zone,
    ): GroupMember {
        val obj = extractJsonObject(raw) ?: return member

        fun staticFill(current: String, key: String): String {
            if (current.isNotBlank()) return current
            val value = obj.str(key)
            if (value.isBlank()) return current
            return FieldCleaning.normalizeStaticFieldValue(key, value, base, zone)
        }

        val profile = member.staticProfile
        val filledProfile = profile.copy(
            gender = staticFill(profile.gender, "gender"),
            age = staticFill(profile.age, "age"),
            race = staticFill(profile.race, "race"),
            appearance = staticFill(profile.appearance, "appearance"),
            personality = staticFill(profile.personality, "personality"),
            values = staticFill(profile.values, "values"),
            fears = staticFill(profile.fears, "fears"),
            background = staticFill(profile.background, "background"),
            keyEvents = staticFill(profile.keyEvents, "keyEvents"),
            speakingStyle = staticFill(profile.speakingStyle, "speakingStyle"),
            language = staticFill(profile.language, "language"),
            userAddress = staticFill(profile.userAddress, "userAddress"),
        )

        val dynamicObj = obj["dynamicState"] as? JsonObject
        val dynamic = member.dynamicState
        val filledDynamic = if (dynamicObj == null) dynamic else dynamic.copy(
            currentSituation = dynamicFill(dynamic.currentSituation, dynamicObj, "currentSituation", base, zone),
            currentLocation = dynamicFill(dynamic.currentLocation, dynamicObj, "currentLocation", base, zone),
            currentMood = dynamicFill(dynamic.currentMood, dynamicObj, "currentMood", base, zone),
            currentOccupation = dynamicFill(dynamic.currentOccupation, dynamicObj, "currentOccupation", base, zone),
            currentGoal = dynamicFill(dynamic.currentGoal, dynamicObj, "currentGoal", base, zone),
            currentRelationship = dynamicFill(dynamic.currentRelationship, dynamicObj, "currentRelationship", base, zone),
            currentImportantOthers = dynamicFill(dynamic.currentImportantOthers, dynamicObj, "currentImportantOthers", base, zone),
        )

        val roleInGroup = member.roleInGroup.ifBlank { FieldCleaning.cleanFieldValue("roleInGroup", obj.str("roleInGroup")) }
        val emoji = member.emoji.ifBlank { obj.str("avatar").ifBlank { obj.str("emoji") } }

        return member.copy(
            emoji = emoji,
            roleInGroup = roleInGroup,
            staticProfile = filledProfile,
            dynamicState = filledDynamic,
        )
    }

    private fun dynamicFill(
        current: String,
        obj: JsonObject,
        key: String,
        base: ZonedDateTime,
        zone: ZoneId,
    ): String {
        if (current.isNotBlank()) return current
        val value = obj.str(key)
        if (value.isBlank()) return current
        return FieldCleaning.sanitizeDynamicStateField(key, value, base, zone)
    }

    // ------------------------------------------------------- editor save apply

    /** Legacy `collectEditDraftFromDom`: clean + normalize the edited target on save. */
    fun normalizeEditedDraft(
        character: Character,
        memberIndex: Int? = null,
        base: ZonedDateTime = ZonedDateTime.now(),
        zone: ZoneId = base.zone,
    ): Character {
        if (memberIndex != null && memberIndex in character.members.indices) {
            val source = character.members[memberIndex]
            // Manually edited dynamic fields drop their AI bookkeeping so the
            // model re-evaluates them (legacy `delete dynamicStateMeta[key]`).
            val updated = normalizeMember(source, base, zone).copy(
                dynamicStateMeta = source.dynamicStateMeta - DYNAMIC_FIELD_KEYS,
            )
            return character.copy(members = character.members.toMutableList().also { it[memberIndex] = updated })
        }
        if (character.isGroup) {
            return character.copy(
                groupSharedDynamic = normalizeDynamicState(character.groupSharedDynamic, base, zone),
                dynamicStateMeta = character.dynamicStateMeta - GROUP_SHARED_DYNAMIC_FIELDS,
            )
        }
        return character.copy(
            staticProfile = normalizeStaticProfile(character.staticProfile, base, zone),
            dynamicState = normalizeDynamicState(character.dynamicState, base, zone),
            dynamicStateMeta = character.dynamicStateMeta - DYNAMIC_FIELD_KEYS,
        )
    }

    /** Cleans + time-normalizes a member's static profile, dynamic state and `roleInGroup`. */
    fun normalizeMember(member: GroupMember, base: ZonedDateTime = ZonedDateTime.now(), zone: ZoneId = base.zone): GroupMember =
        member.copy(
            roleInGroup = FieldCleaning.cleanFieldValue("roleInGroup", member.roleInGroup),
            staticProfile = normalizeStaticProfile(member.staticProfile, base, zone),
            dynamicState = normalizeDynamicState(member.dynamicState, base, zone),
        )

    private fun normalizeStaticProfile(profile: StaticProfile, base: ZonedDateTime, zone: ZoneId): StaticProfile = profile.copy(
        gender = FieldCleaning.normalizeStaticFieldValue("gender", profile.gender, base, zone),
        age = FieldCleaning.normalizeStaticFieldValue("age", profile.age, base, zone),
        race = FieldCleaning.normalizeStaticFieldValue("race", profile.race, base, zone),
        appearance = FieldCleaning.normalizeStaticFieldValue("appearance", profile.appearance, base, zone),
        personality = FieldCleaning.normalizeStaticFieldValue("personality", profile.personality, base, zone),
        values = FieldCleaning.normalizeStaticFieldValue("values", profile.values, base, zone),
        fears = FieldCleaning.normalizeStaticFieldValue("fears", profile.fears, base, zone),
        background = FieldCleaning.normalizeStaticFieldValue("background", profile.background, base, zone),
        keyEvents = FieldCleaning.normalizeStaticFieldValue("keyEvents", profile.keyEvents, base, zone),
        speakingStyle = FieldCleaning.normalizeStaticFieldValue("speakingStyle", profile.speakingStyle, base, zone),
        language = FieldCleaning.normalizeStaticFieldValue("language", profile.language, base, zone),
        userAddress = FieldCleaning.normalizeStaticFieldValue("userAddress", profile.userAddress, base, zone),
    )

    private fun normalizeDynamicState(state: DynamicState, base: ZonedDateTime, zone: ZoneId): DynamicState = state.copy(
        currentSituation = FieldCleaning.sanitizeDynamicStateField("currentSituation", state.currentSituation, base, zone),
        currentLocation = FieldCleaning.sanitizeDynamicStateField("currentLocation", state.currentLocation, base, zone),
        currentMood = FieldCleaning.sanitizeDynamicStateField("currentMood", state.currentMood, base, zone),
        currentOccupation = FieldCleaning.sanitizeDynamicStateField("currentOccupation", state.currentOccupation, base, zone),
        currentGoal = FieldCleaning.sanitizeDynamicStateField("currentGoal", state.currentGoal, base, zone),
        currentRelationship = FieldCleaning.sanitizeDynamicStateField("currentRelationship", state.currentRelationship, base, zone),
        currentImportantOthers = FieldCleaning.sanitizeDynamicStateField("currentImportantOthers", state.currentImportantOthers, base, zone),
    )

    // ------------------------------------------------------------ lorebook

    /** Legacy `addLorebookEntry` default: a fresh user entry is always active. */
    fun newUserLorebookEntry(): LorebookEntry = LorebookEntry(
        id = UUID.randomUUID().toString(),
        enabled = true,
        alwaysActive = true,
        origin = LorebookOrigin.User,
    )

    fun splitLorebookKeywords(raw: String): List<String> =
        raw.split(',', '，', '、', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(AppLimits.Lorebook.KEYWORDS_PER_ENTRY)

    /** Legacy `updateLorebookEntry` keywords rule: emptying keywords flips `alwaysActive` on. */
    fun keywordsToAlwaysActive(current: Boolean, keywords: List<String>): Boolean =
        if (keywords.isEmpty()) true else current

    fun capLorebookName(value: String): String = value.take(AppLimits.Lorebook.NAME_CHARS)

    fun capLorebookContent(value: String): String = value.take(AppLimits.Lorebook.CONTENT_CHARS)

    /**
     * Local name-based near-duplicate compaction for AI-generated entries
     * (legacy `dedupeLorebook`): a later model entry whose normalized name
     * matches an earlier model entry is merged into it. User entries are
     * never touched.
     */
    fun dedupeGeneratedLorebook(entries: List<LorebookEntry>): List<LorebookEntry> {
        val kept = mutableListOf<LorebookEntry>()
        for (entry in entries) {
            if (entry.origin != LorebookOrigin.Model) {
                kept += entry
                continue
            }
            val key = normalizeLorebookName(entry.name)
            val target = if (key.isEmpty()) {
                -1
            } else {
                kept.indexOfFirst { it.origin == LorebookOrigin.Model && normalizeLorebookName(it.name) == key }
            }
            if (target < 0) kept += entry else kept[target] = mergeGeneratedLorebook(kept[target], entry)
        }
        return kept
    }

    private fun mergeGeneratedLorebook(existing: LorebookEntry, incoming: LorebookEntry): LorebookEntry = existing.copy(
        content = when {
            existing.content.isBlank() -> incoming.content
            incoming.content.isBlank() || existing.content.contains(incoming.content) -> existing.content
            else -> existing.content + "\n" + incoming.content
        },
        keywords = (existing.keywords + incoming.keywords).distinct().take(AppLimits.Lorebook.KEYWORDS_PER_ENTRY),
        alwaysActive = existing.alwaysActive || incoming.alwaysActive,
        enabled = true,
    )

    private fun normalizeLorebookName(value: String): String = value.trim().lowercase()
        .replace(Regex("[\\s\u3000]"), "")
        .replace(Regex("[「」『』“”‘’\"'《》〈〉（）()\\[\\]【】{}<>]"), "")
        .replace(Regex("[，。！？、；：,.!?;:·—_\\-]"), "")

    fun parseGeneratedLorebook(element: JsonElement?): List<LorebookEntry> {
        val array = element as? JsonArray ?: return emptyList()
        val entries = array.mapNotNull { it as? JsonObject }.take(AppLimits.Lorebook.ENTRIES).map { obj ->
            LorebookEntry(
                id = UUID.randomUUID().toString(),
                name = trimEllipsis(obj.str("name"), AppLimits.Lorebook.NAME_CHARS),
                content = trimEllipsis(obj.str("content"), AppLimits.Lorebook.CONTENT_CHARS),
                keywords = keywordsOf(obj["keywords"]),
                enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull != false,
                alwaysActive = (obj["alwaysActive"] as? JsonPrimitive)?.booleanOrNull == true,
                origin = LorebookOrigin.Model,
            )
        }
        var activeCount = entries.count { it.alwaysActive }
        return entries.map { entry ->
            if (!entry.alwaysActive && entry.keywords.isEmpty() && activeCount < AppLimits.Lorebook.MAX_ALWAYS_ACTIVE) {
                activeCount++
                entry.copy(alwaysActive = true)
            } else {
                entry
            }
        }
    }

    // ------------------------------------------------------------- internals

    private fun parseStaticProfile(obj: JsonObject): StaticProfile = StaticProfile(
        gender = obj.str("gender"),
        age = obj.str("age"),
        race = obj.str("race"),
        appearance = obj.str("appearance"),
        personality = obj.str("personality"),
        values = obj.str("values"),
        fears = obj.str("fears"),
        background = obj.str("background"),
        keyEvents = obj.str("keyEvents"),
        speakingStyle = obj.str("speakingStyle"),
        language = obj.str("language"),
        userAddress = obj.str("userAddress"),
    )

    private fun parseDynamicState(obj: JsonObject?): DynamicState {
        if (obj == null) return DynamicState()
        return DynamicState(
            currentSituation = obj.str("currentSituation"),
            currentLocation = obj.str("currentLocation"),
            currentMood = obj.str("currentMood"),
            currentOccupation = obj.str("currentOccupation"),
            currentGoal = obj.str("currentGoal"),
            currentRelationship = obj.str("currentRelationship"),
            currentImportantOthers = obj.str("currentImportantOthers"),
        )
    }

    private fun parseMember(obj: JsonObject): GroupMember = GroupMember(
        id = UUID.randomUUID().toString(),
        name = obj.str("name"),
        emoji = obj.str("avatar").ifBlank { obj.str("emoji") }.ifBlank { "👤" },
        roleInGroup = obj.str("roleInGroup"),
        staticProfile = parseStaticProfile(obj),
        dynamicState = parseDynamicState(obj["dynamicState"] as? JsonObject),
    )

    private fun keywordsOf(element: JsonElement?): List<String> {
        if (element == null || element is JsonNull) return emptyList()
        val raw: List<String> = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> element.contentOrNull.orEmpty().split(Regex("[,，、\\n]"))
            else -> emptyList()
        }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }.take(AppLimits.Lorebook.KEYWORDS_PER_ENTRY)
    }

    private fun trimEllipsis(value: String, maxLength: Int): String {
        val text = value.trim()
        return if (text.length > maxLength) text.take(maxLength) + "…" else text
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

    private fun extractJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        }.getOrNull()
    }
}
