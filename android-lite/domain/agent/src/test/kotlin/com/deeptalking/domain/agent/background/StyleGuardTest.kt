package com.deeptalking.domain.agent.background

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.Role
import com.deeptalking.core.model.StaticProfile
import org.junit.Assert.assertEquals
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
    fun detectStyleViolationsCatchesPairedHalfwidthQuotes() {
        val hits = detectStyleViolations("他说\"早点睡\"，我没接话。", character, emptyList())
        assertTrue(hits.contains("halfwidthQuote"))
    }

    @Test
    fun detectStyleViolationsIgnoresASingleHalfwidthQuote() {
        // 单个不配对的引号（英文缩写、敲了一半的引号）不算，否则会天天误报。
        assertFalse(
            detectStyleViolations("他敲了敲桌子，只说了句\"好，我这就去。", character, emptyList())
                .contains("halfwidthQuote"),
        )
    }

    @Test
    fun detectStyleViolationsCatchesThirdPersonSelf() {
        assertTrue(
            detectStyleViolations("小雨把外套搭在椅背上，没说话。", character, emptyList())
                .contains("thirdPersonSelf"),
        )
        // 名字在引号里出现过、同时也在括号外出现过一次 → 仍命中（判的是括号外那次）。
        assertTrue(
            detectStyleViolations("他说「小雨」，小雨没接话。", character, emptyList())
                .contains("thirdPersonSelf"),
        )
    }

    @Test
    fun detectStyleViolationsIgnoresSelfNameInsideQuotesOrBrackets() {
        assertFalse(
            detectStyleViolations("他说「小雨，你先坐」。", character, emptyList()).contains("thirdPersonSelf"),
        )
        assertFalse(
            detectStyleViolations("（小雨把外套搭在椅背上）我先走了。", character, emptyList())
                .contains("thirdPersonSelf"),
        )
        assertFalse(
            detectStyleViolations("纸条上写着“小雨别熬夜”。", character, emptyList()).contains("thirdPersonSelf"),
        )
    }

    @Test
    fun detectStyleViolationsSkipsThirdPersonSelfForGroups() {
        // 群里本来就要写"成员名：内容"，这条只在单角色卡上判。
        val group = Character(id = "g1", name = "小雨", isGroup = true)
        assertFalse(
            detectStyleViolations("小雨：我先走了。", group, emptyList()).contains("thirdPersonSelf"),
        )
    }

    @Test
    fun detectStyleViolationsLeavesACleanReplyAlone() {
        assertTrue(detectStyleViolations("今天风有点大，我们进去坐会儿吧。", character, emptyList()).isEmpty())
    }

    @Test
    fun newStyleViolationLabelsTellHowToWrite() {
        // 标签文案是给模型/用户看的"该怎么写"，不是"哪里错了"。
        assertTrue(STYLE_ADVISORY_LABELS.getValue("halfwidthQuote").contains("「」"))
        assertTrue(STYLE_ADVISORY_LABELS.getValue("thirdPersonSelf").contains("第一人称"))
    }

    @Test
    fun advisoryFormatLabelsAreRecordedButNeverTriggerAStyleCritique() {
        // 观察项照样记进 styleViolations（统计用）……
        val recorded = detectStyleViolations("小雨把外套搭在椅背上，没说话。", character, emptyList())
        assertTrue(recorded.contains("thirdPersonSelf"))
        // ……但送去文风修订的只有"可执行"的问题，否则会白打一次模型调用。
        assertTrue(actionableStyleViolations(recorded).isEmpty())
        assertTrue(
            actionableStyleViolations(
                listOf("halfwidthQuote", "cliche", "thirdPersonSelf", "metaTalk", "bogus"),
            ) == listOf("cliche", "metaTalk"),
        )
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
    fun detectQuickReplyIssuesMirrorsTheCharactersRecentReplies() {
        val history = character.copy(
            instant = listOf(
                ChatMessage(id = "u1", role = Role.User, content = "你到了吗？"),
                ChatMessage(id = "a1", role = Role.Assistant, content = "外面风大，我绕了两条街才找到这家店。"),
            ),
        )
        val previousReplies = recentReplyTexts(history, QUICK_REPLY_GUARD.mirrorLookback)
        // 套用上一轮角色的台词：本轮回复里没有这句话，只有历史里有 → 现在也命中。
        assertTrue(
            detectQuickReplyIssues(
                listOf("我绕了两条街才找到这家店。", "随便聊聊。"),
                history,
                "（把外套搭在椅背上）坐吧，风是挺大。",
                previousReplies,
            ).contains("mirrored"),
        )
        // 与角色历史台词无关的正常用户短句 → 不命中。
        assertFalse(
            detectQuickReplyIssues(
                listOf("那我们进去坐会儿吧。", "顺便看看菜单。"),
                history,
                "（把外套搭在椅背上）坐吧，风是挺大。",
                previousReplies,
            ).contains("mirrored"),
        )
        // 回归：不传历史时只看本轮回复，与旧行为一致。
        assertFalse(
            detectQuickReplyIssues(
                listOf("我绕了两条街才找到这家店。", "随便聊聊。"),
                history,
                "（把外套搭在椅背上）坐吧，风是挺大。",
            ).contains("mirrored"),
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
    fun buildStyleAnchorDoesNotWrapSamplesInQuotes() {
        val styled = Character(
            id = "c1",
            name = "小雨",
            staticProfile = StaticProfile(speakingStyle = "慵懒；示例：「困了」 / 「再说吧」"),
        )
        val anchor = buildStyleAnchor(styled)
        assertTrue(anchor.contains("困了"))
        assertFalse(anchor.contains("「"))
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

    // ------------------------------------------------ 引用/专有名词保护（文风修订）

    @Test
    fun quotedSpansPicksEveryQuoteFlavour() {
        val text = "他发来「月亮代表我的心」，我回《夜曲》，还抄了句“晚安”，外加 'ok'。"
        assertEquals(
            listOf("「月亮代表我的心」", "《夜曲》", "“晚安”", "'ok'"),
            quotedSpans(text),
        )
        assertTrue(quotedSpans(null).isEmpty())
    }

    @Test
    fun critiqueIsRejectedWhenAQuotedPassageChanges() {
        val original = "她说「你别熬夜」，我没接话。"
        // 引用被改写 → 整体回退
        assertFalse(keepsProtectedContent(original, "她说「你不要熬夜」，我没接话。", emptyList()))
        // 引用被删掉 → 回退
        assertFalse(keepsProtectedContent(original, "她说我要早点睡，我没接话。", emptyList()))
        // 引用原样保留（其余文风可改）→ 通过
        assertTrue(keepsProtectedContent(original, "她只说了句「你别熬夜」。我没接话。", emptyList()))
    }

    @Test
    fun critiqueIsRejectedWhenAProtectedNameDisappears() {
        val original = "小雨把《夜曲》放给阿岩听。"
        assertTrue(keepsProtectedContent(original, "小雨把《夜曲》放给阿岩听。", listOf("小雨", "阿岩")))
        assertFalse(keepsProtectedContent(original, "她把《夜曲》放给他听。", listOf("小雨", "阿岩")))
        // 原文里没有的名字不参与判定。
        assertTrue(keepsProtectedContent(original, "她把《夜曲》放给阿岩听。", listOf("林晚")))
    }

    @Test
    fun unpairedQuoteDoesNotSwallowTheTextAroundIt() {
        // 只有一个孤立引号时，不能把"孤立引号 → 远处的下一个引号"之间的正文当成一个引用片段。
        assertEquals(
            listOf("\"早点睡。\""),
            quotedSpans("我回了一句\"好。 他说：\"早点睡。\""),
        )
        // 孤立引号之后的闭合引号，仍属于同一句里的引用。
        assertEquals(
            listOf("\"好。\""),
            quotedSpans("我回了一句\"好。\""),
        )
    }

    @Test
    fun critiqueIsAcceptedWhenOnlyAnUnpairedQuoteRegionIsRewritten() {
        val original = "我回了一句\"好。 他把灯拧暗：\"早点睡。\""
        // 两处极小改写都落在孤立引号与下一个引号之间：不再整条回退。
        val revised = "我回了一句\"行。 他把灯拧暗，压低了声音：\"早点睡。\""
        assertTrue(keepsProtectedContent(original, revised, emptyList()))
        // 同一段里真正的引用被动过 → 照旧回退。
        assertFalse(keepsProtectedContent(original, "我回了一句\"好。 他把灯拧暗：\"睡吧。\"", emptyList()))
    }

    @Test
    fun critiqueIsStillRejectedWhenARealQuotedPassageIsLost() {
        val original = "她低声说\"你别熬夜\"，我没接话。"
        // 引用被改写 → 回退
        assertFalse(keepsProtectedContent(original, "她低声说\"你要早点睡\"，我没接话。", emptyList()))
        // 引用被删掉 → 回退
        assertFalse(keepsProtectedContent(original, "她低声说你别熬夜，我没接话。", emptyList()))
        // 引用逐字保留（其余文风可改）→ 通过
        assertTrue(keepsProtectedContent(original, "她只低声说了句\"你别熬夜\"。我没接话。", emptyList()))
    }

    @Test
    fun critiqueIsStillRejectedWhenANameDisappearsNextToAnUnpairedQuote() {
        val original = "我回了一句\"好。 小雨说：早点睡。"
        assertFalse(keepsProtectedContent(original, "我回了一句\"好。 她说：早点睡。", listOf("小雨")))
        assertTrue(keepsProtectedContent(original, "我回了一句\"好。 小雨说：早点睡。", listOf("小雨")))
    }

    @Test
    fun critiquePassesWhenNoQuotedPassageIsTouched() {
        // 完全没动引用（含书名号与直角引号）的正常通过路径不受切分方式影响。
        assertTrue(
            keepsProtectedContent(
                "他说「早点睡」，我点头，把《夜曲》放回桌上。",
                "他低声道「早点睡」。我点了点头，才把《夜曲》放回桌上。",
                emptyList(),
            ),
        )
        assertTrue(
            keepsProtectedContent(
                "她低声说\"你别熬夜\"，我没接话。",
                "她低声说\"你别熬夜\"。我没接话。",
                emptyList(),
            ),
        )
    }
}
