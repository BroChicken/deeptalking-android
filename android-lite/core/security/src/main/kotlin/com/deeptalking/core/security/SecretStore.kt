package com.deeptalking.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the API key in [EncryptedSharedPreferences], falling back to plain
 * [SharedPreferences] when the crypto backend cannot be initialised (e.g. a
 * broken keystore).
 */
class SecretStore(context: Context) {

    private val prefs: SharedPreferences = createPreferences(context.applicationContext)

    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    fun setApiKey(value: String?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_API_KEY) else putString(KEY_API_KEY, value)
        }.apply()
    }

    /**
     * Per-platform API key slot (legacy `platformSettings[platform].apiKey`).
     * Switching platforms must not overwrite another platform's key.
     */
    fun getApiKey(platform: String): String? =
        prefs.getString(platformKey(platform), null)

    fun setApiKey(platform: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(platformKey(platform)) else putString(platformKey(platform), value)
        }.apply()
    }

    private fun platformKey(platform: String): String = KEY_API_KEY + "_" + platform

    /**
     * Bearer token for the remote read-aloud server (`cosyvoice-server --api-key`).
     * Kept here rather than in `AppConfig` so it never reaches the database, logs
     * or backup exports (see AGENTS.md: secrets only live in [SecretStore]).
     */
    fun getTtsToken(): String? = prefs.getString(KEY_TTS_TOKEN, null)

    fun setTtsToken(value: String?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_TTS_TOKEN) else putString(KEY_TTS_TOKEN, value)
        }.apply()
    }

    /**
     * B 站登录 Cookie（`SESSDATA=…`），只用来取视频字幕与官方 AI 总结。
     *
     * **刻意不进 [snapshot]**：SESSDATA 等价于账号（可读用户资料、发弹幕），一旦跟备份
     * 一起导出就会离开这台设备。它同样不进数据库和日志——设置页只显示"已保存/未保存"。
     */
    fun getBiliCookie(): String? = prefs.getString(KEY_BILI_COOKIE, null)

    fun setBiliCookie(value: String?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_BILI_COOKIE) else putString(KEY_BILI_COOKIE, value)
        }.apply()
    }

    /**
     * Snapshot of every managed secret for an encrypted backup. Only the global/per-platform
     * API key slots and the remote-TTS token are included; unrelated preferences never are.
     */
    fun snapshot(): Map<String, String> = prefs.all.entries
        .filter { (key, value) -> isManagedKey(key) && value is String && value.isNotEmpty() }
        .associate { (key, value) -> key to value as String }

    /** Replaces every managed secret with [values]; unknown/unmanaged keys are untouched. */
    fun restore(values: Map<String, String>) {
        val editor = prefs.edit()
        prefs.all.keys.filter(::isManagedKey).forEach(editor::remove)
        values.forEach { (key, value) ->
            if (isManagedKey(key) && value.isNotEmpty()) editor.putString(key, value)
        }
        editor.apply()
    }

    private fun isManagedKey(key: String): Boolean =
        key == KEY_API_KEY || key == KEY_TTS_TOKEN || key.startsWith(KEY_API_KEY + "_")

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun createPreferences(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        context.getSharedPreferences(FILE_NAME + "_plain", Context.MODE_PRIVATE)
    }

    private companion object {
        const val FILE_NAME = "deeptalking_secrets"
        const val KEY_API_KEY = "api_key"
        const val KEY_TTS_TOKEN = "tts_token"

        /** Never part of [snapshot]: a session cookie is account-level, not app config. */
        const val KEY_BILI_COOKIE = "bili_cookie"
    }
}

/**
 * 把用户粘贴的内容归一成可以直接放进 `Cookie:` 头的一串：
 * 整段 Cookie（含 `SESSDATA=`）原样保留；只粘了 SESSDATA 的值时补上前缀；去掉换行/引号。
 */
fun normalizeBiliCookie(raw: String?): String? {
    val text = raw
        ?.trim()
        ?.trim('"', '\'')
        ?.replace(Regex("\\s+"), "")
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    if (text.contains("SESSDATA=", ignoreCase = true)) return text
    return "SESSDATA=" + text
}
