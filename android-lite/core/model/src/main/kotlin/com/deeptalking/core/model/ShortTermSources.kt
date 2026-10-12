package com.deeptalking.core.model

import com.deeptalking.core.common.AppLimits

/**
 * Index-aligned source triple: `sourceMessageIds[i]` is described by
 * `sourceRoles[i]`, and [userEvidence] only carries ids present in
 * `sourceMessageIds`.
 *
 * The three lists must stay aligned: `MemoryEvidence.knownSources` resolves a
 * source's role *by position*, so a short `sourceRoles` leaves later ids
 * role-less. A role-less source makes `resolveMemorySources` return null, which
 * aborted a whole 20-item analysis batch — and because the same oldest batch is
 * retried forever, one malformed entry permanently dead-locked short-term memory.
 *
 * The bug came from merging `sourceRoles` as if it were a *set*
 * (`(a + b).distinct()`), which collapses it to `["user","assistant"]`. Every
 * merge must go through [alignSources] / [mergeSources] so the invariant holds.
 */
data class AlignedSources(
    val sourceMessageIds: List<String>,
    val sourceRoles: List<String>,
    val userEvidence: List<SourceEvidence>,
)

/**
 * Rebuilds an aligned source triple for [ids]. Roles are taken from the aligned
 * (ids, roles) pair positionally, then [evidence] (which always implies `user`).
 * Ids whose role still cannot be determined fall back to the single role present
 * in [roles], or to `assistant` when [roles] mentions it; if even that fails the
 * id is dropped together with its evidence. An id is never left role-less.
 *
 * Keeps at most [limit] ids (the most recent ones).
 */
fun alignSources(
    ids: List<String>,
    roles: List<String>,
    evidence: List<SourceEvidence>,
    limit: Int = AppLimits.Memory.SUMMARY_SOURCES,
): AlignedSources {
    val roleOf = LinkedHashMap<String, String>()
    ids.forEachIndexed { index, id ->
        roles.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { roleOf.putIfAbsent(id, it) }
    }
    evidence.forEach { roleOf.putIfAbsent(it.sourceMessageId, "user") }
    val known = roles.filter { it.isNotBlank() }.distinct()
    val fallback = when {
        known.size == 1 -> known[0]
        known.contains("assistant") -> "assistant"
        else -> null
    }
    val kept = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeLast(limit)
    val keptIds = kept.filter { id ->
        val role = roleOf[id] ?: fallback ?: return@filter false
        roleOf[id] = role
        true
    }
    return AlignedSources(
        sourceMessageIds = keptIds,
        sourceRoles = keptIds.map { roleOf.getValue(it) },
        userEvidence = evidence
            .filter { it.sourceMessageId in keptIds }
            .distinctBy { it.sourceMessageId to it.text },
    )
}

/**
 * Merges two source triples, aligning each side first so a malformed input cannot
 * shift the other side's roles.
 */
fun mergeSources(
    first: Triple<List<String>, List<String>, List<SourceEvidence>>,
    second: Triple<List<String>, List<String>, List<SourceEvidence>>,
    limit: Int = AppLimits.Memory.SUMMARY_SOURCES,
): AlignedSources {
    val (firstIds, firstRoles, firstEvidence) = first
    val (secondIds, secondRoles, secondEvidence) = second
    val a = alignSources(firstIds, firstRoles, firstEvidence, limit)
    val b = alignSources(secondIds, secondRoles, secondEvidence, limit)
    return alignSources(
        ids = a.sourceMessageIds + b.sourceMessageIds,
        roles = a.sourceRoles + b.sourceRoles,
        evidence = a.userEvidence + b.userEvidence,
        limit = limit,
    )
}
