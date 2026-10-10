package com.deeptalking.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One verbatim user-evidence excerpt backing a memory (legacy
 * `userEvidence: [{ sourceMessageId, text }]`).
 */
@Serializable
data class SourceEvidence(
    val sourceMessageId: String = "",
    val text: String = "",
)

/**
 * Tolerant codec for legacy `userEvidence`, which the WebView app always wrote
 * as an **array of `{sourceMessageId, text}`**, but which an early native build
 * persisted as a plain `String`. Accepts all of: absent/`null`, `""`, a bare
 * string, `["a","b"]`, and `[{sourceMessageId,text}]`.
 */
object SourceEvidenceListSerializer : KSerializer<List<SourceEvidence>> {
    private val delegate = ListSerializer(SourceEvidence.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<SourceEvidence>) {
        val json = encoder as? JsonEncoder
        if (json == null) {
            delegate.serialize(encoder, value)
            return
        }
        json.encodeJsonElement(
            buildJsonArray {
                value.forEach { ev ->
                    add(
                        buildJsonObject {
                            put("sourceMessageId", ev.sourceMessageId)
                            put("text", ev.text)
                        },
                    )
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): List<SourceEvidence> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return parseSourceEvidence(json.decodeJsonElement())
    }
}

internal fun parseSourceEvidence(element: JsonElement): List<SourceEvidence> = when (element) {
    is JsonNull -> emptyList()
    is JsonPrimitive ->
        if (element.isString && element.content.isNotBlank()) {
            listOf(SourceEvidence(sourceMessageId = "", text = element.content))
        } else {
            emptyList()
        }
    is JsonArray -> element.mapNotNull { item ->
        when (item) {
            is JsonObject -> {
                val id = item["sourceMessageId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val text = item["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.isBlank() && text.isBlank()) null else SourceEvidence(id, text)
            }
            is JsonPrimitive ->
                item.contentOrNull?.takeIf { it.isNotBlank() }?.let { SourceEvidence("", it) }
            else -> null
        }
    }
    else -> emptyList()
}

/**
 * Tolerant codec for legacy `conflicts`, written as an array of
 * `{ value, evidence, sourceMessageIds, at }` objects; the native model keeps
 * only the competing values. Accepts absent/`null`, `["a"]`, `[{value}]`, or a
 * bare string, and re-exports the legacy object shape.
 */
object ConflictListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<String>) {
        val json = encoder as? JsonEncoder
        if (json == null) {
            delegate.serialize(encoder, value)
            return
        }
        json.encodeJsonElement(
            buildJsonArray {
                value.forEach { add(buildJsonObject { put("value", it) }) }
            },
        )
    }

    override fun deserialize(decoder: Decoder): List<String> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return parseConflictValues(json.decodeJsonElement())
    }
}

internal fun parseConflictValues(element: JsonElement): List<String> = when (element) {
    is JsonNull -> emptyList()
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    is JsonArray -> element.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> item["value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
    }
    else -> emptyList()
}

/** One competing value kept for reconciliation (legacy `conflicts[]` object). */
@Serializable
data class MemoryConflict(
    val value: String = "",
    val evidence: String = "",
    val sourceMessageIds: List<String> = emptyList(),
    val at: String? = null,
)

/** One manual-edit history record (legacy `corrections[]` object). */
@Serializable
data class MemoryCorrection(
    val at: String? = null,
    val evidence: String = "",
    /** The value before the correction (`null` when created, a value when replaced). */
    val before: String? = null,
)

