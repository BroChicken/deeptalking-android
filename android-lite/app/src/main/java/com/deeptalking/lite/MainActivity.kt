package com.deeptalking.lite

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity() {

    private var webView: WebView? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var webViewClient: WebViewClient? = null
    private var webChromeClient: WebChromeClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webViewClient = WebViewClient()
        webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView,
                url: String,
                message: String,
                result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView,
                url: String,
                message: String,
                result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                try {
                    startActivityForResult(fileChooserParams.createIntent(), REQUEST_FILE)
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = true
        wv.settings.allowFileAccessFromFileURLs = true
        wv.settings.allowUniversalAccessFromFileURLs = true
        wv.settings.databaseEnabled = true
        wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.settings.builtInZoomControls = false
        wv.settings.setSupportZoom(false)
        wv.settings.textZoom = 100

        wv.webViewClient = webViewClient
        wv.webChromeClient = webChromeClient
        wv.setDownloadListener { url, _: String?, _: String?, _: String?, _: Long ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                Toast.makeText(
                    this,
                    "备份下载受限：请使用导出弹窗中的“复制全文”按钮，粘贴保存为 .json 文件",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val root = FrameLayout(this)
        root.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        webView = wv

        wv.loadUrl("file:///android_asset/hub.html")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE) {
            val callback = filePathCallback ?: return
            filePathCallback = null
            val results = if (resultCode == Activity.RESULT_OK && data != null) {
                data.data?.let { arrayOf(it) } ?: data.clipData?.let { clip ->
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
            } else {
                null
            }
            callback.onReceiveValue(results)
        }
    }

    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        val wv = webView
        if (wv != null) {
            wv.stopLoading()
            wv.settings.javaScriptEnabled = false
            wv.removeAllViews()
            wv.destroy()
            webView = null
        }
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_FILE = 10001
    }
}
