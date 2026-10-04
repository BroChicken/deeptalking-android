package com.deeptalking.feature.richtext

/**
 * Pure-Kotlin markdown subset renderer.
 *
 * This is a faithful port of the legacy `formatMessageContent` pipeline in
 * `src/js/core/config.js` (plus its inline rules). It is intentionally free of
 * Android dependencies so it can be unit tested on the JVM.
 *
 * Math segments (`$...$`, `$$...$$`, `\(...\)`, `\[...\]`) are deliberately kept
 * verbatim in the output (they are token-protected so HTML escaping does not
 * touch them). The hosting WebView runs KaTeX auto-render over the final HTML.
 */

private val FENCED_CODE = Regex("```([^\\n]*)\\n([\\s\\S]*?)```")

private val DISPLAY_MATH_DOLLAR = Regex("""\${'$'}\${'$'}([\s\S]+?)\${'$'}\${'$'}""")
private val DISPLAY_MATH_BRACKET = Regex("""\\\[([\s\S]+?)\\\]""")
private val INLINE_MATH_PAREN = Regex("""\\\(([\s\S]+?)\\\)""")
private val INLINE_MATH_DOLLAR = Regex("""(^|[^\\${'$'}])\${'$'}([^${'$'}\n]+)\${'$'}""")

private val INLINE_CODE = Regex("""`([^`\n]+)`""")
private val IMAGE = Regex("""!\[([^\]]*)\]\((https?:\/\/[^\s)]+)\)""")
private val LINK = Regex("""\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)""")
private val ACTION = Regex("""\(([^()\n]+)\)|（([^（）\n]+)）""")
private val BOLD = Regex("""\*\*([^*\n]+)\*\*|__([^_\n]+)__""")
private val DEL = Regex("""~~([^~\n]+)~~""")
private val EM_STAR = Regex("""(^|[^*])\*([^*\n]+)\*(?!\*)""")
private val EM_UNDERSCORE = Regex("""(^|[^_])_([^_\n]+)_(?!_)""")

private val HEADING = Regex("""(#{1,3})\s+(.+)""")
private val BULLET = Regex("""\s*[-*+]\s+(.+)""")
private val ORDERED = Regex("""\s*\d+[.)]\s+(.+)""")
private val BLOCKQUOTE = Regex(""">\s?(.*)""")

private val TOKEN_MARKER = Regex("\uE000(\\d+)\uE001")

/** Stylesheet injected ahead of chat message HTML. */
const val MARKDOWN_CSS: String = """
body {
  margin: 0;
  padding: 0;
  background: transparent;
  color: inherit;
  font-family: -apple-system, BlinkMacSystemFont, "Noto Sans SC", "PingFang SC", sans-serif;
  font-size: 15px;
  line-height: 1.55;
  word-wrap: break-word;
  overflow-wrap: anywhere;
}
.md-paragraph { margin: 0 0 2px; }
.md-paragraph + .md-paragraph { margin-top: 2px; }
h1, h2, h3 { margin: 6px 0 4px; line-height: 1.3; }
h1 { font-size: 1.35em; }
h2 { font-size: 1.2em; }
h3 { font-size: 1.05em; }
ul, ol { margin: 2px 0; padding-left: 22px; }
li { margin: 1px 0; }
blockquote {
  margin: 4px 0;
  padding: 2px 10px;
  border-left: 3px solid rgba(128, 128, 128, 0.5);
  color: rgba(128, 128, 128, 0.95);
}
code {
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 0.9em;
  background: rgba(128, 128, 128, 0.18);
  border-radius: 4px;
  padding: 1px 4px;
}
pre {
  margin: 4px 0;
  padding: 8px 10px;
  overflow-x: auto;
  background: rgba(128, 128, 128, 0.18);
  border-radius: 6px;
}
pre code { background: transparent; padding: 0; }
.action-text { color: #8a8a8a; font-style: italic; }
.message-image { max-width: 100%; height: auto; border-radius: 6px; display: block; }
a { color: #4a90d9; text-decoration: none; }
del { opacity: 0.7; }
.katex-display { margin: 6px 0; overflow-x: auto; overflow-y: hidden; }
"""

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/**
 * Renders a markdown subset to HTML. Math delimiters survive untouched so the
 * WebView's KaTeX auto-render pass can typeset them.
 */
