package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import com.deeptalking.core.model.activeAt
import com.deeptalking.core.model.archiveOldestLorebookEntries
import com.deeptalking.core.model.convergeLorebookCapacity
import com.deeptalking.core.model.lorebookCapacityCount
import com.deeptalking.core.model.rankLorebookForEviction
import com.deeptalking.core.model.restoreArchivedLorebookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The world book is capacity-driven: nothing is evicted on a quiet pass; only a batch that
 * does not fit archives the oldest, least-active **model** entries (at most
 * `EVICT_MAX_PER_PASS`), and manual/常驻 entries are never touched.
 */
class LorebookCapacityTest {

    private fun model(
        id: String,
        created: String? = "2026-01-01T00:00:00Z",
        mentions: Int = 0,
        alwaysActive: Boolean = false,
    ) = LorebookEntry(
        id = id,
        name = id,
        content = "c$id",
        keywords = listOf(id),
        origin = LorebookOrigin.Model,
        alwaysActive = alwaysActive,
        createdAt = created,
        mentions = mentions,
    )

    private fun manual(id: String) = LorebookEntry(
        id = id,
        name = id,
        content = "c$id",
        origin = LorebookOrigin.User,
    )

    private fun aged(count: Int, startIndex: Int = 1, alwaysActive: Boolean = false): List<LorebookEntry> =
        (startIndex until startIndex + count).map { index -> model("e$index", created = "2026-01-01T00:00:00.%04dZ".format(index), alwaysActive = alwaysActive) }

    @Test
    fun aBookBelowTheCeilingArchivesNothing() {
        val character = Character(id = "c1", lorebook = aged(199))
        assertEquals(199, character.lorebookCapacityCount())
        assertEquals(0, lorebookOverflow(character))
        val adjustment = archiveToFit(character, lorebookOverflow(character))
        assertEquals(character, adjustment.character)
        assertTrue(adjustment.archived.isEmpty())
    }

    @Test
    fun aBatchOfThreeOn199FreesExactlyTwoSlots() {
        val character = Character(id = "c1", lorebook = aged(199))
        val withBatch = character.copy(
            lorebook = character.lorebook + listOf(
                model("n1", created = "2026-12-01T00:00:00Z"),
                model("n2", created = "2026-12-01T00:00:00Z"),
                model("n3", created = "2026-12-01T00:00:00Z"),
            ),
        )
        assertEquals(2, lorebookOverflow(withBatch))
        val adjustment = archiveToFit(withBatch, lorebookOverflow(withBatch))
        assertEquals(2, adjustment.archived.size)
        assertEquals(AppLimits.Lorebook.ENTRIES, adjustment.character.lorebookCapacityCount())
        assertEquals(2, adjustment.character.lorebookArchive.size)
        // The two oldest live entries go first.
        assertEquals(listOf("e1", "e2"), adjustment.archived.map { it.id })
    }

    @Test
    fun aBatchOfOnlyUpdatesNeedsNoEviction() {
        val character = Character(id = "c1", lorebook = aged(AppLimits.Lorebook.ENTRIES))
        // Re-writing the same ids does not grow the count, so nothing is archived.
        val rewritten = character.copy(
            lorebook = character.lorebook.map { it.copy(content = it.content + "（更新）", updatedAt = "2026-12-01T00:00:00Z") },
        )
        assertEquals(0, lorebookOverflow(rewritten))
        assertTrue(archiveToFit(rewritten, 0).archived.isEmpty())
    }

    @Test
    fun anAllAlwaysActiveBookRefusesTheWriteInsteadOfTouchingProtectedEntries() {
        val character = Character(id = "c1", lorebook = aged(AppLimits.Lorebook.ENTRIES, alwaysActive = true))
        val withBatch = character.copy(lorebook = character.lorebook + model("new", created = "2026-12-01T00:00:00Z"))
        // The new entry itself is excluded from the candidate pool (see the callers).
        val adjustment = archiveToFit(withBatch, lorebookOverflow(withBatch), excludeIds = setOf("new"))
        assertEquals(0, adjustment.archived.size)
        assertEquals(1, adjustment.unmet)
        assertTrue(adjustment.archived.isEmpty())
    }

    @Test
    fun aBatchOfThreeOnAFullBookArchivesThreeWithinThePerPassCap() {
        val character = Character(id = "c1", lorebook = aged(AppLimits.Lorebook.ENTRIES))
        val withBatch = character.copy(lorebook = character.lorebook + aged(3, startIndex = 5000))
        assertEquals(3, lorebookOverflow(withBatch))
        val adjustment = archiveToFit(withBatch, lorebookOverflow(withBatch))
        assertEquals(3, adjustment.archived.size)
        assertEquals(AppLimits.Lorebook.ENTRIES, adjustment.character.lorebookCapacityCount())
    }

    @Test
    fun evictionIsIdempotentOnRetry() {
        val character = Character(id = "c1", lorebook = aged(AppLimits.Lorebook.ENTRIES))
        val withBatch = character.copy(lorebook = character.lorebook + aged(3, startIndex = 5000))
        val once = archiveToFit(withBatch, lorebookOverflow(withBatch))
        // Re-running the same reconciliation finds nothing left to free.
        val twice = archiveToFit(once.character, lorebookOverflow(once.character))
        assertTrue(twice.archived.isEmpty())
        assertEquals(once.character, twice.character)
    }

