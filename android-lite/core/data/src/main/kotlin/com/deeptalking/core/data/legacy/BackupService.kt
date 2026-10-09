package com.deeptalking.core.data.legacy

import com.deeptalking.core.data.AppJson
import com.deeptalking.core.data.CharacterRepository
import com.deeptalking.core.data.ChatRepository
import com.deeptalking.core.data.ConfigRepository
import com.deeptalking.core.model.AppConfig
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
import com.deeptalking.core.model.MessageAttachment
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.SceneState
import com.deeptalking.core.model.SceneSummary
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.StaticFillMeta
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.core.model.Sticker
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes an imported backup file the way `decodeImportBuffer`
 * (src/js/storage/backup.js) does: strict UTF-8 first, then GBK, then a lenient
 * UTF-8 decode. The strict pass rejects invalid UTF-8 so a GBK-encoded (legacy
 * Windows) backup still parses.
 */
fun decodeImportBytes(bytes: ByteArray): String {
    val strictUtf8 = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    if (strictUtf8 != null) return strictUtf8

    val gbk = runCatching {
        Charset.forName("GBK").newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    return gbk ?: String(bytes, Charsets.UTF_8)
}

/** Reads a stored media reference back into a `data:image/...;base64,...` URI for export. */
fun interface MediaSource {
    fun toDataUri(ref: String): String?
}

/**
 * Exports/imports the native stores in the legacy JSON root shape so a backup
 * file stays usable by the WebView build and vice-versa.
 *
 * Media (message images + stickers) is inlined as base64 `data:` URIs on export
 * (legacy-compatible) and written back to local files on import, so a backup
 * restored on another device still shows images.
 *
 * - `apiKey` is intentionally never exported (mirrors `exportAllData`); the
 *   per-platform `apiKey` half is likewise dropped.
 * - `counters`, `lastInjectedRecallIds`, `pendingRecall`, `dynamicStateMeta`,
 *   `staticFieldMeta` and full `requestMetrics` round-trip in the legacy shape.
 * - `activeCharacterId` is accepted as a parameter (native stores do not model
 *   an active character); pass the current selection, or omit for `null`.
 */
class BackupService(
    private val characters: CharacterRepository,
    private val chat: ChatRepository,
    private val config: ConfigRepository,
    private val stickerSink: StickerSink? = null,
    private val mediaSource: MediaSource? = null,
    /**
     * App version written into the exported root (`version`), mirroring the
     * legacy `APP_VERSION`. Pass `BuildConfig.VERSION_NAME` from `:app`.
     */
    private val appVersion: String = "native",
) {
    private val codec = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun exportJson(activeCharacterId: String? = null): String {
        val all = characters.observeAll().first()
        val appConfig = config.observe().first()
        val messagesByCharacter = all.associate { it.id to chat.getMessages(it.id) }

        val root = buildJsonObject {
            put("config", AppJson.encodeToJsonElement(appConfig))
            putJsonObject("characters") {
                for (character in all) {
                    put(character.id, characterToJson(character, messagesByCharacter[character.id].orEmpty()))
                }
            }
            if (activeCharacterId.isNullOrBlank()) {
                put("activeCharacterId", JsonNull)
            } else {
                put("activeCharacterId", activeCharacterId)
            }
            put("activeTheme", appConfig.activeTheme)
            put("exportDate", appConfig.exportDateOrNow())
            put("version", appVersion)
        }
        return codec.encodeToString(JsonElement.serializer(), root)
    }

    suspend fun importJson(json: String): ImportSummary =
        LegacyImportService(characters, chat, config, stickerSink).importJson(json)

    private fun AppConfig.exportDateOrNow(): String =
        java.time.Instant.now().toString().substringBefore('.').ifBlank { java.time.Instant.now().toString() }

    private fun characterToJson(character: Character, messages: List<ChatMessage>): JsonObject = buildJsonObject {
        put("id", character.id)
        put("entityType", if (character.isGroup) "group" else "character")
        put("basicInfo", basicInfoToJson(character.name, character.emoji, character.staticProfile))
        put("dynamicState", dynamicStateToJson(character.dynamicState))
        if (character.dynamicStateMeta.isNotEmpty()) {
            put("dynamicStateMeta", dynamicStateMetaToJson(character.dynamicStateMeta))
        }
        if (character.avatarRepairPending) put("avatarRepairPending", true)
        put(
            "memory",
            memoryToJson(
                instant = messages.ifEmpty { character.instant },
                shortTerm = character.shortTerm,
                longTerm = character.longTerm,
                scenes = character.scenes,
                sceneState = character.sceneState,
                pendingRecall = character.pendingRecall,
                counters = character.counters,
                lastInjectedRecallIds = character.lastInjectedRecallIds,
                revision = character.revision,
            ),
        )
        putJsonArray("lorebook") { character.lorebook.forEach { add(lorebookToJson(it)) } }
        putJsonArray("stickers") { character.stickers.forEach { add(stickerToJson(it)) } }
        if (character.fieldsMigrationVersion.isNotBlank()) {
            put("fieldsMigrationVersion", character.fieldsMigrationVersion)
        }
        if (character.timeParseVersion > 0) {
            put("timeParseVersion", character.timeParseVersion)
        }
        character.staticFillMeta?.let { put("staticFillMeta", staticFillMetaToJson(it)) }
        if (character.staticFieldMeta.isNotEmpty()) {
            put("staticFieldMeta", JsonObject(character.staticFieldMeta))
        }
        character.lorebookMigratedAt?.let { put("lorebookMigratedAt", it) }
        if (character.isGroup) {
            putJsonObject("groupInfo") {
                put("name", character.name)
                put("avatar", character.emoji)
                put("description", character.description)
                put("scene", character.groupSharedDynamic.currentLocation)
                put("interactionRules", character.interactionRules)
            }
            putJsonArray("members") { character.members.forEach { add(memberToJson(it)) } }
        }
    }

    private fun memberToJson(member: GroupMember): JsonObject = buildJsonObject {
        put("id", member.id)
        put("basicInfo", basicInfoToJson(member.name, member.emoji, member.staticProfile))
        put("dynamicState", dynamicStateToJson(member.dynamicState))
        if (member.dynamicStateMeta.isNotEmpty()) {
            put("dynamicStateMeta", dynamicStateMetaToJson(member.dynamicStateMeta))
        }
        if (member.avatarRepairPending) put("avatarRepairPending", true)
        put("roleInGroup", member.roleInGroup)
        put(
            "memory",
            memoryToJson(
                instant = member.instant,
                shortTerm = member.shortTerm,
                longTerm = member.longTerm,
                scenes = member.scenes,
                sceneState = member.sceneState,
                pendingRecall = member.pendingRecall,
                counters = member.counters,
                revision = member.revision,
            ),
        )
        putJsonArray("lorebook") { member.lorebook.forEach { add(lorebookToJson(it)) } }
        member.staticFillMeta?.let { put("staticFillMeta", staticFillMetaToJson(it)) }
        if (member.staticFieldMeta.isNotEmpty()) {
            put("staticFieldMeta", JsonObject(member.staticFieldMeta))
        }
        if (member.fieldsMigrationVersion.isNotBlank()) {
            put("fieldsMigrationVersion", member.fieldsMigrationVersion)
        }
        if (member.timeParseVersion > 0) {
            put("timeParseVersion", member.timeParseVersion)
        }
        member.lorebookMigratedAt?.let { put("lorebookMigratedAt", it) }
    }

    private fun dynamicStateMetaToJson(meta: Map<String, DynamicStateMeta>): JsonObject =
        JsonObject(
            meta.mapValues { (_, value) ->
                buildJsonObject { value.updatedAt?.let { put("updatedAt", it) } }
            },
        )

    private fun basicInfoToJson(name: String, emoji: String, profile: StaticProfile): JsonObject =
        buildJsonObject {
            put("name", name)
            put("avatar", emoji)
            put("gender", profile.gender)
            put("age", profile.age)
            put("race", profile.race)
            put("appearance", profile.appearance)
            put("personality", profile.personality)
            put("values", profile.values)
            put("fears", profile.fears)
            put("background", profile.background)
            put("keyEvents", profile.keyEvents)
            put("speakingStyle", profile.speakingStyle)
            put("language", profile.language)
            put("userAddress", profile.userAddress)
        }

    private fun dynamicStateToJson(state: DynamicState): JsonObject = buildJsonObject {
        put("currentSituation", state.currentSituation)
        put("currentLocation", state.currentLocation)
        put("currentMood", state.currentMood)
        put("currentOccupation", state.currentOccupation)
        put("currentGoal", state.currentGoal)
        put("currentTone", state.currentTone)
    }

    private fun memoryToJson(
        instant: List<ChatMessage>,
        shortTerm: List<ShortTermMemory>,
        longTerm: List<LongTermMemory>,
        scenes: List<SceneSummary> = emptyList(),
        sceneState: SceneState? = null,
        pendingRecall: List<LongTermMemory> = emptyList(),
        counters: MemoryCounters = MemoryCounters(),
        lastInjectedRecallIds: List<String> = emptyList(),
        revision: Int = 0,
    ): JsonObject = buildJsonObject {
        putJsonArray("instant") { instant.forEach { add(instantToJson(it)) } }
        putJsonArray("shortTerm") { shortTerm.forEach { add(shortTermToJson(it)) } }
        put("longTerm", longTermMapToJson(longTerm))
        if (scenes.isNotEmpty()) {
            putJsonArray("scenes") { scenes.forEach { add(sceneToJson(it)) } }
        }
        sceneState?.let { put("sceneState", sceneStateToJson(it)) }
        if (pendingRecall.isNotEmpty()) {
            putJsonArray("pendingRecall") { pendingRecall.forEach { add(longTermToJson(it)) } }
        }
        if (lastInjectedRecallIds.isNotEmpty()) {
            putJsonArray("lastInjectedRecallIds") { lastInjectedRecallIds.forEach { add(it) } }
        }
        put("counters", countersToJson(counters))
        if (revision != 0) put("revision", revision)
    }

    private fun countersToJson(counters: MemoryCounters): JsonObject = buildJsonObject {
        counters.extractionRetryAt?.let { put("extractionRetryAt", it) }
        counters.analysisRetryAt?.let { put("analysisRetryAt", it) }
        counters.sceneRetryAt?.let { put("sceneRetryAt", it) }
        counters.lorebookRetryAt?.let { put("lorebookRetryAt", it) }
        put("extractionFailures", counters.extractionFailures)
        put("analysisFailures", counters.analysisFailures)
        put("sceneFailures", counters.sceneFailures)
        put("lorebookFailures", counters.lorebookFailures)
        put("lorebookScannedCount", counters.lorebookScannedCount)
        put("messageSequence", counters.messageSequence)
    }

    private fun longTermMapToJson(items: List<LongTermMemory>): JsonObject = buildJsonObject {
        for (category in MemoryCategory.entries) {
            putJsonArray(categoryKey(category)) {
                items.filter { it.category == category }.forEach { add(longTermToJson(it)) }
            }
        }
    }

    private fun instantToJson(message: ChatMessage): JsonObject = buildJsonObject {
        put("id", message.id)
        put("role", roleKey(message.role))
        put("content", message.content)
        if (message.sequence != 0) put("sequence", message.sequence)
        message.timestamp?.let { put("timestamp", it) }
        message.extractedAt?.let { put("extractedAt", it) }
        if (message.internalOnly) put("internalOnly", true)
        val images = message.attachments
            .filter { it.kind == MessageAttachment.Kind.Image }
            .map { mediaSource?.toDataUri(it.uri) ?: it.uri }
        if (images.isNotEmpty()) {
            putJsonArray("images") { images.forEach { add(it) } }
        }
        if (message.staticChanges.isNotEmpty()) {
            putJsonArray("staticChanges") { message.staticChanges.forEach { add(it) } }
        }
        if (message.lorebookChanges.isNotEmpty()) {
            putJsonArray("lorebookChanges") { message.lorebookChanges.forEach { add(it) } }
        }
        if (message.styleViolations.isNotEmpty()) {
            putJsonArray("styleViolations") { message.styleViolations.forEach { add(it) } }
        }
        if (message.quickReplyIssues.isNotEmpty()) {
            putJsonArray("quickReplyIssues") { message.quickReplyIssues.forEach { add(it) } }
        }
    }

    private fun shortTermToJson(memory: ShortTermMemory): JsonObject = buildJsonObject {
        put("id", memory.id)
        put("content", memory.content)
        memory.createdAt?.let { put("timestamp", it) }
        memory.analyzedAt?.let { put("analyzedAt", it) }
        memory.lorebookScannedAt?.let { put("lorebookScannedAt", it) }
        put("revision", memory.revision)
        put("analyzedRevision", memory.analyzedRevision)
        put("lorebookScannedRevision", memory.lorebookScannedRevision)
        memory.sourceTs?.let { put("_sourceTs", it) }
        memory.eventTime?.let { put("eventTime", it) }
        if (memory.sourceMessageIds.isNotEmpty()) {
            putJsonArray("sourceMessageIds") { memory.sourceMessageIds.forEach { add(it) } }
        }
        if (memory.participants.isNotEmpty()) {
            putJsonArray("participants") { memory.participants.forEach { add(it) } }
        }
        if (memory.location.isNotEmpty()) put("location", memory.location)
        memory.timeRef?.let { anchor ->
            putJsonObject("timeRef") { put("anchor", anchor) }
        }
        if (memory.sourceRoles.isNotEmpty()) {
            putJsonArray("sourceRoles") { memory.sourceRoles.forEach { add(it) } }
        }
        if (memory.userEvidence.isNotEmpty()) {
            putJsonArray("userEvidence") {
                memory.userEvidence.forEach { ev ->
                    add(
                        buildJsonObject {
                            put("sourceMessageId", ev.sourceMessageId)
                            put("text", ev.text)
                        },
                    )
                }
            }
        }
    }

    private fun longTermToJson(memory: LongTermMemory): JsonObject = buildJsonObject {
        put("id", memory.id)
        put("category", categoryKey(memory.category))
        put("key", memory.key)
        put("value", memory.value)
        putJsonArray("tags") { memory.tags.forEach { add(it) } }
        put("importance", memory.importance)
        put("subject", subjectKey(memory.subject))
        if (memory.sourceMessageIds.isNotEmpty()) {
            putJsonArray("sourceMessageIds") { memory.sourceMessageIds.forEach { add(it) } }
        }
        if (memory.evidence.isNotEmpty()) put("evidence", memory.evidence)
        memory.eventTime?.let { put("eventTime", it) }
        memory.dueAt?.let { put("dueAt", it) }
        memory.promisor?.let { put("promisor", it) }
        memory.promisee?.let { put("promisee", it) }
        put("status", statusKey(memory.status))
        memory.createdAt?.let { put("createdAt", it) }
        memory.updatedAt?.let { put("updatedAt", it) }
        memory.lastRecalled?.let { put("lastRecalled", it) }
        put("recallCount", memory.recallCount)
        if (memory.participants.isNotEmpty()) {
            putJsonArray("participants") { memory.participants.forEach { add(it) } }
        }
        if (memory.location.isNotEmpty()) put("location", memory.location)
        if (memory.learnedBonus != 0) put("learnedBonus", memory.learnedBonus)
        if (memory.usageCount != 0) put("usageCount", memory.usageCount)
        memory.lastUsageAt?.let { put("lastUsageAt", it) }
        memory.arcOf?.let { put("arcOf", it) }
        memory.arcStage?.let { put("arcStage", it) }
        memory.recordedAt?.let { put("recordedAt", it) }
        if (memory.conflicts.isNotEmpty()) {
            putJsonArray("conflicts") {
                memory.conflicts.forEach { conflict ->
                    add(
                        buildJsonObject {
                            put("value", conflict.value)
                            put("evidence", conflict.evidence)
                            putJsonArray("sourceMessageIds") { conflict.sourceMessageIds.forEach { add(it) } }
                            put("at", conflict.at)
                        },
                    )
                }
            }
        }
        memory.conflictedAt?.let { put("conflictedAt", it) }
        if (memory.relatedTo.isNotEmpty()) {
            putJsonArray("relatedTo") { memory.relatedTo.forEach { add(it) } }
        }
        if (memory.sourceRoles.isNotEmpty()) {
            putJsonArray("sourceRoles") { memory.sourceRoles.forEach { add(it) } }
        }
        if (memory.userEvidence.isNotEmpty()) {
            putJsonArray("userEvidence") {
                memory.userEvidence.forEach { ev ->
                    add(
                        buildJsonObject {
                            put("sourceMessageId", ev.sourceMessageId)
                            put("text", ev.text)
                        },
                    )
                }
            }
        }
        if (memory.corrections.isNotEmpty()) {
            putJsonArray("corrections") {
                memory.corrections.forEach { correction ->
                    add(
                        buildJsonObject {
                            put("at", correction.at)
                            put("evidence", correction.evidence)
                            put("before", correction.before)
                        },
                    )
                }
            }
        }
    }

    private fun sceneToJson(scene: SceneSummary): JsonObject = buildJsonObject {
        put("id", scene.id)
        put("key", scene.key)
        put("content", scene.content)
        scene.startedAt?.let { put("startedAt", it) }
        scene.endedAt?.let { put("endedAt", it) }
        scene.createdAt?.let { put("createdAt", it) }
    }

    private fun sceneStateToJson(state: SceneState): JsonObject = buildJsonObject {
        put("key", state.key)
        put("startCount", state.startCount)
        state.startSequence?.let { put("startSequence", it) }
        put("messageCount", state.messageCount)
    }

    private fun staticFillMetaToJson(meta: StaticFillMeta): JsonObject = buildJsonObject {
        meta.attemptedAt?.let { put("attemptedAt", it) }
        put("failures", meta.failures)
        meta.retryAt?.let { put("retryAt", it) }
    }

    private fun lorebookToJson(entry: LorebookEntry): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("name", entry.name)
        putJsonArray("keywords") { entry.keywords.forEach { add(it) } }
        put("content", entry.content)
        put("enabled", entry.enabled)
        put("alwaysActive", entry.alwaysActive)
        put("order", entry.order)
        put("origin", if (entry.origin == LorebookOrigin.Model) "ai" else "user")
        put("mentions", entry.mentions)
        put("misses", entry.misses)
        entry.lastMentionedAt?.let { put("lastMentionedAt", it) }
        entry.createdAt?.let { put("createdAt", it) }
    }

    private fun stickerToJson(sticker: Sticker): JsonObject = buildJsonObject {
        put("id", sticker.id)
        put("tag", sticker.tag)
        // Inline the payload so a restored backup (other device / after clear-data)
        // can re-materialize the image; keep fileRef as a fallback.
        mediaSource?.toDataUri(sticker.fileRef)?.let { put("dataUrl", it) }
        put("fileRef", sticker.fileRef)
        sticker.createdAt?.let { put("createdAt", it) }
        sticker.updatedAt?.let { put("updatedAt", it) }
    }

    private fun categoryKey(category: MemoryCategory): String = when (category) {
        MemoryCategory.UserProfile -> "userProfile"
        MemoryCategory.Relationship -> "relationship"
        MemoryCategory.Events -> "events"
        MemoryCategory.Promises -> "promises"
        MemoryCategory.Habits -> "habits"
    }

    private fun roleKey(role: Role): String = when (role) {
        Role.User -> "user"
        Role.Assistant -> "assistant"
        Role.System -> "system"
        Role.Tool -> "tool"
    }

    private fun subjectKey(subject: MemorySubject): String = when (subject) {
        MemorySubject.User -> "user"
        MemorySubject.Relationship -> "relationship"
        MemorySubject.World -> "world"
        MemorySubject.Character -> "character"
        MemorySubject.Legacy -> "legacy"
    }

    private fun statusKey(status: PromiseStatus): String = when (status) {
        PromiseStatus.Active -> "active"
        PromiseStatus.Resolved -> "resolved"
        PromiseStatus.Cancelled -> "cancelled"
    }
}
