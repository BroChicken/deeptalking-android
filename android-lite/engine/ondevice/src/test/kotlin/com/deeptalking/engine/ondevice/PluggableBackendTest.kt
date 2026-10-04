package com.deeptalking.engine.ondevice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the on-device interfaces are truly pluggable: a fake backend can be
 * registered without the callers depending on any concrete model.
 */
class PluggableBackendTest {

    private class FakeLlm(override val id: String = "fake-llm") : LlmBackend {
        override fun stream(request: LlmRequest): Flow<LlmChunk> = flowOf(LlmChunk.Completed(LlmResult(text = "streamed")))
        override suspend fun complete(request: LlmRequest): LlmResult = LlmResult(text = "completed")
    }

    private class FakeEmbedding(override val id: String = "fake-embed") : EmbeddingBackend {
        override suspend fun embed(texts: List<String>): List<FloatArray> =
            texts.map { floatArrayOf(it.length.toFloat()) }
    }

    private class FakeAsr(override val id: String = "fake-asr") : AsrBackend {
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String = "heard"
    }

    private class FakeTts(override val id: String = "fake-tts") : TtsBackend {
        override suspend fun synthesize(text: String, voice: String?): Flow<AudioChunk> =
            flowOf(AudioChunk(ShortArray(0), 16_000, isLast = true))
    }

    @Test
    fun `llm-only registry leaves optional backends null`() {
        val registry = InferenceRegistry(llm = FakeLlm())
        assertEquals("fake-llm", registry.llm.id)
        assertNull(registry.embedding)
        assertNull(registry.asr)
        assertNull(registry.tts)
    }

    @Test
    fun `all backends can be registered and resolved`() = runBlocking {
        val registry = InferenceRegistry(
            llm = FakeLlm(),
            embedding = FakeEmbedding(),
            asr = FakeAsr(),
            tts = FakeTts(),
        )
        assertNotNull(registry.embedding)
        assertEquals(1, registry.embedding!!.embed(listOf("a")).size)
        assertEquals("heard", registry.asr!!.transcribe(ShortArray(0), 16_000))
        var sawLast = false
        registry.tts!!.synthesize("hi").collect { sawLast = it.isLast }
        assertTrue("fake tts should emit a final chunk", sawLast)
        assertEquals("completed", registry.llm.complete(LlmRequest(model = "m")).text)
    }
}
