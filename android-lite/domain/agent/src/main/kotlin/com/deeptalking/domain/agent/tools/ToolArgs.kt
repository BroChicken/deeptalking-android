package com.deeptalking.domain.agent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Shared lenient JSON helpers for tool argument parsing. */
internal object ToolArgs {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parse(raw: String?): JsonObject {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(clean(text)).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }

    private fun clean(text: String): String {
        var value = text
        if (value.startsWith("```")) {
            value = value.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        return value
    }

    fun element(obj: JsonObject, key: String): JsonElement? = obj[key]

    fun obj(obj: JsonObject, key: String): JsonObject? = obj[key] as? JsonObject

    fun array(obj: JsonObject, key: String): JsonArray? = obj[key] as? JsonArray

    fun string(obj: JsonObject, key: String): String =
        (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun int(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.intOrNull

    fun double(obj: JsonObject, key: String): Double? =
        (obj[key] as? JsonPrimitive)?.doubleOrNull

    fun bool(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.booleanOrNull

    fun strings(obj: JsonObject, key: String): List<String> =
        array(obj, key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private val JsonPrimitive.booleanOrNull: Boolean?
        get() = contentOrNull?.let { it == "true" || it == "1" }
}

internal fun ok(content: String): String = content

internal fun errorJson(reason: String): String =
    """{"ok":false,"reason":${quote(reason)}}"""

internal fun quote(text: String): String {
    val sb = StringBuilder("\"")
    text.forEach { ch ->
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}