fun renderMarkdownToHtml(text: String): String {
    val tokens = mutableListOf<String>()

    fun token(html: String): String {
        val marker = "\uE000" + tokens.size + "\uE001"
        tokens.add(html)
        return marker
    }

    var source = text

    source = FENCED_CODE.replace(source) { match ->
        val language = match.groupValues[1].trim()
        val code = match.groupValues[2]
        token(
            "<pre><code data-language=\"${escapeHtml(language)}\">${escapeHtml(code)}</code></pre>"
        )
    }
    source = DISPLAY_MATH_DOLLAR.replace(source) { match -> token(match.value) }
    source = DISPLAY_MATH_BRACKET.replace(source) { match -> token(match.value) }
    source = INLINE_MATH_PAREN.replace(source) { match -> token(match.value) }
    source = INLINE_MATH_DOLLAR.replace(source) { match ->
        val prefix = match.groupValues[1]
        val tex = match.groupValues[2]
        prefix + token("\$" + tex + "\$")
    }

    val lines = escapeHtml(source).split("\n")
    val html = mutableListOf<String>()
    var listType = ""

    fun closeList() {
        if (listType.isNotEmpty()) {
            html.add("</$listType>")
            listType = ""
        }
    }

    fun formatInline(value: String): String {
        var result = value
        result = INLINE_CODE.replace(result) { match ->
            token("<code>${match.groupValues[1]}</code>")
        }
        result = IMAGE.replace(result) { match ->
            val alt = match.groupValues[1]
            val url = match.groupValues[2]
            token(
                "<img class=\"message-image\" src=\"$url\" alt=\"$alt\" loading=\"lazy\" " +
                    "onerror=\"this.remove()\" onclick=\"openImagePreview(this.src)\">"
            )
        }
        result = LINK.replace(result) { match ->
            val label = match.groupValues[1]
            val url = match.groupValues[2]
            "<a href=\"$url\" target=\"_blank\" rel=\"noopener noreferrer\">$label</a>"
        }
        result = ACTION.replace(result) { match ->
            "<span class=\"action-text\">${match.value}</span>"
        }
        result = BOLD.replace(result) { match ->
            val first = match.groupValues[1]
            val content = if (first.isNotEmpty()) first else match.groupValues[2]
            "<strong>$content</strong>"
        }
        result = DEL.replace(result) { match -> "<del>${match.groupValues[1]}</del>" }
        result = EM_STAR.replace(result) { match ->
            match.groupValues[1] + "<em>" + match.groupValues[2] + "</em>"
        }
        result = EM_UNDERSCORE.replace(result) { match ->
            match.groupValues[1] + "<em>" + match.groupValues[2] + "</em>"
        }
        return result
    }

    for (line in lines) {
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            closeList()
            val level = heading.groupValues[1].length
            html.add("<h$level>${formatInline(heading.groupValues[2])}</h$level>")
            continue
        }
        val bullet = BULLET.matchEntire(line)
        if (bullet != null) {
            if (listType.isNotEmpty() && listType != "ul") closeList()
            if (listType.isEmpty()) {
                html.add("<ul>")
                listType = "ul"
            }
            html.add("<li>${formatInline(bullet.groupValues[1])}</li>")
            continue
        }
        val ordered = ORDERED.matchEntire(line)
        if (ordered != null) {
            if (listType.isNotEmpty() && listType != "ol") closeList()
            if (listType.isEmpty()) {
                html.add("<ol>")
                listType = "ol"
            }
            html.add("<li>${formatInline(ordered.groupValues[1])}</li>")
            continue
        }
        closeList()
        val quote = BLOCKQUOTE.matchEntire(line)
        if (quote != null) {
            html.add("<blockquote>${formatInline(quote.groupValues[1])}</blockquote>")
        } else if (line.isNotEmpty()) {
            html.add("<div class=\"md-paragraph\">${formatInline(line)}</div>")
        } else {
            html.add("<br>")
        }
    }
    closeList()

    val joined = html.joinToString("")
    return TOKEN_MARKER.replace(joined) { match -> tokens[match.groupValues[1].toInt()] }
}
