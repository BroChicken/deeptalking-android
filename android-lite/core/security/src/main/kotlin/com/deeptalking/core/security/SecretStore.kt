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
    }
}