/** Tolerant codec: accepts the legacy `{value,evidence,sourceMessageIds,at}` objects, bare strings, or absent. */
object MemoryConflictListSerializer : KSerializer<List<MemoryConflict>> {
    private val delegate = ListSerializer(MemoryConflict.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<MemoryConflict>) {
        val json = encoder as? JsonEncoder
        if (json == null) {
            delegate.serialize(encoder, value)
            return
        }
        json.encodeJsonElement(
            buildJsonArray {
                value.forEach { conflict ->
                    add(
                        buildJsonObject {
                            put("value", conflict.value)
                            put("evidence", conflict.evidence)
                            put("sourceMessageIds", buildJsonArray { conflict.sourceMessageIds.forEach { add(it) } })
                            if (conflict.at != null) put("at", conflict.at) else put("at", JsonNull)
                        },
                    )
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): List<MemoryConflict> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return parseConflicts(json.decodeJsonElement())
    }
}

internal fun parseConflicts(element: JsonElement): List<MemoryConflict> = when (element) {
    is JsonNull -> emptyList()
    is JsonPrimitive ->
        element.contentOrNull?.takeIf { it.isNotBlank() }?.let { listOf(MemoryConflict(value = it)) } ?: emptyList()
    is JsonArray -> element.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let { MemoryConflict(value = it) }
            is JsonObject -> {
                val value = item["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (value.isBlank()) null else MemoryConflict(
                    value = value,
                    evidence = item["evidence"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    sourceMessageIds = (item["sourceMessageIds"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                    at = item["at"]?.jsonPrimitive?.contentOrNull,
                )
            }
            else -> null
        }
    }
    else -> emptyList()
}

/** Tolerant codec: accepts legacy `{at,evidence,before}` objects, bare strings, or absent. */
object MemoryCorrectionListSerializer : KSerializer<List<MemoryCorrection>> {
    private val delegate = ListSerializer(MemoryCorrection.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<MemoryCorrection>) {
        val json = encoder as? JsonEncoder
        if (json == null) {
            delegate.serialize(encoder, value)
            return
        }
        json.encodeJsonElement(
            buildJsonArray {
                value.forEach { correction ->
                    add(
                        buildJsonObject {
                            put("at", correction.at)
                            put("evidence", correction.evidence)
                            put("before", correction.before)
                        },
                    )
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): List<MemoryCorrection> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return parseCorrections(json.decodeJsonElement())
    }
}

internal fun parseCorrections(element: JsonElement): List<MemoryCorrection> = when (element) {
    is JsonNull -> emptyList()
    is JsonPrimitive ->
        element.contentOrNull?.takeIf { it.isNotBlank() }?.let { listOf(MemoryCorrection(before = it)) } ?: emptyList()
    is JsonArray -> element.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let { MemoryCorrection(before = it) }
            is JsonObject -> MemoryCorrection(
                at = item["at"]?.jsonPrimitive?.contentOrNull,
                evidence = item["evidence"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                before = item["before"]?.jsonPrimitive?.contentOrNull,
            )
            else -> null
        }
    }
    else -> emptyList()
}

/** Per-field dynamic-state bookkeeping (legacy `dynamicStateMeta[key] = { updatedAt }`). */
@Serializable
data class DynamicStateMeta(
    val updatedAt: String? = null,
)

/**
 * Automatic-memory-task retry/failure counters (legacy `memory.counters`), plus
 * the monotonic message sequence high-water mark.
 */
@Serializable
data class MemoryCounters(
    val extractionRetryAt: String? = null,
    val analysisRetryAt: String? = null,
    val sceneRetryAt: String? = null,
    val lorebookRetryAt: String? = null,
    val consolidateRetryAt: String? = null,
    val extractionFailures: Int = 0,
    val analysisFailures: Int = 0,
    val sceneFailures: Int = 0,
    val lorebookFailures: Int = 0,
    val consolidateFailures: Int = 0,
    val lorebookScannedCount: Int = 0,
    val messageSequence: Int = 0,
)

/** Latest chat cache-usage snapshot (legacy `config.cacheStats`). */
@Serializable
data class CacheStats(
    val hitTokens: Int? = null,
    val missTokens: Int? = null,
    val promptTokens: Int? = null,
    val updatedAt: String? = null,
)

/**
 * Tolerant codec for the persisted pending-recall list. Accepts the legacy
 * full-item array, `null`, or the transient native request object
 * `{category,tags}` (which cannot be reconstructed into items → dropped).
 */
object PendingRecallListSerializer : KSerializer<List<LongTermMemory>> {
    private val delegate = ListSerializer(LongTermMemory.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<LongTermMemory>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<LongTermMemory> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        return when (val element = json.decodeJsonElement()) {
            is JsonNull -> emptyList()
            is JsonArray -> element.mapNotNull {
                runCatching { json.json.decodeFromJsonElement(LongTermMemory.serializer(), it) }.getOrNull()
            }
            else -> emptyList()
        }
    }
}

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
    /** Revision counter, bumped when the summary is rewritten (legacy `revision`, min 1). */
    val revision: Int = 1,
    /** Revision the analysis consumer last processed (legacy `analyzedRevision`). */
    val analyzedRevision: Int = 0,
    /** Revision the lorebook consumer last processed (legacy `lorebookScannedRevision`). */
    val lorebookScannedRevision: Int = 0,
    /** Source message timestamp used for same-day reconciliation (legacy `_sourceTs`). */
    @SerialName("_sourceTs") val sourceTs: String? = null,
    /** Roles of the source messages (legacy `sourceRoles`, e.g. `user`/`assistant`). */
    val sourceRoles: List<String> = emptyList(),
    /** Verbatim user evidence excerpts (legacy `userEvidence`). */
    @Serializable(with = SourceEvidenceListSerializer::class)
    val userEvidence: List<SourceEvidence> = emptyList(),
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
    /** Semantically-different competing values kept for reconciliation (legacy `conflicts`). */
    @Serializable(with = MemoryConflictListSerializer::class)
    val conflicts: List<MemoryConflict> = emptyList(),
    /** When the last conflict was recorded (legacy `conflictedAt`). */
    val conflictedAt: String? = null,
    /** Ids of related memories (legacy `relatedTo`). */
    val relatedTo: List<String> = emptyList(),
    /** Roles of the source messages (legacy `sourceRoles`). */
    val sourceRoles: List<String> = emptyList(),
    /** Verbatim user evidence excerpts (legacy `userEvidence`). */
    @Serializable(with = SourceEvidenceListSerializer::class)
    val userEvidence: List<SourceEvidence> = emptyList(),
    /** Prior values replaced by manual edits (legacy corrections history). */
    @Serializable(with = MemoryCorrectionListSerializer::class)
    val corrections: List<MemoryCorrection> = emptyList(),
)
