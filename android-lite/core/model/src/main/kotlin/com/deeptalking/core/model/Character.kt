package com.deeptalking.core.model

import kotlinx.serialization.Serializable

@Serializable
data class StaticProfile(
    val gender: String = "",
    val age: String = "",
    val race: String = "",
    val appearance: String = "",
    val personality: String = "",
    val values: String = "",
    val fears: String = "",
    val background: String = "",
    val keyEvents: String = "",
    val speakingStyle: String = "",
    val language: String = "",
    val userAddress: String = "",
)

@Serializable
data class DynamicState(
    val currentSituation: String = "",
    val currentLocation: String = "",
    val currentMood: String = "",
    val currentOccupation: String = "",
    val currentGoal: String = "",
    /** Delivery instruction for read-aloud TTS (语气/情绪/语速/音量); replaces the legacy relationship fields. */
    val currentTone: String = "",
)

@Serializable
data class GroupMember(
    val id: String = "",
    val name: String = "",
    val emoji: String = "",
    /** Member-only "群内定位" (legacy `member.roleInGroup`). */
    val roleInGroup: String = "",
    val staticProfile: StaticProfile = StaticProfile(),
    val dynamicState: DynamicState = DynamicState(),
    val dynamicStateMeta: Map<String, DynamicStateMeta> = emptyMap(),
    val shortTerm: List<ShortTermMemory> = emptyList(),
    val longTerm: List<LongTermMemory> = emptyList(),
    val lorebook: List<LorebookEntry> = emptyList(),
    /** Member conversation window (legacy `member.memory.instant`). */
    val instant: List<ChatMessage> = emptyList(),
    /** Member pending recall items (legacy `member.memory.pendingRecall`). */
    @Serializable(with = PendingRecallListSerializer::class)
    val pendingRecall: List<LongTermMemory> = emptyList(),
    val scenes: List<SceneSummary> = emptyList(),
    val sceneState: SceneState? = null,
    val counters: MemoryCounters = MemoryCounters(),
    val revision: Int = 0,
    val avatarRepairPending: Boolean = false,
    val staticFillMeta: StaticFillMeta? = null,
    val staticFieldMeta: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val fieldsMigrationVersion: String = "",
    val timeParseVersion: Int = 0,
    val lorebookMigratedAt: String? = null,
    /** Guards the one-time deterministic memory repair (reconcile + near-duplicate dedupe). */
    val memoryRepairVersion: Int = 0,
)

/** Character or group entity. Groups set [isGroup] and populate [members]. */
@Serializable
data class Character(
    val id: String,
    val name: String = "",
    val emoji: String = "",
    val description: String = "",
    val isGroup: Boolean = false,
    /** Group-only "成员互动规则" (legacy `groupInfo.interactionRules`). */
    val interactionRules: String = "",
    val staticProfile: StaticProfile = StaticProfile(),
    val dynamicState: DynamicState = DynamicState(),
    val shortTerm: List<ShortTermMemory> = emptyList(),
    val longTerm: List<LongTermMemory> = emptyList(),
    val lorebook: List<LorebookEntry> = emptyList(),
    val stickers: List<Sticker> = emptyList(),
    /** Legacy short conversation window (JS `memory.instant`). */
    val instant: List<ChatMessage> = emptyList(),
    val groupSharedDynamic: DynamicState = DynamicState(),
    val members: List<GroupMember> = emptyList(),
    val fieldsMigrationVersion: String = "",
    /** Per-field dynamic-state bookkeeping (legacy `dynamicStateMeta`). */
    val dynamicStateMeta: Map<String, DynamicStateMeta> = emptyMap(),
    /** True when the avatar was detected damaged and awaits AI repair (legacy `avatarRepairPending`). */
    val avatarRepairPending: Boolean = false,
    /** Persisted pending-recall items (legacy `memory.pendingRecall`, up to 6 full items). */
    @Serializable(with = PendingRecallListSerializer::class)
    val pendingRecall: List<LongTermMemory> = emptyList(),
    /** Ids injected by the last recall, for precise clearing (legacy `memory.lastInjectedRecallIds`). */
    val lastInjectedRecallIds: List<String> = emptyList(),
    /** Automatic-memory-task retry/failure counters (legacy `memory.counters`). */
    val counters: MemoryCounters = MemoryCounters(),
    /** Stored scene summaries used by the volatile context (legacy `memory.scenes`). */
    val scenes: List<SceneSummary> = emptyList(),
    /** Cursor for the scene-summary task (legacy `memory.sceneState`). */
    val sceneState: SceneState? = null,
    /** Retry bookkeeping for automatic static-field completion (legacy `staticFillMeta`). */
    val staticFillMeta: StaticFillMeta? = null,
    /** Per-field static-fill bookkeeping (legacy `staticFieldMeta`). */
    val staticFieldMeta: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    /** Guards the one-time relative-time migration (legacy `timeParseVersion`). */
    val timeParseVersion: Int = 0,
    /** When the one-time world-book migration ran (legacy `lorebookMigratedAt`). */
    val lorebookMigratedAt: String? = null,
    /** Guards the one-time deterministic memory repair (reconcile + near-duplicate dedupe). */
    val memoryRepairVersion: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Memory revision counter, bumped when a branch is discarded (legacy `memory.revision`). */
    val revision: Int = 0,
)

@Serializable
data class SceneState(
    val key: String = "",
    /** Logical message count when the current scene started (legacy `startCount`). */
    val startCount: Int = 0,
    /** Message sequence when the current scene started (legacy `startSequence`). */
    val startSequence: Int? = null,
    val messageCount: Int = 0,
)

@Serializable
data class StaticFillMeta(
    val attemptedAt: String? = null,
    val failures: Int = 0,
    val retryAt: String? = null,
)

@Serializable
data class SceneSummary(
    val id: String = "",
    /** Location key the scene belongs to (legacy `key`). */
    val key: String = "",
    val content: String = "",
    val startedAt: String? = null,
    val endedAt: String? = null,
    val createdAt: String? = null,
)
