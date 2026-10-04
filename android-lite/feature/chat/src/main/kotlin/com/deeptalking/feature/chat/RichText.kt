package com.deeptalking.feature.chat

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.deeptalking.feature.richtext.RichTextWebView

/**
 * Renders pre-formatted message HTML (see `renderMarkdownToHtml`) with the
 * shared rich-text WebView.
 */
@Composable
fun RichText(html: String, modifier: Modifier = Modifier) {
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
            webView.renderHtml(html)
        },
    )
}
