package com.deeptalking.core.data.legacy

import com.deeptalking.core.model.AppConfig
import com.deeptalking.core.model.CacheStats
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.DynamicStateMeta
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemoryCounters
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PlatformSlot
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.RequestMetric
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.SceneState
import com.deeptalking.core.model.SceneSummary
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.StaticFillMeta
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.model.Sticker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Turns a legacy base64/data-URI media payload into a stable local reference
 * (normally a relative file key like `images/<sha>.jpg`). Tests inject an
 * in-memory fake; the app uses [FileStickerSink].
 */
fun interface StickerSink {
    fun refFor(dataUri: String): String

    /** Stores a message-image data URI; defaults to [refFor]. */
    fun imageRefFor(dataUri: String): String = refFor(dataUri)
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

/** Legacy `TIME_PARSE_VERSION` (src/js/memory/time.js). */
const val TIME_PARSE_VERSION: Int = 1

/** Legacy `STICKER_LIMITS.maxTagChars` (src/js/core/config.js). */
const val MAX_STICKER_TAG_CHARS: Int = 6

private const val DAY_START_HOUR: Int = 2
private const val MAX_PARTICIPANTS: Int = 8

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

/** Maps a legacy long-term memory entry. Category comes from the item or defaults to [MemoryCategory.Events]. */
fun mapLongTerm(dto: LegacyLongTerm): LongTermMemory =
    mapLongTerm(dto, if (dto.category != null) categoryOf(dto.category) else MemoryCategory.Events)

/** Maps a legacy long-term memory entry, taking its category from the enclosing map key. */
fun mapLongTerm(dto: LegacyLongTerm, category: MemoryCategory): LongTermMemory = LongTermMemory(
    id = dto.id ?: "",
    category = category,
    subject = subjectOf(dto.subject),
    key = dto.key ?: "",
    value = dto.value ?: "",
    tags = (dto.tags ?: emptyList()).take(8),
    importance = (dto.importance ?: 0.0).coerceIn(0.0, 10.0).toInt(),
    sourceMessageIds = (dto.sourceMessageIds ?: emptyList()).take(8),
    evidence = (dto.evidence ?: "").take(300),
    eventTime = dto.eventTime ?: dto.createdAt,
    dueAt = dto.dueAt,
    promisor = dto.promisor,
    promisee = dto.promisee,
    status = statusOf(dto.status),
    createdAt = dto.createdAt,
    updatedAt = dto.updatedAt ?: dto.createdAt,
    lastRecalled = dto.lastRecalled,
    recallCount = dto.recallCount ?: 0,
    participants = (dto.participants ?: emptyList()).take(MAX_PARTICIPANTS),
    location = dto.location ?: "",
    learnedBonus = (dto.learnedBonus ?: 0.0).toInt().coerceIn(-3, 3),
    usageCount = (dto.usageCount ?: 0).coerceAtLeast(0),
    lastUsageAt = dto.lastUsageAt,
    arcOf = dto.arcOf,
    arcStage = dto.arcStage,
    recordedAt = dto.recordedAt,
    conflicts = dto.conflicts,
    conflictedAt = dto.conflictedAt,
    relatedTo = dto.relatedTo ?: emptyList(),
    sourceRoles = dto.sourceRoles ?: emptyList(),
    userEvidence = dto.userEvidence.take(160),
    corrections = dto.corrections,
)

fun mapLorebook(dto: LegacyLorebookEntry): LorebookEntry = LorebookEntry(
    id = dto.id ?: "",
    name = (dto.name ?: "").take(60),
    content = (dto.content ?: "").take(2_000),
    keywords = extractKeywords(dto.keywords),
    enabled = dto.enabled != false,
    alwaysActive = dto.alwaysActive == true,
    // `normalizeLorebook` treats anything that is not exactly "ai" as a user entry.
    origin = if (dto.origin == "ai") LorebookOrigin.Model else LorebookOrigin.User,
    createdAt = null,
    updatedAt = dto.lastMentionedAt,
    mentions = (dto.mentions ?: 0.0).toInt().coerceAtLeast(0),
    lastMentionedAt = dto.lastMentionedAt,
    misses = (dto.misses ?: 0.0).toInt().coerceAtLeast(0),
    order = dto.order ?: 100,
)

private fun mapShortTerm(dto: LegacyShortTerm): ShortTermMemory = ShortTermMemory(
    id = dto.id ?: "",
    content = dto.content ?: "",
    sourceMessageIds = (dto.sourceMessageIds ?: emptyList()).take(160),
    timeRef = dto.timeRef?.anchor,
    eventTime = dto.eventTime,
    createdAt = dto.timestamp,
    analyzedAt = dto.analyzedAt,
    lorebookScannedAt = dto.lorebookScannedAt,
    participants = (dto.participants ?: emptyList()).take(MAX_PARTICIPANTS),
    location = dto.location ?: "",
    sourceRoles = dto.sourceRoles ?: emptyList(),
    revision = (dto.revision ?: 1).coerceAtLeast(1),
    analyzedRevision = (dto.analyzedRevision ?: 0).takeIf { it != 0 }
        ?: if (dto.analyzedAt != null) 1 else 0,
    lorebookScannedRevision = (dto.lorebookScannedRevision ?: 0).takeIf { it != 0 }
        ?: if (dto.lorebookScannedAt != null) 1 else 0,
    sourceTs = dto.sourceTs,
    userEvidence = dto.userEvidence.take(160),
)

private fun mapInstant(dto: LegacyInstantMessage, imageSink: StickerSink?): ChatMessage = ChatMessage(
    id = dto.id ?: "",
    role = mapRole(dto.role),
    content = dto.content ?: "",
    timestamp = dto.timestamp,
    sequence = dto.sequence ?: 0,
    attachments = (dto.images ?: emptyList())
        .filter { it.isNotBlank() }
        .take(4)
        .map { uri ->
            val ref = if (uri.startsWith("data:", ignoreCase = true)) {
                imageSink?.imageRefFor(uri) ?: uri
            } else {
                uri
            }
            MessageAttachment(kind = MessageAttachment.Kind.Image, uri = ref)
        },
    internalOnly = dto.internalOnly == true,
    extractedAt = dto.extractedAt,
    staticChanges = dto.staticChanges ?: emptyList(),
    lorebookChanges = dto.lorebookChanges ?: emptyList(),
    styleViolations = dto.styleViolations ?: emptyList(),
    quickReplyIssues = dto.quickReplyIssues ?: emptyList(),
)

/** Normalizes a sticker tag the way `normalizeStickers` does (strip whitespace, cap 6). */
internal fun normalizeStickerTag(raw: String?): String =
    (raw ?: "").replace(Regex("[\\s\\r\\n]+"), "").take(MAX_STICKER_TAG_CHARS).ifEmpty { "未分类" }

/** Maps a legacy sticker. Returns null when there is no image payload at all. */
fun mapSticker(dto: LegacySticker, stickerSink: StickerSink?): Sticker? {
    val dataUri = dto.dataUrl
    if (dataUri != null && dataUri.startsWith("data:image/", ignoreCase = true)) {
        return Sticker(
            id = dto.id ?: "",
            tag = normalizeStickerTag(dto.tag),
            // Without a sink the raw data URI is kept so no data is lost.
            fileRef = stickerSink?.refFor(dataUri) ?: dataUri,
            createdAt = dto.createdAt,
            updatedAt = dto.updatedAt ?: dto.createdAt,
        )
    }
    val existingRef = dto.fileRef?.takeIf { it.isNotBlank() } ?: return null
    return Sticker(
        id = dto.id ?: "",
        tag = normalizeStickerTag(dto.tag),
        fileRef = existingRef,
        createdAt = dto.createdAt,
        updatedAt = dto.updatedAt ?: dto.createdAt,
    )
}

/**
 * Maps a sticker list, de-duplicating by `dataUrl` like `normalizeStickers`
 * (the first payload wins). Entries without an image payload are dropped.
 */
fun mapStickers(stickers: List<LegacySticker>?, stickerSink: StickerSink?): List<Sticker> {
    val seen = HashSet<String>()
    val result = ArrayList<Sticker>()
    for (dto in stickers.orEmpty()) {
        val dataUri = dto.dataUrl
        if (dataUri != null && dataUri.startsWith("data:image/", ignoreCase = true)) {
            if (!seen.add(dataUri)) continue
        }
        result += mapSticker(dto, stickerSink) ?: continue
    }
    return result
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

private fun mapMembers(dto: LegacyCharacter, stickerSink: StickerSink?): List<GroupMember> =
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
            dynamicStateMeta = mapDynamicStateMeta(member.dynamicStateMeta),
            shortTerm = (memory?.shortTerm ?: emptyList()).map { mapShortTerm(it) }.takeLast(80),
            longTerm = memory?.longTerm?.flatMap { (category, items) ->
                items.map { mapLongTerm(it, categoryOf(category)) }
            } ?: emptyList(),
            lorebook = (member.lorebook ?: emptyList()).map { mapLorebook(it) }.take(200),
            instant = (memory?.instant ?: emptyList())
                .filter { it.isLoading != true }
                .map { mapInstant(it, stickerSink) }
                .takeLast(160),
            pendingRecall = memory?.pendingRecall?.map { mapLongTerm(it) }.orEmpty().take(6),
            scenes = (memory?.scenes ?: emptyList())
                .filter { !it.content.isNullOrBlank() }
                .map { mapScene(it) }
                .takeLast(8),
            sceneState = memory?.sceneState?.let { mapSceneState(it) },
            counters = mapCounters(memory?.counters),
            revision = (memory?.revision ?: 0).coerceAtLeast(0),
            avatarRepairPending = member.avatarRepairPending == true,
            staticFillMeta = member.staticFillMeta?.let { mapStaticFillMeta(it) },
            staticFieldMeta = mapStaticFieldMeta(member.staticFieldMeta),
            fieldsMigrationVersion = member.fieldsMigrationVersion ?: "",
            timeParseVersion = (member.timeParseVersion ?: 0).coerceAtLeast(0),
            lorebookMigratedAt = member.lorebookMigratedAt,
        )
    }

