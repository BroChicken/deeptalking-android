package com.deeptalking.domain.memory

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.DynamicStateMeta
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.StaticProfile
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Legacy `convertLegacyRelativeTimes` (`src/js/memory/updates.js:253`): rewrites
 * stored relative-time phrases ("昨天下午", "下周三") in short-term / long-term
 * content into absolute dates, using each item's own timestamp as the base.
 * Static profile fields use each field's `staticFieldMeta.updatedAt`; the
 * shared character is migrated and every group member is recursed into.
 * Idempotent, guarded by [Character.timeParseVersion].
 */
object RelativeTimeMigration {

    const val TIME_PARSE_VERSION = 1

    private val STATIC_PROFILE_KEYS: List<String> = listOf(
        "gender", "age", "race", "appearance", "personality", "values",
        "fears", "background", "keyEvents", "speakingStyle", "language", "userAddress",
    )

    fun convert(character: Character, now: ZonedDateTime = ZonedDateTime.now()): Character {
        if (character.timeParseVersion >= TIME_PARSE_VERSION) return character
        val zone = now.zone
        val fallback = fallbackTime(character.instant.map { it.timestamp }, zone) ?: now
        return character.copy(
            shortTerm = migrateShortTerm(character.shortTerm, zone, fallback),
            longTerm = migrateLongTerm(character.longTerm, zone, fallback),
            dynamicState = migrateDynamicState(character.dynamicState, character.dynamicStateMeta, zone, fallback),
            staticProfile = migrateStaticProfile(character.staticProfile, character.staticFieldMeta, zone),
            members = character.members.map { migrateMember(it, zone, fallback) },
            timeParseVersion = TIME_PARSE_VERSION,
        )
    }

    private fun migrateMember(member: GroupMember, zone: ZoneId, rootFallback: ZonedDateTime): GroupMember {
        val fallback = fallbackTime(member.instant.map { it.timestamp }, zone) ?: rootFallback
        return member.copy(
            shortTerm = migrateShortTerm(member.shortTerm, zone, fallback),
            longTerm = migrateLongTerm(member.longTerm, zone, fallback),
            dynamicState = migrateDynamicState(member.dynamicState, member.dynamicStateMeta, zone, fallback),
            staticProfile = migrateStaticProfile(member.staticProfile, member.staticFieldMeta, zone),
        )
    }

    private fun fallbackTime(timestamps: List<String?>, zone: ZoneId): ZonedDateTime? =
        timestamps.asSequence().mapNotNull { parseInstant(it) }.lastOrNull()?.atZone(zone)

    private fun migrateShortTerm(
        items: List<ShortTermMemory>,
        zone: ZoneId,
        fallback: ZonedDateTime,
    ): List<ShortTermMemory> = items.map { item ->
        val base = parseInstant(item.eventTime)?.atZone(zone)
            ?: parseInstant(item.createdAt)?.atZone(zone)
            ?: fallback
        item.copy(content = parseRelativeText(item.content, base, zone))
    }

    private fun migrateLongTerm(
        items: List<LongTermMemory>,
        zone: ZoneId,
        fallback: ZonedDateTime,
    ): List<LongTermMemory> = items.map { item ->
        val base = parseInstant(item.eventTime)?.atZone(zone)
            ?: parseInstant(item.recordedAt)?.atZone(zone)
            ?: parseInstant(item.createdAt)?.atZone(zone)
            ?: fallback
        item.copy(
            key = parseRelativeText(item.key, base, zone),
            value = parseRelativeText(item.value, base, zone),
        )
    }

    private fun migrateDynamicState(
        state: DynamicState,
        meta: Map<String, DynamicStateMeta>,
        zone: ZoneId,
        fallback: ZonedDateTime,
    ): DynamicState {
        fun base(key: String): ZonedDateTime = parseInstant(meta[key]?.updatedAt)?.atZone(zone) ?: fallback
        return DynamicState(
            currentSituation = parseRelativeText(state.currentSituation, base("currentSituation"), zone),
            currentLocation = parseRelativeText(state.currentLocation, base("currentLocation"), zone),
            currentMood = parseRelativeText(state.currentMood, base("currentMood"), zone),
            currentOccupation = parseRelativeText(state.currentOccupation, base("currentOccupation"), zone),
            currentGoal = parseRelativeText(state.currentGoal, base("currentGoal"), zone),
            currentTone = state.currentTone,
        )
    }

    private fun migrateStaticProfile(
        profile: StaticProfile,
        meta: Map<String, JsonElement>,
        zone: ZoneId,
    ): StaticProfile {
        var result = profile
        STATIC_PROFILE_KEYS.forEach { key ->
            val current = staticFieldValue(result, key)
            if (current.isBlank()) return@forEach
            val base = parseInstant(staticMetaUpdatedAt(meta, key))?.atZone(zone) ?: return@forEach
            val next = parseRelativeText(current, base, zone)
            if (next != current) result = result.withStaticField(key, next)
        }
        return result
    }

    private fun staticMetaUpdatedAt(meta: Map<String, JsonElement>, key: String): String? {
        val obj = meta[key] as? JsonObject ?: return null
        return (obj["updatedAt"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun staticFieldValue(profile: StaticProfile, key: String): String = when (key) {
        "gender" -> profile.gender
        "age" -> profile.age
        "race" -> profile.race
        "appearance" -> profile.appearance
        "personality" -> profile.personality
        "values" -> profile.values
        "fears" -> profile.fears
        "background" -> profile.background
        "keyEvents" -> profile.keyEvents
        "speakingStyle" -> profile.speakingStyle
        "language" -> profile.language
        "userAddress" -> profile.userAddress
        else -> ""
    }

    private fun StaticProfile.withStaticField(key: String, value: String): StaticProfile = when (key) {
        "gender" -> copy(gender = value)
        "age" -> copy(age = value)
        "race" -> copy(race = value)
        "appearance" -> copy(appearance = value)
        "personality" -> copy(personality = value)
        "values" -> copy(values = value)
        "fears" -> copy(fears = value)
        "background" -> copy(background = value)
        "keyEvents" -> copy(keyEvents = value)
        "speakingStyle" -> copy(speakingStyle = value)
        "language" -> copy(language = value)
        "userAddress" -> copy(userAddress = value)
        else -> this
    }

    private fun parseInstant(value: String?): Instant? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching {
                java.time.LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant()
            }.getOrNull()
            ?: runCatching {
                LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant()
            }.getOrNull()
    }
}
