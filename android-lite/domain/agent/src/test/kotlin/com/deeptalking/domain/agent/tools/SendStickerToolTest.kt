package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.Sticker
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.sticker.FetchedSticker
import com.deeptalking.domain.agent.sticker.StickerFetchResult
import com.deeptalking.domain.agent.sticker.StickerFetcher
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendStickerToolTest {

    private val character = Character(
        id = "c1",
        stickers = listOf(
            Sticker(id = "s1", keyword = "无语凝噎", category = "speechless", fileRef = "f1"),
            Sticker(id = "s2", keyword = "笑死我了", category = "happy", fileRef = "f2"),
            Sticker(id = "s3", keyword = "笑死我了", category = "happy", fileRef = "f3"),
        ),
    )

    private fun context(c: Character = character) = AgentContext(character = c, activeCharacterId = c.id)

    private fun call(arguments: String) = ToolCall(id = "t1", name = "send_sticker", arguments = arguments)

    @Test
    fun `keyword hit sends a library sticker`() = runTest {
        val result = SendStickerTool().execute(call("""{"keyword":"无语凝噎"}"""), context())
        assertFalse(result.isError)
        assertEquals("f1", result.stickerFileRef)
        assertTrue(result.contentJson.contains("\"source\":\"library\""))
    }

    @Test
    fun `duplicate keywords pick among all of them`() = runTest {
        val refs = (1..12)
            .map { SendStickerTool().execute(call("""{"keyword":"笑死我了"}"""), context()).stickerFileRef }
            .toSet()
        assertEquals(setOf("f2", "f3"), refs)
    }

    @Test
    fun `substring match still works when the exact keyword misses`() = runTest {
        val result = SendStickerTool().execute(call("""{"keyword":"无语"}"""), context())
        assertEquals("f1", result.stickerFileRef)
    }

    @Test
    fun `miss without query reports the available keywords`() = runTest {
        val result = SendStickerTool().execute(call("""{"keyword":"完全不存在"}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("无语凝噎"))
    }

    @Test
    fun `query fetches a sticker and sends it`() = runTest {
        var asked: String? = null
        val fetcher = StickerFetcher { _, query ->
            asked = query
            StickerFetchResult.Stored(
                FetchedSticker(keyword = "被无语到", category = "speechless", fileRef = "f9", total = 7),
            )
        }
        val result = SendStickerTool(fetcher = fetcher).execute(call("""{"query":"被无语到"}"""), context())
        assertEquals("被无语到", asked)
        assertFalse(result.isError)
        assertEquals("f9", result.stickerFileRef)
        assertTrue(result.contentJson.contains("\"source\":\"web\""))
        assertTrue("总数要回给模型，它才能如实说库里现在几张", result.contentJson.contains("\"total\":7"))
    }

    @Test
    fun `a fetch that has no result reports the reason verbatim`() = runTest {
        val fetcher = StickerFetcher { _, _ ->
            StickerFetchResult.Failed("候选图都下载失败，稍后再试")
        }
        val result = SendStickerTool(fetcher = fetcher).execute(call("""{"query":"被无语到"}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("下载失败"))
    }

    @Test
    fun `a throwing fetcher falls back to the keyword list`() = runTest {
        val fetcher = StickerFetcher { _, _ -> throw IllegalStateException("boom") }
        val result = SendStickerTool(fetcher = fetcher).execute(call("""{"query":"被无语到"}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("无语凝噎"))
    }

    @Test
    fun `a cold library can still fetch its first sticker`() = runTest {
        val empty = Character(id = "c2")
        val fetcher = StickerFetcher { _, _ ->
            StickerFetchResult.Stored(
                FetchedSticker(keyword = "第一张", category = "other", fileRef = "f1", total = 1),
            )
        }
        val tool = SendStickerTool(fetcher = fetcher)
        assertTrue("空库也要能现找，否则新角色永远没有表情", tool.isEnabled(context(empty)))
        val result = tool.execute(call("""{"query":"第一张"}"""), context(empty))
        assertEquals("f1", result.stickerFileRef)
    }

    @Test
    fun `nothing to send when the library is empty`() = runTest {
        val result = SendStickerTool().execute(call("""{"keyword":"开心"}"""), context(Character(id = "c2")))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertNull(result.stickerFileRef)
    }

    @Test
    fun `the tool follows the stickers switch, not the library size`() {
        assertTrue(SendStickerTool().isEnabled(context(Character(id = "c2"))))
        assertTrue(SendStickerTool().isEnabled(context()))
        assertFalse(SendStickerTool(stickersEnabled = false).isEnabled(context()))
    }

    @Test
    fun `groups still have no stickers`() {
        // 群组不维护表情库、也没有共享池，所以即使全局开关打开也不给它这个工具。
        assertFalse(SendStickerTool().isEnabled(context(Character(id = "g1", isGroup = true))))
    }

    @Test
    fun `schema groups keywords by category and keeps duplicates out of the enum`() {
        val definition = SendStickerTool().definitionFor(context())
        assertTrue(definition.description.contains("无语"))
        assertTrue(definition.description.contains("无语凝噎"))
        assertTrue(definition.parametersJson.contains("\"无语凝噎\""))
        assertEquals(1, Regex("\"笑死我了\"").findAll(definition.parametersJson).count())
    }
}
