package com.deeptalking.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * Markdown-subset renderer producing an [AnnotatedString] for the no-math fast
 * path, mirroring `renderMarkdownToHtml` (`src/js/core/config.js`): block-level
 * headings / lists / blockquotes / fenced code, plus inline emphasis, strikethrough,
 * inline code, action text (parenthesised stage directions), links (clickable) and
 * `![alt](url)` images (routed to the WebView by [RichText]).
 */
fun renderMarkdownAnnotated(
    text: String,
    actionColor: Color = Color.Unspecified,
    textColor: Color = Color.Unspecified,
    linkColor: Color = Color.Unspecified,
    codeBackground: Color = Color.Unspecified,
    quoteColor: Color = Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    val lines = text.split('\n')
    var inFence = false
    lines.forEachIndexed { index, line ->
        if (inFence) {
            if (line.trimStart().startsWith("```")) {
                inFence = false
            } else {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                    append(line)
                }
            }
        } else {
            val trimmed = line.trimStart()
            val heading = HEADING.matchEntire(trimmed)
            val bullet = BULLET.matchEntire(line)
            val ordered = ORDERED.matchEntire(line)
            val quote = BLOCKQUOTE.matchEntire(trimmed)
            when {
                trimmed.startsWith("```") -> inFence = true
                heading != null -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingSize(heading.groupValues[1].length)),
                ) { appendInline(heading.groupValues[2], actionColor, textColor, linkColor, codeBackground) }
                bullet != null -> {
                    append("• ")
                    appendInline(bullet.groupValues[1], actionColor, textColor, linkColor, codeBackground)
                }
                ordered != null -> {
                    append("${ordered.groupValues[1]}. ")
                    appendInline(ordered.groupValues[2], actionColor, textColor, linkColor, codeBackground)
                }
                quote != null -> withStyle(
                    SpanStyle(color = quoteColor, fontStyle = FontStyle.Italic),
                ) {
                    append("▏ ")
                    appendInline(quote.groupValues[1], actionColor, textColor, linkColor, codeBackground)
                }
                else -> appendInline(line, actionColor, textColor, linkColor, codeBackground)
            }
        }
        if (index < lines.size - 1) append('\n')
    }
}

private val HEADING = Regex("""(#{1,3})\s+(.+)""")
private val BULLET = Regex("""\s*[-*+]\s+(.+)""")
private val ORDERED = Regex("""\s*(\d+)[.)]\s+(.+)""")
private val BLOCKQUOTE = Regex(""">\s?(.*)""")
private val LINK = Regex("""\[([^\]]+)\]\((https?://[^\s)]+)\)""")

private fun headingSize(level: Int) = when (level) {
    1 -> 1.35.em
    2 -> 1.2.em
    else -> 1.05.em
}

/**
 * Appends [source] applying the inline markdown rules. Kept as a plain
 * [AnnotatedString.Builder] receiver so block-level callers can nest it.
 */
private fun AnnotatedString.Builder.appendInline(
    source: String,
    actionColor: Color,
    textColor: Color,
    linkColor: Color,
    codeBackground: Color,
) {
    var i = 0
    val plain = StringBuilder()
    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
    val actionStyle = SpanStyle(
        fontStyle = FontStyle.Italic,
        color = if (actionColor == Color.Unspecified) Color.Unspecified else actionColor,
    )
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    val linkStyle = SpanStyle(
        color = if (linkColor == Color.Unspecified) Color.Unspecified else linkColor,
        textDecoration = TextDecoration.Underline,
    )
    fun flush() {
        if (plain.isEmpty()) return
        val value = plain.toString()
        if (textColor == Color.Unspecified) append(value) else withStyle(SpanStyle(color = textColor)) { append(value) }
        plain.clear()
    }
    while (i < source.length) {
        when {
            source.startsWith("**", i) || source.startsWith("__", i) -> {
                val marker = source.substring(i, i + 2)
                val end = source.indexOf(marker, i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(source.substring(i + 2, end), actionColor, textColor, linkColor, codeBackground)
                    }
                    i = end + 2
                } else {
                    plain.append(source[i]); i++
                }
            }
            source.startsWith("~~", i) -> {
                val end = source.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(source.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    plain.append(source[i]); i++
                }
            }
            source[i] == '[' -> {
                val match = LINK.find(source, i)
                if (match != null && match.range.first == i) {
                    flush()
                    val label = match.groupValues[1]
                    val url = match.groupValues[2]
                    withLink(LinkAnnotation.Url(url)) { withStyle(linkStyle) { append(label) } }
                    i = match.range.last + 1
                } else {
                    plain.append(source[i]); i++
                }
            }
            source[i] == '`' -> {
                val end = source.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    withStyle(codeStyle) { append(source.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    plain.append(source[i]); i++
                }
            }
            source[i] == '(' || source[i] == '（' -> {
                val close = if (source[i] == '(') ')' else '）'
                val end = source.indexOf(close, i + 1)
                if (end > i + 1 && end - i <= 80 && !source.substring(i, end).contains('\n')) {
                    flush()
                    withStyle(actionStyle) { append(source.substring(i, end + 1)) }
                    i = end + 1
                } else {
                    plain.append(source[i]); i++
                }
            }
            source[i] == '*' || source[i] == '_' -> {
                val ch = source[i]
                val next = source.indexOf(ch, i + 1)
                if (next > i + 1) {
                    flush()
                    withStyle(italicStyle) { append(source.substring(i + 1, next)) }
                    i = next + 1
                } else {
                    plain.append(source[i]); i++
                }
            }
            else -> {
                plain.append(source[i]); i++
            }
        }
    }
    flush()
}
