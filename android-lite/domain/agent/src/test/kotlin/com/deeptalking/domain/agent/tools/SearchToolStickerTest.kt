package com.deeptalking.domain.agent.tools

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.sticker.FetchedSticker
import com.deeptalking.domain.agent.sticker.StickerFetchResult
import com.deeptalking.domain.agent.sticker.StickerFetcher
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 找表情走 `search(platform=sticker)`：只入库、不发、不贴图，把 keyword 交回模型；
 * 失败时把原因与环节（search/download/save）带回去，并在气泡底部留一行给用户看的账。
 */
class SearchToolStickerTest {

    private val character = Character(id = "c1")

    private fun context(c: Character = character) = AgentContext(character = c, activeCharacterId = c.id)

    private fun call(arguments: String) = ToolCall(id = "t1", name = "search", arguments = arguments)

    private fun tool(fetcher: StickerFetcher?, enabled: Boolean = true) =
        SearchTool(web = null, stickerFetcher = fetcher, stickersEnabled = enabled)

    @Test
    fun `sticker search stores several and sends the first one in this reply`() = runTest {
        var askedQuery = ""
        var askedCount = 0
        val fetcher = StickerFetcher { _, query, count ->
            askedQuery = query
            askedCount = count
            StickerFetchResult.Stored(
                stickers = listOf(
                    FetchedSticker(keyword = "无语", category = "speechless", fileRef = "s1"),
                    FetchedSticker(keyword = "裂开", category = "speechless", fileRef = "s2"),
                ),
                total = 9,
            )
        }
        val result = tool(fetcher).execute(call("""{"platform":"sticker","query":"被无语到","count":2}"""), context())

        assertEquals("被无语到", askedQuery)
        assertEquals(2, askedCount)
        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertTrue(result.contentJson.contains("\"platform\":\"sticker\""))
        assertTrue(result.contentJson.contains("\"sent\":true"))
        assertTrue(result.contentJson.contains("\"keyword\":\"无语\""))
        assertTrue(result.contentJson.contains("\"total\":9"))
        assertEquals("第一张直接作为这条回复的表情发出", "s1", result.stickerFileRef)
        assertNull("表情不是回复内附图", result.imageFileRef)
        assertEquals("表情库 +2：无语、裂开（共 9 张）· 已发出「无语」", result.stickerNotice)
    }

    @Test
    fun `the default search fetches three so the library grows for later use`() = runTest {
        var askedCount = -1
        val fetcher = StickerFetcher { _, _, count ->
            askedCount = count
            StickerFetchResult.Stored(listOf(FetchedSticker("x", "other", "s1")), total = 1)
        }
        val result = tool(fetcher).execute(call("""{"platform":"sticker","query":"猫"}"""), context())
        assertEquals(AppLimits.Sticker.FETCH_DEFAULT_COUNT, askedCount)
        assertEquals("只有一张也要发出去", "s1", result.stickerFileRef)
    }

    @Test
    fun `count is clamped to the configured maximum`() = runTest {
        var askedCount = -1
        val fetcher = StickerFetcher { _, _, count ->
            askedCount = count
            StickerFetchResult.Stored(listOf(FetchedSticker("x", "other", "s1")), total = 1)
        }
        tool(fetcher).execute(call("""{"platform":"sticker","query":"猫","count":99}"""), context())
        assertEquals(AppLimits.Sticker.FETCH_MAX_COUNT, askedCount)
    }

    @Test
    fun `a failure keeps the reason and shows it on the bubble`() = runTest {
        val fetcher = StickerFetcher { _, _, _ ->
            StickerFetchResult.Failed("候选图都下载失败（HTTP 403）", "download")
        }
        val result = tool(fetcher).execute(call("""{"platform":"sticker","query":"猫"}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("HTTP 403"))
        assertEquals("没找到表情：候选图都下载失败（HTTP 403）", result.stickerNotice)
    }

    @Test
    fun `a throwing fetcher does not crash the turn`() = runTest {
        val fetcher = StickerFetcher { _, _, _ -> throw IllegalStateException("boom") }
        val result = tool(fetcher).execute(call("""{"platform":"sticker","query":"猫"}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.stickerNotice!!.startsWith("没找到表情："))
    }

    @Test
    fun `no fetcher wired means an explicit error`() = runTest {
        val result = tool(null).execute(call("""{"platform":"sticker","query":"猫"}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertEquals("没找到表情：表情功能不可用", result.stickerNotice)
    }

    @Test
    fun `groups cannot search stickers`() = runTest {
        val fetcher = StickerFetcher { _, _, _ -> StickerFetchResult.Failed("should not be called", "search") }
        val group = Character(id = "g1", isGroup = true)
        val result = tool(fetcher).execute(call("""{"platform":"sticker","query":"猫"}"""), context(group))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("群组"))
        assertEquals("没找到表情：群组没有表情库", result.stickerNotice)
    }

    @Test
    fun `an empty query is rejected before touching the network`() = runTest {
        var called = false
        val fetcher = StickerFetcher { _, _, _ ->
            called = true
            StickerFetchResult.Failed("x", "search")
        }
        val result = tool(fetcher).execute(call("""{"platform":"sticker","query":"  "}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(!called)
    }

    @Test
    fun `an unknown platform is still rejected`() = runTest {
        val result = tool(null).execute(call("""{"platform":"weibo","query":"猫"}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("platform"))
    }
}
