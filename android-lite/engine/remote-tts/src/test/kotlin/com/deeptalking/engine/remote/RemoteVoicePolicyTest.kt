package com.deeptalking.engine.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `AppConfig.ttsRemoteVoice` is "" for "follow the on-device voice", resolved as
 * `ttsRemoteVoice.ifBlank { on-device stem }`. Every delete has to be decided in those
 * terms, or the picker ends up pointing at a voice that no longer exists.
 */
class RemoteVoicePolicyTest {

    @Test
    fun `deleting the selected voice moves the selection to one that remains`() {
        assertEquals(
            "b",
            RemoteVoicePolicy.nextAfterDelete(
                deletedId = "a",
                selectedId = "a",
                followStem = "local",
                remaining = listOf("b", "c"),
            ),
        )
    }

    @Test
    fun `deleting the last remaining voice leaves an empty selection`() {
        // "" means "follow the on-device voice" again - the caller must warn instead of
        // silently dropping the user into a 404 ("电脑端已无可用音色").
        assertEquals(
            "",
            RemoteVoicePolicy.nextAfterDelete("a", "a", followStem = "local", remaining = emptyList()),
        )
    }

    @Test
    fun `deleting another voice keeps the selection untouched`() {
        assertEquals(
            "a",
            RemoteVoicePolicy.nextAfterDelete("b", "a", followStem = "local", remaining = listOf("a")),
        )
        // Follow mode, and the deleted voice is not the on-device one.
        assertEquals(
            "",
            RemoteVoicePolicy.nextAfterDelete("other", "", followStem = "local", remaining = listOf("local")),
        )
    }

    @Test
    fun `follow mode plus deleting the on-device stem moves the selection`() {
        assertEquals(
            "b",
            RemoteVoicePolicy.nextAfterDelete(
                deletedId = "local",
                selectedId = "",
                followStem = "local",
                remaining = listOf("b"),
            ),
        )
    }

    @Test
    fun `a stale selection still counts as the effective voice`() {
        // ttsRemoteVoice may name a voice the PC no longer has; it is what the request
        // sends, so deleting a different voice must not touch it.
        assertEquals(
            "ghost",
            RemoteVoicePolicy.nextAfterDelete("a", "ghost", followStem = "local", remaining = listOf("b")),
        )
    }

    @Test
    fun `nothing to resolve means nothing changes`() {
        assertEquals("", RemoteVoicePolicy.nextAfterDelete("a", "", followStem = "", remaining = listOf("b")))
        assertEquals("a", RemoteVoicePolicy.nextAfterDelete("", "a", followStem = "local", remaining = listOf("b")))
    }

    @Test
    fun `ids are compared trimmed and the remaining list is filtered defensively`() {
        assertEquals(
            "b",
            RemoteVoicePolicy.nextAfterDelete(
                deletedId = "  a ",
                selectedId = "a",
                followStem = "local",
                remaining = listOf("a", "b"),
            ),
        )
    }
}
