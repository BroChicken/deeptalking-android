package com.deeptalking.feature.richtext

import android.content.Context
import android.view.View
import android.webkit.WebView

/** Lightweight HTML renderer used for rich chat content. */
class RichTextWebView(context: Context) : WebView(context) {

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        setBackgroundColor(0)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    fun renderHtml(html: String) {
        val body = buildString {
            append("<!DOCTYPE html><html><head>")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<link rel=\"stylesheet\" href=\"katex/katex.min.css\">")
            append("<style>")
            append(MARKDOWN_CSS)
            append("</style>")
            append("</head><body>")
            append(html)
            append("</body>")
            append("<script src=\"katex/katex.min.js\"></script>")
            append("<script src=\"katex/contrib/auto-render.min.js\"></script>")
            append(
                "<script>renderMathInElement(document.body,{delimiters:[" +
                    "{left:'\$\$',right:'\$\$',display:true}," +
                    "{left:'\$',right:'\$',display:false}," +
                    "{left:'\\\\(',right:'\\\\)',display:false}" +
                    "],throwOnError:false});</script>"
            )
            append("</html>")
        }
        loadDataWithBaseURL("file:///android_asset/", body, "text/html", "utf-8", null)
    }
}
