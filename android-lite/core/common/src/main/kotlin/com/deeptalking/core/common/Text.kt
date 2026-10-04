package com.deeptalking.core.common

/** Text helpers ported from the legacy `toText` / `trimText` helpers. */
fun Any?.toTextOrNull(): String? = when (this) {
    null -> null
    is String -> this
    else -> this.toString()
}

fun Any?.toText(fallback: String = ""): String = toTextOrNull() ?: fallback

fun String?.trimTo(maxChars: Int): String {
    val value = this?.trim().orEmpty()
    return if (value.length <= maxChars) value else value.substring(0, maxChars)
}

fun String?.normalizedWhitespace(): String = this.orEmpty().replace(Regex("\\s+"), " ").trim()

/** Wraps a substring in a marker pair, used for token-protected rendering. */
internal fun encodeToken(index: Int): String = "\uE000$index\uE001"
