package com.deeptalking.domain.agent

import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.domain.agent.prompts.RULE_A
import com.deeptalking.domain.agent.prompts.buildLorebookInjection
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.domain.memory.matchLorebookEntries
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system prompt's "available tools" list must describe the tool set the model actually
 * receives, and a body-less world-book entry must be visible exactly when it can be filled.
 */
class PromptToolListTest {

    @Test
    fun ruleAListsEveryRegisteredTool() {
        // `send_sticker` is only registered when the character has stickers and
        // `submit_response` is the terminal call described by the output-format rule, so
        // neither belongs in this enumeration.
        val registered = defaultTools(MemoryServiceImpl(), web = null, stickersEnabled = true)
            .map { it.definition.name }
            .filterNot { it == "send_sticker" || it == "submit_response" }

        registered.forEach { name ->
            assertTrue("工具清单里缺少 $name", RULE_A.contains(name))
        }
    }

    @Test
    fun aPlaceholderReachesTheModelWithItsIdAndDisappearsWhenItIrrelevant() {
        val placeholder = LorebookEntry(
            id = "lore_1",
            name = "赤月王国",
            keywords = listOf("赤月"),
            content = "",
            enabled = true,
            alwaysActive = true,
            origin = LorebookOrigin.Model,
        )

        val hit = buildLorebookInjection(matchLorebookEntries(listOf(placeholder), "我们去赤月看看吧"))
        assertTrue(hit.contains("赤月王国：（正文为空，待补充；条目ID: lore_1"))
        // The filling instruction is only worth the characters when a placeholder is present.
        assertTrue(hit.contains("upsert_lorebook_entry"))

        val miss = buildLorebookInjection(matchLorebookEntries(listOf(placeholder), "今天天气不错"))
        assertFalse(miss.contains("赤月王国"))
    }

    @Test
    fun aNormalEntryIsInjectedInTheUsualShape() {
        val entry = LorebookEntry(
            id = "lore_2",
            name = "银月商会",
            keywords = listOf("银月"),
            content = "银月商会在西街，经营药材。",
            alwaysActive = true,
            origin = LorebookOrigin.Model,
        )

        val injection = buildLorebookInjection(matchLorebookEntries(listOf(entry), "随便聊聊"))

        assertTrue(injection.contains("【世界书·常驻前提（始终成立的设定）】"))
        assertTrue(injection.contains("银月商会：银月商会在西街，经营药材。"))
        assertFalse(injection.contains("正文为空"))
    }
}
