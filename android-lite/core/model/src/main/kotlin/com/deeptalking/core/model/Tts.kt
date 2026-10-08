package com.deeptalking.core.model

/** Lifecycle of an on-device read-aloud request, driving the chat bubble feedback. */
enum class TtsPhase { Idle, Synthesizing, Playing, Done, Error }

private val IMAGE_MD = Regex("""!\[[^\]]*\]\([^)]*\)""")
private val LINK_MD = Regex("""\[([^\]]+)\]\([^)]*\)""")
private val CODE_SPAN = Regex("""`([^`]*)`""")
private val UNDERSCORE_EMPHASIS = Regex("""(?<![A-Za-z0-9])_([^_]+)_(?![A-Za-z0-9])""")
private val BRACKET_ASIDE = Regex("""【[^】]*】""")
private val HEADING = Regex("""^#{1,6}\s*""")
private val QUOTE = Regex("""^>\s?""")
private val BULLET = Regex("""^[-*+]\s+""")
private val ORDERED = Regex("""^\d+[.)]\s+""")
private val WHITESPACE = Regex("""\s+""")

/**
 * Extracts the speakable text from an assistant reply.
 *
 * Removes stage directions wrapped in （）/() (any same-line group), bracketed
 * 【】 asides, and Markdown markers (emphasis, code, links, headings, quotes,
 * list bullets), so the TTS engine only ever reads the spoken lines.
 */
fun extractSpeechText(source: String): String {
    if (source.isBlank()) return ""
    var s = source
    s = IMAGE_MD.replace(s, " ")
    s = LINK_MD.replace(s, "$1")
    s = CODE_SPAN.replace(s, "$1")
    s = s.replace("**", "").replace("__", "").replace("~~", "")
    s = UNDERSCORE_EMPHASIS.replace(s, "$1")
    s = s.replace("*", "")
    s = BRACKET_ASIDE.replace(s, " ")
    s = stripParentheticals(s)
    val cleaned = s.lines().map { line ->
        line.trim()
            .replace(HEADING, "")
            .replace(QUOTE, "")
            .replace(BULLET, "")
            .replace(ORDERED, "")
            .trim()
    }.filter { it.isNotEmpty() }
    return WHITESPACE.replace(cleaned.joinToString(" "), " ").trim()
}

/** Drops every same-line （…）/(…) stage direction, however long. */
private fun stripParentheticals(source: String): String {
    val sb = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (c == '(' || c == '（') {
            val close = if (c == '(') ')' else '）'
            val end = source.indexOf(close, i + 1)
            if (end > i && source.indexOf('\n', i) !in (i + 1)..end) {
                appendSeparatorIfAscii(sb, source.getOrNull(end + 1))
                i = end + 1
                continue
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/** Inserts a single space when a removed group sat between ASCII word characters. */
private fun appendSeparatorIfAscii(sb: StringBuilder, next: Char?) {
    val prev = sb.lastOrNull()
    val prevAscii = prev != null && prev.code < 128 && prev.isLetterOrDigit()
    val nextAscii = next != null && next.code < 128 && next.isLetterOrDigit()
    if (prevAscii || nextAscii) sb.append(' ')
}
