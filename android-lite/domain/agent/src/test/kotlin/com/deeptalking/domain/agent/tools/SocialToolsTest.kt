package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.defaultTools
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `hot_topics` / `social_search`: argument validation, provider degradation and
 * the forwarding of platform/query/limit. Uses a recording fake provider — no
 * network in tests.
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

    @Test
    fun hotTopicsForwardsPlatformAndLimit() = runBlocking {
        val web = FakeWeb()
        val result = HotTopicsTool(web).execute(call("""{"platform":"zhihu","limit":20}"""), context)
        assertEquals("""{"ok":true}""", result.contentJson)
        assertEquals(listOf("hotBoard:zhihu:20"), web.calls)
    }

    @Test
    fun hotTopicsDefaultsToTenAndAcceptsEveryPlatform() = runBlocking {
        val web = FakeWeb()
        val tool = HotTopicsTool(web)
        tool.execute(call("""{"platform":"baidu"}"""), context)
        tool.execute(call("""{"platform":"toutiao"}"""), context)
        tool.execute(call("""{"platform":"DOUYIN"}"""), context)
        assertEquals(listOf("hotBoard:baidu:10", "hotBoard:toutiao:10", "hotBoard:douyin:10"), web.calls)
    }

    @Test
    fun hotTopicsRejectsUnknownPlatformWithoutCallingTheProvider() = runBlocking {
        val web = FakeWeb()
        val result = HotTopicsTool(web).execute(call("""{"platform":"weibo"}"""), context)
        assertTrue(result.contentJson.contains("baidu / toutiao / douyin / zhihu"))
        assertTrue(web.calls.isEmpty())
    }

    @Test
    fun hotTopicsClampsTheLimit() = runBlocking {
        val web = FakeWeb()
        HotTopicsTool(web).execute(call("""{"platform":"baidu","limit":999}"""), context)
        assertEquals(listOf("hotBoard:baidu:30"), web.calls)
    }

    @Test
    fun missingProviderDegradesToAnError() = runBlocking {
        val result = HotTopicsTool(null).execute(call("""{"platform":"baidu"}"""), context)
        assertTrue(result.contentJson.contains("联网功能不可用"))
        val other = SocialSearchTool(null).execute(call("""{"platform":"hupu","query":"原神"}"""), context)
        assertTrue(other.contentJson.contains("联网功能不可用"))
    }

    @Test
    fun socialSearchForwardsPlatformQueryAndLimit() = runBlocking {
        val web = FakeWeb()
        val result = SocialSearchTool(web).execute(
            call("""{"platform":"hupu","query":"原神","limit":5}"""),
            context,
        )
        assertEquals("""{"ok":true}""", result.contentJson)
        assertEquals(listOf("searchSocial:hupu:原神:5"), web.calls)
    }

    @Test
    fun socialSearchRejectsMissingQueryAndUnknownPlatform() = runBlocking {
        val web = FakeWeb()
        val tool = SocialSearchTool(web)
        assertTrue(tool.execute(call("""{"platform":"hupu"}"""), context).contentJson.contains("缺少检索关键词"))
        assertTrue(
            tool.execute(call("""{"platform":"weibo","query":"原神"}"""), context)
                .contentJson.contains("hupu / netease / bilibili"),
        )
        assertTrue(web.calls.isEmpty())
    }

    @Test
    fun socialSearchDefaultsToSix() = runBlocking {
        val web = FakeWeb()
        SocialSearchTool(web).execute(call("""{"platform":"netease","query":"海阔天空"}"""), context)
        assertEquals(listOf("searchSocial:netease:海阔天空:6"), web.calls)
    }

    @Test
    fun allThreeWebToolsAreRegistered() {
        val names = defaultTools(MemoryServiceImpl(), web = null, stickersEnabled = false)
            .map { it.definition.name }
        assertTrue(names.contains("web_search"))
        assertTrue(names.contains("web_fetch"))
        assertTrue(names.contains("hot_topics"))
        assertTrue(names.contains("social_search"))
        // submit_response 永远最后，新工具排在它前面。
        assertEquals("submit_response", names.last())
        assertTrue(names.indexOf("hot_topics") < names.indexOf("submit_response"))
        assertTrue(names.indexOf("social_search") < names.indexOf("submit_response"))
    }
}
