package com.deeptalking.core.model

import kotlinx.serialization.Serializable

/**
 * One speaker turn inside a single assistant message.
 *
 * A group reply stays **one** [ChatMessage]: history, the `instant` window and the fold
 * rhythm keep counting it as one message, while [ChatMessage.segments] carries the
 * per-member turns so the chat can draw one bubble per member and the turn worker /
 * read-aloud can use a real speaker instead of guessing from the text.
 *
 * - [speaker] is the member name as listed on the character. It is **empty** when the
 *   turn could not be attributed to a known member (see [parseGroupSpeechSegments]).
 * - [text] is the display text: it keeps the original quotes, punctuation and stage
 *   directions (`（笑）`) because the bubble shows exactly what the model wrote.
 *   Read-aloud uses [extractSpeechSegments], which cleans the text for TTS.
 */
@Serializable
data class SpeechSegment(
    val speaker: String = "",
    val text: String = "",
) {
    /** True when this turn belongs to a member of the group. */
    val isAttributed: Boolean get() = speaker.isNotBlank()
}

/**
 * Splits a group assistant reply into display [SpeechSegment]s.
 *
 * The reply format is one `成员名：内容` line per turn, with the speaker's turn spanning
 * the following lines until the next speaker line. Rules:
 *
 * - Only names in [memberNames] are accepted as a speaker (matched at the start of a
 *   line, followed by a full-width `：` or a half-width `:`). Order does not matter;
 *   the longest matching name wins, so `张三丰` is not read as `张三`.
 * - Content is preserved as written (quotes, `（动作）`, Markdown); only the speaker
 *   prefix and the surrounding blank lines are removed.
 * - Attribution is **never guessed**: if no line starts with a known member name, the
 *   whole reply becomes a single segment with an empty speaker. The same happens for
 *   a [memberNames] list that is empty (single-character chat).
 * - A reply that is only speaker prefixes (`张三：`) falls back to the raw text as one
 *   unattributed segment, so nothing is silently dropped.
 *
 * Blank input yields an empty list. Consecutive lines by the same speaker stay separate
 * segments (two short bubbles) rather than being merged.
 */
fun parseGroupSpeechSegments(source: String, memberNames: List<String>): List<SpeechSegment> {
    if (source.isBlank()) return emptyList()
    val names = memberNames
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
    if (names.isEmpty()) return listOf(SpeechSegment(text = source.trim()))

    data class Pending(val speaker: String, val body: StringBuilder)

    val pending = mutableListOf<Pending>()
    for (rawLine in source.lines()) {
        val line = rawLine.trim()
        val speaker = names.firstOrNull { name ->
            line.startsWith(name) && line.getOrNull(name.length).let { it == '：' || it == ':' }
        }
        when {
            speaker != null ->
                pending += Pending(speaker, StringBuilder(line.substring(speaker.length + 1).trimStart()))

            pending.isEmpty() -> pending += Pending("", StringBuilder(line))

            line.isNotEmpty() -> {
                val last = pending.last()
                if (last.body.isNotEmpty()) last.body.append('\n')
                last.body.append(line)
            }
        }
    }

    val segments = pending
        .map { SpeechSegment(speaker = it.speaker, text = it.body.toString().trim()) }
        .filter { it.text.isNotEmpty() }
    return segments.ifEmpty { listOf(SpeechSegment(text = source.trim())) }
}

/**
 * Segments the chat UI should draw for one message.
 *
 * New assistant rows persist [ChatMessage.segments]. Rows written before that field was
 * persisted (or imported without it) fall back to parsing the raw `成员名：内容` text;
 * the fallback is only enabled when at least one known member is attributed, otherwise
 * the normal single-bubble layout stays in place. Single characters and in-flight rows
 * never get segments.
 */
fun displaySpeechSegments(message: ChatMessage, character: Character): List<SpeechSegment> {
    if (message.role != Role.Assistant || message.isLoading) return emptyList()
    val stored = message.segments.filter { it.text.isNotBlank() }
    if (stored.isNotEmpty()) return stored
    if (!character.isGroup || message.content.isBlank()) return emptyList()
    val parsed = parseGroupSpeechSegments(message.content, character.members.map { it.name })
    return if (parsed.any { it.speaker.isNotBlank() }) parsed else emptyList()
}
