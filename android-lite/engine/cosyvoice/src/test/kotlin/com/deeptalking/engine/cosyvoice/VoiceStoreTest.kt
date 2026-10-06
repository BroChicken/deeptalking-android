package com.deeptalking.engine.cosyvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun voiceFile(name: String): File = File(temp.root, name).apply { writeBytes(byteArrayOf(0)) }

    @Test
    fun addListAndRenameRoundTrip() {
        val store = VoiceStore(temp.root)
        val file = voiceFile("voice-abc.gguf")
        store.add(file, "小明", builtin = false)

        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("小明", listed[0].name)
        assertEquals("voice-abc.gguf", listed[0].file)

        assertTrue(store.rename("voice-abc.gguf", "新名字"))
        assertEquals("新名字", VoiceStore(temp.root).list()[0].name)
    }

    @Test
    fun builtinCannotBeRemoved() {
        val store = VoiceStore(temp.root)
        voiceFile("builtin_1.gguf")
        store.add(File(temp.root, "builtin_1.gguf"), "内置音色 1", builtin = true)

        assertFalse(store.remove("builtin_1.gguf"))
        assertTrue(File(temp.root, "builtin_1.gguf").exists())
    }

    @Test
    fun removeDeletesUserVoice() {
        val store = VoiceStore(temp.root)
        val file = voiceFile("voice-xyz.gguf")
        store.add(file, "临时", builtin = false)

        assertTrue(store.remove("voice-xyz.gguf"))
        assertFalse(file.exists())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun untrackedFilesShowUpWithFallbackName() {
        val store = VoiceStore(temp.root)
        voiceFile("voice-loose.gguf")
        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("voice-loose", listed[0].name)
    }
}
