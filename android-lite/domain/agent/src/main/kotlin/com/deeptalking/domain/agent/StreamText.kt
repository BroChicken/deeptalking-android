package com.deeptalking.domain.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Display-only helpers for streamed assistant text, ported from
 * `src/js/api/response-parsing.js` / `src/js/api/responses.js`.
 *
 * The model may stream a structured JSON object or a partially JSON-quoted
 * `reply`; the loading bubble must only ever show the human-readable reply, never
 * raw JSON, unparsed `reply:{...}` fragments, or literal `\n` sequences.
 */
internal object StreamText {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private val DOUBLE_ESCAPED_NEWLINE = Regex("""\\\\n""")
    private val ESCAPED_NEWLINE = Regex("""\\n""")
    private val MEM_UPDATE_FULL = Regex(
        """<\s*MEM_UPDATE\s*>[\s\S]*?</\s*MEM_UPDATE\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val MEM_UPDATE_OPEN = Regex("""<\s*MEM_UPDATE\s*>""", RegexOption.IGNORE_CASE)
    private val REPLY_KEY = Regex(""""reply"\s*:""")
    private val REPAIRED_REPLY = Regex(""""reply"\s*:\s*("(?:[^"\\]|\\.)*")""")

    /** Restores literal `\n` to real newlines, keeping an already-double-escaped `\\n` literal. */
    fun unescapeLiteralNewlines(text: String): String =
        text.replace(DOUBLE_ESCAPED_NEWLINE, "\u0000")
            .replace(ESCAPED_NEWLINE, "\n")
            .replace("\u0000", "\\n")

    /** Strips any `MEM_UPDATE` tail and unescapes literal newlines (legacy `getDisplayText`). */
    fun getDisplayText(text: String): String {
        var result = text
        val full = MEM_UPDATE_FULL.find(result)
        if (full != null) {
            result = result.replace(full.value, "").trim()
        } else {
            val open = MEM_UPDATE_OPEN.find(result)
            if (open != null) result = result.substring(0, open.range.first).trim()
        }
        return unescapeLiteralNewlines(result)
    }

    /** Best-effort extraction of the `reply` string from a (possibly partial) JSON document. */
    fun extractReplyFromJson(raw: String?): String? {
        val text = raw ?: return null
        val key = REPLY_KEY.find(text) ?: return null
        var i = key.range.last + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '"') return null
        val start = i
        var j = i + 1
        var backslashes = 0
        while (j < text.length) {
            val ch = text[j]
            if (ch == '\\') {
                backslashes++
                j++
                continue
            }
            if (ch == '"') {
                if (backslashes % 2 == 0) {
                    val token = text.substring(start, j + 1)
                    val parsed = runCatching {
                        (json.parseToJsonElement(token) as? JsonPrimitive)?.contentOrNull
                    }.getOrNull()
                    if (parsed != null) return parsed
                    // Unescaped quotes / control chars inside the reply value: apply
                    // the same targeted repair then re-extract (legacy responses.js:186-193).
                    val repaired = ResponseParser.repairFreetextFields(text)
                    val repairedMatch = REPAIRED_REPLY.find(repaired)
                    if (repairedMatch != null) {
                        return runCatching {
                            (json.parseToJsonElement(repairedMatch.groupValues[1]) as? JsonPrimitive)?.contentOrNull
                        }.getOrNull()
                    }
                    return null
                }
                backslashes = 0
                j++
                continue
            }
            backslashes = 0
            j++
        }
        return parsePartialReply(text.substring(start))
    }

    private fun parsePartialReply(candidateWithQuote: String): String? {
        var candidate = candidateWithQuote
        repeat(12) {
            runCatching {
                val element = json.parseToJsonElement(candidate + "\"")
                return (element as? JsonPrimitive)?.contentOrNull
            }
            if (candidate.length <= 1) return null
            candidate = candidate.dropLast(1)
        }
        return null
    }

    /**
     * Computes what the streaming bubble should show. Returns null when nothing
     * safe to display is available yet (e.g. raw JSON with no extractable reply).
     */
    fun displayFor(fullText: String, salvaged: String?): String? {
        (salvaged ?: extractReplyFromJson(fullText))?.let { reply ->
            return getDisplayText(reply).takeIf { it.isNotBlank() }
        }
        val trimmed = fullText.trimStart()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("{")) {
            return getDisplayText(fullText).takeIf { it.isNotBlank() }
        }
        return null
    }
}
