package com.deeptalking.domain.agent.background

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleGuardTest {

    private val character = Character(id = "c1", name = "小雨")

    @Test
    fun detectStyleViolationsCatchesCliche() {
        val hits = detectStyleViolations("他勾起嘴角，转身离开。", character, emptyList())
        assertTrue(hits.contains("cliche"))
    }

    @Test
    fun detectStyleViolationsCatchesMetaTalk() {
        val hits = detectStyleViolations("好的，我改成这样说话。", character, emptyList())
        assertTrue(hits.contains("metaTalk"))
    }

    @Test
    fun detectStyleViolationsCatchesSpeakingForUser() {
        val styled = Character(id = "c1", name = "小雨", staticProfile = StaticProfile(userAddress = "明明"))
        val hits = detectStyleViolations("明明：「那我们先走吧」", styled, emptyList())
        assertTrue(hits.contains("speaksForUser"))
    }

    @Test
    fun detectStyleViolationsCatchesReusedImagery() {
        val hits = detectStyleViolations(
            "雨声顺着窗缝涌进来，他正发着呆。",
            character,
            listOf("她把窗户轻轻推开，雨声顺着窗缝涌进来。"),
        )
        assertTrue(hits.contains("reusedImagery"))
    }

    @Test
    fun detectStyleViolationsCatchesRecitedLore() {
        val withLore = character.copy(
            lorebook = listOf(LorebookEntry(name = "赤月王国", content = "位于大陆西侧，终年飘雪。")),
        )
        val hits = detectStyleViolations("赤月王国位于大陆西侧，终年飘雪。", withLore, emptyList())
        assertTrue(hits.contains("recitedLore"))
    }

    @Test
    fun detectStyleViolationsIsDeduplicated() {
        val hits = detectStyleViolations("好的，我改成这样。她勾起嘴角笑了。", character, emptyList())
        assertTrue(hits.count { it == "metaTalk" } == 1)
        assertTrue(hits.count { it == "cliche" } == 1)
    }

    @Test
    fun detectQuickReplyIssuesCatchesTooLongReply() {
        val tooLong = "测试文本".repeat(20)
        val hits = detectQuickReplyIssues(listOf(tooLong, "嗯"), character, "")
        assertTrue(hits.contains("tooLong"))
    }

    @Test
    fun detectQuickReplyIssuesCatchesActionParen() {
        val hits = detectQuickReplyIssues(listOf("（轻轻叹气）", "嗯"), character, "")
        assertTrue(hits.contains("action"))
    }

    @Test
    fun detectQuickReplyIssuesCatchesMissingAndPlaceholder() {
        assertTrue(detectQuickReplyIssues(listOf("就一句"), character, "随便说说").contains("missing"))
        assertTrue(detectQuickReplyIssues(emptyList(), character, "随便说说").contains("missing"))
        assertTrue(detectQuickReplyIssues(listOf("短句一", "短句二"), character, "随便说说").contains("placeholder"))
    }

    @Test
    fun detectQuickReplyIssuesCatchesMirroredAndCharacterName() {
        val reply = "（把外套搭在椅背上）外面风大，我绕了两条街才找到这家店。"
        assertTrue(
            detectQuickReplyIssues(listOf("我绕了两条街才找到这家店。", "随便聊聊。"), character, reply).contains("mirrored"),
        )
        val named = Character(id = "c2", name = "阿甲")
        assertTrue(
            detectQuickReplyIssues(listOf("阿甲：你先坐下。", "随便聊聊。"), named, reply).contains("characterName"),
        )
    }

    @Test
    fun sharesDistinctivePhraseDetectsReuse() {
        assertTrue(
            sharesDistinctivePhrase(
                "春风吹过湖面泛起层层涟漪",
                listOf("那片湖面泛起层层涟漪很美"),
            ),
        )
    }

    @Test
    fun sharesDistinctivePhraseRejectsUnrelated() {
        assertFalse(
            sharesDistinctivePhrase(
                "今天天气真不错适合出门散步",
                listOf("他喜欢吃苹果喝牛奶"),
            ),
        )
    }

    @Test
    fun buildStyleReviewReportsStoredViolations() {
        val messages = (1..3).map { index ->
            ChatMessage(
                id = "m$index",
                role = Role.Assistant,
                content = "回复$index",
                styleViolations = if (index == 2) listOf("cliche") else emptyList(),
            )
        }
        val review = buildStyleReview(character.copy(instant = messages))
        assertTrue(review.contains("陈词滥调"))
    }

    @Test
    fun buildStyleAnchorSkipsBlankStyle() {
        assertTrue(buildStyleAnchor(character).isEmpty())
        assertTrue(buildStyleAnchor(null).isEmpty())
    }

    @Test
    fun buildStyleAnchorExtractsSamplesFromFullStyle() {
        val longHead = "说话风格描述".repeat(80)
        val styled = Character(
            id = "c1",
            name = "小雨",
            staticProfile = StaticProfile(speakingStyle = "$longHead；示例：「阿这」 / 「懂了懂了」 / 「行吧行吧」"),
        )
        val anchor = buildStyleAnchor(styled)
        assertTrue(anchor.contains("声音契约"))
        assertTrue(anchor.contains("阿这"))
        assertTrue(anchor.contains("行吧行吧"))
    }

    @Test
    fun buildStyleAnchorRequiresSignatureMarkers() {
        val styled = Character(
            id = "c1",
            name = "小雨",
            staticProfile = StaticProfile(speakingStyle = "慵懒；示例：「困了」 / 「再说吧」"),
        )
        val anchor = buildStyleAnchor(styled)
        assertTrue(anchor.contains("至少自然带出"))
        assertTrue(anchor.contains("默认 AI 助手腔"))
    }

    @Test
    fun buildGroupVoiceContractOneBlockPerStyledMember() {
        val group = Character(
            id = "g1",
            name = "茶话会",
            isGroup = true,
            members = listOf(
                GroupMember(id = "m1", name = "阿岩", staticProfile = StaticProfile(speakingStyle = "热血；示例：「冲啊」 / 「走着」")),
                GroupMember(id = "m2", name = "林晚", staticProfile = StaticProfile(speakingStyle = "")),
                GroupMember(id = "m3", name = "老周", staticProfile = StaticProfile(speakingStyle = "慢悠悠；示例：「急啥」 / 「先坐」")),
            ),
        )
        val contract = buildGroupVoiceContract(group)
        assertTrue(contract.contains("群聊声音锁定"))
        assertTrue(contract.contains("成员声音·阿岩"))
        assertTrue(contract.contains("成员声音·老周"))
        assertFalse(contract.contains("成员声音·林晚"))
    }

    @Test
    fun buildGroupVoiceContractEmptyWhenNoStyledMemberOrNotGroup() {
        val group = Character(
            id = "g1",
            name = "茶话会",
            isGroup = true,
            members = listOf(GroupMember(id = "m1", name = "阿岩")),
        )
        assertTrue(buildGroupVoiceContract(group).isEmpty())
        assertTrue(buildGroupVoiceContract(character).isEmpty())
    }
}
