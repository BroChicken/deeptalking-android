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
 * The web tools follow **one tool = one platform**: each tool is bound to a single
 * platform, so there is no `platform` parameter for the model to get wrong, and
 * `web_fetch` no longer hides a B站 keyword search.
 *
 * Uses a recording fake provider — no network in tests.
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

    private fun hot(web: WebContentProvider?, platform: String, name: String) =
        HotBoardTool(web, platform = platform, name = name, label = "测试榜单")

    private fun social(web: WebContentProvider?, platform: String, name: String) =
        SocialSearchTool(web, platform = platform, name = name, label = "测试平台", hint = "提示。")

    // ---------------------------------------------------------------- hot boards

    @Test
    fun hotBoardToolQueriesItsOwnPlatformOnlyAndForwardsTheLimit() = runBlocking {
        val web = FakeWeb()
        val result = hot(web, "zhihu", "zhihu_hot").execute(call("""{"limit":20}"""), context)
        assertEquals("""{"ok":true}""", result.contentJson)
        assertEquals(listOf("hotBoard:zhihu:20"), web.calls)
    }

    @Test
    fun everyHotBoardToolIsBoundToItsOwnPlatformAndDefaultsToTen() = runBlocking {
        val web = FakeWeb()
        hot(web, "baidu", "baidu_hot").execute(call("{}"), context)
        hot(web, "toutiao", "toutiao_hot").execute(call("{}"), context)
        hot(web, "douyin", "douyin_hot").execute(call("{}"), context)
        hot(web, "zhihu", "zhihu_hot").execute(call("{}"), context)
        assertEquals(
            listOf("hotBoard:baidu:10", "hotBoard:toutiao:10", "hotBoard:douyin:10", "hotBoard:zhihu:10"),
            web.calls,
        )
    }

    @Test
    fun hotBoardClampsTheLimit() = runBlocking {
        val web = FakeWeb()
        hot(web, "baidu", "baidu_hot").execute(call("""{"limit":999}"""), context)
        assertEquals(listOf("hotBoard:baidu:30"), web.calls)
    }

    @Test
    fun hotBoardToolsTakeNoPlatformArgument() {
        // 回归护栏：平台是工具身份的一部分，绝不能再退回「模型传 platform」。
        listOf("baidu_hot", "toutiao_hot", "douyin_hot", "zhihu_hot").forEach { name ->
            val params = hot(null, "baidu", name).definition.parametersJson
            assertFalse("$name 不应再有 platform 参数", params.contains("\"platform\""))
        }
    }

    // ------------------------------------------------------------ social search

    @Test
    fun eachSocialToolGoesToItsOwnPlatform() = runBlocking {
        val web = FakeWeb()
        social(web, "bilibili", "search_video").execute(call("""{"query":"火影忍者","limit":5}"""), context)
        social(web, "netease", "search_music").execute(call("""{"query":"海阔天空"}"""), context)
        social(web, "hupu", "search_posts").execute(call("""{"query":"原神"}"""), context)
        assertEquals(
            listOf(
                "searchSocial:bilibili:火影忍者:5",
                "searchSocial:netease:海阔天空:6",
                "searchSocial:hupu:原神:6",
            ),
            web.calls,
        )
    }

    @Test
    fun socialToolsRejectAMissingQueryWithoutCallingTheProvider() = runBlocking {
        val web = FakeWeb()
        val result = social(web, "netease", "search_music").execute(call("""{"query":"  "}"""), context)
        assertTrue(result.contentJson.contains("缺少检索关键词"))
        assertTrue(web.calls.isEmpty())
    }

    @Test
    fun socialToolsTakeNoPlatformArgument() {
        listOf("search_video", "search_music", "search_posts").forEach { name ->
            val params = social(null, "hupu", name).definition.parametersJson
            assertFalse("$name 不应再有 platform 参数", params.contains("\"platform\""))
        }
    }

    // --------------------------------------------------------------- web_fetch

    @Test
    fun webFetchReadsOnlyLinksAndPointsKeywordSearchesAtThePlatformTools() = runBlocking {
        val web = FakeWeb()
        val result = WebFetchTool(web).execute(call("""{"keyword":"火影忍者"}"""), context)
        assertTrue(result.contentJson.contains("search_video"))
        assertTrue(web.calls.isEmpty())

        WebFetchTool(web).execute(call("""{"url":"https://example.com/a"}"""), context)
        assertEquals(listOf("fetch:https://example.com/a"), web.calls)
    }

    @Test
    fun missingProviderDegradesToAnError() = runBlocking {
        assertTrue(
            hot(null, "baidu", "baidu_hot").execute(call("{}"), context).contentJson.contains("联网功能不可用"),
        )
        assertTrue(
            social(null, "hupu", "search_posts").execute(call("""{"query":"原神"}"""), context)
                .contentJson.contains("联网功能不可用"),
        )
    }

    // ------------------------------------------------------------- registration

    @Test
    fun everyWebToolIsRegisteredBeforeSubmitResponse() {
        val names = defaultTools(MemoryServiceImpl(), web = null, stickersEnabled = false)
            .map { it.definition.name }
        listOf(
            "web_search",
            "web_fetch",
            "search_video",
            "search_music",
            "search_posts",
            "baidu_hot",
            "toutiao_hot",
            "douyin_hot",
            "zhihu_hot",
        ).forEach { name ->
            assertTrue("$name 未注册", names.contains(name))
            assertTrue("$name 必须排在 submit_response 之前", names.indexOf(name) < names.indexOf("submit_response"))
        }
        // 旧的多平台工具已下线。
        assertFalse(names.contains("hot_topics"))
        assertFalse(names.contains("social_search"))
        assertEquals("submit_response", names.last())
    }
}
