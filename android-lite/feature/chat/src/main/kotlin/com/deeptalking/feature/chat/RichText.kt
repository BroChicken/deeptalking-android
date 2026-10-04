package com.deeptalking.feature.chat

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.deeptalking.core.designsystem.legacy
import com.deeptalking.feature.richtext.RichTextWebView
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
fun RichText(source: String, isUser: Boolean, modifier: Modifier = Modifier) {
    if (containsMath(source)) {
        val html = remember(source) { renderMarkdownToHtml(source) }
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
                if (webView.tag != html) {
                    webView.tag = html
                    webView.renderHtml(html)
                }
            },
        )
    } else {
        val legacy = MaterialTheme.legacy
        val actionColor = if (isUser) legacy.onUserBubble.copy(alpha = 0.75f) else legacy.actionText
        Text(
            text = remember(source, isUser, actionColor) {
                renderMarkdownAnnotated(source, actionColor = actionColor)
            },
            color = LocalContentColor.current,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
