package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.Sticker
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.sticker.FetchedSticker
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
            FetchedSticker(keyword = "被无语到", category = "speechless", fileRef = "f9")
        }
        val result = SendStickerTool(fetcher = fetcher).execute(call("""{"query":"被无语到"}"""), context())
        assertEquals("被无语到", asked)
        assertFalse(result.isError)
        assertEquals("f9", result.stickerFileRef)
        assertTrue(result.contentJson.contains("\"source\":\"web\""))
    }

    @Test
    fun `failed fetch falls back to the keyword list`() = runTest {
        val fetcher = StickerFetcher { _, _ -> null }
        val result = SendStickerTool(fetcher = fetcher).execute(call("""{"query":"被无语到"}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("无语凝噎"))
    }

    @Test
    fun `nothing to send when the library is empty`() = runTest {
        val result = SendStickerTool().execute(call("""{"keyword":"开心"}"""), context(Character(id = "c2")))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertNull(result.stickerFileRef)
    }

    @Test
    fun `tool is only enabled for characters that own stickers`() {
        assertFalse(SendStickerTool().isEnabled(context(Character(id = "c2"))))
        assertTrue(SendStickerTool().isEnabled(context()))
        assertFalse(SendStickerTool(stickersEnabled = false).isEnabled(context()))
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
