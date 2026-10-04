package com.deeptalking.core.data.legacy

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.model.Sticker
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns a legacy base64/data-URI sticker into a stable reference (normally a
 * local file path). Tests inject an in-memory fake; the app uses
 * [FileStickerSink].
 */
fun interface StickerSink {
    fun refFor(dataUri: String): String
}

private fun pick(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() } ?: ""

private fun appendUnique(base: String, extra: String): String {
    val trimmedExtra = extra.trim()
    if (trimmedExtra.isEmpty()) return base
    val trimmedBase = base.trim()
    if (trimmedBase.isEmpty()) return trimmedExtra
    if (trimmedBase.contains(trimmedExtra)) return trimmedBase
    return "$trimmedBase $trimmedExtra"
}

private fun normalizeUserAddress(value: String?): String =
    (value ?: "").replace(Regex("\\s+"), "").take(20)

private fun extractKeywords(element: JsonElement?): List<String> = when (element) {
    null -> emptyList()
    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.filter { it.isNotEmpty() }
    is JsonPrimitive -> element.content.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}.take(20)

/** `message.role === 'user' ? 'user' : 'assistant'` in the legacy schema. */
fun mapRole(raw: String?): Role = when (raw?.trim()?.lowercase()) {
    "user" -> Role.User
    "system" -> Role.System
    "tool" -> Role.Tool
    "assistant" -> Role.Assistant
    else -> Role.Assistant
}

private fun categoryOf(raw: String?): MemoryCategory = when (raw?.trim()) {
    "userProfile" -> MemoryCategory.UserProfile
    "relationship" -> MemoryCategory.Relationship
    "promises" -> MemoryCategory.Promises
    "habits" -> MemoryCategory.Habits
    "events", null, "" -> MemoryCategory.Events
    else -> MemoryCategory.Events
}

private fun subjectOf(raw: String?): MemorySubject = when (raw?.trim()) {
    "user" -> MemorySubject.User
    "relationship" -> MemorySubject.Relationship
    "world" -> MemorySubject.World
    "character" -> MemorySubject.Character
    "legacy", null, "" -> MemorySubject.Legacy
    else -> MemorySubject.Legacy
}

private fun statusOf(raw: String?): PromiseStatus = when (raw?.trim()) {
    "resolved" -> PromiseStatus.Resolved
    "cancelled" -> PromiseStatus.Cancelled
    "active", null, "" -> PromiseStatus.Active
    else -> PromiseStatus.Active
}

/** Maps a legacy long-term memory entry. Category defaults to [MemoryCategory.Events]. */
fun mapLongTerm(dto: LegacyLongTerm): LongTermMemory =
    mapLongTerm(dto, MemoryCategory.Events)

/** Maps a legacy long-term memory entry, taking its category from the enclosing map key. */
fun mapLongTerm(dto: LegacyLongTerm, category: MemoryCategory): LongTermMemory = LongTermMemory(
    id = dto.id ?: "",
    category = category,
    subject = subjectOf(dto.subject),
    key = dto.key ?: "",
    value = dto.value ?: "",
    tags = dto.tags ?: emptyList(),
    importance = (dto.importance ?: 0.0).coerceIn(0.0, 10.0).toInt(),
    sourceMessageIds = dto.sourceMessageIds ?: emptyList(),
    evidence = dto.evidence ?: "",
    eventTime = dto.eventTime ?: dto.createdAt,
    dueAt = dto.dueAt,
    promisor = dto.promisor,
    promisee = dto.promisee,
    status = statusOf(dto.status),
    createdAt = dto.createdAt,
    updatedAt = dto.updatedAt ?: dto.createdAt,
    lastRecalled = dto.lastRecalled,
    recallCount = dto.recallCount ?: 0,
)

fun mapLorebook(dto: LegacyLorebookEntry): LorebookEntry = LorebookEntry(
    id = dto.id ?: "",
    name = dto.name ?: "",
    content = dto.content ?: "",
    keywords = extractKeywords(dto.keywords),
    enabled = dto.enabled != false,
    alwaysActive = dto.alwaysActive == true,
    // `normalizeLorebook` treats anything that is not exactly "ai" as a user entry.
    origin = if (dto.origin == "ai") LorebookOrigin.Model else LorebookOrigin.User,
    createdAt = null,
    updatedAt = dto.lastMentionedAt,
    misses = (dto.misses ?: 0.0).toInt().coerceAtLeast(0),
)

private fun mapShortTerm(dto: LegacyShortTerm): ShortTermMemory = ShortTermMemory(
    id = dto.id ?: "",
    content = dto.content ?: "",
    sourceMessageIds = dto.sourceMessageIds ?: emptyList(),
    timeRef = dto.timeRef?.anchor,
    eventTime = dto.eventTime,
    createdAt = dto.timestamp,
)

