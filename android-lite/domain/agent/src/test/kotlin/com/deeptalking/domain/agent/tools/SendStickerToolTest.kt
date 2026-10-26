package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.Sticker
import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.engine.ondevice.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `keyword hit sends a library sticker and does not need a notice`() = runTest {
        val result = SendStickerTool().execute(call("""{"keyword":"无语凝噎"}"""), context())
        assertFalse(result.isError)
        assertEquals("f1", result.stickerFileRef)
        assertTrue(result.contentJson.contains("\"source\":\"library\""))
        // 发成功了，气泡本身就是反馈，不再额外写一行账。
        assertNull(result.stickerNotice)
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
    fun `a miss points at search and reports it on the bubble`() = runTest {
        val result = SendStickerTool().execute(call("""{"keyword":"完全不存在"}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        // 模型下一步该干什么，直接写在错误里；否则它会自己编一个词或跑去 web_search。
        assertTrue(result.contentJson.contains("search(platform=sticker"))
        assertTrue(result.contentJson.contains("无语凝噎"))
        assertEquals("没发出：库里没有「完全不存在」", result.stickerNotice)
    }

    @Test
    fun `the legacy query argument is redirected to search instead of silently failing`() = runTest {
        val result = SendStickerTool().execute(call("""{"query":"被无语到"}"""), context())
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertTrue(result.contentJson.contains("search(platform=sticker"))
        assertEquals("没发出：找表情要用 search(platform=sticker)", result.stickerNotice)
        assertNull(result.stickerFileRef)
    }

    @Test
    fun `nothing to send when the library is empty`() = runTest {
        val result = SendStickerTool().execute(call("""{"keyword":"开心"}"""), context(Character(id = "c2")))
        assertTrue(result.contentJson.contains("\"ok\":false"))
        assertNull(result.stickerFileRef)
        assertNotNull(result.stickerNotice)
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

    @Test
    fun `schema still accepts a query property so old calls fail with a redirect instead of a protocol error`() {
        val definition = SendStickerTool().definitionFor(context())
        assertTrue(definition.parametersJson.contains("\"query\""))
        assertTrue(definition.description.contains("search(platform=sticker"))
    }
}
