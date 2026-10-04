package com.deeptalking.feature.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun rendersAtxHeadings() {
        assertEquals("<h1>Title</h1>", renderMarkdownToHtml("# Title"))
        assertEquals("<h2>Sub</h2>", renderMarkdownToHtml("## Sub"))
        assertEquals("<h3>Deep</h3>", renderMarkdownToHtml("### Deep"))
    }

    @Test
    fun rendersInlineCode() {
        assertEquals(
            "<div class=\"md-paragraph\"><code>x = 1</code></div>",
            renderMarkdownToHtml("`x = 1`"),
        )
    }

    @Test
    fun rendersBold() {
        assertEquals(
            "<div class=\"md-paragraph\"><strong>hi</strong></div>",
            renderMarkdownToHtml("**hi**"),
        )
    }

    @Test
    fun rendersLinks() {
        val html = renderMarkdownToHtml("[site](https://example.com)")
        assertTrue(
            html.contains(
                "<a href=\"https://example.com\" target=\"_blank\" rel=\"noopener noreferrer\">site</a>"
            )
        )
    }

    @Test
    fun rendersActionSpans() {
        assertTrue(renderMarkdownToHtml("(smiles)").contains("<span class=\"action-text\">(smiles)</span>"))
        assertTrue(renderMarkdownToHtml("（微笑）").contains("<span class=\"action-text\">（微笑）</span>"))
    }

    @Test
    fun escapesHtml() {
        val html = renderMarkdownToHtml("<b>no</b>")
        assertTrue(html.contains("&lt;b&gt;no&lt;/b&gt;"))
        assertTrue(!html.contains("<b>no</b>"))
    }

    @Test
    fun inlineMathSurvives() {
        val html = renderMarkdownToHtml("value \$x^2\$")
        assertTrue(html.contains("\$x^2\$"))
    }

    @Test
    fun displayMathSurvives() {
        val html = renderMarkdownToHtml("\$\$E=mc^2\$\$")
        assertTrue(html.contains("\$\$E=mc^2\$\$"))
    }

    @Test
    fun fencedCodeBlockIsEscaped() {
        val html = renderMarkdownToHtml("```kotlin\nval x = 1 < 2\n```")
        assertTrue(html.contains("<pre><code data-language=\"kotlin\">"))
        assertTrue(html.contains("val x = 1 &lt; 2"))
    }
}
