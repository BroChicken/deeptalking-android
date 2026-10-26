package com.deeptalking.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVoiceChoicesTest {

    private val builtin = VoiceOption("builtin_1_v2.gguf", "内置音色 1", builtin = true)
    private val mine = VoiceOption("voice-ab12.gguf", "我的声音", builtin = false)

    /* ------------------------------- labels ------------------------------- */

    @Test
    fun `the follow entry comes first and stores an empty id`() {
        val choices = remoteVoiceChoices(listOf(builtin), "builtin_1_v2.gguf", emptyList(), emptyMap())
        assertEquals("跟随端侧音色（内置音色 1）", choices.first().label)
        assertEquals(FOLLOW_VOICE_ID, choices.first().id)
    }

    @Test
    fun `the PC display name wins when the two ends differ`() {
        // This is the "rename on the PC did nothing" bug: before, only the phone name was
        // rendered, so the PC's edited name had no effect on the list.
        val choices = remoteVoiceChoices(
            voices = listOf(mine),
            activeVoiceId = "voice-ab12.gguf",
            remoteVoices = listOf("voice-ab12"),
            remoteVoiceNames = mapOf("voice-ab12" to "电脑上的名字"),
        )

        assertEquals("电脑上的名字（我的声音）", choices[1].label)
        assertEquals("voice-ab12", choices[1].id)
    }

    @Test
    fun `equal names keep the existing phone label`() {
        val choices = remoteVoiceChoices(
            voices = listOf(builtin, mine),
            activeVoiceId = "builtin_1_v2.gguf",
            remoteVoices = listOf("builtin_1_v2", "voice-ab12"),
            remoteVoiceNames = mapOf("builtin_1_v2" to "内置音色 1", "voice-ab12" to "我的声音"),
        )

        assertEquals("内置音色 1（builtin_1_v2）", choices[1].label)
        assertEquals("我的声音（voice-ab12）", choices[2].label)
    }

    @Test
    fun `a voice the PC does not have is marked unregistered`() {
        val choices = remoteVoiceChoices(
            voices = listOf(builtin, mine),
            activeVoiceId = "builtin_1_v2.gguf",
            remoteVoices = listOf("builtin_1_v2"),
            remoteVoiceNames = mapOf("builtin_1_v2" to "内置音色 1"),
        )

        assertEquals("内置音色 1（builtin_1_v2）", choices[1].label)
        assertEquals("我的声音（voice-ab12） · 服务端未注册", choices[2].label)
    }

    @Test
    fun `an unknown manifest marks nothing as unregistered`() {
        // Empty manifest = "the server did not tell us" (old server / WebUI mode / not
        // fetched yet), never "the server has nothing".
        val choices = remoteVoiceChoices(listOf(mine), "voice-ab12.gguf", emptyList(), emptyMap())
        assertFalse(choices.any { it.label.contains("服务端未注册") })
    }

    @Test
    fun `a voice only the PC has is marked PC only`() {
        val choices = remoteVoiceChoices(
            voices = listOf(builtin),
            activeVoiceId = "builtin_1_v2.gguf",
            remoteVoices = listOf("builtin_1_v2", "pc-only"),
            remoteVoiceNames = mapOf("builtin_1_v2" to "内置音色 1", "pc-only" to "电脑独有"),
        )

        assertEquals("电脑独有 · 仅服务端", choices[2].label)
        assertEquals("pc-only", choices[2].id)
    }

    @Test
    fun `a PC only voice without a display name falls back to its id`() {
        val choices = remoteVoiceChoices(
            voices = emptyList(),
            activeVoiceId = "",
            remoteVoices = listOf("pc-only"),
            remoteVoiceNames = emptyMap(),
        )
        assertEquals("pc-only · 仅服务端", choices[1].label)
    }

    /* ----------------------------- edit target ----------------------------- */

    @Test
    fun `the configured remote voice wins when the server still has it`() {
        assertEquals("b", remoteVoiceEditTarget("b", "a.gguf", listOf("a", "b")))
    }

    @Test
    fun `follow mode resolves to the on-device stem`() {
        assertEquals("a", remoteVoiceEditTarget("", "a.gguf", listOf("a", "b")))
        // A stored absolute path resolves to the same stem.
        assertEquals("a", remoteVoiceEditTarget("", "/data/voices/a.gguf", listOf("a")))
    }

    @Test
    fun `an on-device voice that was never uploaded has no target`() {
        // Guessing another voice here is exactly the old "renamed the first server voice"
        // bug, so the buttons must be disabled instead.
        assertEquals("", remoteVoiceEditTarget("", "mine.gguf", listOf("a", "b")))
    }

    @Test
    fun `only a phone without any voice falls back to the first server voice`() {
        assertEquals("a", remoteVoiceEditTarget("", "", listOf("a", "b")))
        assertEquals("", remoteVoiceEditTarget("", "", emptyList()))
    }

    @Test
    fun `a stale selection falls through to the on-device stem`() {
        assertEquals("a", remoteVoiceEditTarget("gone", "a.gguf", listOf("a")))
    }

    /* --------------------------- enable conditions -------------------------- */

    @Test
    fun `rename is disabled while busy or without a target`() {
        assertFalse(canRenameRemoteVoice(busy = true, target = "a"))
        assertFalse(canRenameRemoteVoice(busy = false, target = "   "))
        assertTrue(canRenameRemoteVoice(busy = false, target = "a"))
    }

    @Test
    fun `delete needs a manifest and a concrete voice`() {
        // No manifest: the PC server predates the delete endpoint.
        assertFalse(canDeleteRemoteVoice(busy = false, target = "a", remoteVoices = emptyList()))
        assertFalse(canDeleteRemoteVoice(busy = true, target = "a", remoteVoices = listOf("a")))
        // Manifest present but the local voice was never uploaded: nothing to delete.
        assertFalse(canDeleteRemoteVoice(busy = false, target = "", remoteVoices = listOf("a")))
        assertTrue(canDeleteRemoteVoice(busy = false, target = "a", remoteVoices = listOf("a")))
    }

    @Test
    fun `the edit target decides which voice the buttons name`() {
        val voices = listOf(builtin, mine)
        val remote = listOf("builtin_1_v2", "voice-ab12")
        val names = mapOf("builtin_1_v2" to "电脑内置", "voice-ab12" to "我的声音")
        val target = remoteVoiceEditTarget("", "voice-ab12.gguf", remote)
        assertEquals("voice-ab12", target)
        assertEquals("我的声音", names[target])
        assertEquals(voices.size, remoteVoiceChoices(voices, "voice-ab12.gguf", remote, names).size - 1)
    }
}
