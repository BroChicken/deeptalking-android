package com.deeptalking.engine.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTtsRequestTest {

    private val config = RemoteTtsConfig(
        baseUrl = "http://10.84.116.224:8080/",
        token = "sk-local",
        model = "cosyvoice-3",
        voice = "v1",
        speed = 1.25f,
    )

    @Test
    fun `speech body asks for one-shot raw PCM`() {
        val body = buildSpeechBody("你好", config, style = null)
        assertTrue(body.contains("\"model\":\"cosyvoice-3\""))
        assertTrue(body.contains("\"voice\":\"v1\""))
        assertTrue(body.contains("\"input\":\"你好\""))
        assertTrue(body.contains("\"response_format\":\"pcm\""))
        // Non-streaming: the server returns the complete payload, so the phone never
        // plays chunks faster than the PC can generate them (that is what stuttered).
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"speed\":1.25"))
    }

    @Test
    fun `a blank style omits instructions so the server stays in zero-shot mode`() {
        assertFalse(buildSpeechBody("hi", config, style = null).contains("instructions"))
        assertFalse(buildSpeechBody("hi", config, style = "   ").contains("instructions"))
    }

    @Test
    fun `a style becomes the instructions field verbatim`() {
        val body = buildSpeechBody("hi", config, style = "平静地说，语速偏慢")
        assertTrue(body.contains("\"instructions\":\"平静地说，语速偏慢\""))
    }

    @Test
    fun `text is JSON-escaped`() {
        val body = buildSpeechBody("他说：\"走了\"\n然后转身", config, style = null)
        assertTrue(body.contains("\\\"走了\\\""))
        assertTrue(body.contains("\\n"))
    }

    @Test
    fun `speed is clamped to the range the server accepts`() {
        assertTrue(buildSpeechBody("hi", config.copy(speed = 9f), null).contains("\"speed\":2.0"))
        assertTrue(buildSpeechBody("hi", config.copy(speed = 0f), null).contains("\"speed\":0.5"))
    }

    @Test
    fun `speech url ignores a trailing slash and the sample rate falls back to 24k`() {
        assertEquals("http://10.84.116.224:8080/v1/audio/speech", speechUrl(config.baseUrl))
        assertEquals(24000, config.resolvedSampleRate)
        assertEquals(22050, config.copy(sampleRate = 22050).resolvedSampleRate)
    }

    @Test
    fun `shared knobs are always sent with the values shown in settings`() {
        val body = buildSpeechBody("hi", config, style = null)
        // Seeded from the model's own metadata, so the phone and the PC always agree on
        // what was used (see docs/REMOTE_TTS.md section 9).
        assertTrue(body.contains("\"chunk_tokens\":28"))
        assertTrue(body.contains("\"temperature\":1.0"))
        assertTrue(body.contains("\"top_k\":25"))
        assertTrue(body.contains("\"top_p\":0.8"))
        assertTrue(body.contains("\"win_size\":10"))
        assertTrue(body.contains("\"tau_r\":0.1"))
        assertTrue(body.contains("\"min_token_text_ratio\":2.0"))
        assertTrue(body.contains("\"max_token_text_ratio\":20.0"))

        val tuned = buildSpeechBody(
            "hi",
            config.copy(
                chunkTokens = 16,
                temperature = 0.6,
                topK = 10,
                topP = 0.5,
                winSize = 20,
                tauR = 0.3,
                minTokenTextRatio = 3.0,
                maxTokenTextRatio = 12.0,
            ),
            style = null,
        )
        assertTrue(tuned.contains("\"chunk_tokens\":16"))
        assertTrue(tuned.contains("\"temperature\":0.6"))
        assertTrue(tuned.contains("\"top_k\":10"))
        assertTrue(tuned.contains("\"top_p\":0.5"))
        assertTrue(tuned.contains("\"win_size\":20"))
        assertTrue(tuned.contains("\"tau_r\":0.3"))
        assertTrue(tuned.contains("\"min_token_text_ratio\":3.0"))
        assertTrue(tuned.contains("\"max_token_text_ratio\":12.0"))
    }

    @Test
    fun `bearer token tolerates a pasted prefix and blank values`() {
        assertNull(bearerToken(null))
        assertNull(bearerToken("   "))
        assertEquals("Bearer sk-a", bearerToken("sk-a"))
        assertEquals("Bearer sk-a", bearerToken("bearer sk-a"))
        assertEquals("Bearer sk-a", bearerToken("  Bearer sk-a  "))
    }

    @Test
    fun `stored zero knobs fall back to the model defaults`() {
        // Builds that predate "always send the knobs" stored 0 for all of them ("leave it
        // to the model"). The server rejects temperature 0, so 0 must never go out.
        val body = buildSpeechBody(
            "hi",
            config.copy(
                chunkTokens = 0,
                temperature = 0.0,
                topK = 0,
                topP = 0.0,
                winSize = 0,
                tauR = 0.0,
                minTokenTextRatio = 0.0,
                maxTokenTextRatio = 0.0,
            ),
            style = null,
        )
        assertTrue(body.contains("\"chunk_tokens\":28"))
        assertTrue(body.contains("\"temperature\":1.0"))
        assertTrue(body.contains("\"top_k\":25"))
        assertTrue(body.contains("\"top_p\":0.8"))
        assertTrue(body.contains("\"win_size\":10"))
        assertTrue(body.contains("\"tau_r\":0.1"))
        assertTrue(body.contains("\"min_token_text_ratio\":2.0"))
        assertTrue(body.contains("\"max_token_text_ratio\":20.0"))
    }

    @Test
    fun `voice upload url targets v1 voices and escapes the name`() {
        assertEquals(
            "http://10.84.116.224:8080/v1/voices?name=builtin_1_v2",
            voiceUploadUrl(config.baseUrl, "builtin_1_v2"),
        )
        assertEquals("http://pc:8080/v1/voices?name=a%2Bb", voiceUploadUrl("http://pc:8080/", "a+b"))
    }
}
