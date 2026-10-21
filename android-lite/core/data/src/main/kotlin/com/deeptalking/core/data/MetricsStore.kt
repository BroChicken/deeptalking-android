package com.deeptalking.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistence primitive behind [MetricsRepository]: **one** raw JSON blob (a serialized
 * `List<RequestMetric>`) kept in a store of its own, deliberately *not* the `config` row
 * that holds `AppConfig`. Metrics are written on every request (including every tool
 * round), so piggybacking them on `AppConfig` meant a full config read-modify-write per
 * row and a lost-update race with settings edits.
 *
 * The interface exists so the repository is testable on the JVM; production uses
 * [DataStoreMetricsStore].
 */
interface MetricsStore {
    /** Emits the stored blob after every write; `null` before the first write. */
    fun observeRaw(): Flow<String?>

    /**
     * Atomically replaces the blob. [transform] receives the value currently on disk and
     * runs inside the store's own write transaction, so two concurrent appends cannot
     * read the same snapshot and silently drop each other's row.
     */
    suspend fun updateRaw(transform: (String?) -> String)
}

private val Context.metricsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "deeptalking_metrics",
)

private val METRICS_KEY = stringPreferencesKey("request_metrics")

/**
 * [MetricsStore] on its own DataStore file (`deeptalking_metrics`), so writing a metric
 * never touches the `config` row, the settings DataStore, or the Room tables.
 */
class DataStoreMetricsStore(private val context: Context) : MetricsStore {

    override fun observeRaw(): Flow<String?> =
        context.metricsDataStore.data.map { prefs -> prefs[METRICS_KEY] }

    override suspend fun updateRaw(transform: (String?) -> String) {
        context.metricsDataStore.edit { prefs ->
            val next = transform(prefs[METRICS_KEY])
            if (next.isEmpty()) prefs.remove(METRICS_KEY) else prefs[METRICS_KEY] = next
        }
    }
}
