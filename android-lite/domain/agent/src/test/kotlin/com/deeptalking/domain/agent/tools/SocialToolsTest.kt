package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.defaultTools
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Platform search and hot boards are one tool each with an explicit `platform` enum
 * (`search` / `hot_board`). Uses a recording fake provider — no network in tests.
 */
class SocialToolsTest {

    /** Records every provider call and returns a canned body. */
    private class FakeWeb(private val reply: String = """{"ok":true}""") : WebContentProvider {
        val calls = mutableListOf<String>()

        private fun record(call: String): String {
            calls += call
            return reply
        }

        override suspend fun searchWeb(query: String, limit: Int): String = record("searchWeb:$query:$limit")

        override suspend fun fetch(url: String): String = record("fetch:$url")

        override suspend fun searchBilibili(keyword: String, limit: Int): String = record("bilibili:$keyword:$limit")

        override suspend fun hotBoard(platform: String, limit: Int): String = record("hotBoard:$platform:$limit")

        override suspend fun searchSocial(platform: String, query: String, limit: Int): String =
            record("searchSocial:$platform:$query:$limit")
    }

    private val context = AgentContext(character = Character(id = "c1", name = "小雨"), activeCharacterId = "c1")

    private fun call(arguments: String) = ToolCall(id = "t1", name = "tool", arguments = arguments)

    // ---------------------------------------------------------------- hot board

    @Test
    fun `hot_board forwards the platform and defaults to ten`() = runBlocking {
        val web = FakeWeb()
        val result = HotBoardTool(web).execute(call("""{"platform":"zhihu"}"""), context)
        assertEquals("""{"ok":true}""", result.contentJson)
        assertEquals(listOf("hotBoard:zhihu:10"), web.calls)
    }

    @Test
    fun `hot_board clamps the limit`() = runBlocking {
        val web = FakeWeb()
        HotBoardTool(web).execute(call("""{"platform":"baidu","limit":999}"""), context)
        assertEquals(listOf("hotBoard:baidu:30"), web.calls)
    }

    @Test
    fun `hot_board rejects an unknown or missing platform without calling the provider`() = runBlocking {
        val web = FakeWeb()
        assertTrue(
            HotBoardTool(web).execute(call("""{"platform":"weibo"}"""), context)
                .contentJson.contains("platform 只能是 baidu / toutiao / douyin / zhihu"),
        )
        assertTrue(
            HotBoardTool(web).execute(call("{}"), context).contentJson.contains("platform 只能是"),
        )
        assertTrue(web.calls.isEmpty())
    }

    @Test
    fun `hot_board schema exposes exactly the four platforms`() {
        val params = HotBoardTool(null).definition.parametersJson
        listOf("baidu", "toutiao", "douyin", "zhihu").forEach { assertTrue(it, params.contains("\"$it\"")) }
        assertFalse(params.contains("weibo"))
        assertTrue(params.contains("\"required\":[\"platform\"]"))
    }

    // ------------------------------------------------------------ platform search

    @Test
    fun `search forwards the platform query and limit`() = runBlocking {
        val web = FakeWeb()
        SearchTool(web).execute(call("""{"platform":"hupu","query":"原神","limit":5}"""), context)
        SearchTool(web).execute(call("""{"platform":"netease","query":"海阔天空"}"""), context)
        SearchTool(web).execute(call("""{"platform":"hupu","query":"原神","limit":99}"""), context)
        assertEquals(
            listOf(
                "searchSocial:hupu:原神:5",
                "searchSocial:netease:海阔天空:6",
                "searchSocial:hupu:原神:20",
            ),
            web.calls,
        )
    }

    @Test
    fun `search rejects a missing query or an unknown platform without calling the provider`() = runBlocking {
        val web = FakeWeb()
        assertTrue(
            SearchTool(web).execute(call("""{"platform":"netease","query":"  "}"""), context)
                .contentJson.contains("缺少检索关键词"),
        )
        assertTrue(
            SearchTool(web).execute(call("""{"platform":"weibo","query":"热搜"}"""), context)
                .contentJson.contains("platform 只能是 netease / hupu / sticker"),
        )
        assertTrue(web.calls.isEmpty())
    }

    @Test
    fun `search schema exposes netease, hupu and the sticker platform`() {
        val params = SearchTool(null).definition.parametersJson
        listOf("netease", "hupu", "sticker").forEach { assertTrue(it, params.contains("\"$it\"")) }
        // B 站视频已被 bilibili_video 接管，不能再出现在 search 的枚举里。
        assertFalse(params.contains("\"bilibili\""))
        assertTrue(params.contains("\"required\":[\"platform\",\"query\"]"))
        // 找表情一次最多入库 3 张。
        assertTrue(params.contains("\"count\""))
    }

    // --------------------------------------------------------------- web_fetch

    @Test
    fun `web fetch reads only links and points keyword searches at search`() = runBlocking {
        val web = FakeWeb()
        val result = WebFetchTool(web).execute(call("""{"keyword":"火影忍者"}"""), context)
        assertTrue(result.contentJson.contains("search"))
        assertFalse(result.contentJson.contains("search_video"))
        assertTrue(web.calls.isEmpty())

        WebFetchTool(web).execute(call("""{"url":"https://example.com/a"}"""), context)
        assertEquals(listOf("fetch:https://example.com/a"), web.calls)
    }

    @Test
    fun `missing provider degrades to an error`() = runBlocking {
        assertTrue(
            HotBoardTool(null).execute(call("""{"platform":"baidu"}"""), context)
                .contentJson.contains("联网功能不可用"),
        )
        assertTrue(
            SearchTool(null).execute(call("""{"platform":"hupu","query":"原神"}"""), context)
                .contentJson.contains("联网功能不可用"),
        )
    }

    // ------------------------------------------------------------- registration

    @Test
    fun `the eight tools are registered in a stable order`() {
        val names = defaultTools(MemoryServiceImpl(), web = null, stickersEnabled = true).map { it.definition.name }
        assertEquals(
            listOf(
                "search_memory",
                "web_search",
                "web_fetch",
                "search",
                "hot_board",
                "memory_write",
                "upsert_lorebook_entry",
                "send_sticker",
            ),
            names,
        )
    }

    @Test
    fun `send sticker is omitted when stickers are disabled and no legacy tool survives`() {
        val names = defaultTools(MemoryServiceImpl(), web = null, stickersEnabled = false).map { it.definition.name }
        assertEquals(7, names.size)
        assertFalse(names.contains("send_sticker"))
        // 旧工具（多平台 / 多记忆工具 / 输出协议）已下线。
        listOf(
            "submit_response",
            "ask_user",
            "get_current_time",
            "update_character_field",
            "add_todo",
            "list_memories",
            "delete_memory",
            "update_memory",
            "search_video",
            "search_music",
            "search_posts",
            "baidu_hot",
            "toutiao_hot",
            "douyin_hot",
            "zhihu_hot",
        ).forEach { name ->
            assertFalse("$name 不应再注册", names.contains(name))
        }
    }
}
