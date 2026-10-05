package com.deeptalking.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryCategory {
    @SerialName("userProfile") UserProfile,
    @SerialName("relationship") Relationship,
    @SerialName("events") Events,
    @SerialName("promises") Promises,
    @SerialName("habits") Habits;

    /** The lowercase store key used by the legacy JS and by category filters. */
    val legacyKey: String
        get() = when (this) {
            UserProfile -> "userProfile"
            Relationship -> "relationship"
            Events -> "events"
            Promises -> "promises"
            Habits -> "habits"
        }
}

@Serializable
enum class MemorySubject {
    @SerialName("user") User,
    @SerialName("relationship") Relationship,
    @SerialName("world") World,
    @SerialName("character") Character,
    @SerialName("legacy") Legacy,
}

@Serializable
enum class PromiseStatus {
    @SerialName("active") Active,
    @SerialName("resolved") Resolved,
    @SerialName("cancelled") Cancelled,
}

@Serializable
data class ShortTermMemory(
    val id: String = "",
    val content: String = "",
    val sourceMessageIds: List<String> = emptyList(),
    val timeRef: String? = null,
    val eventTime: String? = null,
    val createdAt: String? = null,
    /** When this item was folded into long-term memory (legacy `analyzedAt`). */
    val analyzedAt: String? = null,
    /** Participants of the event (legacy `participants`). */
    val participants: List<String> = emptyList(),
    /** Event location (legacy `location`). */
    val location: String = "",
    /** When this item was last handed to the lorebook consolidation pass. */
    val lorebookScannedAt: String? = null,
)

@Serializable
data class LongTermMemory(
    val id: String = "",
    val category: MemoryCategory = MemoryCategory.Events,
    val subject: MemorySubject = MemorySubject.Legacy,
    val key: String = "",
    val value: String = "",
    val tags: List<String> = emptyList(),
    val importance: Int = 0,
    val sourceMessageIds: List<String> = emptyList(),
    val evidence: String = "",
    val eventTime: String? = null,
    val dueAt: String? = null,
    val promisor: String? = null,
    val promisee: String? = null,
    val status: PromiseStatus = PromiseStatus.Active,
    val memberName: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val lastRecalled: String? = null,
    val recallCount: Int = 0,
    /** When a due-reminder notification was posted for this promise (native `set_reminder` firing). */
    val notifiedAt: String? = null,
    /** Event participants, for identity/merge (legacy `participants`). */
    val participants: List<String> = emptyList(),
    /** Event location, for identity/merge (legacy `location`). */
    val location: String = "",
    /** Self-learned importance delta in [-3, 3] (legacy `learnedBonus`). */
    val learnedBonus: Int = 0,
    /** Times this memory was used (recall or retrieval hit), legacy `usageCount`. */
    val usageCount: Int = 0,
    /** Last time this memory was used, ISO (legacy `lastUsageAt`). */
    val lastUsageAt: String? = null,
    /** Story-arc label (legacy `arcOf`). */
    val arcOf: String? = null,
    /** Story-arc stage 起始/发展/转折/现状 (legacy `arcStage`). */
    val arcStage: String? = null,
    /** Recorded-at timestamp (legacy `recordedAt`). */
    val recordedAt: String? = null,
)