/**
 * Maps one legacy character (single or group) into the native [Character].
 *
 * Every legacy memory field now has a native home, including `counters`,
 * `lastInjectedRecallIds`, `dynamicStateMeta`, `staticFieldMeta`, per-scene
 * `key`/`startedAt`/`endedAt` and `sceneState.startCount`/`startSequence`.
 * Only `basicInfo.avatarPixel` (deprecated grid) has no native field.
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
        shortTerm = (memory?.shortTerm ?: emptyList()).map { mapShortTerm(it) }.takeLast(80),
        longTerm = memory?.longTerm?.flatMap { (category, items) ->
            items.map { mapLongTerm(it, categoryOf(category)) }
        } ?: emptyList(),
        lorebook = (dto.lorebook ?: emptyList()).map { mapLorebook(it) }.take(200),
        stickers = mapStickers(dto.stickers, stickerSink),
        instant = (memory?.instant ?: emptyList())
            .filter { it.isLoading != true }
            .map { mapInstant(it, stickerSink) }
            .takeLast(160),
        dynamicStateMeta = mapDynamicStateMeta(dto.dynamicStateMeta),
        avatarRepairPending = dto.avatarRepairPending == true,
        groupSharedDynamic = if (isGroup) sharedDynamic else DynamicState(),
        members = if (isGroup) mapMembers(dto, stickerSink) else emptyList(),
        fieldsMigrationVersion = dto.fieldsMigrationVersion ?: "",
        pendingRecall = memory?.pendingRecall?.map { mapLongTerm(it) }.orEmpty().take(6),
        lastInjectedRecallIds = (memory?.lastInjectedRecallIds ?: emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(6),
        counters = mapCounters(memory?.counters),
        scenes = (memory?.scenes ?: emptyList())
            .filter { !it.content.isNullOrBlank() }
            .map { mapScene(it) }
            .takeLast(8),
        sceneState = memory?.sceneState?.let { mapSceneState(it) },
        staticFillMeta = dto.staticFillMeta?.let { mapStaticFillMeta(it) },
        staticFieldMeta = mapStaticFieldMeta(dto.staticFieldMeta),
        timeParseVersion = (dto.timeParseVersion ?: 0).coerceAtLeast(0),
        lorebookMigratedAt = dto.lorebookMigratedAt,
        revision = (memory?.revision ?: 0).coerceAtLeast(0),
    )
}

private fun mapScene(dto: LegacyScene): SceneSummary = SceneSummary(
    id = dto.id ?: "",
    key = dto.key ?: "",
    content = dto.content ?: "",
    startedAt = dto.startedAt,
    endedAt = dto.endedAt,
    createdAt = dto.createdAt,
)

private fun mapSceneState(dto: LegacySceneState): SceneState = SceneState(
    key = dto.key ?: "",
    startCount = (dto.startCount ?: 0).coerceAtLeast(0),
    startSequence = dto.startSequence,
    messageCount = (dto.messageCount ?: 0).coerceAtLeast(0),
)

private fun mapDynamicStateMeta(element: JsonElement?): Map<String, DynamicStateMeta> {
    val obj = element as? JsonObject ?: return emptyMap()
    return obj.entries.associate { (key, value) ->
        val meta = value as? JsonObject
        key to DynamicStateMeta(updatedAt = meta?.stringOrNull("updatedAt"))
    }
}

private fun mapStaticFieldMeta(element: JsonElement?): Map<String, JsonElement> {
    val obj = element as? JsonObject ?: return emptyMap()
    return obj.toMap()
}

/** Legacy `memory.counters`; retry timestamps in the past are dropped like `normalizeRetryAt`. */
private fun mapCounters(element: JsonElement?): MemoryCounters {
    val obj = element as? JsonObject ?: return MemoryCounters()
    return MemoryCounters(
        extractionRetryAt = normalizeRetryAt(obj.stringOrNull("extractionRetryAt")),
        analysisRetryAt = normalizeRetryAt(obj.stringOrNull("analysisRetryAt")),
        sceneRetryAt = normalizeRetryAt(obj.stringOrNull("sceneRetryAt")),
        lorebookRetryAt = normalizeRetryAt(obj.stringOrNull("lorebookRetryAt")),
        extractionFailures = (obj.intLenient("extractionFailures") ?: 0).coerceAtLeast(0),
        analysisFailures = (obj.intLenient("analysisFailures") ?: 0).coerceAtLeast(0),
        sceneFailures = (obj.intLenient("sceneFailures") ?: 0).coerceAtLeast(0),
        lorebookFailures = (obj.intLenient("lorebookFailures") ?: 0).coerceAtLeast(0),
        lorebookScannedCount = (obj.intLenient("lorebookScannedCount") ?: 0).coerceAtLeast(0),
        messageSequence = (obj.intLenient("messageSequence") ?: 0).coerceAtLeast(0),
    )
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Lenient number read (`Number(...)`): numeric strings are accepted. */
private fun JsonObject.intLenient(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()?.toInt()

/** Strict `typeof === 'number'` read (used by `normalizeCacheStats`). */
private fun JsonObject.numberOnly(key: String): Int? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString && it.content.toDoubleOrNull() != null }
        ?.content?.toDoubleOrNull()?.toInt()

private fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

/** Legacy `normalizeRetryAt`: keep only a future timestamp, else null. */
private fun normalizeRetryAt(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    val ts = parseInstant(text) ?: return null
    return if (ts.toEpochMilli() > System.currentTimeMillis()) {
        ts.truncatedTo(ChronoUnit.MILLIS).toString()
    } else {
        null
    }
}

private val metricCodec = Json { ignoreUnknownKeys = true; isLenient = true }

/** Legacy `normalizeCacheStats`. */
private fun mapCacheStats(element: JsonElement?): CacheStats? {
    val obj = element as? JsonObject ?: return null
    val hitTokens = obj.numberOnly("hitTokens")
    val missTokens = obj.numberOnly("missTokens")
    val promptTokens = obj.numberOnly("promptTokens")
    val hasCacheFields = hitTokens != null && missTokens != null
    if (!hasCacheFields && promptTokens == null) return null
    return CacheStats(
        hitTokens = if (hasCacheFields) hitTokens!!.coerceAtLeast(0) else null,
        missTokens = if (hasCacheFields) missTokens!!.coerceAtLeast(0) else null,
        promptTokens = promptTokens?.takeIf { it >= 0 },
        updatedAt = parseInstant(obj.stringOrNull("updatedAt"))
            ?.truncatedTo(ChronoUnit.MILLIS)?.toString() ?: nowIso(),
    )
}

/** Legacy `requestMetrics` (`filter(isPlainObject).slice(-60)`). */
private fun mapRequestMetrics(element: JsonElement?): List<RequestMetric> {
    val arr = element as? JsonArray ?: return emptyList()
    return arr.mapNotNull { item ->
        if (item !is JsonObject) return@mapNotNull null
        runCatching { metricCodec.decodeFromJsonElement(RequestMetric.serializer(), item) }.getOrNull()
    }.takeLast(60)
}

private fun mapStaticFillMeta(dto: LegacyStaticFillMeta): StaticFillMeta = StaticFillMeta(
    attemptedAt = dto.attemptedAt,
    failures = (dto.failures ?: 0).coerceAtLeast(0),
    retryAt = dto.retryAt,
)

/** Maps the legacy config; missing or malformed values fall back to legacy defaults. */
fun mapConfig(dto: LegacyConfig?): AppConfig {
    if (dto == null) return AppConfig()
    val efforts = setOf("none", "low", "medium", "high", "max")
    val platform = dto.apiPlatform?.takeIf { it.isNotBlank() } ?: "deepseek"
    val requestedModel = dto.modelName?.takeIf { it.isNotBlank() } ?: "deepseek-flash"
    // `normalizeAppData`: on the deepseek platform the retired `deepseek-chat`
    // alias is coerced to the current default model.
    val modelName = if (platform == "deepseek" && requestedModel == "deepseek-chat") {
        "deepseek-flash"
    } else {
        requestedModel
    }
    return AppConfig(
        apiPlatform = platform,
        apiBaseUrl = (dto.apiBaseUrl ?: dto.apiEndpoint)?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() } ?: "https://api.deepseek.com/v1",
        modelName = modelName,
        temperature = (dto.temperature ?: 0.8).coerceIn(0.0, 2.0),
        stream = dto.stream ?: true,
        reasoningEffort = dto.reasoningEffort?.takeIf { it in efforts } ?: "medium",
        proactiveEnabled = dto.proactiveEnabled ?: true,
        styleCritique = dto.styleCritique ?: true,
        quickReplyRepair = dto.quickReplyRepair ?: true,
        cacheStats = mapCacheStats(dto.cacheStats),
        requestMetrics = mapRequestMetrics(dto.requestMetrics),
        platformSettings = dto.platformSettings.orEmpty().mapValues { (_, slot) ->
            PlatformSlot(
                baseUrl = slot.baseUrl?.trim()?.trimEnd('/').orEmpty(),
                modelName = slot.modelName?.trim().orEmpty(),
            )
        }.filterValues { it.baseUrl.isNotEmpty() || it.modelName.isNotEmpty() },
    )
}

