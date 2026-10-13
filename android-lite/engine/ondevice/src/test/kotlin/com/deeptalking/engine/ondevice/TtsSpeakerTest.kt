package com.deeptalking.engine.ondevice

import com.deeptalking.core.model.TtsPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TtsSpeaker` is the seam that lets a second read-aloud engine be plugged in
 * without touching the caller (`AppViewModel`) or the UI. These tests pin the
 * contract a new implementation has to honour.
 */
class TtsSpeakerTest {

    /** Minimal in-memory engine: records the turns/phases it was asked to speak. */
    private class FakeSpeaker : TtsSpeaker {
        override val id: String = "fake-speaker"
        val spoken = mutableListOf<List<TtsTurn>>()
        val phases = mutableListOf<TtsPhase>()
        var cancelled = 0
        var stopped = 0

        override suspend fun speak(turns: List<TtsTurn>, onPhase: (TtsPhase) -> Unit) {
            spoken += turns
            onPhase(TtsPhase.Synthesizing)
            phases += TtsPhase.Synthesizing
            onPhase(TtsPhase.Playing)
            phases += TtsPhase.Playing
        }

        override fun cancel() { cancelled++ }

        override fun stop() { stopped++ }
    }

    @Test
    fun `a fake speaker satisfies the caller's contract`() = runTest {
        val speaker: TtsSpeaker = FakeSpeaker()
        assertEquals("fake-speaker", speaker.id)

        speaker.speak(listOf(TtsTurn("甲", "冷静"), TtsTurn("乙"))) { }

        val fake = speaker as FakeSpeaker
        assertEquals(1, fake.spoken.size)
        assertEquals("甲", fake.spoken[0][0].text)
        assertEquals("冷静", fake.spoken[0][0].style)
        assertEquals(listOf(TtsPhase.Synthesizing, TtsPhase.Playing), fake.phases)
    }

    @Test
    fun `phase callbacks and cancel are observable from a collector`() = runTest {
        val speaker = FakeSpeaker()
        val seen = MutableStateFlow(TtsPhase.Idle)
        val job = launch { speaker.speak(listOf(TtsTurn("hi"))) { seen.value = it } }
        job.join()
        assertEquals(TtsPhase.Playing, seen.first())

        speaker.cancel()
        speaker.stop()
        assertEquals(1, speaker.cancelled)
        assertEquals(1, speaker.stopped)
    }

    @Test
    fun `turn styling is optional`() {
        val turn = TtsTurn("只有文本")
        assertEquals(null, turn.style)
        assertTrue(turn.text.isNotEmpty())
        assertFalse(turn.text.isBlank())
    }
}
