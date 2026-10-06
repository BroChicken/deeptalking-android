package com.deeptalking.core.data.legacy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import com.deeptalking.core.model.MemoryConflict
import com.deeptalking.core.model.MemoryConflictListSerializer
import com.deeptalking.core.model.MemoryCorrection
import com.deeptalking.core.model.MemoryCorrectionListSerializer
import com.deeptalking.core.model.SourceEvidence
import com.deeptalking.core.model.SourceEvidenceListSerializer

/**
 * Permissive DTOs mirroring the legacy localStorage/backup JSON shape.
 *
 * The authoritative root written by `src/js/storage/persistence.js` is:
 * `{ config, characters, activeCharacterId, activeTheme, version }`
 * and the export in `src/js/storage/backup.js` additionally adds `exportDate`.
 *
 * Everything is nullable/defaulted and every decoder runs with
 * `ignoreUnknownKeys = true`, so partially populated or newer legacy files still
 * parse. Field names match `src/js/storage/schema.js#normalizeCharacter`,
 * `src/js/characters/entities.js#createCharacterObj`,
 * `src/js/memory/lorebook.js#normalizeLorebook`,
 * `src/js/media/stickers.js` and `src/js/storage/persistence.js`.
 */
@Serializable
data class LegacyRoot(
    @SerialName("config") val config: LegacyConfig? = null,
    @SerialName("characters") val characters: Map<String, LegacyCharacter>? = null,
    @SerialName("activeCharacterId") val activeCharacterId: String? = null,
    @SerialName("activeTheme") val activeTheme: String? = null,
    @SerialName("version") val version: String? = null,
    /** Legacy global sticker list; `normalizeAppData` folded it into the active character. */
    @SerialName("stickers") val stickers: List<LegacySticker>? = null,
)

/**
 * Shared bag for the 1.2.0 field structure. Older payloads nested dynamic fields
 * under `dynamicState.fields`; some payloads also carried static fields under
 * `basicInfo.fields`. This bag tolerates every spelling we have seen.
 */
@Serializable
data class LegacyFieldBag(
    // Current static profile fields (STATIC_PROFILE_FIELDS).
    @SerialName("gender") val gender: String? = null,
    @SerialName("age") val age: String? = null,
    @SerialName("race") val race: String? = null,
    @SerialName("appearance") val appearance: String? = null,
    @SerialName("personality") val personality: String? = null,
    @SerialName("values") val values: String? = null,
    @SerialName("fears") val fears: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("keyEvents") val keyEvents: String? = null,
    @SerialName("speakingStyle") val speakingStyle: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("userAddress") val userAddress: String? = null,
    // Legacy static fields that `normalizeCharacter` folds elsewhere.
    @SerialName("worldView") val worldView: String? = null,
    @SerialName("occupation") val occupation: String? = null,
    @SerialName("goals") val goals: String? = null,
    @SerialName("relationshipWithUser") val relationshipWithUser: String? = null,
    @SerialName("importantOthers") val importantOthers: String? = null,
    @SerialName("scene") val scene: String? = null,
    @SerialName("sceneNotes") val sceneNotes: String? = null,
    @SerialName("currentSituation") val currentSituation: String? = null,
    // Current dynamic state (DYNAMIC_STATE_FIELDS).
    @SerialName("currentLocation") val currentLocation: String? = null,
    @SerialName("currentMood") val currentMood: String? = null,
    @SerialName("currentOccupation") val currentOccupation: String? = null,
    @SerialName("currentGoal") val currentGoal: String? = null,
    @SerialName("currentRelationship") val currentRelationship: String? = null,
    @SerialName("currentImportantOthers") val currentImportantOthers: String? = null,
    // Legacy dynamic fields merged by `mergeLegacyDynamicFields`.
    @SerialName("recentDevelopment") val recentDevelopment: String? = null,
    @SerialName("currentFocus") val currentFocus: String? = null,
    @SerialName("currentEmotionalTendency") val currentEmotionalTendency: String? = null,
    @SerialName("currentSocialStyle") val currentSocialStyle: String? = null,
)

