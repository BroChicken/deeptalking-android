package com.deeptalking.feature.chat

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em
import androidx.compose.ui.viewinterop.AndroidView
import com.deeptalking.core.designsystem.legacy
import com.deeptalking.feature.richtext.RichTextWebView
import com.deeptalking.feature.richtext.containsImageMarkdown
import com.deeptalking.feature.richtext.containsMath
import com.deeptalking.feature.richtext.renderMarkdownToHtml

/**
 * Renders a chat message body.
 *
 * Performance: a WebView per message is extremely heavy inside a scrolling
 * list, so we only spin one up when the message actually contains math that
 * needs KaTeX ([containsMath]). Everything else is rendered as an
 * [androidx.compose.ui.text.AnnotatedString] via [renderMarkdownAnnotated],
 * which keeps list scrolling smooth.
 *
 * Stage directions wrapped in parentheses are tinted with the legacy
 * `.action-text` colour so they read differently from spoken lines.
 */
@Composable
fun RichText(source: String, isUser: Boolean, modifier: Modifier = Modifier, onImageClick: ((String) -> Unit)? = null) {
    val legacy = MaterialTheme.legacy
    val contentColor = if (isUser) legacy.onUserBubble else legacy.onAiBubble
    if (containsMath(source) || containsImageMarkdown(source)) {
        val html = remember(source) { renderMarkdownToHtml(source) }
        val extraCss = remember(isUser, legacy) {
            val action = if (isUser) legacy.onUserBubble.copy(alpha = 0.75f) else legacy.actionText
            // The WebView has no theme by default, so its body text would render
            // pure black; force the bubble's real content colour instead.
            "body{color:${contentColor.toHex()}}" +
                "h1,h2,h3{color:${contentColor.toHex()}}" +
                ".action-text{color:${action.toHex()};font-style:italic}" +
                "a{color:${legacy.accent.toHex()};text-decoration:none}" +
                "code,pre{background:${legacy.input.toHex()}}"
        }
        AndroidView(
            modifier = modifier.fillMaxWidth(),
            factory = { context ->
                RichTextWebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    settings.javaScriptEnabled = true
                }
            },
            update = { webView ->
                webView.onImageClick = onImageClick
                val stamp = html + "\u0000" + extraCss
                if (webView.tag != stamp) {
                    webView.tag = stamp
                    webView.renderHtml(html, extraCss)
                }
            },
        )
    } else {
        val actionColor = if (isUser) legacy.onUserBubble.copy(alpha = 0.75f) else legacy.actionText
        Text(
            text = remember(source, isUser, actionColor, legacy) {
                renderMarkdownAnnotated(
                    source,
                    actionColor = actionColor,
                    linkColor = legacy.accent,
                    codeBackground = legacy.input,
                    quoteColor = legacy.textMuted,
                )
            },
            color = LocalContentColor.current,
            lineHeight = 1.55.em,
            modifier = modifier,
        )
    }
}

private fun Color.toHex(): String = "#%02X%02X%02X".format(
    (red * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue * 255).toInt().coerceIn(0, 255),
)
