package com.deeptalking.engine.ondevice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferenceRegistryTest {

    private val fake = object : LlmBackend {
        override val id: String = "fake"
        override fun stream(request: LlmRequest): Flow<LlmChunk> = emptyFlow()
        override suspend fun complete(request: LlmRequest): LlmResult = LlmResult("hi")
    }

    @Test
    fun llmIdIsExposed() {
        assertEquals("fake", InferenceRegistry(fake).llm.id)
    }

    @Test
    fun optionalBackendsAreNullByDefault() {
        val registry = InferenceRegistry(fake)
        assertNull(registry.embedding)
        assertNull(registry.asr)
        assertNull(registry.tts)
    }
}
