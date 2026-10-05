package com.deeptalking.feature.richtext

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Lightweight HTML renderer used for rich chat content (math via KaTeX, and
 * messages embedding markdown images).
 *
 * Chat is read-only, so links open in the external browser and image taps are
 * forwarded to the same external viewer instead of navigating the message
 * WebView (which would otherwise hijack the back button). Model-controlled HTML
 * is escaped by [renderMarkdownToHtml] before it reaches [loadDataWithBaseURL].
 */
class RichTextWebView(context: Context) : WebView(context) {

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        setBackgroundColor(0)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString().orEmpty()
                if (url.startsWith("http", ignoreCase = true)) {
                    openExternal(url)
                    return true
                }
                return true
            }
        }
        addJavascriptInterface(ImageBridge(), "DeepTalking")
    }

    fun renderHtml(html: String, extraCss: String = "") {
        val body = buildString {
            append("<!DOCTYPE html><html><head>")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<link rel=\"stylesheet\" href=\"katex/katex.min.css\">")
            append("<style>")
            append(MARKDOWN_CSS)
            append(extraCss)
            append("</style>")
            append("</head><body>")
            append(html)
            append("</body>")
            append("<script src=\"katex/katex.min.js\"></script>")
            append("<script src=\"katex/contrib/auto-render.min.js\"></script>")
            append(
                "<script>renderMathInElement(document.body,{delimiters:[" +
                    "{left:'\$\$',right:'\$\$',display:true}," +
                    "{left:'\\\\(',right:'\\\\)',display:false}," +
                    "{left:'\\\\[',right:'\\\\]',display:true}," +
                    "{left:'\$',right:'\$',display:false}" +
                    "],throwOnError:false});</script>"
            )
            append(
                "<script>document.querySelectorAll('img.message-image').forEach(function(img){" +
                    "img.onclick=function(){DeepTalking.openExternal(img.src);};});</script>"
            )
            append("</html>")
        }
        loadDataWithBaseURL("file:///android_asset/", body, "text/html", "utf-8", null)
    }

    private fun openExternal(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private inner class ImageBridge {
        @JavascriptInterface
        fun openExternal(url: String) = this@RichTextWebView.openExternal(url)
    }
}
