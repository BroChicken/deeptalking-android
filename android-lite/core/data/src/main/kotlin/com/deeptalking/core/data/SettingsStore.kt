package com.deeptalking.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "deeptalking_settings",
)

/** Minimal key/value store for small settings that do not warrant the Room schema. */
class SettingsStore(private val context: Context) {

    val stream: Flow<Preferences> = context.settingsDataStore.data

    suspend fun putString(key: String, value: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = value
        }
    }

    suspend fun getString(key: String): String? =
        context.settingsDataStore.data.first()[stringPreferencesKey(key)]

    suspend fun putBoolean(key: String, value: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(key)] = value
        }
    }

    suspend fun getBoolean(key: String, default: Boolean = false): Boolean =
        context.settingsDataStore.data.first()[booleanPreferencesKey(key)] ?: default
}
