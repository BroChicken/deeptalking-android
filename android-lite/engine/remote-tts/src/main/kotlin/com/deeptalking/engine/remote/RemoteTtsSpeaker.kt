package com.deeptalking.engine.remote

import com.deeptalking.core.model.TtsPhase
import com.deeptalking.engine.audio.PcmOutput
import com.deeptalking.engine.audio.PcmPlayer
import com.deeptalking.engine.ondevice.TtsSpeaker
import com.deeptalking.engine.ondevice.TtsTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns

/**
 * [TtsSpeaker] backed by a remote OpenAI Speech-compatible server
 * (`cosyvoice-server --api` on the PC, reached over USB tethering / LAN).
 *
 * Playback is incremental: each PCM chunk is written to the speaker as it arrives,
 * so first audio is heard before the utterance finishes and the socket applies
 * natural backpressure while the buffer is full.
 *
 * For a remote engine "cancel synthesis" and "stop playback" are the same
 * operation — closing the connection is what makes the server stop generating —
 * so [cancel] and [stop] are equivalent.
 */
class RemoteTtsSpeaker(
    private val source: RemoteTtsAudioSource,
    private val player: PcmOutput,
) : TtsSpeaker {

    /** Convenience wiring for production: HTTP source plus the platform player. */
    constructor(source: RemoteTtsClient) : this(source, PcmPlayer())

    /** Convenience wiring for production: HTTP source plus the platform player. */
    constructor(
        userAgent: String = "DeepTalking-Lite",
        dns: Dns = Dns.SYSTEM,
    ) : this(RemoteTtsClient(userAgent, dns), PcmPlayer())

    override val id: String = "remote-tts"

    @Volatile
    private var config: RemoteTtsConfig = RemoteTtsConfig()

    @Volatile
    private var activeCancel: RemoteTtsCancellation? = null

    @Volatile
    private var stopRequested = false

    /** Applies the current settings snapshot; called before every read-aloud. */
    fun configure(config: RemoteTtsConfig) {
        this.config = config
    }

    override suspend fun speak(turns: List<TtsTurn>, onPhase: (TtsPhase) -> Unit) = withContext(Dispatchers.IO) {
        val usable = turns.filter { it.text.isNotBlank() }
        if (usable.isNotEmpty()) {
            val current = config
            stopRequested = false
            player.begin(current.resolvedSampleRate)
            onPhase(TtsPhase.Synthesizing)
            var playing = false
            var aborted = false
            for (turn in usable) {
                if (aborted || stopRequested) break
                try {
                    source.speech(turn.text, turn.style, current) { handle -> activeCancel = handle }
                        .collect { chunk ->
                            if (chunk.isLast || aborted) return@collect
                            if (!playing && chunk.pcm.isNotEmpty()) {
                                playing = true
                                onPhase(TtsPhase.Playing)
                            }
                            if (!player.write(chunk.pcm, chunk.sampleRate)) {
                                aborted = true
                                // Stop the server generating; the read loop then ends and
                                // the collector closes the flow.
                                activeCancel?.cancel()
                            }
                        }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    // A teardown-induced socket error is not a read-aloud failure.
                    if (!stopRequested) throw t
                    aborted = true
                }
            }
        }
    }

    override fun cancel() {
        stopRequested = true
        player.stop()
        activeCancel?.cancel()
    }

    override fun stop() = cancel()

    fun release() {
        stopRequested = true
        player.release()
    }
}
