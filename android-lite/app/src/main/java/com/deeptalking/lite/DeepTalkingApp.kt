package com.deeptalking.lite

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class DeepTalkingApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        lastCrash = readCrashFile()
        core = NativeCore(this)
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { persist(throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun readCrashFile(): String? =
        runCatching {
            File(filesDir, CRASH_FILE).takeIf { it.exists() }?.readText()
                ?: getExternalFilesDir(null)?.let { File(it, CRASH_FILE).takeIf { file -> file.exists() }?.readText() }
        }.getOrNull()

    /** Clears the stored crash so the dialog does not show again. */
    fun clearCrash() {
        lastCrash = null
        runCatching { File(filesDir, CRASH_FILE).delete() }
        runCatching { getExternalFilesDir(null)?.let { File(it, CRASH_FILE).delete() } }
    }

    private fun persist(throwable: Throwable) {
        val buffer = StringWriter()
        throwable.printStackTrace(PrintWriter(buffer))
        val text = buffer.toString()
        File(filesDir, CRASH_FILE).writeText(text)
        // Also write to the app-specific external dir so it can be pulled with a
        // file manager even when the UI cannot compose.
        runCatching { getExternalFilesDir(null)?.let { File(it, CRASH_FILE).writeText(text) } }
        lastCrash = text
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"

        lateinit var core: NativeCore
            private set

        @Volatile
        private var instance: DeepTalkingApp? = null

        /** Stack trace of the most recent crash/startup error (surfaced for diagnostics). */
        @Volatile
        var lastCrash: String? = null
            private set

        /** Records a non-fatal startup error through the same channel, for diagnosis. */
        fun recordError(throwable: Throwable) {
            runCatching { instance?.persist(throwable) }
        }
    }
}
