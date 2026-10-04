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
)
