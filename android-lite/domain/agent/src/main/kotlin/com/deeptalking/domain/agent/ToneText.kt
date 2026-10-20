package com.deeptalking.domain.agent

/**
 * Splits the plain-text reply protocol's trailing read-aloud directive off the body.
 *
 * The model writes the visible reply first, then — on its own line — `【语气】<朗读指导>`
 * (see `docs/OUTBOUND_CALLS_V2.md` §一(一)4). Only the exact `【语气】` marker is cut, so
 * ordinary bracketed text such as `【系统提示】` stays part of the body.
 */
internal object ToneText {

    const val MARKER = "【语气】"

    /** Tolerates full/half-width spaces inside the brackets, e.g. `【 语气 】`. */
    private val MARKER_REGEX = Regex("【\\s*语气\\s*】")

    /** A suffix that is an incomplete marker still being streamed (`【`, `【语`, `【语气`). */
    private val PARTIAL_REGEX = Regex("【\\s*(?:语(?:\\s*气)?)?\\s*$")

    private const val MAX_TONE_LENGTH = 200

    data class Split(val body: String, val tone: String?)

    /**
     * Splits [text] on the **last** marker. Without a marker the body is the text with
     * trailing blank lines removed and [Split.tone] is null.
     */
    fun split(text: String): Split {
        val marker = MARKER_REGEX.findAll(text).lastOrNull()
            ?: return Split(text.trimEnd(), null)
        val body = text.substring(0, marker.range.first).trimEnd()
        return Split(body, toneAfter(text, marker.range.last + 1))
    }

    /**
     * Streaming-safe view of [text]: everything before a complete marker, or before a
     * trailing partial marker (held back so `【语` never flashes up), with dangling
     * newlines removed. The tone itself is never returned.
     */
    fun display(text: String): String {
        MARKER_REGEX.findAll(text).lastOrNull()?.let { marker ->
            return text.substring(0, marker.range.first).trimEnd()
        }
        val partial = PARTIAL_REGEX.find(text)
        if (partial != null) return text.substring(0, partial.range.first).trimEnd()
        return text.trimEnd()
    }

    /** First line after the marker, minus leading colon/spaces, capped at 200 chars. */
    private fun toneAfter(text: String, start: Int): String? {
        if (start >= text.length) return null
        val firstLine = text.substring(start).substringBefore('\n').trimEnd('\r')
        val tone = firstLine.trim().trimStart(':', '：').trim()
        if (tone.isEmpty()) return null
        return tone.take(MAX_TONE_LENGTH).trim().ifEmpty { null }
    }
}
