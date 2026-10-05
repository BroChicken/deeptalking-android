package com.deeptalking.core.data.legacy

import com.deeptalking.core.data.AppJson
import com.deeptalking.core.data.CharacterRepository
import com.deeptalking.core.data.ChatRepository
import com.deeptalking.core.data.ConfigRepository
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
 * - `apiKey` is intentionally never exported (mirrors `exportAllData`).
 * - `platformSettings`, `cacheStats` and `requestMetrics` are not part of
 *   [com.deeptalking.core.model.AppConfig] and therefore omitted.
 * - `memory.scenes`, `sceneState`, `counters`, `pendingRecall`,
 *   `lastInjectedRecallIds`, group member `roleInGroup` and
 *   `groupInfo.interactionRules` have no native field and are omitted.
 * - `activeCharacterId` is always `null` (not modeled).
 */
class BackupService(
    private val characters: CharacterRepository,
    private val chat: ChatRepository,
    private val config: ConfigRepository,
    private val stickerSink: StickerSink? = null,
    private val mediaSource: MediaSource? = null,
) {
    private val codec = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun exportJson(): String {
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
            put("activeCharacterId", JsonNull)
            put("activeTheme", appConfig.activeTheme)
            put("exportDate", appConfig.exportDateOrNow())
            put("version", "native")
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
        put("memory", memoryToJson(messages.ifEmpty { character.instant }, character.shortTerm, character.longTerm))
        putJsonArray("lorebook") { character.lorebook.forEach { add(lorebookToJson(it)) } }
        putJsonArray("stickers") { character.stickers.forEach { add(stickerToJson(it)) } }
        if (character.fieldsMigrationVersion.isNotBlank()) {
            put("fieldsMigrationVersion", character.fieldsMigrationVersion)
        }
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
        put("roleInGroup", member.roleInGroup)
        put("memory", memoryToJson(emptyList(), member.shortTerm, member.longTerm))
        putJsonArray("lorebook") { member.lorebook.forEach { add(lorebookToJson(it)) } }
    }

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
        put("currentRelationship", state.currentRelationship)
        put("currentImportantOthers", state.currentImportantOthers)
    }

    private fun memoryToJson(
        instant: List<ChatMessage>,
        shortTerm: List<ShortTermMemory>,
        longTerm: List<LongTermMemory>,
    ): JsonObject = buildJsonObject {
        putJsonArray("instant") { instant.forEach { add(instantToJson(it)) } }
        putJsonArray("shortTerm") { shortTerm.forEach { add(shortTermToJson(it)) } }
        put("longTerm", longTermMapToJson(longTerm))
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
        message.timestamp?.let { put("timestamp", it) }
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
    }

    private fun shortTermToJson(memory: ShortTermMemory): JsonObject = buildJsonObject {
        put("id", memory.id)
        put("content", memory.content)
        memory.createdAt?.let { put("timestamp", it) }
        memory.eventTime?.let { put("eventTime", it) }
        if (memory.sourceMessageIds.isNotEmpty()) {
            putJsonArray("sourceMessageIds") { memory.sourceMessageIds.forEach { add(it) } }
        }
        memory.timeRef?.let { anchor ->
            putJsonObject("timeRef") { put("anchor", anchor) }
        }
    }

    private fun longTermToJson(memory: LongTermMemory): JsonObject = buildJsonObject {
        put("id", memory.id)
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
    }

    private fun lorebookToJson(entry: LorebookEntry): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("name", entry.name)
        putJsonArray("keywords") { entry.keywords.forEach { add(it) } }
        put("content", entry.content)
        put("enabled", entry.enabled)
        put("alwaysActive", entry.alwaysActive)
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