    @Test
    fun rankingIsDeterministic() {
        val entries = listOf(
            model("c", created = "2026-03-01T00:00:00Z"),
            model("a", created = "2026-01-01T00:00:00Z", mentions = 4),
            model("b", created = "2026-01-01T00:00:00Z", mentions = 1),
        )
        val first = rankLorebookForEviction(entries).map { it.id }
        val second = rankLorebookForEviction(entries.reversed()).map { it.id }
        assertEquals(first, second)
        // Same active time -> fewer mentions first; the newest is last.
        assertEquals(listOf("b", "a", "c"), first)
    }

    @Test
    fun activeTimeIsTheMaxOfMentionAndCreation() {
        val mentioned = model("m", created = "2020-01-01T00:00:00Z").copy(lastMentionedAt = "2026-06-01T00:00:00Z")
        val created = model("c", created = "2025-01-01T00:00:00Z")
        assertEquals("2026-06-01T00:00:00Z", mentioned.activeAt)
        // A recently mentioned (but ancient) entry must not be evicted before a plain older one.
        assertEquals(listOf("c", "m"), rankLorebookForEviction(listOf(mentioned, created)).map { it.id })
    }

    @Test
    fun anEntryWithoutAnyTimestampSortsLast() {
        val dated = model("dated", created = "2026-01-01T00:00:00Z")
        val undated = model("undated", created = null)
        assertEquals("", undated.activeAt)
        assertEquals(listOf("dated", "undated"), rankLorebookForEviction(listOf(undated, dated)).map { it.id })
    }

    @Test
    fun anOverCapacityImportConvergesInOneStep() {
        val character = Character(id = "c1", lorebook = aged(205))
        val converged = character.convergeLorebookCapacity()
        assertEquals(AppLimits.Lorebook.ENTRIES, converged.lorebookCapacityCount())
        assertEquals(5, converged.lorebookArchive.size)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), converged.lorebookArchive.map { it.id })
        // Converging again is a no-op.
        assertEquals(converged, converged.convergeLorebookCapacity())
    }

    @Test
    fun manualEntriesAreNeverCountedNorArchived() {
        val live = aged(210, startIndex = 1000) + (1..10).map { manual("u$it") }
        val converged = Character(id = "c1", lorebook = live).convergeLorebookCapacity()
        // Only the 210 model entries count; converging to the ceiling drops exactly 10.
        assertEquals(AppLimits.Lorebook.ENTRIES, converged.lorebookCapacityCount())
        assertEquals(AppLimits.Lorebook.ENTRIES, converged.lorebook.count { it.origin == LorebookOrigin.Model })
        assertEquals(10, converged.lorebookArchive.size)
        assertTrue(converged.lorebookArchive.all { it.origin == LorebookOrigin.Model })
        // Every manual entry is still live.
        assertEquals(10, converged.lorebook.count { it.origin == LorebookOrigin.User })
    }

    @Test
    fun memberBooksShareTheOneGlobalCapacity() {
        val shared = aged(150, startIndex = 1)
        val memberEntries = aged(60, startIndex = 1000)
        val character = Character(
            id = "c1",
            isGroup = true,
            lorebook = shared,
            members = listOf(GroupMember(id = "m1", name = "甲", lorebook = memberEntries)),
        )
        assertEquals(210, character.lorebookCapacityCount())
        val converged = character.convergeLorebookCapacity()
        assertEquals(AppLimits.Lorebook.ENTRIES, converged.lorebookCapacityCount())
        // The archive keeps its origin: overflow from the shared book lands in the shared archive.
        assertEquals(10, converged.lorebookArchive.size)
        assertTrue(converged.members.single().lorebookArchive.isEmpty())
        assertEquals(60, converged.members.single().lorebook.size)
    }

    @Test
    fun archiveThenRestoreRoundTripsToTheOriginalBook() {
        val character = Character(id = "c1", isGroup = true, lorebookArchive = emptyList())
        val memberEntry = model("m1entry", created = "2020-01-01T00:00:00Z")
        val sharedEntry = model("s1", created = "2026-01-01T00:00:00Z")
        val grouped = character.copy(
            lorebook = listOf(sharedEntry),
            members = listOf(GroupMember(id = "m1", name = "甲", lorebook = listOf(memberEntry))),
        )
        val (archived, removed) = grouped.archiveOldestLorebookEntries(1)
        assertEquals(listOf("m1entry"), removed.map { it.id })
        assertTrue(archived.members.single().lorebookArchive.any { it.id == "m1entry" })
        // Restore puts it back into the member book, not the shared one.
        val restored = archived.restoreArchivedLorebookEntry("m1entry")
        assertTrue(restored.members.single().lorebook.any { it.id == "m1entry" })
        assertTrue(restored.members.single().lorebookArchive.isEmpty())
        assertEquals(grouped, restored)
    }
}
