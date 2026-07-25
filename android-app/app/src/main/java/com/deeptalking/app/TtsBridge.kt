package com.deeptalking.app

import android.webkit.JavascriptInterface
import org.json.JSONObject

class TtsBridge(private val voiceManager: VoiceManager) {
    @JavascriptInterface
    fun speak(payload: String) {
        val data = JSONObject(payload)
        voiceManager.speak(
            packId = data.optString("voicePack", "xiaoya"),
            text = data.optString("text"),
            speed = data.optDouble("speed", 1.0).toFloat(),
            playbackRate = data.optDouble("playbackRate", 1.0).toFloat(),
            volume = data.optDouble("volume", 0.9).toFloat()
        )
    }

    @JavascriptInterface
    fun stop() = voiceManager.stop()
}
