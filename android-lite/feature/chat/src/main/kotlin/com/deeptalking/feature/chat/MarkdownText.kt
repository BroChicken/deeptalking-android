package com.deeptalking.feature.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Minimal markdown-subset renderer producing an [AnnotatedString] for the
 * no-math fast path. Mirrors the inline rules in `renderMarkdownToHtml`
 * (bold / italic / strikethrough / inline code / action text) closely enough
 * that the common prose case needs no WebView. Math messages still go through
 * the KaTeX WebView.
 */
fun renderMarkdownAnnotated(text: String): AnnotatedString {
    val codeStyle = SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    val actionStyle = SpanStyle(fontStyle = FontStyle.Italic)
    return buildAnnotatedString {
        var i = 0
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                append(sb.toString())
                sb.clear()
            }
        }
        while (i < text.length) {
            val ch = text[i]
            when {
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i + 2) {
                        flush()
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(renderMarkdownAnnotated(text.substring(i + 2, end)))
                        }
                        i = end + 2
                    } else {
                        sb.append(ch); i++
                    }
                }
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        flush()
                        withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        sb.append(ch); i++
                    }
                }
                ch == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        withStyle(codeStyle) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        sb.append(ch); i++
                    }
                }
                ch == '*' || ch == '_' -> {
                    val next = text.indexOf(ch, i + 1)
                    if (next > i + 1) {
                        flush()
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, next))
                        }
                        i = next + 1
                    } else {
                        sb.append(ch); i++
                    }
                }
                ch == '(' || ch == '（' -> {
                    val close = if (ch == '(') ')' else '）'
                    val end = text.indexOf(close, i + 1)
                    if (end > i + 1 && end - i <= 80) {
                        flush()
                        withStyle(actionStyle) { append(text.substring(i, end + 1)) }
                        i = end + 1
                    } else {
                        sb.append(ch); i++
                    }
                }
                else -> {
                    sb.append(ch); i++
                }
            }
        }
        flush()
    }
}
