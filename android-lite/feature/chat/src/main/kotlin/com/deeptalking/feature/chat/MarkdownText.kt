package com.deeptalking.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Minimal markdown-subset renderer producing an [AnnotatedString] for the
 * no-math fast path. Mirrors the inline rules in `renderMarkdownToHtml`
 * (bold / italic / strikethrough / inline code / action text). Action text
 * — parenthesised stage directions — is italicised and tinted with
 * [actionColor] to match the legacy `.action-text` CSS rule. Math messages
 * still go through the KaTeX WebView.
 */
fun renderMarkdownAnnotated(
    text: String,
    actionColor: Color = Color.Unspecified,
    textColor: Color = Color.Unspecified,
): AnnotatedString {
    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace)
    val actionStyle = SpanStyle(
        fontStyle = FontStyle.Italic,
        color = if (actionColor == Color.Unspecified) Color.Unspecified else actionColor,
    )
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    return buildAnnotatedString {
        var i = 0
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                if (textColor == Color.Unspecified) append(sb.toString()) else withStyle(SpanStyle(color = textColor)) { append(sb.toString()) }
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
                            append(renderMarkdownAnnotated(text.substring(i + 2, end), actionColor, textColor))
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
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
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
                ch == '(' || ch == '（' -> {
                    val close = if (ch == '(') ')' else '）'
                    val end = text.indexOf(close, i + 1)
                    if (end > i + 1 && end - i <= 80 && !text.substring(i, end).contains('\n')) {
                        flush()
                        withStyle(actionStyle) { append(text.substring(i, end + 1)) }
                        i = end + 1
                    } else {
                        sb.append(ch); i++
                    }
                }
                ch == '*' || ch == '_' -> {
                    val next = text.indexOf(ch, i + 1)
                    if (next > i + 1) {
                        flush()
                        withStyle(italicStyle) { append(text.substring(i + 1, next)) }
                        i = next + 1
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
