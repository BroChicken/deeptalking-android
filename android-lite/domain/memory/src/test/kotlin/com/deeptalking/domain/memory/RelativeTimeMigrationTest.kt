package com.deeptalking.domain.memory

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.DynamicStateMeta
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.ShortTermMemory
import com.deeptalking.core.model.StaticProfile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RelativeTimeMigrationTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 5, 12, 0, 0, 0, zone)

    @Test
    fun staticProfileFieldUsesItsOwnMetaTimestamp() {
        val character = Character(
            id = "c1",
            staticProfile = StaticProfile(personality = "昨天很开朗", background = "明天出发"),
            staticFieldMeta = mapOf(
                "personality" to buildJsonObject { put("updatedAt", "2026-10-01T18:00:00+08:00") },
            ),
        )

        val migrated = RelativeTimeMigration.convert(character, now)

        assertEquals("2026-09-30很开朗", migrated.staticProfile.personality)
        assertFalse(migrated.staticProfile.personality.contains("昨天"))
        assertEquals("明天出发", migrated.staticProfile.background)
    }

    @Test
    fun dynamicStateUsesItsMetaTimestamp() {
        val character = Character(
            id = "c1",
            dynamicState = DynamicState(currentGoal = "明天完成"),
            dynamicStateMeta = mapOf(
                "currentGoal" to DynamicStateMeta(updatedAt = "2026-10-01T18:00:00+08:00"),
            ),
        )

        val migrated = RelativeTimeMigration.convert(character, now)

        assertEquals("2026-10-02完成", migrated.dynamicState.currentGoal)
    }

    @Test
    fun groupMemberMemoryAndStaticFieldsAreMigrated() {
        val member = GroupMember(
            id = "m1",
            name = "甲",
            shortTerm = listOf(
                ShortTermMemory(content = "昨天见面", createdAt = "2026-10-01T18:00:00+08:00"),
            ),
            staticProfile = StaticProfile(age = "三天后生日"),
            staticFieldMeta = mapOf(
                "age" to buildJsonObject { put("updatedAt", "2026-10-01T18:00:00+08:00") },
            ),
        )
        val character = Character(id = "g1", isGroup = true, members = listOf(member))

        val migrated = RelativeTimeMigration.convert(character, now)

        val migratedMember = migrated.members.single()
        assertEquals("2026-09-30见面", migratedMember.shortTerm.single().content)
        assertEquals("2026-10-04生日", migratedMember.staticProfile.age)
    }

    @Test
    fun alreadyMigratedCharacterIsUnchanged() {
        val character = Character(id = "c1", timeParseVersion = RelativeTimeMigration.TIME_PARSE_VERSION)

        val migrated = RelativeTimeMigration.convert(character, now)

        assertTrue(migrated === character)
    }
}
