package com.deeptalking.feature.characters

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.StaticProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CharacterParityTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val base: ZonedDateTime = ZonedDateTime.of(2026, 10, 5, 12, 0, 0, 0, zone)

    @Test
    fun parsesFullGroupDraft() {
        val raw = """
            {
              "entityType": "group",
              "groupInfo": {"name": "小队", "avatar": "👥", "description": "一起冒险", "scene": "森林营地", "interactionRules": "轮流行动"},
              "lorebook": [
                {"name": "赤月王国", "keywords": ["赤月"], "content": "古老王国", "alwaysActive": false},
                {"name": "常驻设定", "keywords": [], "content": "世界是魔法世界", "alwaysActive": false}
              ],
              "members": [
                {"name": "阿黎", "avatar": "🐱", "gender": "女", "age": "19", "race": "人类", "appearance": "银发",
                 "personality": "冷静", "values": "守护", "fears": "黑暗", "background": "孤儿", "keyEvents": "觉醒",
                 "speakingStyle": "简短", "language": "通用语", "userAddress": "队长", "roleInGroup": "领队",
                 "dynamicState": {"currentSituation": "露营", "currentMood": "平静"}},
                {"name": "阿岩", "personality": "热血", "roleInGroup": "前锋"}
              ]
            }
        """.trimIndent()

        val draft = CharacterParity.parseGeneratedDraft(raw)!!
        assertTrue(draft.isGroup)
        assertEquals("小队", draft.name)
        assertEquals("👥", draft.emoji)
        assertEquals("一起冒险", draft.description)
        assertEquals("森林营地", draft.scene)
        assertEquals("轮流行动", draft.interactionRules)
        assertEquals(2, draft.members.size)

        val first = draft.members[0]
        assertEquals("阿黎", first.name)
        assertEquals("🐱", first.emoji)
        assertEquals("女", first.staticProfile.gender)
        assertEquals("19", first.staticProfile.age)
        assertEquals("领队", first.roleInGroup)
        assertEquals("露营", first.dynamicState.currentSituation)
        assertEquals("平静", first.dynamicState.currentMood)

        assertEquals(2, draft.lorebook.size)
        assertEquals(LorebookOrigin.Model, draft.lorebook[0].origin)
        assertFalse(draft.lorebook[0].alwaysActive)
        assertEquals(listOf("赤月"), draft.lorebook[0].keywords)
        assertTrue(draft.lorebook[1].alwaysActive)
    }

    @Test
    fun parsesFullCharacterDraft() {
        val raw = """
            {"entityType":"character","name":"林晚","avatar":"🌙","gender":"女","personality":"温柔但固执",
             "background":"城里的医生","dynamicState":{"currentMood":"疲惫"},
             "lorebook":[{"name":"港城","keywords":["港城"],"content":"港口城市"}]}
        """.trimIndent()

        val draft = CharacterParity.parseGeneratedDraft(raw)!!
        assertFalse(draft.isGroup)
        assertEquals("林晚", draft.name)
        assertEquals("🌙", draft.emoji)
        assertEquals("温柔但固执", draft.staticProfile.personality)
        assertEquals("城里的医生", draft.staticProfile.background)
        assertEquals("疲惫", draft.dynamicState.currentMood)
        assertEquals(1, draft.lorebook.size)
        assertEquals("港城", draft.lorebook[0].name)
    }

    @Test
    fun parsesGroupMembersJsonWithFullFields() {
        val raw = """{"members":[{"name":"阿黎","avatar":"🐱","gender":"女","roleInGroup":"领队",
            "dynamicState":{"currentGoal":"找到出口"}}]}"""
        val members = CharacterParity.parseGroupMembersJson(raw)
        assertEquals(1, members.size)
        assertEquals("女", members[0].staticProfile.gender)
        assertEquals("领队", members[0].roleInGroup)
        assertEquals("找到出口", members[0].dynamicState.currentGoal)
    }

    @Test
    fun parsesGroupMembersTextLines() {
        val members = CharacterParity.parseGroupMembersText("阿黎｜冷静\n阿岩｜热血")
        assertEquals(2, members.size)
        assertEquals("阿黎", members[0].name)
        assertEquals("冷静", members[0].staticProfile.personality)
    }

    @Test
    fun avatarDescriptionIncludesRoleInGroup() {
        val member = GroupMember(
            name = "阿黎",
            staticProfile = StaticProfile(appearance = "银发", personality = "冷静"),
            roleInGroup = "领队",
        )
        assertEquals("阿黎，银发，冷静，领队", CharacterParity.buildAvatarDescription(member, null))
        assertEquals("林晚，银发，温柔", CharacterParity.buildAvatarDescription(
            Character(id = "c", name = "林晚", staticProfile = StaticProfile(appearance = "银发", personality = "温柔")),
            null,
        ))
        assertEquals("一个神秘角色", CharacterParity.buildAvatarDescription(Character(id = "e"), null))
    }

    @Test
    fun applyMemberFillOnlyFillsEmptyFields() {
        val member = GroupMember(
            name = "阿黎",
            staticProfile = StaticProfile(personality = "沉稳"),
        )
        val raw = """{"gender":"女","userAddress":" 明明 ","roleInGroup":"医疗","avatar":"🐱","personality":"开朗",
            "dynamicState":{"currentMood":"开心","currentLocation":"明天在诊所"}}"""
        val filled = CharacterParity.applyMemberFillPayload(member, raw, base, zone)
        assertEquals("沉稳", filled.staticProfile.personality)
        assertEquals("女", filled.staticProfile.gender)
        assertEquals("明明", filled.staticProfile.userAddress)
        assertEquals("医疗", filled.roleInGroup)
        assertEquals("🐱", filled.emoji)
        assertEquals("开心", filled.dynamicState.currentMood)
        assertEquals("2026-10-06在诊所", filled.dynamicState.currentLocation)
    }

    @Test
    fun normalizeEditedDraftAppliesTimeAndAddressRules() {
        val character = Character(
            id = "c",
            name = "林晚",
            staticProfile = StaticProfile(userAddress = " 明明 "),
            dynamicState = DynamicState(currentLocation = "明天晚上"),
        )
        val normalized = CharacterParity.normalizeEditedDraft(character, null, base, zone)
        assertEquals("明明", normalized.staticProfile.userAddress)
        assertEquals("2026-10-06 晚上", normalized.dynamicState.currentLocation)
    }

    @Test
    fun newLorebookEntryDefaultsToAlwaysActive() {
        val entry = CharacterParity.newUserLorebookEntry()
        assertTrue(entry.alwaysActive)
        assertEquals(LorebookOrigin.User, entry.origin)
        assertTrue(entry.enabled)
    }

    @Test
    fun keywordsRuleMatchesLegacy() {
        assertTrue(CharacterParity.keywordsToAlwaysActive(current = false, keywords = emptyList()))
        assertFalse(CharacterParity.keywordsToAlwaysActive(current = false, keywords = listOf("赤月")))
        assertTrue(CharacterParity.keywordsToAlwaysActive(current = true, keywords = listOf("赤月")))
    }

    @Test
    fun splitKeywordsHonoursCaps() {
        assertEquals(listOf("a", "b", "c", "d", "e"), CharacterParity.splitLorebookKeywords("a,b，c、d\ne"))
        assertEquals(20, CharacterParity.splitLorebookKeywords((1..25).joinToString(",")).size)
        assertEquals(60, CharacterParity.capLorebookName("x".repeat(100)).length)
        assertEquals(2000, CharacterParity.capLorebookContent("x".repeat(3000)).length)
    }

    @Test
    fun promptsCarryFullSchema() {
        val characterPrompt = CharacterParity.buildQuickGeneratePrompt("一个医生", isGroup = false)
        assertTrue(characterPrompt.contains("dynamicState"))
        assertTrue(characterPrompt.contains("lorebook"))
        assertTrue(characterPrompt.contains("userAddress"))

        val groupPrompt = CharacterParity.buildQuickGeneratePrompt("一支小队", isGroup = true)
        assertTrue(groupPrompt.contains("members"))
        assertTrue(groupPrompt.contains("groupInfo"))
        assertTrue(groupPrompt.contains("roleInGroup"))
    }
}