@Serializable
data class LegacyBasicInfo(
    @SerialName("name") val name: String? = null,
    @SerialName("avatar") val avatar: String? = null,
    /** Deprecated 16x16 grid; kept as raw JSON because its shape varied. */
    @SerialName("avatarPixel") val avatarPixel: JsonElement? = null,
    // Flattened static fields (the current shape).
    @SerialName("gender") val gender: String? = null,
    @SerialName("age") val age: String? = null,
    @SerialName("race") val race: String? = null,
    @SerialName("appearance") val appearance: String? = null,
    @SerialName("personality") val personality: String? = null,
    @SerialName("values") val values: String? = null,
    @SerialName("fears") val fears: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("keyEvents") val keyEvents: String? = null,
    @SerialName("speakingStyle") val speakingStyle: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("userAddress") val userAddress: String? = null,
    // Legacy static/dynamic fields that used to live on basicInfo.
    @SerialName("worldView") val worldView: String? = null,
    @SerialName("occupation") val occupation: String? = null,
    @SerialName("goals") val goals: String? = null,
    @SerialName("relationshipWithUser") val relationshipWithUser: String? = null,
    @SerialName("importantOthers") val importantOthers: String? = null,
    @SerialName("scene") val scene: String? = null,
    @SerialName("sceneNotes") val sceneNotes: String? = null,
    @SerialName("currentSituation") val currentSituation: String? = null,
    // Nested variant: `basicInfo.fields`.
    @SerialName("fields") val fields: LegacyFieldBag? = null,
)

@Serializable
data class LegacyDynamicState(
    @SerialName("currentSituation") val currentSituation: String? = null,
    @SerialName("currentLocation") val currentLocation: String? = null,
    @SerialName("currentMood") val currentMood: String? = null,
    @SerialName("currentOccupation") val currentOccupation: String? = null,
    @SerialName("currentGoal") val currentGoal: String? = null,
    @SerialName("currentRelationship") val currentRelationship: String? = null,
    @SerialName("currentImportantOthers") val currentImportantOthers: String? = null,
    // Legacy dynamic fields.
    @SerialName("recentDevelopment") val recentDevelopment: String? = null,
    @SerialName("currentFocus") val currentFocus: String? = null,
    @SerialName("currentEmotionalTendency") val currentEmotionalTendency: String? = null,
    @SerialName("currentSocialStyle") val currentSocialStyle: String? = null,
    /** Nested variant: `dynamicState.fields`. */
    @SerialName("fields") val fields: LegacyFieldBag? = null,
)

@Serializable
data class LegacyMemory(
    @SerialName("instant") val instant: List<LegacyInstantMessage>? = null,
    @SerialName("shortTerm") val shortTerm: List<LegacyShortTerm>? = null,
    /** Keyed by category: userProfile / relationship / events / promises / habits. */
    @SerialName("longTerm") val longTerm: Map<String, List<LegacyLongTerm>>? = null,
    /** Legacy `memory.pendingRecall` is a list of full long-term items. */
    @SerialName("pendingRecall") val pendingRecall: List<LegacyLongTerm>? = null,
    @SerialName("scenes") val scenes: List<LegacyScene>? = null,
    @SerialName("sceneState") val sceneState: LegacySceneState? = null,
    /** Legacy `memory.counters` (automatic-task retry/failure bookkeeping). */
    @SerialName("counters") val counters: JsonElement? = null,
    /** Ids injected by the most recent recall (legacy `memory.lastInjectedRecallIds`). */
    @SerialName("lastInjectedRecallIds") val lastInjectedRecallIds: List<String>? = null,
    @SerialName("revision") val revision: Int? = null,
)

@Serializable
data class LegacyScene(
    @SerialName("id") val id: String? = null,
    @SerialName("key") val key: String? = null,
    @SerialName("content") val content: String? = null,
    @SerialName("fromMessageId") val fromMessageId: String? = null,
    @SerialName("toMessageId") val toMessageId: String? = null,
    @SerialName("startedAt") val startedAt: String? = null,
    @SerialName("endedAt") val endedAt: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)

@Serializable
data class LegacySceneState(
    @SerialName("key") val key: String? = null,
    @SerialName("startMessageId") val startMessageId: String? = null,
    @SerialName("startCount") val startCount: Int? = null,
    @SerialName("startSequence") val startSequence: Int? = null,
    @SerialName("messageCount") val messageCount: Int? = null,
)

@Serializable
data class LegacyStaticFillMeta(
    @SerialName("attemptedAt") val attemptedAt: String? = null,
    @SerialName("failures") val failures: Int? = null,
    @SerialName("retryAt") val retryAt: String? = null,
)

@Serializable
data class LegacyInstantMessage(
    @SerialName("id") val id: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: String? = null,
    @SerialName("sequence") val sequence: Int? = null,
    @SerialName("timestamp") val timestamp: String? = null,
    @SerialName("extractedAt") val extractedAt: String? = null,
    @SerialName("images") val images: List<String>? = null,
    @SerialName("internalOnly") val internalOnly: Boolean? = null,
    @SerialName("isLoading") val isLoading: Boolean? = null,
    @SerialName("staticChanges") val staticChanges: List<String>? = null,
    @SerialName("lorebookChanges") val lorebookChanges: List<String>? = null,
    @SerialName("styleViolations") val styleViolations: List<String>? = null,
    @SerialName("quickReplyIssues") val quickReplyIssues: List<String>? = null,
)

