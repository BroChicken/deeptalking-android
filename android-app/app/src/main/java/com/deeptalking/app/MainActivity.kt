package com.deeptalking.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var voiceManager: VoiceManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        voiceManager = VoiceManager(this)
        val webView = findViewById<WebView>(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(TtsBridge(voiceManager), "AndroidTts")
        webView.loadUrl("file:///android_asset/hub.html")

        // Minimal native kokoro verification: auto-speak on launch (sid 3 = zf_001, Chinese female).
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(this, "Kokoro 试听：sid=3", Toast.LENGTH_SHORT).show()
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
