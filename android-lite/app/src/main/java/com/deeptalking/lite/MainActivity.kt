package com.deeptalking.lite

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.deeptalking.core.designsystem.DeepTalkingTheme
import com.deeptalking.core.notifications.NotificationChannels

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationChannels.ensure(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        setContent {
            DeepTalkingTheme {
                AppRoot(DeepTalkingApp.core)
            }
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 20001
    }
}