/** Result of [reconcileLegacyMemories]: the repaired character and how many entries were merged. */
data class MemoryReconciliation(
    val character: Character,
    val repaired: Int,
)

/**
 * Legacy `reconcileLegacyMemories` (src/js/memory/updates.js): merges short-term
 * items that share an event identity (logical day + time slot), and merges
 * `events` long-term items the same way. Pure: returns a copy plus the number of
 * merged-away entries. The native model has no `sourceRoles` / `userEvidence`
 * fields, so those halves of the legacy merge are skipped.
 */
fun reconcileLegacyMemories(character: Character): MemoryReconciliation {
    var repaired = 0

    val short = character.shortTerm.toMutableList()
    val seen = HashMap<String, Pair<Int, ShortTermMemory>>()
    val droppedShort = HashSet<Int>()
    short.forEachIndexed { index, item ->
        val identity = eventIdentity(item.eventTime, item.participants, item.location)
        if (identity.isEmpty()) return@forEachIndexed
        val primary = seen[identity]
        if (primary == null) {
            seen[identity] = index to item
            return@forEachIndexed
        }
        val old = primary.second
        val merged = ShortTermMemory(
            id = old.id,
            content = mergeTimelineContent(old.eventTime, old.content, item.eventTime, item.content),
            sourceMessageIds = (old.sourceMessageIds + item.sourceMessageIds).distinct().take(8),
            timeRef = old.timeRef,
            eventTime = earlierTimestamp(old.eventTime, item.eventTime),
            createdAt = earlierTimestamp(old.createdAt, item.createdAt),
            analyzedAt = null,
            participants = mergeParticipants(old.participants, item.participants),
            location = mergeLocations(old.location, item.location),
            lorebookScannedAt = null,
            sourceRoles = (old.sourceRoles + item.sourceRoles).distinct(),
            userEvidence = (old.userEvidence + item.userEvidence).distinctBy { it.sourceMessageId to it.text },
        )
        short[primary.first] = merged
        seen[identity] = primary.first to merged
        droppedShort += index
        repaired++
    }
    val reconciledShort = short.filterIndexed { index, _ -> index !in droppedShort }

    val long = character.longTerm.toMutableList()
    val groups = LinkedHashMap<String, MutableList<Int>>()
    long.forEachIndexed { index, item ->
        if (item.category != MemoryCategory.Events) return@forEachIndexed
        val identity = eventIdentity(item.eventTime, item.participants, item.location)
        if (identity.isEmpty()) return@forEachIndexed
        groups.getOrPut(identity) { mutableListOf() }.add(index)
    }
    val droppedLong = HashSet<Int>()
    for (group in groups.values) {
        if (group.size < 2) continue
        val primaryIndex = group.first()
        for (entryIndex in group.drop(1)) {
            val old = long[primaryIndex]
            val newer = long[entryIndex]
            long[primaryIndex] = old.copy(
                value = mergeTimelineContent(old.eventTime, old.value, newer.eventTime, newer.value),
                tags = (old.tags + newer.tags).distinct().take(8),
                importance = maxOf(old.importance, newer.importance),
                sourceMessageIds = (old.sourceMessageIds + newer.sourceMessageIds).distinct().take(8),
                evidence = old.evidence.ifEmpty { newer.evidence },
                eventTime = earlierTimestamp(old.eventTime, newer.eventTime),
                participants = mergeParticipants(old.participants, newer.participants),
                location = mergeLocations(old.location, newer.location),
                updatedAt = newer.updatedAt ?: old.updatedAt,
                sourceRoles = (old.sourceRoles + newer.sourceRoles).distinct(),
                userEvidence = (old.userEvidence + newer.userEvidence).distinctBy { it.sourceMessageId to it.text },
            )
            droppedLong += entryIndex
            repaired++
        }
    }
    val reconciledLong = long.filterIndexed { index, _ -> index !in droppedLong }

    return MemoryReconciliation(
        character = character.copy(shortTerm = reconciledShort, longTerm = reconciledLong),
        repaired = repaired,
    )
}

