package com.deeptalking.lite

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.deeptalking.core.notifications.NotificationChannels

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let the system bars follow the phone's dark/light mode (dark system =
        // dark bottom bar, light system = light bar) and draw edge-to-edge.
        enableEdgeToEdge()
        NotificationChannels.ensure(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        // If the previous run crashed, show the platform dialog (no Compose) so the
        // trace is always readable/copyable even when composition itself was the cause.
        val crash = DeepTalkingApp.lastCrash
        if (crash != null) {
            showCrashDialog(crash)
            return
        }
        setContent {
            AppRoot(DeepTalkingApp.core)
        }
    }

    private fun showCrashDialog(trace: String) {
        AlertDialog.Builder(this)
            .setTitle("上次运行诊断")
            .setMessage(trace)
            .setCancelable(false)
            .setPositiveButton("复制并退出") { _, _ ->
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
                }
                (application as? DeepTalkingApp)?.clearCrash()
                finish()
            }
            .setNegativeButton("清除并继续") { _, _ ->
                (application as? DeepTalkingApp)?.clearCrash()
                recreate()
            }
            .show()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 20001
    }
}
