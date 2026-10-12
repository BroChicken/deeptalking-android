package com.deeptalking.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sourceRoles` is index-aligned with `sourceMessageIds`; these guard the merge
 * helpers against the `.distinct()` bug that collapsed roles to
 * `["user","assistant"]` and left later ids role-less (which dead-locked the
 * analysis batch).
 */
class AlignedSourcesTest {

    private fun evidence(id: String, text: String) = SourceEvidence(sourceMessageId = id, text = text)

    @Test
    fun alignSourcesKeepsRolesIndexAligned() {
        val aligned = alignSources(
            ids = listOf("a", "b", "c"),
            roles = listOf("user", "assistant", "user"),
            evidence = listOf(evidence("a", "hi"), evidence("c", "ho")),
        )
        assertEquals(listOf("a", "b", "c"), aligned.sourceMessageIds)
        assertEquals(listOf("user", "assistant", "user"), aligned.sourceRoles)
    }

    @Test
    fun alignSourcesRecoversShortRolesInsteadOfLeavingIdsRoleLess() {
        // The exact poison-pill shape: 8 ids, a roles list collapsed to 2 entries.
        val ids = (1..8).map { "m$it" }
        val aligned = alignSources(
            ids = ids,
            roles = listOf("user", "assistant"),
            evidence = ids.take(7).map { evidence(it, "text-$it") },
        )
        assertEquals(ids, aligned.sourceMessageIds)
        assertEquals(ids.size, aligned.sourceRoles.size)
        assertTrue(aligned.sourceRoles.none { it.isBlank() })
        assertEquals("assistant", aligned.sourceRoles[7])
    }

    @Test
    fun alignSourcesDropsIdsItCannotResolveAtAll() {
        val aligned = alignSources(
            ids = listOf("a", "b"),
            roles = emptyList(),
            evidence = listOf(evidence("a", "hi")),
        )
        assertEquals(listOf("a"), aligned.sourceMessageIds)
        assertEquals(listOf("user"), aligned.sourceRoles)
    }

    @Test
    fun alignSourcesNeverLeavesARoleLessIdAndKeepsEvidenceSubset() {
        val aligned = alignSources(
            ids = listOf("a", "b", "c"),
            roles = listOf("user"),
            evidence = listOf(evidence("a", "x"), evidence("zzz", "y")),
        )
        assertEquals(aligned.sourceMessageIds.size, aligned.sourceRoles.size)
        assertTrue(aligned.sourceRoles.none { it.isBlank() })
        assertTrue(aligned.userEvidence.all { it.sourceMessageId in aligned.sourceMessageIds })
    }

    @Test
    fun mergeSourcesAlignsEachSideBeforeMerging() {
        // First side is malformed (3 ids, 2 roles); second side is aligned. The
        // merged triple must stay aligned and role-complete.
        val merged = mergeSources(
            Triple(listOf("a", "b", "c"), listOf("user", "assistant"), listOf(evidence("a", "x"))),
            Triple(listOf("d", "e"), listOf("user", "assistant"), listOf(evidence("d", "y"), evidence("e", "z"))),
        )
        assertEquals(listOf("a", "b", "c", "d", "e"), merged.sourceMessageIds)
        assertEquals(merged.sourceMessageIds.size, merged.sourceRoles.size)
        assertTrue(merged.sourceRoles.none { it.isBlank() })
        assertEquals(listOf("user", "assistant", "assistant", "user", "assistant"), merged.sourceRoles)
    }
}