private fun mapInstant(dto: LegacyInstantMessage): ChatMessage = ChatMessage(
    id = dto.id ?: "",
    role = mapRole(dto.role),
    content = dto.content ?: "",
    timestamp = dto.timestamp,
    attachments = (dto.images ?: emptyList())
        .filter { it.isNotBlank() }
        .take(4)
        .map { uri -> MessageAttachment(kind = MessageAttachment.Kind.Image, uri = uri) },
    internalOnly = dto.internalOnly == true,
    staticChanges = dto.staticChanges ?: emptyList(),
    lorebookChanges = dto.lorebookChanges ?: emptyList(),
)

/** Maps a legacy sticker. Returns null when there is no image payload at all. */
fun mapSticker(dto: LegacySticker, stickerSink: StickerSink?): Sticker? {
    val dataUri = dto.dataUrl
    if (dataUri != null && dataUri.startsWith("data:image/", ignoreCase = true)) {
        return Sticker(
            id = dto.id ?: "",
            tag = dto.tag ?: "",
            // Without a sink the raw data URI is kept so no data is lost.
            fileRef = stickerSink?.refFor(dataUri) ?: dataUri,
            createdAt = dto.createdAt,
        )
    }
    val existingRef = dto.fileRef?.takeIf { it.isNotBlank() } ?: return null
    return Sticker(
        id = dto.id ?: "",
        tag = dto.tag ?: "",
        fileRef = existingRef,
        createdAt = dto.createdAt,
    )
}

private fun mapStaticProfile(
    basic: LegacyBasicInfo?,
    fields: LegacyFieldBag?,
    outerFields: LegacyFieldBag?,
): StaticProfile {
    fun read(get: (LegacyFieldBag) -> String?, key: String?): String =
        pick(key, fields?.let(get), outerFields?.let(get))

    val background = pick(
        basic?.background,
        fields?.background,
        outerFields?.background,
    )
    val worldView = pick(
        basic?.worldView,
        fields?.worldView,
        outerFields?.worldView,
    )
    return StaticProfile(
        gender = read({ it.gender }, basic?.gender),
        age = read({ it.age }, basic?.age),
        race = read({ it.race }, basic?.race),
        appearance = read({ it.appearance }, basic?.appearance),
        personality = read({ it.personality }, basic?.personality),
        values = read({ it.values }, basic?.values),
        fears = read({ it.fears }, basic?.fears),
        background = if (background.isBlank()) worldView else background,
        keyEvents = read({ it.keyEvents }, basic?.keyEvents),
        speakingStyle = read({ it.speakingStyle }, basic?.speakingStyle),
        language = read({ it.language }, basic?.language),
        userAddress = normalizeUserAddress(read({ it.userAddress }, basic?.userAddress)),
    )
}

