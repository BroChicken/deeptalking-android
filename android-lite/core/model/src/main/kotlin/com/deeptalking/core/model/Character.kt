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
    val currentRelationship: String = "",
    val currentImportantOthers: String = "",
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
    val shortTerm: List<ShortTermMemory> = emptyList(),
    val longTerm: List<LongTermMemory> = emptyList(),
    val lorebook: List<LorebookEntry> = emptyList(),
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
    /** Persisted pending recall request, if any (legacy `memory.pendingRecall`). */
    val pendingRecall: PendingRecall? = null,
    /** Stored scene summaries used by the volatile context (legacy `memory.scenes`). */
    val scenes: List<SceneSummary> = emptyList(),
    /** Cursor for the scene-summary task (legacy `memory.sceneState`). */
    val sceneState: SceneState? = null,
    /** Retry bookkeeping for automatic static-field completion (legacy `staticFillMeta`). */
    val staticFillMeta: StaticFillMeta? = null,
    /** Guards the one-time relative-time migration (legacy `timeParseVersion`). */
    val timeParseVersion: Int = 0,
    /** When the one-time world-book migration ran (legacy `lorebookMigratedAt`). */
    val lorebookMigratedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Memory revision counter, bumped when a branch is discarded (legacy `memory.revision`). */
    val revision: Int = 0,
)

@Serializable
data class SceneState(
    val key: String = "",
    val startMessageId: String? = null,
    val messageCount: Int = 0,
)

@Serializable
data class StaticFillMeta(
    val attemptedAt: String? = null,
    val failures: Int = 0,
    val retryAt: String? = null,
)

@Serializable
data class PendingRecall(
    val category: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class SceneSummary(
    val id: String = "",
    val content: String = "",
    val fromMessageId: String? = null,
    val toMessageId: String? = null,
    val createdAt: String? = null,
)