/**
 * Legacy `ensureMessageSequences` (src/js/memory/policy.js) assigned a stored
 * monotonic `sequence` per instant message, a `counters.messageSequence` high
 * water mark and a `sceneState.startSequence` anchor. The mapper only derives
 * the sequence each message would get from list order (matching the legacy
 * fresh-store case, where the first message is 1). Nothing is mutated.
 */
fun ensureMessageSequences(messages: List<ChatMessage>): List<Int> =
    messages.indices.map { it + 1 }

/** Legacy `getEventIdentity`: logical day (rolling over at 02:00) plus time slot, or "". */
@Suppress("UNUSED_PARAMETER")
fun eventIdentity(eventTime: String?, participants: List<String>, location: String): String {
    val instant = parseInstant(eventTime) ?: return ""
    val zoned = instant.atZone(ZoneId.systemDefault())
    val logicalDay = zoned.minusHours(DAY_START_HOUR.toLong()).toLocalDate()
    return "${logicalDay.year}年${logicalDay.monthValue}月${logicalDay.dayOfMonth}日[" +
        "${timeSlot(zoned.hour + zoned.minute / 60.0)}]"
}

private fun timeSlot(hourOfDay: Double): String = when {
    hourOfDay < 4.0 -> "深夜"
    hourOfDay < 6.0 -> "凌晨"
    hourOfDay < 7.5 -> "清晨"
    hourOfDay < 10.5 -> "早晨"
    hourOfDay < 12.0 -> "上午"
    hourOfDay < 13.5 -> "中午"
    hourOfDay < 17.0 -> "下午"
    hourOfDay < 19.0 -> "傍晚"
    hourOfDay < 22.0 -> "晚上"
    else -> "夜里"
}