private fun mapDynamicState(dto: LegacyCharacter): DynamicState {
    val dyn = dto.dynamicState
    val basic = dto.basicInfo
    val bags = listOfNotNull(dyn?.fields, dto.fields, basic?.fields)

    fun firstBag(getter: (LegacyFieldBag) -> String?): String? =
        bags.asSequence().mapNotNull { getter(it) }.firstOrNull { it.isNotBlank() }

    fun read(direct: String?, getter: (LegacyFieldBag) -> String?, fallback: String? = null): String =
        pick(direct, firstBag(getter), fallback)

    val situation = appendUnique(
        read(dyn?.currentSituation, { it.currentSituation }, basic?.currentSituation),
        pick(dyn?.recentDevelopment, firstBag { it.recentDevelopment }),
    )
    val goal = appendUnique(
        read(dyn?.currentGoal, { it.currentGoal }, basic?.goals),
        pick(dyn?.currentFocus, firstBag { it.currentFocus }),
    )
    val mood = appendUnique(
        read(dyn?.currentMood, { it.currentMood }),
        pick(dyn?.currentEmotionalTendency, firstBag { it.currentEmotionalTendency }),
    )
    var location = read(dyn?.currentLocation, { it.currentLocation })
    if (location.isBlank()) {
        location = listOf(basic?.scene, basic?.sceneNotes)
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    return DynamicState(
        currentSituation = situation,
        currentLocation = location,
        currentMood = mood,
        currentOccupation = read(dyn?.currentOccupation, { it.currentOccupation }, basic?.occupation),
        currentGoal = goal,
        currentRelationship = read(dyn?.currentRelationship, { it.currentRelationship }, basic?.relationshipWithUser),
        currentImportantOthers = read(dyn?.currentImportantOthers, { it.currentImportantOthers }, basic?.importantOthers),
    )
}

private fun mapMembers(dto: LegacyCharacter): List<GroupMember> =
    (dto.members ?: emptyList()).map { member ->
        val memory = member.memory
        GroupMember(
            id = member.id ?: "",
            name = member.basicInfo?.name ?: "",
            emoji = member.basicInfo?.avatar ?: "👤",
            roleInGroup = member.roleInGroup ?: "",
            staticProfile = mapStaticProfile(member.basicInfo, member.basicInfo?.fields, null),
            dynamicState = mapDynamicState(
                LegacyCharacter(
                    dynamicState = member.dynamicState,
                    basicInfo = member.basicInfo,
                )
            ),
            shortTerm = (memory?.shortTerm ?: emptyList()).map { mapShortTerm(it) },
            longTerm = memory?.longTerm?.flatMap { (category, items) ->
                items.map { mapLongTerm(it, categoryOf(category)) }
            } ?: emptyList(),
            lorebook = (member.lorebook ?: emptyList()).map { mapLorebook(it) },
        )
    }

/**
 * Maps one legacy character (single or group) into the native [Character].
 *
 * Not mapped (no field in the native model): `basicInfo.avatarPixel`,
 * `groupInfo.interactionRules`, group member `roleInGroup`, and the whole
 * `memory.scenes` / `sceneState` / `counters` / `pendingRecall` /
 * `lastInjectedRecallIds` block.
 */
fun mapCharacter(dto: LegacyCharacter, stickerSink: StickerSink?): Character {
    val isGroup = dto.entityType == "group"
    val basic = dto.basicInfo
    val groupInfo = dto.groupInfo
    val memory = dto.memory

    val staticProfile = if (isGroup) StaticProfile() else {
        mapStaticProfile(basic, basic?.fields, dto.fields)
    }
    val dynamicState = mapDynamicState(dto)

    val groupBaseDescription = pick(groupInfo?.description, basic?.personality)
    val worldView = pick(groupInfo?.worldView, basic?.worldView, dto.fields?.worldView)
    val description = if (isGroup) {
        if (worldView.isNotBlank() && !groupBaseDescription.contains(worldView)) {
            appendUnique(groupBaseDescription, worldView)
        } else {
            groupBaseDescription
        }
    } else {
        pick(basic?.personality, dto.fields?.personality)
    }

    val groupScene = groupInfo?.scene
    val effectiveDynamic = if (isGroup && !groupScene.isNullOrBlank()) {
        dynamicState.copy(currentLocation = groupScene)
    } else {
        dynamicState
    }
    val sharedDynamic = DynamicState(
        currentSituation = effectiveDynamic.currentSituation,
        currentLocation = effectiveDynamic.currentLocation,
    )

    return Character(
        id = dto.id ?: "",
        name = if (isGroup) pick(groupInfo?.name, basic?.name) else (basic?.name ?: ""),
        emoji = if (isGroup) pick(groupInfo?.avatar, basic?.avatar, "👥") else pick(basic?.avatar, "👤"),
        description = description,
        isGroup = isGroup,
        interactionRules = groupInfo?.interactionRules ?: "",
        staticProfile = staticProfile,
        dynamicState = effectiveDynamic,
        shortTerm = (memory?.shortTerm ?: emptyList()).map { mapShortTerm(it) },
        longTerm = memory?.longTerm?.flatMap { (category, items) ->
            items.map { mapLongTerm(it, categoryOf(category)) }
        } ?: emptyList(),
        lorebook = (dto.lorebook ?: emptyList()).map { mapLorebook(it) },
        stickers = (dto.stickers ?: emptyList()).mapNotNull { mapSticker(it, stickerSink) },
        instant = (memory?.instant ?: emptyList())
            .filter { it.isLoading != true }
            .map { mapInstant(it) },
        groupSharedDynamic = if (isGroup) sharedDynamic else DynamicState(),
        members = if (isGroup) mapMembers(dto) else emptyList(),
        fieldsMigrationVersion = dto.fieldsMigrationVersion ?: "",
    )
}

/** Maps the legacy config; missing or malformed values fall back to legacy defaults. */
fun mapConfig(dto: LegacyConfig?): AppConfig {
    if (dto == null) return AppConfig()
    val efforts = setOf("none", "low", "medium", "high", "max")
    return AppConfig(
        apiPlatform = dto.apiPlatform?.takeIf { it.isNotBlank() } ?: "deepseek",
        apiBaseUrl = (dto.apiBaseUrl ?: dto.apiEndpoint)?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() } ?: "https://api.deepseek.com/v1",
        modelName = dto.modelName?.takeIf { it.isNotBlank() } ?: "deepseek-flash",
        temperature = (dto.temperature ?: 0.8).coerceIn(0.0, 2.0),
        stream = dto.stream ?: true,
        reasoningEffort = dto.reasoningEffort?.takeIf { it in efforts } ?: "medium",
        proactiveEnabled = dto.proactiveEnabled ?: true,
        styleCritique = dto.styleCritique ?: true,
        quickReplyRepair = dto.quickReplyRepair ?: true,
    )
}
