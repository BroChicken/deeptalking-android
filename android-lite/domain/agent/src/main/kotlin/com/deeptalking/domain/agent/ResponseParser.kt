package com.deeptalking.domain.agent

import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.MemorySubject
import com.deeptalking.core.model.PromiseStatus
import com.deeptalking.core.model.ShortTermMemory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A `promiseUpdates` entry from `submit_response`. */
data class PromiseUpdate(
    val promiseId: String,
    val status: String,
    val sourceMessageIds: List<String> = emptyList(),
    val evidence: String = "",
)

/** A `recall` request from `submit_response`. */
data class RecallRequest(
    val category: String?,
    val tags: List<String> = emptyList(),
)

/** A `memberDynamicState` entry from `submit_response` (group conversations). */
data class MemberDynamicStateUpdate(
    val memberName: String,
    val dynamicState: JsonObject,
)

/** Parsed `submit_response` payload. Raw JSON sub-objects are kept for the orchestrator. */
data class ParsedTurn(
    val reply: String,
    val quickReplies: List<String> = emptyList(),
    val shortTerm: List<ShortTermMemory> = emptyList(),
    val longTerm: List<LongTermMemory> = emptyList(),
    val dynamicState: JsonObject? = null,
    val staticFields: JsonObject? = null,
    val memberDynamicState: List<MemberDynamicStateUpdate> = emptyList(),
    val promiseUpdates: List<PromiseUpdate> = emptyList(),
    val recall: RecallRequest? = null,
    val raw: JsonObject? = null,
)

/**
 * Parses structured model output (`submit_response` arguments or a raw JSON
 * body) with a lenient JSON repair path ported from response-parsing.js.
 */
object ResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val FREE_TEXT_KEYS = listOf(
        "reply", "content", "value", "key", "evidence", "question", "summary", "description", "note", "reason",
    )

    private const val TRAILING_COMMA = ",(\\s*[}\\]])"

    private val trailingCommaRegex = Regex(TRAILING_COMMA)

    private val JSON_SHAPES = listOf('{' to '}', '[' to ']')

    // ---------------------------------------------------------------- entry points

    fun parseSubmitResponse(arguments: String?): ParsedTurn? {
        val root = parseJsonLenient(arguments.orEmpty()) as? JsonObject ?: return null
        if (root.string("reply").orEmpty().isBlank()) return null
        return buildTurn(root)
    }

    /** Parse a whole assistant text that may be a JSON object or plain prose. */
    fun parseTurnFromText(text: String): ParsedTurn {
        val root = parseJsonLenient(text) as? JsonObject
        if (root != null && !root.string("reply").orEmpty().isBlank()) {
            return buildTurn(root)
        }
        return ParsedTurn(reply = unescapeLiteralNewlines(text))
    }

    fun buildTurn(root: JsonObject): ParsedTurn {
        val reply = unescapeLiteralNewlines(root.string("reply").orEmpty())
        val quickReplies = parseQuickReplyList(root["quickReplies"])
        val shortTerm = (root["shortTerm"] as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val content = obj.string("content").orEmpty()
            if (content.isBlank()) return@mapNotNull null
            ShortTermMemory(
                content = content,
                sourceMessageIds = stringList(obj["sourceMessageIds"]),
            )
        }.orEmpty()
        val longTerm = (root["longTerm"] as? JsonArray)?.mapNotNull { item ->
            (item as? JsonObject)?.let(::parseLongTerm)
        }.orEmpty()
        val memberDynamicState = (root["memberDynamicState"] as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.string("memberName")
            val state = obj["dynamicState"] as? JsonObject
            if (name.isNullOrBlank() || state == null) return@mapNotNull null
            MemberDynamicStateUpdate(name, state)
        }.orEmpty()
        val promiseUpdates = (root["promiseUpdates"] as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.string("promiseId")
            val status = obj.string("status")
            if (id.isNullOrBlank() || status.isNullOrBlank()) return@mapNotNull null
            PromiseUpdate(id, status, stringList(obj["sourceMessageIds"]), obj.string("evidence").orEmpty())
        }.orEmpty()
        val recall = (root["recall"] as? JsonObject)?.let { obj ->
            RecallRequest(obj.string("category"), stringList(obj["tags"]))
        }
        return ParsedTurn(
            reply = reply,
            quickReplies = quickReplies,
            shortTerm = shortTerm,
            longTerm = longTerm,
            dynamicState = root["dynamicState"] as? JsonObject,
            staticFields = root["staticFields"] as? JsonObject,
            memberDynamicState = memberDynamicState,
            promiseUpdates = promiseUpdates,
            recall = recall,
            raw = root,
        )
    }

    // ---------------------------------------------------------------- quick replies

    fun parseQuickReplyList(raw: String?): List<String> =
        parseQuickReplyList(parseJsonLenient(raw.orEmpty()))

    fun parseQuickReplyList(content: JsonElement?): List<String> {
        val values: JsonArray? = when (content) {
            is JsonArray -> content
            is JsonObject -> content["quickReplies"] as? JsonArray
                ?: content["replies"] as? JsonArray
                ?: content["suggestions"] as? JsonArray
            is JsonPrimitive -> parseJsonLenient(content.contentOrNull.orEmpty()) as? JsonArray
            else -> null
        }
        if (values == null) return emptyList()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (item in values) {
            val text = when (item) {
                is JsonObject -> item.string("text") ?: item.string("content") ?: item.string("reply").orEmpty()
                is JsonPrimitive -> item.contentOrNull.orEmpty()
                else -> ""
            }
            val normalized = text.take(80).replace(Regex("\\s+"), " ").trim()
            if (normalized.isEmpty()) continue
            if (!seen.add(normalized.lowercase())) continue
            result += normalized
            if (result.size >= 2) break
        }
        return result
    }

    fun parseQuickRepliesFromText(text: String): List<String> {
        val match = Regex("[\"']?quickReplies[\"']?\\s*[:=]\\s*(\\[[\\s\\S]*?])", RegexOption.IGNORE_CASE)
            .find(text) ?: return emptyList()
        val array = parseJsonLenient(match.groupValues[1]) as? JsonArray ?: return emptyList()
        return parseQuickReplyList(array)
    }

    // ---------------------------------------------------------------- lenient JSON

    fun parseJsonLenient(text: String): JsonElement? {
        val cleaned = stripFences(text).trim()
        if (cleaned.isEmpty()) return null
        parseOrNull(cleaned)?.let { return it }
        parseOrNull(removeTrailingCommas(cleaned))?.let { return it }

        val repaired = repairFreetextFields(cleaned)
        parseOrNull(repaired)?.let { return it }
        parseOrNull(removeTrailingCommas(repaired))?.let { return it }

        val shapeStart = cleaned.indexOf('{')
        val shapeEnd = cleaned.lastIndexOf('}')
        if (shapeStart in 0 until shapeEnd) {
            val slice = cleaned.substring(shapeStart, shapeEnd + 1)
            val repairedSlice = repairFreetextFields(slice)
            parseOrNull(repairedSlice)?.let { return it }
            parseOrNull(removeTrailingCommas(repairedSlice))?.let { return it }
        }

        JSON_SHAPES.forEach { (open, close) ->
            val start = cleaned.indexOf(open)
            val end = cleaned.lastIndexOf(close)
            if (start in 0 until end) {
                val slice = cleaned.substring(start, end + 1)
                parseOrNull(removeTrailingCommas(safeJson(slice)))?.let { return it }
            }
        }
        parseOrNull(removeTrailingCommas(safeJson(cleaned)))?.let { return it }
        return null
    }

    /**
     * Escapes bare control characters that appear inside JSON string literals
     * (port of `safeJson` in response-parsing.js:267-289). The model often emits
     * real newlines/tabs inside `reply`-style values, which strict JSON rejects.
     */
    internal fun safeJson(value: String): String {
        val result = StringBuilder()
        var inString = false
        var escaped = false
        for (ch in value) {
            if (inString) {
                when {
                    escaped -> { escaped = false; result.append(ch) }
                    ch == '\\' -> { escaped = true; result.append(ch) }
                    ch == '"' -> { inString = false; result.append(ch) }
                    ch.code < 0x20 -> result.append("\\u%04x".format(ch.code))
                    else -> result.append(ch)
                }
            } else {
                if (ch == '"') inString = true
                result.append(ch)
            }
        }
        return result.toString()
    }

    private fun parseOrNull(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()

    // ---------------------------------------------------------------- output salvage

    /**
     * Port of `extractResponsesText` (responses.js:92-104): the canonical
     * `output_text` or the joined `output[type=message] > content[type=output_text]`.
     */
    fun extractResponsesText(response: JsonObject?): String {
        if (response == null) return ""
        (response["output_text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { return it }
        val output = response["output"] as? JsonArray ?: return ""
        return output.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj.string("type") != "message") return@mapNotNull null
            val content = obj["content"] as? JsonArray ?: return@mapNotNull null
            content.mapNotNull { part ->
                val p = part as? JsonObject ?: return@mapNotNull null
                if (p.string("type") != "output_text") return@mapNotNull null
                p.string("text").orEmpty()
            }.joinToString("")
        }.joinToString("")
    }

    /**
     * Port of `extractAnyResponseText` (responses.js:106-124): collects every
     * `output_text` node anywhere in the tree except under a `reasoning` item.
     */
    fun extractAnyResponseText(response: JsonObject?): String {
        if (response == null) return ""
        val chunks = mutableListOf<String>()
        fun collect(node: JsonElement?) {
            when (node) {
                null -> return
                is JsonArray -> node.forEach { collect(it) }
                is JsonObject -> {
                    val type = node.string("type")
                    if (type == "reasoning") return
                    if (type == "output_text") {
                        val text = node.string("text").orEmpty()
                        if (text.isNotBlank()) chunks += text
                        return
                    }
                    (node["content"] as? JsonArray)?.forEach { collect(it) }
                }
                else -> Unit
            }
        }
        collect(response["output"] ?: response)
        val combined = chunks.joinToString("\n").trim()
        if (combined.isNotEmpty()) return combined
        return (response["output_text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    }

    /**
     * Port of `extractReplyJsonFromAnyOutput` (responses.js:126-149): scans
     * `reasoning` items (content and summary text, plus optional extra reasoning
     * items) for an embedded `"reply"` JSON object and returns the first candidate.
     */
    fun extractReplyJsonFromAnyOutput(response: JsonObject?, extraReasoning: List<String> = emptyList()): String {
        val candidates = mutableListOf<String>()
        fun scanTexts(texts: List<String>) {
            texts.forEach { txt ->
                val idx = txt.indexOf("\"reply\"")
                if (idx < 0) return@forEach
                val start = txt.lastIndexOf('{', idx)
                if (start < 0) return@forEach
                val candidate = txt.substring(start)
                val parsed = parseJsonLenient(candidate) as? JsonObject
                if (parsed != null && !parsed.string("reply").orEmpty().isBlank()) candidates += candidate
            }
        }
        val output = response?.get("output") as? JsonArray
        output?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            if (obj.string("type") != "reasoning") return@forEach
            scanTexts(reasoningTexts(obj))
        }
        if (candidates.isEmpty()) {
            extraReasoning.forEach { raw ->
                val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                if (obj != null) scanTexts(reasoningTexts(obj))
            }
        }
        return candidates.firstOrNull().orEmpty()
    }

    private fun reasoningTexts(obj: JsonObject): List<String> {
        val texts = mutableListOf<String>()
        (obj["content"] as? JsonArray)?.forEach { (it as? JsonObject)?.string("text")?.let(texts::add) }
        (obj["summary"] as? JsonArray)?.forEach { (it as? JsonObject)?.string("text")?.let(texts::add) }
        return texts
    }

    /** Removes a trailing comma before a closing brace/bracket. */
    private fun removeTrailingCommas(text: String): String =
        trailingCommaRegex.replace(text) { it.groupValues[1] }

    private fun stripFences(text: String): String {
        var value = text.trim()
        if (value.startsWith("```")) {
            value = value.removePrefix("```json").removePrefix("```")
            val end = value.lastIndexOf("```")
            if (end >= 0) value = value.substring(0, end)
        }
        return value.trim()
    }

    // ---------------------------------------------------------------- repair

    private val keyPattern = Regex("\"(${FREE_TEXT_KEYS.joinToString("|")})\"\\s*:")

    /** Port of repairFreetextFieldsByScan: re-escape quotes/newlines inside free-text values. */
    internal fun repairFreetextFields(raw: String): String {
        if (raw.isEmpty()) return raw
        val source = repairStringArrayField(raw, "quickReplies")
        val out = StringBuilder()
        var cursor = 0
        var searchFrom = 0
        var guard = 0
        while (guard++ < 200) {
            val match = keyPattern.find(source, searchFrom) ?: break
            val keyEnd = match.range.last + 1
            var i = keyEnd
            while (i < source.length && source[i].isWhitespace()) i++
            if (i >= source.length || source[i] != '"') {
                searchFrom = keyEnd
                continue
            }
            val openQuote = i
            val body = StringBuilder()
            var escaped = false
            var j = openQuote + 1
            var closed = false
            while (j < source.length) {
                val ch = source[j]
                if (escaped) {
                    body.append(ch); escaped = false; j++; continue
                }
                if (ch == '\\') {
                    body.append(ch); escaped = true; j++; continue
                }
                if (ch == '"' && looksLikeJsonContinuation(source, j + 1)) {
                    val peek = skipWs(source, j + 1)
                    val isCloseType = peek < source.length && (source[peek] == '}' || source[peek] == ']')
                    if (isCloseType && hasLaterCandidate(source, j + 1)) {
                        body.append(ch); j++; continue
                    }
                    closed = true
                    break
                }
                body.append(ch)
                j++
            }
            if (!closed) {
                searchFrom = keyEnd
                continue
            }
            out.append(source, cursor, openQuote + 1)
            out.append(encodeJsonStringBody(decodeJsonEscapes(body.toString())))
            out.append('"')
            cursor = j + 1
            searchFrom = cursor
        }
        out.append(source, cursor, source.length)
        return out.toString()
    }

    private fun looksLikeJsonContinuation(text: String, index: Int): Boolean {
        var i = skipWs(text, index)
        if (i >= text.length) return false
        val ch = text[i]
        if (ch == '}' || ch == ']') return true
        if (ch != ',') return false
        i = skipWs(text, i + 1)
        if (i >= text.length) return false
        if (text[i] == '"') {
            var j = i + 1
            while (j < text.length && text[j] != '"') {
                if (text[j] == '\\') j++
                j++
            }
            j++
            j = skipWs(text, j)
            return j < text.length && text[j] == ':'
        }
        return text[i] == '{' || text[i] == '['
    }

    private fun hasLaterCandidate(source: String, fromIndex: Int): Boolean {
        var escaped = false
        var j = fromIndex
        while (j < source.length) {
            val ch = source[j]
            if (escaped) { escaped = false; j++; continue }
            if (ch == '\\') { escaped = true; j++; continue }
            if (ch == '"' && looksLikeJsonContinuation(source, j + 1)) return true
            j++
        }
        return false
    }

    private fun skipWs(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Port of repairStringArrayField for a single string-array key. */
    private fun repairStringArrayField(raw: String, key: String): String {
        val keyMatch = Regex("\"$key\"\\s*:\\s*\\[").find(raw) ?: return raw
        val start = raw.indexOf('[', keyMatch.range.first)
        if (start < 0) return raw
        var i = start + 1
        var depth = 1
        var inStr = false
        var escaped = false
        var end = -1
        while (i < raw.length) {
            val ch = raw[i]
            if (inStr) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inStr = false
                }
            } else {
                when (ch) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) { end = i; break }
                    }
                }
            }
            i++
        }
        if (end < 0) return raw
        val inner = raw.substring(start + 1, end)
        val fixed = splitTopLevel(inner).map { fixArrayItem(it) }
        return raw.substring(0, start + 1) + fixed.joinToString(",") + raw.substring(end)
    }

    private fun splitTopLevel(inner: String): List<String> {
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var inStr = false
        var escaped = false
        var depth = 0
        for (c in inner) {
            buf.append(c)
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '[', '{' -> depth++
                ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    parts += buf.dropLast(1).toString()
                    buf.clear()
                }
            }
        }
        if (buf.isNotBlank()) parts += buf.toString()
        return parts
    }

    private fun fixArrayItem(part: String): String {
        val t = part.trim()
        if (!t.startsWith("\"")) return part
        val body = StringBuilder()
        var escaped = false
        var idx = 1
        var closed = false
        while (idx < t.length) {
            val c = t[idx]
            when {
                escaped -> { body.append(c); escaped = false }
                c == '\\' -> { body.append(c); escaped = true }
                c == '"' -> {
                    val rest = t.substring(idx + 1).trim()
                    if (rest.isEmpty() || rest.startsWith(",")) closed = true else body.append(c)
                }
                else -> body.append(c)
            }
            if (closed) break
            idx++
        }
        if (!closed) return part
        return "\"" + encodeJsonStringBody(decodeJsonEscapes(body.toString())) + "\""
    }

    private fun decodeJsonEscapes(raw: String): String =
        Regex("\\\\(u[0-9a-fA-F]{4}|.)").replace(raw) { m ->
            val code = m.groupValues[1]
            when {
                code.startsWith("u") -> code.substring(1).toInt(16).toChar().toString()
                code == "n" -> "\n"
                code == "r" -> "\r"
                code == "t" -> "\t"
                code == "b" -> "\b"
                code == "f" -> "\u000C"
                code == "\"" || code == "\\" || code == "/" -> code
                else -> m.value
            }
        }

    private fun encodeJsonStringBody(text: String): String {
        val sb = StringBuilder()
        text.forEach { ch ->
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- literal newlines

    fun unescapeLiteralNewlines(text: String): String =
        text.replace("\\\\n", "\u0000").replace("\\n", "\n").replace("\u0000", "\\n")

    // ---------------------------------------------------------------- mapping helpers

    private fun parseLongTerm(obj: JsonObject): LongTermMemory {
        val category = when (obj.string("category").orEmpty().lowercase()) {
            "userprofile" -> MemoryCategory.UserProfile
            "relationship" -> MemoryCategory.Relationship
            "promises" -> MemoryCategory.Promises
            "habits" -> MemoryCategory.Habits
            else -> MemoryCategory.Events
        }
        val subject = when (obj.string("subject").orEmpty().lowercase()) {
            "user" -> MemorySubject.User
            "relationship" -> MemorySubject.Relationship
            "world" -> MemorySubject.World
            "character" -> MemorySubject.Character
            else -> MemorySubject.Legacy
        }
        val status = when (obj.string("status").orEmpty().lowercase()) {
            "resolved" -> PromiseStatus.Resolved
            "cancelled" -> PromiseStatus.Cancelled
            else -> PromiseStatus.Active
        }
        return LongTermMemory(
            category = category,
            subject = subject,
            key = obj.string("key").orEmpty(),
            value = obj.string("value").orEmpty(),
            tags = stringList(obj["tags"]),
            importance = (obj["importance"] as? JsonPrimitive)?.intOrNull ?: 0,
            sourceMessageIds = stringList(obj["sourceMessageIds"]),
            evidence = obj.string("evidence").orEmpty(),
            eventTime = obj.string("eventTime")?.takeIf { it.isNotBlank() },
            dueAt = obj.string("dueAt")?.takeIf { it.isNotBlank() },
            promisor = obj.string("promisor")?.takeIf { it.isNotBlank() },
            promisee = obj.string("promisee")?.takeIf { it.isNotBlank() },
            status = status,
            memberName = obj.string("memberName")?.takeIf { it.isNotBlank() },
        )
    }

    private fun stringList(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
