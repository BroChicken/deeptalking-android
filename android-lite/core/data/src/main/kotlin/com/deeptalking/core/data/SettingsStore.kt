package com.deeptalking.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow

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
}
