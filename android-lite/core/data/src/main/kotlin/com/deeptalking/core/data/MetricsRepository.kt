package com.deeptalking.core.data

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.RequestMetric
import com.deeptalking.core.model.trimMetricsPerTaskType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/** Serializer of the stored blob (`List<RequestMetric>` as a JSON array). */
private val METRICS_SERIALIZER = ListSerializer(RequestMetric.serializer())

/**
 * Rolling per-request metrics (usage / cache / duration), stored **outside** `AppConfig`.
 *
 * A main chat turn writes one row per model request (tool rounds included) and every
 * background pass writes its own rows, so the list is hot: it is appended on each
 * request. Keeping it in `AppConfig.requestMetrics` meant one full config read-modify-write
 * per row (and a race with settings writes); here each append is a single atomic
 * read-modify-write of [MetricsStore], independent of the config column.
 *
 * Retention is [cap] rows **per `taskType`** (see [trimMetricsPerTaskType]); order is
 * always oldest → newest.
 *
 * `AppConfig.requestMetrics` stays untouched at runtime: it is now only a legacy input
 * (see [migrateLegacy]) and the shape backup export keeps using.
 */
class MetricsRepository(
    private val store: MetricsStore,
    private val cap: Int = AppLimits.Api.REQUEST_METRICS,
) {

    /** Observable read, e.g. for a metrics panel. Emits on every write. */
    fun observe(): Flow<List<RequestMetric>> = store.observeRaw().map(::decode)

    /** One-shot read (oldest → newest). */
    suspend fun all(): List<RequestMetric> = observe().first()

    /** Appends one row atomically; the oldest rows beyond [cap] per task type are dropped. */
    suspend fun append(metric: RequestMetric) = appendAll(listOf(metric))

    /**
     * Appends [metrics] in order inside a single atomic read-modify-write, then trims per
     * task type. Used by backup import and by callers that recorded several rows of one
     * request (`chat.main` + `chat.worker`).
     */
    suspend fun appendAll(metrics: List<RequestMetric>) {
        if (metrics.isEmpty()) return
        store.updateRaw { raw ->
            encode((decode(raw) + metrics).trimMetricsPerTaskType(cap))
        }
    }

    /** Atomic full replace (backup import / migration); [metrics] are trimmed and re-ordered as given. */
    suspend fun replaceAll(metrics: List<RequestMetric>) {
        store.updateRaw { encode(metrics.trimMetricsPerTaskType(cap)) }
    }

    /** Drops every stored row. The (now empty) store still counts as written, so [migrateLegacy] stays a no-op. */
    suspend fun clear() = replaceAll(emptyList())

    /** Every stored row as a JSON array — the batch export side of backup. */
    suspend fun exportJson(): String = encode(all())

    /**
     * Batch import of JSON produced by [exportJson] (or a legacy backup's `requestMetrics`
     * array). With [merge] the rows are appended to what is stored (default); otherwise the
     * stored rows are replaced. Unparseable input is a no-op that returns 0, so a damaged
     * backup cannot wipe the store.
     *
     * @return how many rows the input carried.
     */
    suspend fun importJson(json: String, merge: Boolean = true): Int {
        val rows = runCatching { AppJson.decodeFromString(METRICS_SERIALIZER, json) }.getOrNull()
            ?: return 0
        if (merge) appendAll(rows) else replaceAll(rows)
        return rows.size
    }

    /**
     * Adopts the legacy `AppConfig.requestMetrics` list **once**, on first use: when the
     * store has never been written it is seeded with the trimmed legacy rows, and any later
     * call is a no-op (so removing `requestMetrics` from `AppConfig` cannot resurrect
     * already-trimmed rows or duplicate them after an app update).
     *
     * Not called yet — the send path and backup wiring still read `AppConfig` until the
     * follow-up batch switches them over.
     *
     * @return how many rows were migrated (0 when the store already had its own data).
     */
    suspend fun migrateLegacy(legacy: List<RequestMetric>): Int {
        if (legacy.isEmpty()) return 0
        val adopted = legacy.trimMetricsPerTaskType(cap)
        var migrated = 0
        store.updateRaw { raw ->
            if (raw != null) {
                raw
            } else {
                migrated = adopted.size
                encode(adopted)
            }
        }
        return migrated
    }

    /** Stored rows, oldest → newest; a corrupt blob reads as empty rather than failing. */
    private fun decode(raw: String?): List<RequestMetric> {
        val text = raw ?: return emptyList()
        return runCatching { AppJson.decodeFromString(METRICS_SERIALIZER, text) }.getOrDefault(emptyList())
    }

    private fun encode(metrics: List<RequestMetric>): String =
        AppJson.encodeToString(METRICS_SERIALIZER, metrics)
}
