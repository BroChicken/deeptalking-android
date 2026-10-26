package com.deeptalking.engine.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVoiceCatalogTest {

    @Test
    fun `manifest keeps id name size and hash`() {
        val body = """
            {"voices":[
              {"id":"builtin_1_v2","name":"内置音色 1","size":41234,"hash":"0f0e0d0c"},
              {"id":"voice-ab12","name":"我的声音","size":40000,"hash":"deadbeef"}
            ]}
        """.trimIndent()

        val voices = parseVoiceManifest(body)

        assertEquals(listOf("builtin_1_v2", "voice-ab12"), voices.map { it.id })
        assertEquals("内置音色 1", voices[0].name)
        assertEquals(41234L, voices[0].size)
        assertEquals("0f0e0d0c", voices[0].hash)
    }

    @Test
    fun `a missing or blank name falls back to the id`() {
        // Display names were added later than ids, so an old server sends ids only.
        val voices = parseVoiceManifest("""{"voices":[{"id":"a"},{"id":"b","name":"   "}]}""")
        assertEquals(listOf("a" to "a", "b" to "b"), voices.map { it.id to it.name })
    }

    @Test
    fun `entries without a usable id are skipped`() {
        // The id is the speaker name the speech request needs (and the file name on the
        // PC); an entry without one can never be selected, so it is worthless.
        val voices = parseVoiceManifest("""{"voices":[{"name":"没有 id"},{"id":"   "},{"id":"ok"}]}""")
        assertEquals(listOf("ok"), voices.map { it.id })
    }

    @Test
    fun `bad json yields an empty list`() {
        assertEquals(emptyList<RemoteVoiceInfo>(), parseVoiceManifest("<html>404</html>"))
        assertEquals(emptyList<RemoteVoiceInfo>(), parseVoiceManifest(""))
        assertEquals(emptyList<RemoteVoiceInfo>(), parseVoiceManifest("""{"voices":"nope"}"""))
        // A server without the endpoint answers `{}` or a plain object: "no manifest",
        // not "the PC has no voices".
        assertEquals(emptyList<RemoteVoiceInfo>(), parseVoiceManifest("""{}"""))
    }

    @Test
    fun `unknown fields are ignored and one junk entry does not kill the manifest`() {
        val body = """{"voices":[{"id":"a","extra":1,"nested":{"x":[1]}},42,{"id":"b"}],"next":null}"""
        assertEquals(listOf("a", "b"), parseVoiceManifest(body).map { it.id })
    }

    @Test
    fun `item url ignores a trailing slash and percent-encodes the id`() {
        assertEquals(
            "http://pc:8080/v1/voices/builtin_1_v2",
            voiceItemUrl("http://pc:8080/", "builtin_1_v2"),
        )
        // A space must be %20 in a path segment (`+` only means space in a query string).
        assertEquals("http://pc:8080/v1/voices/a%20b", voiceItemUrl("http://pc:8080", "a b"))
        assertEquals("http://pc:8080/v1/voices/a%2Fb", voiceItemUrl("  http://pc:8080/  ", "a/b"))
    }

    @Test
    fun `manifest names are trimmed like the server does when it persists them`() {
        val voices = parseVoiceManifest("""{"voices":[{"id":"a","name":"  电脑名  "}]}""")
        assertEquals("电脑名", voices.single().name)
        assertTrue(voices.single().name == "电脑名")
    }
}
