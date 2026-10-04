package com.deeptalking.domain.agent.background

import com.deeptalking.core.model.Character
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
}
