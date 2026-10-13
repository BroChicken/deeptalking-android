package com.deeptalking.engine.remote

import com.deeptalking.core.model.TtsPhase
import com.deeptalking.engine.audio.PcmOutput
import com.deeptalking.engine.ondevice.AudioChunk
import com.deeptalking.engine.ondevice.TtsTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The remote engine has to honour the same `TtsSpeaker` contract as the on-device
 * one: turns in order, `Synthesizing` → `Playing` once, prompt stop, and no bogus
 * error when the stop itself tears the socket down.
 */
class RemoteTtsSpeakerTest {

    private class FakePlayer(
        /** Called after each accepted write, so a test can simulate a user stop. */
        var onWrite: () -> Unit = {},
    ) : PcmOutput {
        val chunks = mutableListOf<ShortArray>()
        val rates = mutableListOf<Int>()
        var beginRate = 0
        var stopped = false

        /** Writes accepted before the sink reports "stopped"; Int.MAX_VALUE = never. */
        var acceptWrites = Int.MAX_VALUE

        override fun begin(rate: Int) {
            beginRate = rate
            stopped = false
        }

        override fun write(samples: ShortArray, rate: Int): Boolean {
            if (stopped) return false
            chunks += samples
            rates += rate
            onWrite()
            return chunks.size < acceptWrites
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            stopped = true
        }
    }

    private class RecordingCancellation : RemoteTtsCancellation {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeSource(
        private val chunk: ShortArray = shortArrayOf(1, 2, 3),
        private val fail: Throwable? = null,
    ) : RemoteTtsAudioSource {
        val requests = mutableListOf<Pair<String, String?>>()
        val configsSeen = mutableListOf<RemoteTtsConfig>()
        val handles = mutableListOf<RecordingCancellation>()

        /** Throw an [IOException] once the request has been cancelled (socket teardown). */
        var failOnCancel = false

        val cancels: Int get() = handles.count { it.cancelled }

        override fun speech(
            text: String,
            style: String?,
            config: RemoteTtsConfig,
            registerCancel: (RemoteTtsCancellation?) -> Unit,
        ): Flow<AudioChunk> = flow {
            requests += text to style
            configsSeen += config
            val handle = RecordingCancellation()
            handles += handle
            registerCancel.invoke(handle)
            try {
                fail?.let { throw it }
                emit(AudioChunk(chunk, config.resolvedSampleRate, isLast = false))
                if (failOnCancel && handle.cancelled) throw IOException("socket closed")
                emit(AudioChunk(chunk, config.resolvedSampleRate, isLast = false))
                if (failOnCancel && handle.cancelled) throw IOException("socket closed")
                emit(AudioChunk(ShortArray(0), config.resolvedSampleRate, isLast = true))
            } finally {
                registerCancel.invoke(null)
            }
        }
    }

    private val config = RemoteTtsConfig(
        baseUrl = "http://10.84.116.224:8080",
        token = "sk-local",
        model = "cosyvoice-3",
        voice = "v1",
        speed = 1.0f,
        sampleRate = 24000,
    )

    @Test
    fun `turns are spoken in order with their own styles and one phase flip`() = runTest {
        val source = FakeSource()
        val player = FakePlayer()
        val speaker = RemoteTtsSpeaker(source, player)
        speaker.configure(config)
        val phases = mutableListOf<TtsPhase>()

        speaker.speak(listOf(TtsTurn("甲", "冷静"), TtsTurn("乙"))) { phases += it }

        assertEquals(listOf("甲", "冷静", "乙", null), source.requests.flatMap { listOf(it.first, it.second) })
        assertEquals(listOf(TtsPhase.Synthesizing, TtsPhase.Playing), phases)
        assertEquals(4, player.chunks.size)
        assertEquals(24000, player.beginRate)
        assertTrue(player.rates.all { it == 24000 })
    }

    @Test
    fun `blank turns are skipped entirely`() = runTest {
        val source = FakeSource()
        val player = FakePlayer()
        val speaker = RemoteTtsSpeaker(source, player)
        speaker.configure(config)
        val phases = mutableListOf<TtsPhase>()

        speaker.speak(listOf(TtsTurn("  "), TtsTurn("", "tone"))) { phases += it }

        assertTrue(source.requests.isEmpty())
        assertEquals(0, player.chunks.size)
        assertTrue(phases.isEmpty())
    }

    @Test
    fun `a missing base url fails loudly instead of playing silence`() = runTest {
        val speaker = RemoteTtsSpeaker(RemoteTtsClient(), FakePlayer())
        speaker.configure(RemoteTtsConfig(baseUrl = ""))
        val failure = runCatching { speaker.speak(listOf(TtsTurn("hi"))) { } }.exceptionOrNull()
        assertTrue(failure is RemoteTtsException)
        assertTrue(failure!!.message!!.contains("未配置"))
    }

    @Test
    fun `a sink that reports stopped aborts the stream and cancels the request`() = runTest {
        val source = FakeSource()
        val player = FakePlayer().apply { acceptWrites = 1 }
        val speaker = RemoteTtsSpeaker(source, player)
        speaker.configure(config)

        speaker.speak(listOf(TtsTurn("一段很长的话"))) { }

        assertEquals(1, player.chunks.size)
        assertEquals(1, source.cancels)
    }

    @Test
    fun `a teardown-induced socket error is not reported as a failure`() = runTest {
        val source = FakeSource().apply { failOnCancel = true }
        lateinit var speaker: RemoteTtsSpeaker
        val player = FakePlayer(onWrite = { speaker.cancel() }).apply { acceptWrites = 1 }
        speaker = RemoteTtsSpeaker(source, player)
        speaker.configure(config)

        // Must not throw: the IOException comes from our own cancel().
        speaker.speak(listOf(TtsTurn("说话"))) { }

        assertTrue(player.stopped)
        assertTrue(source.cancels >= 1)
    }

    @Test
    fun `a genuine server failure propagates`() = runTest {
        val source = FakeSource(fail = RemoteTtsException("TTS HTTP 500：boom"))
        val speaker = RemoteTtsSpeaker(source, FakePlayer())
        speaker.configure(config)

        val failure = runCatching { speaker.speak(listOf(TtsTurn("hi"))) { } }.exceptionOrNull()

        assertTrue(failure is RemoteTtsException)
        assertEquals("TTS HTTP 500：boom", failure!!.message)
    }

    @Test
    fun `stop is the same teardown as cancel for a remote engine`() = runTest {
        val source = FakeSource()
        val player = FakePlayer()
        val speaker = RemoteTtsSpeaker(source, player)
        speaker.configure(config)

        speaker.stop()

        assertTrue(player.stopped)
        assertNull(source.configsSeen.firstOrNull())
    }
}
