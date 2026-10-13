package com.deeptalking.domain.agent.background

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val salvageJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

/**
 * Best-effort recovery of a short→long analysis reply that was cut off at the
 * provider's output cap.
 *
 * The contract asks for `{"status","analyzedShortTermIds":[...],"longTerm":[...]}`.
 * A truncated reply fails strict parsing, and the whole batch used to be discarded —
 * so the same items were retried on every pass and the window could never be consumed
 * (it piled up for months while the analysis kept failing silently).
 *
 * Here we keep whatever did arrive: every `longTerm` element that parses on its own,
 * plus the echoed ids — or, when the cut happened before the id list, the short ids
 * those surviving elements cite. Only the raw text is inspected, so nothing is
 * invented: an element is kept only if it is valid JSON on its own.
 *
 * Returns null when nothing usable was recovered.
 */
internal fun salvageAnalysisReply(raw: String): JsonObject? {
    val ids = salvageStringArray(raw, "analyzedShortTermIds")
    val items = salvageObjectArray(raw, "longTerm")
    if (ids.isNullOrEmpty() && items.isNullOrEmpty()) return null
    val acknowledged = ids.orEmpty().ifEmpty {
        items.orEmpty().flatMap { element ->
            ((element as? JsonObject)?.get("sourceShortTermIds") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
                .orEmpty()
        }
    }.filter { it.isNotBlank() }.distinct()
    return buildJsonObject {
        put("status", "ok")
        put("analyzedShortTermIds", buildJsonArray { acknowledged.forEach { add(JsonPrimitive(it)) } })
        put("longTerm", buildJsonArray { items.orEmpty().forEach { add(it) } })
    }
}

/** Top-level string array stored under [key], or null when the key is absent. */
private fun salvageStringArray(raw: String, key: String): List<String>? {
    val open = arrayStart(raw, key) ?: return null
    val out = mutableListOf<String>()
    var index = open + 1
    while (index < raw.length) {
        when (raw[index]) {
            ']' -> return out
            '"' -> {
                // A truncated trailing string is dropped rather than half-parsed.
                val end = stringEnd(raw, index) ?: return out
                out += unescape(raw.substring(index, end + 1))
                index = end + 1
            }
            else -> index++
        }
    }
    return out
}

/** Import of [JsonObject] elements inside the top-level array under [key]. */
private fun salvageObjectArray(raw: String, key: String): List<JsonObject>? {
    val open = arrayStart(raw, key) ?: return null
    val out = mutableListOf<JsonObject>()
    var index = open + 1
    while (index < raw.length) {
        when (raw[index]) {
            ']' -> return out
            '{' -> {
                // The element being written when the reply was cut is simply skipped.
                val end = objectEnd(raw, index) ?: return out
                val element = runCatching { salvageJson.parseToJsonElement(raw.substring(index, end + 1)) }
                    .getOrNull() as? JsonObject
                if (element != null) out += element
                index = end + 1
            }
            else -> index++
        }
    }
    return out
}

/** Offset of the `[` that opens the array stored under [key], or null. */
private fun arrayStart(raw: String, key: String): Int? {
    val keyAt = raw.indexOf("\"$key\"")
    if (keyAt < 0) return null
    val open = raw.indexOf('[', keyAt + key.length + 2)
    return open.takeIf { it >= 0 }
}

/** Offset of the quote closing the string that starts at [startQuote], or null. */
private fun stringEnd(raw: String, startQuote: Int): Int? {
    var index = startQuote + 1
    while (index < raw.length) {
        when (raw[index]) {
            '\\' -> index += 2
            '"' -> return index
            else -> index++
        }
    }
    return null
}

/** Offset of the brace closing the object that starts at [startBrace], or null. */
private fun objectEnd(raw: String, startBrace: Int): Int? {
    var depth = 0
    var index = startBrace
    while (index < raw.length) {
        when (raw[index]) {
            '"' -> index = stringEnd(raw, index) ?: return null
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index
            }
            else -> Unit
        }
        index++
    }
    return null
}

private fun unescape(quoted: String): String =
    runCatching { (salvageJson.parseToJsonElement(quoted) as? JsonPrimitive)?.content }
        .getOrNull()
        ?: quoted.trim('"')
