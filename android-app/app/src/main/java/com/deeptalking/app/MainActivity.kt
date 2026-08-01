package com.deeptalking.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var voiceManager: VoiceManager
    private lateinit var statusText: TextView

    private fun appendLog(msg: String) {
        try {
            val dir = File(getExternalFilesDir(null), "logs")
            dir.mkdirs()
            File(dir, "tts_error.txt").appendText(msg + "\n")
        } catch (e: Exception) {
            Log.e("MainActivity", "appendLog failed", e)
        }
    }

    private fun showError(msg: String) {
        Log.e("MainActivity", msg)
        appendLog(msg)
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            statusText.text = "错误: $msg"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val msg = "UNCAUGHT: ${throwable.javaClass.name}: ${throwable.message}"
            appendLog(msg)
            throwable.printStackTrace(System.err)
            Log.e("MainActivity", msg, throwable)
        }

        voiceManager = VoiceManager(this) { msg -> showError(msg) }

        val webView = findViewById<WebView>(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(TtsBridge(voiceManager), "AndroidTts")
        webView.loadUrl("file:///android_asset/hub.html")

        // Minimal native kokoro verification: auto-speak on launch (sid 3 = zf_001, Chinese female).
        Handler(Looper.getMainLooper()).postDelayed({
            statusText.text = "Kokoro 试听：sid=3"
            voiceManager.speak(
                packId = "kokoro",
                text = "你好，我是你的本地语音助手，正在用 Kokoro 模型为你合成声音。",
                speed = 1.0f,
                playbackRate = 1.0f,
                volume = 0.9f,
                sid = 3
            )
        }, 2500)
    }

    override fun onDestroy() {
        voiceManager.close()
        super.onDestroy()
    }
}