@Serializable
data class LegacyShortTerm(
    @SerialName("id") val id: String? = null,
    @SerialName("content") val content: String? = null,
    @SerialName("timestamp") val timestamp: String? = null,
    @SerialName("analyzedAt") val analyzedAt: String? = null,
    @SerialName("lorebookScannedAt") val lorebookScannedAt: String? = null,
    @SerialName("eventTime") val eventTime: String? = null,
    @SerialName("sourceMessageIds") val sourceMessageIds: List<String>? = null,
    @SerialName("participants") val participants: List<String>? = null,
    @SerialName("location") val location: String? = null,
    @SerialName("timeRef") val timeRef: LegacyTimeRef? = null,
    @SerialName("sourceRoles") val sourceRoles: List<String>? = null,
    /** Legacy revision counter, min 1. */
    @SerialName("revision") val revision: Int? = null,
    /** Revision the analysis consumer last processed. */
    @SerialName("analyzedRevision") val analyzedRevision: Int? = null,
    /** Revision the lorebook consumer last processed. */
    @SerialName("lorebookScannedRevision") val lorebookScannedRevision: Int? = null,
    /** Source message timestamp used for same-day reconciliation. */
    @SerialName("_sourceTs") val sourceTs: String? = null,
    /** Legacy wrote an array of `{sourceMessageId,text}`; an early native build wrote a `String`. */
    @SerialName("userEvidence")
    @Serializable(with = SourceEvidenceListSerializer::class)
    val userEvidence: List<SourceEvidence> = emptyList(),
)

@Serializable
data class LegacyTimeRef(
    @SerialName("anchor") val anchor: String? = null,
)

@Serializable
data class LegacyLongTerm(
    @SerialName("id") val id: String? = null,
    /** Only written by native exports for pendingRecall items (legacy store items omit it). */
    @SerialName("category") val category: String? = null,
    @SerialName("key") val key: String? = null,
    @SerialName("value") val value: String? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("importance") val importance: Double? = null,
    @SerialName("subject") val subject: String? = null,
    @SerialName("sourceMessageIds") val sourceMessageIds: List<String>? = null,
    @SerialName("evidence") val evidence: String? = null,
    @SerialName("eventTime") val eventTime: String? = null,
    @SerialName("dueAt") val dueAt: String? = null,
    @SerialName("promisor") val promisor: String? = null,
    @SerialName("promisee") val promisee: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
    @SerialName("lastRecalled") val lastRecalled: String? = null,
    @SerialName("recallCount") val recallCount: Int? = null,
    @SerialName("participants") val participants: List<String>? = null,
    @SerialName("location") val location: String? = null,
    @SerialName("learnedBonus") val learnedBonus: Double? = null,
    @SerialName("usageCount") val usageCount: Int? = null,
    @SerialName("lastUsageAt") val lastUsageAt: String? = null,
    @SerialName("arcOf") val arcOf: String? = null,
    @SerialName("arcStage") val arcStage: String? = null,
    @SerialName("recordedAt") val recordedAt: String? = null,
    /** Legacy array of `{value,evidence,sourceMessageIds,at}` objects. */
    @SerialName("conflicts")
    @Serializable(with = MemoryConflictListSerializer::class)
    val conflicts: List<MemoryConflict> = emptyList(),
    @SerialName("conflictedAt") val conflictedAt: String? = null,
    @SerialName("relatedTo") val relatedTo: List<String>? = null,
    @SerialName("sourceRoles") val sourceRoles: List<String>? = null,
    /** Legacy array of `{sourceMessageId,text}` (also tolerated as a plain string). */
    @SerialName("userEvidence")
    @Serializable(with = SourceEvidenceListSerializer::class)
    val userEvidence: List<SourceEvidence> = emptyList(),
    /** Legacy array of `{at,evidence,before}` manual-edit history objects. */
    @SerialName("corrections")
    @Serializable(with = MemoryCorrectionListSerializer::class)
    val corrections: List<MemoryCorrection> = emptyList(),
)

@Serializable
data class LegacyLorebookEntry(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    /** Array in current data, but `normalizeLorebook` also accepts a delimited string. */
    @SerialName("keywords") val keywords: JsonElement? = null,
    @SerialName("content") val content: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
    @SerialName("order") val order: Int? = null,
    @SerialName("alwaysActive") val alwaysActive: Boolean? = null,
    @SerialName("origin") val origin: String? = null,
    @SerialName("mentions") val mentions: Double? = null,
    @SerialName("lastMentionedAt") val lastMentionedAt: String? = null,
    @SerialName("misses") val misses: Double? = null,
    @SerialName("sourceMessageIds") val sourceMessageIds: List<String>? = null,
    @SerialName("evidence") val evidence: String? = null,
)

