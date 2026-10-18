package com.deeptalking.feature.characters

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.DynamicState
import com.deeptalking.core.model.DynamicStateMeta
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
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
                 "personality": "冷静", "fears": "黑暗", "background": "孤儿",
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
    fun generatedLorebookDropsContentOverLimitAndKeepsTheRest() {
        val limit = AppLimits.Lorebook.CONTENT_CHARS
        val overLong = "长".repeat(limit + 1)
        val exact = "字".repeat(limit)
        val raw = """
            {"entityType":"character","name":"林晚",
             "lorebook":[
               {"name":"超长条目","keywords":["超长"],"content":"$overLong"},
               {"name":"恰好条目","keywords":["恰好"],"content":"$exact"},
               {"name":"正常条目","keywords":["正常"],"content":"简短设定"}
             ]}
        """.trimIndent()

        val draft = CharacterParity.parseGeneratedDraft(raw)!!
        // 超过 400 字的条目整条丢弃（不截断），恰好 400 字及以下的条目保留。
        assertEquals(listOf("恰好条目", "正常条目"), draft.lorebook.map { it.name })
        assertEquals(exact, draft.lorebook[0].content)
        assertEquals("简短设定", draft.lorebook[1].content)
        assertTrue(draft.lorebook.all { it.content.length <= limit })
    }

    @Test
    fun noKeywordDraftEntryStillAutoActivatesAsLegacy() {
        // keywordsToAlwaysActive 的老逻辑：关键词清空时自动置为常驻（保留，不改）；
        // parseGeneratedLorebook 对无关键词的模型条目同样会自动常驻（上限内），此处仅断言现状。
        val draft = CharacterParity.parseGeneratedDraft(
            """{"entityType":"character","name":"林晚",
               "lorebook":[{"name":"常驻设定","keywords":[],"content":"世界是魔法世界","alwaysActive":false}]}""",
        )!!
        assertEquals(1, draft.lorebook.size)
        assertTrue(draft.lorebook[0].alwaysActive)
        assertTrue(CharacterParity.keywordsToAlwaysActive(current = false, keywords = emptyList()))
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
    fun normalizeEditedDraftClearsDynamicStateMeta() {
        val character = Character(
            id = "c",
            name = "林晚",
            dynamicState = DynamicState(currentLocation = "明天"),
            dynamicStateMeta = mapOf(
                "currentLocation" to DynamicStateMeta("2026-10-01T00:00:00Z"),
                "currentMood" to DynamicStateMeta("2026-10-01T00:00:00Z"),
            ),
        )
        val normalized = CharacterParity.normalizeEditedDraft(character, null, base, zone)
        assertTrue(normalized.dynamicStateMeta.isEmpty())
    }

    @Test
    fun normalizeGroupEditedDraftUsesSharedDynamicAndClearsSharedMeta() {
        val group = Character(
            id = "g",
            isGroup = true,
            groupSharedDynamic = DynamicState(currentSituation = "后天在营地"),
            dynamicStateMeta = mapOf(
                "currentLocation" to DynamicStateMeta("x"),
                "currentMood" to DynamicStateMeta("y"),
            ),
            members = listOf(GroupMember(id = "m", name = "阿黎")),
        )
        val normalized = CharacterParity.normalizeEditedDraft(group, null, base, zone)
        assertEquals("2026-10-07在营地", normalized.groupSharedDynamic.currentSituation)
        assertEquals(setOf("currentMood"), normalized.dynamicStateMeta.keys)
    }

    @Test
    fun normalizeMemberEditedDraftClearsMemberDynamicStateMeta() {
        val member = GroupMember(
            id = "m",
            name = "阿黎",
            dynamicState = DynamicState(currentMood = "开心"),
            dynamicStateMeta = mapOf("currentMood" to DynamicStateMeta("x")),
        )
        val group = Character(id = "g", isGroup = true, members = listOf(member))
        val normalized = CharacterParity.normalizeEditedDraft(group, 0, base, zone)
        assertTrue(normalized.members[0].dynamicStateMeta.isEmpty())
    }

    // ------------------------------------------------ 用户手写通道（编辑器保存）

    @Test
    fun userEditedDraftKeepsContrastSentencesAndLongText() {
        val longBackground = "他走过很多地方。".repeat(40)
        val character = Character(
            id = "c",
            name = "林晚",
            staticProfile = StaticProfile(
                personality = "他不是冷漠，而是懒得解释。",
                background = longBackground,
            ),
        )

        val result = CharacterParity.normalizeUserEditedDraft(character, base = base, zone = zone)

        assertEquals("他不是冷漠，而是懒得解释。", result.character.staticProfile.personality)
        assertEquals(longBackground, result.character.staticProfile.background)
        assertTrue(result.notices.any { it.fieldLabel == "性格特征" && it.reason.contains("对比句") })
    }

    @Test
    fun buildChannelStillRejectsContrastAndClamps() {
        val character = Character(
            id = "c",
            name = "林晚",
            staticProfile = StaticProfile(
                personality = "他不是冷漠，而是懒得解释。",
                background = "他走过很多地方。".repeat(40),
            ),
        )

        val normalized = CharacterParity.normalizeEditedDraft(character, null, base, zone)

        assertEquals("", normalized.staticProfile.personality)
        assertTrue(normalized.staticProfile.background.length <= AppLimits.StaticProfile.BACKGROUND_CHARS)
    }

    @Test
    fun dedupeGeneratedLorebookPrefersLaterSameNameModelEntriesAndKeepsUserEntries() {
        val first = LorebookEntry(
            name = "赤月王国",
            content = "古老王国",
            keywords = listOf("赤月"),
            alwaysActive = true,
            origin = LorebookOrigin.Model,
        )
        val duplicate = LorebookEntry(
            name = "赤月王国 ",
            content = "银月商会旧址",
            keywords = listOf("王都"),
            alwaysActive = false,
            origin = LorebookOrigin.Model,
        )
        val user = LorebookEntry(name = "赤月王国", content = "用户手写", origin = LorebookOrigin.User)
        val deduped = CharacterParity.dedupeGeneratedLorebook(listOf(first, duplicate, user))
        assertEquals(2, deduped.size)
        val merged = deduped.first { it.origin == LorebookOrigin.Model }
        // 同名 AI 条目取后者为准：内容不再拼接，替换为后出现条目的完整内容。
        assertEquals("银月商会旧址", merged.content)
        // alwaysActive 也取后者（false），不再做 OR。
        assertFalse(merged.alwaysActive)
        assertEquals(listOf("王都", "赤月"), merged.keywords)
        assertTrue(deduped.any { it.origin == LorebookOrigin.User })
    }

    @Test
    fun dedupeGeneratedLorebookLaterBlankContentFallsBackToEarlier() {
        val first = LorebookEntry(
            name = "港城",
            content = "港口城市",
            keywords = listOf("港城"),
            origin = LorebookOrigin.Model,
        )
        val later = LorebookEntry(
            name = "港城",
            content = "",
            keywords = listOf("码头"),
            alwaysActive = true,
            origin = LorebookOrigin.Model,
        )
        val deduped = CharacterParity.dedupeGeneratedLorebook(listOf(first, later))
        assertEquals(1, deduped.size)
        // 后者 content 为空时回退到前者，避免整条内容丢失。
        assertEquals("港口城市", deduped[0].content)
        assertTrue(deduped[0].alwaysActive)
        assertEquals(listOf("码头", "港城"), deduped[0].keywords)
    }

    @Test
    fun newLorebookEntryDefaultsToAlwaysActiveAndAiMaintained() {
        // A fresh entry starts AI-maintained (the editor's owner chip switches it to 人工).
        val entry = CharacterParity.newLorebookEntry()
        assertTrue(entry.alwaysActive)
        assertEquals(LorebookOrigin.Model, entry.origin)
        assertTrue(entry.enabled)
        assertTrue(entry.createdAt!!.isNotBlank())
    }

    @Test
    fun lorebookListEditsAreLocalAndAdditive() {
        // The editor mirrors every edit into its draft, so the ops must be pure and must
        // not disturb the other entries (two quick edits used to undo each other).
        val a = LorebookEntry(id = "a", name = "甲", content = "甲内容")
        val b = LorebookEntry(id = "b", name = "乙", content = "乙内容")
        val base = listOf(a, b)

        val added = CharacterParity.addLorebookEntry(
            Character(id = "c1", lorebook = base),
            LorebookEntry(id = "c", name = "丙", content = "丙内容"),
        )
        assertEquals(listOf("a", "b", "c"), added.lorebook.map { it.id })
        assertEquals(base, listOf(a, b))

        val removed = CharacterParity.removeLorebookEntry(base, "a")
        assertEquals(listOf("b"), removed.map { it.id })

        val updated = CharacterParity.updateLorebookEntry(base, b.copy(content = "乙改"))
        assertEquals(listOf("a", "b"), updated.map { it.id })
        assertEquals("甲内容", updated[0].content)
        assertEquals("乙改", updated[1].content)
        // 两次连续编辑互不覆盖：第二次基于第一次的结果。
        val second = CharacterParity.updateLorebookEntry(updated, a.copy(content = "甲改"))
        assertEquals("甲改", second[0].content)
        assertEquals("乙改", second[1].content)
    }

    @Test
    fun addingBeyondTheEntryCapArchivesTheOldest() {
        val full = (1..com.deeptalking.core.common.AppLimits.Lorebook.ENTRIES).map {
            LorebookEntry(id = "e$it", name = "n$it", content = "c", createdAt = "2026-01-01T00:00:00Z")
        }
        val character = Character(id = "c1", lorebook = full)
        val next = CharacterParity.addLorebookEntry(character, LorebookEntry(id = "extra", name = "x", content = "c"))
        // Capacity-driven: the new entry lands and the oldest live entry is archived.
        assertEquals(com.deeptalking.core.common.AppLimits.Lorebook.ENTRIES, next.lorebook.size)
        assertTrue(next.lorebook.any { it.id == "extra" })
        assertEquals(listOf("e1"), next.lorebookArchive.map { it.id })
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
        assertEquals(
            com.deeptalking.core.common.AppLimits.Lorebook.KEYWORDS_PER_ENTRY,
            CharacterParity.splitLorebookKeywords((1..25).joinToString(",")).size,
        )
        assertEquals(60, CharacterParity.capLorebookName("x".repeat(100)).length)
        assertEquals(
            com.deeptalking.core.common.AppLimits.Lorebook.CONTENT_CHARS,
            CharacterParity.capLorebookContent("x".repeat(3000)).length,
        )
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