private fun mergeTimelineContent(aEvent: String?, aContent: String, bEvent: String?, bContent: String): String {
    val aTime = parseInstant(aEvent)?.toEpochMilli() ?: 0L
    val bTime = parseInstant(bEvent)?.toEpochMilli() ?: 0L
    val first = if (aTime <= bTime) aContent else bContent
    val second = if (aTime <= bTime) bContent else aContent
    return clampText(first, 900) + "\n" + clampText(second, 900)
}

private fun earlierTimestamp(a: String?, b: String?): String? {
    val at = parseInstant(a)
    val bt = parseInstant(b)
    if (at == null) return b ?: a
    if (bt == null) return a ?: b
    return if (at <= bt) a else b
}

private fun clampText(value: String, maxLength: Int): String {
    val text = value.trim()
    return if (maxLength > 0 && text.length > maxLength) text.take(maxLength) + "…" else text
}

private fun normalizeParticipants(values: List<String>): List<String> =
    values.map { clampText(it, 80) }
        .filter { it.isNotEmpty() }
        .take(MAX_PARTICIPANTS)
        .sorted()

private fun mergeParticipants(a: List<String>, b: List<String>): List<String> =
    normalizeParticipants(a + b).distinct().take(MAX_PARTICIPANTS)

private fun mergeLocations(a: String, b: String): String {
    val merged = linkedSetOf<String>()
    clampText(a, 160).takeIf { it.isNotEmpty() }?.let { merged += it }
    clampText(b, 160).takeIf { it.isNotEmpty() }?.let { merged += it }
    return merged.joinToString("、").ifEmpty { "未说明" }
}

private fun parseInstant(value: String?): Instant? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()
}
