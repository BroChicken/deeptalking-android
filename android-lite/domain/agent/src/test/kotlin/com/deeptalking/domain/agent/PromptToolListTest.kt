package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.domain.agent.prompts.PersonaInputs
import com.deeptalking.domain.agent.prompts.buildLorebookInjection
import com.deeptalking.domain.agent.prompts.buildSystemPrompt
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.domain.memory.matchLorebookEntries
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system prompt must describe the V2 tool set the model actually receives
 * (docs/OUTBOUND_CALLS_V2.md §一(三)) and must not mention any retired tool or the old
 * JSON/submit_response protocol. A body-less world-book entry must be visible exactly when
 * it can be filled.
 */
class PromptToolListTest {

    private val character = Character(id = "c1", name = "小雨")

    private fun systemPrompt(): String = buildSystemPrompt(PersonaInputs(character = character))

    @Test
    fun systemPromptListsTheEightV2Tools() {
        val prompt = systemPrompt()
        listOf(
            "search_memory",
            "web_search",
            "web_fetch",
            "search",
            "hot_board",
            "memory_write",
            "upsert_lorebook_entry",
            "send_sticker",
        ).forEach { name ->
            assertTrue("system prompt 缺少工具 $name", prompt.contains(name))
        }
    }

    @Test
    fun systemPromptNeverMentionsRetiredToolsOrTheJsonProtocol() {
        val prompt = systemPrompt()
        listOf(
            "submit_response",
            "list_memories",
            "delete_memory",
            "update_memory",
            "add_todo",
            "update_character_field",
            "get_current_time",
            "ask_user",
            "search_video",
            "search_music",
            "search_posts",
            "baidu_hot",
            "toutiao_hot",
            "douyin_hot",
            "zhihu_hot",
            "quickReplies",
        ).forEach { retired ->
            assertFalse("system prompt 仍在提旧工具/旧协议：$retired", prompt.contains(retired))
        }
    }

    @Test
    fun platformToolsTakeAnExplicitPlatform() {
        val prompt = systemPrompt()
        assertTrue(prompt.contains("platform=bilibili"))
        assertTrue(prompt.contains("platform=baidu"))
        assertTrue(prompt.contains("upsert_lorebook_entry"))
    }

    @Test
    fun outputChannelKeepsTheVoiceLock() {
        val prompt = systemPrompt()
        assertTrue(prompt.contains("【输出通道】"))
        assertTrue(prompt.contains("【语气】"))
        assertTrue(prompt.contains("至少自然带出其中 2 处签名标记"))
        assertTrue(prompt.contains("不得向默认 AI 助手腔靠拢"))
    }

    @Test
    fun groupRulesOnlyAppearForGroups() {
        assertFalse(systemPrompt().contains("【群组附加规则】"))

        val group = Character(
            id = "g1",
            name = "合租小屋",
            isGroup = true,
            members = emptyList(),
        )
        val groupPrompt = buildSystemPrompt(PersonaInputs(character = group))
        assertTrue(groupPrompt.contains("【群组附加规则】"))
        assertTrue(groupPrompt.contains("10. "))
        assertTrue(groupPrompt.contains("11. "))
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

        assertTrue(injection.contains("【世界书】"))
        assertTrue(injection.contains("银月商会：银月商会在西街，经营药材。"))
        assertFalse(injection.contains("正文为空"))
        // Always-active only decides whether an entry is injected; it is no longer labelled.
        assertFalse(injection.contains("常驻前提"))
    }
}
