package com.deeptalking.domain.agent

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.StaticProfile
import com.deeptalking.domain.agent.prompts.PersonaInputs
import com.deeptalking.domain.agent.prompts.buildRoleContext
import com.deeptalking.domain.agent.prompts.buildSystemPrompt
import com.deeptalking.domain.agent.prompts.cardIdentityLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色卡是**视图**：静态字段（10 个，一个不多一个不少）按章渲染成 markdown，
 * 章节顺序固定、空字段整章消失，不再出现 `性别: 值` 那种扁平标签。
 *
 * 这些断言守的是"卡的结构"而不是具体措辞——将来调章节名不会大面积红，
 * 但"退回扁平列表""空字段占位""群组丢成员""超长不裁剪"都会立刻被抓到。
 */
class CharacterCardTest {

    private val fullProfile = StaticProfile(
        gender = "女",
        age = "24",
        race = "人类",
        appearance = "黑色短发，常穿深灰衬衫。",
        personality = "想事情慢，不轻易表态。",
        fears = "怕被当成没脾气的人。",
        background = "两年前从出版社辞职，盘下这间旧书店。",
        speakingStyle = "说话慢，句尾常带“吧”。",
        language = "普通话，偶尔冒一句上海话。",
        userAddress = "小满",
    )

    private val lin = Character(id = "c1", name = "林夏", emoji = "🍃", staticProfile = fullProfile)

    @Test
    fun theCardUsesChaptersInsteadOfFlatLabels() {
        val card = buildRoleContext(lin, staticOnly = true)
        assertTrue(card.startsWith("# 角色卡：林夏 🍃"))
        listOf("## 你是谁", "## 性格", "## 怕什么", "## 怎么说", "## 你的样子").forEach {
            assertTrue("缺章节 $it\n$card", card.contains(it))
        }
        assertTrue(card.contains("女，24 岁，人类。"))
        assertTrue(card.contains("声音底色：说话慢，句尾常带“吧”。"))
        assertTrue(card.contains("语言/方言：普通话，偶尔冒一句上海话。"))
        assertTrue(card.contains("称呼你：小满"))
        // 旧的扁平格式必须彻底消失，否则等于两套写法并存。
        listOf("性别: ", "年龄: ", "种族: ", "外貌特征: ", "声音底色: ", "对用户的称呼: ").forEach {
            assertFalse("仍在输出旧扁平标签 $it", card.contains(it))
        }
    }

    @Test
    fun chaptersFollowTheDocumentedOrder() {
        val card = buildRoleContext(lin, staticOnly = true)
        val order = listOf("# 角色卡", "## 你是谁", "## 性格", "## 怕什么", "## 怎么说", "## 你的样子")
            .map { card.indexOf(it) }
        assertEquals("章节顺序被打乱：$order", order.sorted(), order)
    }

    @Test
    fun aFieldThatIsEmptyDropsItsWholeChapter() {
        val sparse = Character(id = "c2", name = "阿九", staticProfile = StaticProfile(personality = "话少。"))
        val card = buildRoleContext(sparse, staticOnly = true)
        assertTrue(card.contains("## 性格\n话少。"))
        listOf("## 你是谁", "## 怕什么", "## 怎么说", "## 你的样子").forEach {
            assertFalse("空字段不该占章节 $it\n$card", card.contains(it))
        }
        assertTrue(card.startsWith("# 角色卡：阿九"))
    }

    @Test
    fun theIdentityLineOnlyUsesWhatIsSet() {
        assertEquals("女，24 岁，人类。", cardIdentityLine(fullProfile))
        assertEquals("24 岁。", cardIdentityLine(StaticProfile(age = "24")))
        // 非纯数字年龄不补“岁”，原样保留（“二十出头”这类写法是合法输入）。
        assertEquals("二十出头。", cardIdentityLine(StaticProfile(age = "二十出头")))
        assertEquals("", cardIdentityLine(StaticProfile()))
    }

    @Test
    fun theGroupCardHasItsOwnChaptersAndOneSectionPerMember() {
        val group = Character(
            id = "g1",
            name = "合租小屋",
            emoji = "🏠",
            isGroup = true,
            description = "三个人合租的房子，饭会一起做。",
            interactionRules = "不互相叫醒，不说教。",
            members = listOf(
                GroupMember(
                    id = "m1",
                    name = "阿岚",
                    roleInGroup = "室友",
                    staticProfile = StaticProfile(
                        personality = "嘴上没把门的。",
                        speakingStyle = "语速快，爱用反问。",
                    ),
                ),
                GroupMember(id = "m2", name = "小舟"),
            ),
        )
        val card = buildRoleContext(group, staticOnly = true)
        assertTrue(card.startsWith("# 群组卡：合租小屋 🏠"))
        assertTrue(card.contains("## 这个群"))
        assertTrue(card.contains("三个人合租的房子，饭会一起做。"))
        assertTrue(card.contains("## 群里的规矩"))
        assertTrue(card.contains("不互相叫醒，不说教。"))
        assertTrue(card.contains("## 成员"))
        assertTrue(card.contains("### 阿岚（室友）"))
        assertTrue(card.contains("- 性格：嘴上没把门的。"))
        assertTrue(card.contains("- 怎么说：声音底色：语速快，爱用反问。"))
        // 静态卡不带场景：场景是动态字段，只在每轮上下文的非 staticOnly 版本里出现。
        assertFalse(card.contains("场景："))
    }

    @Test
    fun anOversizedCardIsTrimmedToTheCap() {
        val huge = Character(
            id = "c3",
            name = "长卡",
            staticProfile = StaticProfile(
                background = "背".repeat(5_000),
                personality = "性".repeat(2_000),
            ),
        )
        val card = buildRoleContext(huge, staticOnly = true)
        assertTrue(
            "角色卡超过上限：${card.length} > ${AppLimits.Prompt.ROLE_CHARS}",
            card.length <= AppLimits.Prompt.ROLE_CHARS + 16,
        )
    }

    @Test
    fun theSystemPromptCarriesTheCardAfterTheRulesAndSaysItIsDataNotAFormat() {
        val prompt = buildSystemPrompt(PersonaInputs(character = lin))
        val rulesAt = prompt.indexOf("【交互规则】")
        val cardAt = prompt.indexOf("【角色卡】")
        assertTrue(rulesAt in 0 until cardAt)
        assertTrue(prompt.contains("不是输出格式"))
        assertTrue(prompt.substring(cardAt).contains("## 怎么说"))
    }
}