@Serializable
data class LegacySticker(
    @SerialName("id") val id: String? = null,
    @SerialName("dataUrl") val dataUrl: String? = null,
    /** Native-only: already-migrated file path (written by [BackupService]). */
    @SerialName("fileRef") val fileRef: String? = null,
    @SerialName("tag") val tag: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
)

@Serializable
data class LegacyConfig(
    @SerialName("apiPlatform") val apiPlatform: String? = null,
    @SerialName("apiBaseUrl") val apiBaseUrl: String? = null,
    /** Very old name for the same value. */
    @SerialName("apiEndpoint") val apiEndpoint: String? = null,
    @SerialName("apiKey") val apiKey: String? = null,
    @SerialName("modelName") val modelName: String? = null,
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("stream") val stream: Boolean? = null,
    @SerialName("reasoningEffort") val reasoningEffort: String? = null,
    @SerialName("proactiveEnabled") val proactiveEnabled: Boolean? = null,
    @SerialName("styleCritique") val styleCritique: Boolean? = null,
    @SerialName("quickReplyRepair") val quickReplyRepair: Boolean? = null,
    @SerialName("platformSettings") val platformSettings: Map<String, LegacyPlatformSlot>? = null,
    /** Latest chat cache-usage snapshot (legacy `config.cacheStats`). */
    @SerialName("cacheStats") val cacheStats: JsonElement? = null,
    /** Rolling API usage/cache metrics (legacy `config.requestMetrics`). */
    @SerialName("requestMetrics") val requestMetrics: JsonElement? = null,
)

@Serializable
data class LegacyPlatformSlot(
    @SerialName("baseUrl") val baseUrl: String? = null,
    @SerialName("modelName") val modelName: String? = null,
    @SerialName("apiKey") val apiKey: String? = null,
)

@Serializable
data class LegacyCharacter(
    @SerialName("id") val id: String? = null,
    @SerialName("entityType") val entityType: String? = null,
    @SerialName("basicInfo") val basicInfo: LegacyBasicInfo? = null,
    @SerialName("dynamicState") val dynamicState: LegacyDynamicState? = null,
    @SerialName("dynamicStateMeta") val dynamicStateMeta: JsonElement? = null,
    @SerialName("avatarRepairPending") val avatarRepairPending: Boolean? = null,
    @SerialName("stickers") val stickers: List<LegacySticker>? = null,
    @SerialName("lorebook") val lorebook: List<LegacyLorebookEntry>? = null,
    @SerialName("memory") val memory: LegacyMemory? = null,
    @SerialName("groupInfo") val groupInfo: LegacyGroupInfo? = null,
    @SerialName("members") val members: List<LegacyGroupMember>? = null,
    @SerialName("fieldsMigrationVersion") val fieldsMigrationVersion: String? = null,
    @SerialName("staticFillMeta") val staticFillMeta: LegacyStaticFillMeta? = null,
    @SerialName("staticFieldMeta") val staticFieldMeta: JsonElement? = null,
    @SerialName("timeParseVersion") val timeParseVersion: Int? = null,
    @SerialName("lorebookMigratedAt") val lorebookMigratedAt: String? = null,
    /** Flattened variant: dynamic fields hoisted to the character root. */
    @SerialName("fields") val fields: LegacyFieldBag? = null,
)

@Serializable
data class LegacyGroupInfo(
    @SerialName("name") val name: String? = null,
    @SerialName("avatar") val avatar: String? = null,
    @SerialName("avatarPixel") val avatarPixel: JsonElement? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("scene") val scene: String? = null,
    @SerialName("interactionRules") val interactionRules: String? = null,
    @SerialName("worldView") val worldView: String? = null,
    @SerialName("background") val background: String? = null,
)

@Serializable
data class LegacyGroupMember(
    @SerialName("id") val id: String? = null,
    @SerialName("basicInfo") val basicInfo: LegacyBasicInfo? = null,
    @SerialName("dynamicState") val dynamicState: LegacyDynamicState? = null,
    @SerialName("dynamicStateMeta") val dynamicStateMeta: JsonElement? = null,
    @SerialName("roleInGroup") val roleInGroup: String? = null,
    @SerialName("lorebook") val lorebook: List<LegacyLorebookEntry>? = null,
    @SerialName("memory") val memory: LegacyMemory? = null,
    @SerialName("avatarRepairPending") val avatarRepairPending: Boolean? = null,
    @SerialName("staticFillMeta") val staticFillMeta: LegacyStaticFillMeta? = null,
    @SerialName("staticFieldMeta") val staticFieldMeta: JsonElement? = null,
    @SerialName("timeParseVersion") val timeParseVersion: Int? = null,
    @SerialName("lorebookMigratedAt") val lorebookMigratedAt: String? = null,
    @SerialName("fieldsMigrationVersion") val fieldsMigrationVersion: String? = null,
)
