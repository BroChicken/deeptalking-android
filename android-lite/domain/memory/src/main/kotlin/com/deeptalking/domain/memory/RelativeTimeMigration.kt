package com.deeptalking.domain.memory

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.DynamicState
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Legacy `convertLegacyRelativeTimes` (`src/js/memory/updates.js:253`): rewrites
 * stored relative-time phrases ("昨天下午", "下周三") in short-term / long-term
 * content into absolute dates, using each item's own timestamp as the base.
 * Idempotent, guarded by [Character.timeParseVersion].
 */
object RelativeTimeMigration {

    const val TIME_PARSE_VERSION = 1

    fun convert(character: Character, now: ZonedDateTime = ZonedDateTime.now()): Character {
        if (character.timeParseVersion >= TIME_PARSE_VERSION) return character
        val zone = now.zone
        val fallback = character.instant
            .asSequence()
            .mapNotNull { parseInstant(it.timestamp) }
            .lastOrNull()
            ?.atZone(zone)
            ?: now

        val shortTerm = character.shortTerm.map { item ->
            val base = parseInstant(item.eventTime)?.atZone(zone)
                ?: parseInstant(item.createdAt)?.atZone(zone)
                ?: fallback
            item.copy(content = parseRelativeText(item.content, base, zone))
        }

        val longTerm = character.longTerm.map { item ->
            val base = parseInstant(item.eventTime)?.atZone(zone)
                ?: parseInstant(item.recordedAt)?.atZone(zone)
                ?: parseInstant(item.createdAt)?.atZone(zone)
                ?: fallback
            item.copy(
                key = parseRelativeText(item.key, base, zone),
                value = parseRelativeText(item.value, base, zone),
            )
        }

        val dynamic = character.dynamicState
        val migratedDynamic = DynamicState(
            currentSituation = parseRelativeText(dynamic.currentSituation, fallback, zone),
            currentLocation = parseRelativeText(dynamic.currentLocation, fallback, zone),
            currentMood = parseRelativeText(dynamic.currentMood, fallback, zone),
            currentOccupation = parseRelativeText(dynamic.currentOccupation, fallback, zone),
            currentGoal = parseRelativeText(dynamic.currentGoal, fallback, zone),
            currentRelationship = parseRelativeText(dynamic.currentRelationship, fallback, zone),
            currentImportantOthers = parseRelativeText(dynamic.currentImportantOthers, fallback, zone),
        )

        return character.copy(
            shortTerm = shortTerm,
            longTerm = longTerm,
            dynamicState = migratedDynamic,
            timeParseVersion = TIME_PARSE_VERSION,
        )
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
