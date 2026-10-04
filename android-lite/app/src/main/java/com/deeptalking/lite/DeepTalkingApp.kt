package com.deeptalking.lite

import android.app.Application

class DeepTalkingApp : Application() {

    override fun onCreate() {
        super.onCreate()
        core = NativeCore(this)
    }

    companion object {
        lateinit var core: NativeCore
            private set
    }
}
