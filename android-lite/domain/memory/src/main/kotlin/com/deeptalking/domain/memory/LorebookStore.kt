package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.common.trimTo
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.LorebookOrigin
import java.time.Instant

/**
 * Lorebook write helpers ported from `src/js/memory/lorebook.js` (`upsertLorebookEntry`,
 * `resolveLorebookSources`). Used by the automatic consolidation task; the
 * `upsert_lorebook_entry` tool keeps its own matching implementation.
 */

data class LorebookUpsertResult(
    val ok: Boolean,
    val reason: String = "",
    val list: List<LorebookEntry> = emptyList(),
    val entry: LorebookEntry? = null,
    val created: Boolean = false,
    val mergedIntoUser: Boolean = false,
)

/** A proposed lorebook entry (model output) before validation/merge. */
data class LorebookProposal(
    val name: String,
    val content: String,
    val keywords: List<String> = emptyList(),
    val alwaysActive: Boolean = false,
)

/**
 * Validates that both source ids and evidence trace back to real short-term
 * items whose content matches the evidence. Mirrors `resolveLorebookSources`.
 */
fun resolveLorebookSources(character: Character, sourceIds: List<String>, evidence: String?): Boolean {
    val excerpt = evidence?.trimTo(300).orEmpty()
    if (excerpt.isEmpty()) return false
    val ids = sourceIds.map { it.trim() }.filter { it.isNotEmpty() }
    if (ids.isEmpty()) return false
    val items = ids.mapNotNull { id -> character.shortTerm.firstOrNull { it.id == id } }
    if (items.isEmpty()) return false
    return items.any { evidenceMatchesSummary(it.content, excerpt) }
}

/**
 * Inserts or merges [proposal] into [list]. AI near-duplicates merge; user-written
 * entries are protected; a new entry needs a keyword or explicit `alwaysActive`.
 */
fun upsertLorebookEntry(
    list: List<LorebookEntry>,
    proposal: LorebookProposal,
    entryId: String = "",
    nowIso: String = Instant.now().toString(),
): LorebookUpsertResult {
    val name = proposal.name.trim().take(AppLimits.Lorebook.NAME_CHARS)
    val content = proposal.content.trim().take(AppLimits.Lorebook.CONTENT_CHARS)
    if (name.isEmpty() || content.isEmpty()) {
        return LorebookUpsertResult(false, "条目名与内容不能为空")
    }
    val keywords = proposal.keywords.map { it.trim() }.filter { it.isNotEmpty() }
        .take(AppLimits.Lorebook.KEYWORDS_PER_ENTRY)

    val exact = findEntry(list, entryId, name)
    if (exact != null) {
        if (exact.origin != LorebookOrigin.Model) {
            return LorebookUpsertResult(true, list = list, entry = exact, created = false, mergedIntoUser = true)
        }
        val updated = exact.copy(
            name = exact.name.ifEmpty { name },
            content = content,
            keywords = if (keywords.isNotEmpty()) keywords else exact.keywords,
            alwaysActive = exact.alwaysActive || proposal.alwaysActive,
            misses = 0,
            updatedAt = nowIso,
        )
        return LorebookUpsertResult(true, list = replaceEntry(list, exact.id, updated), entry = updated)
    }

    val similar = findSimilarLorebookEntry(list, LorebookEntry(name = name, content = content, keywords = keywords))
    if (similar != null) {
        if (similar.origin != LorebookOrigin.Model) {
            return LorebookUpsertResult(true, list = list, entry = similar, mergedIntoUser = true)
        }
        val merged = mergeLorebookEntry(similar, LorebookEntry(name = name, content = content, keywords = keywords, updatedAt = nowIso))
        return LorebookUpsertResult(true, list = replaceEntry(list, similar.id, merged), entry = merged)
    }

    if (keywords.isEmpty() && !proposal.alwaysActive) {
        return LorebookUpsertResult(false, "条目需要关键词或显式常驻")
    }
    if (proposal.alwaysActive &&
        list.count { it.origin == LorebookOrigin.Model && it.alwaysActive } >= AppLimits.Lorebook.MAX_ALWAYS_ACTIVE
    ) {
        return LorebookUpsertResult(false, "常驻条目已达上限")
    }
    if (list.size >= AppLimits.Lorebook.ENTRIES) {
        return LorebookUpsertResult(false, "世界书条目已达上限")
    }
    val entry = LorebookEntry(
        id = "lore_" + System.currentTimeMillis() + "_" + (0..0xFFFFFF).random().toString(16),
        name = name,
        content = content,
        keywords = keywords,
        alwaysActive = proposal.alwaysActive,
        origin = LorebookOrigin.Model,
        createdAt = nowIso,
        updatedAt = nowIso,
    )
    return LorebookUpsertResult(true, list = list + entry, entry = entry, created = true)
}

/**
 * Applies a model-proposed entry to a character: writes to the shared lorebook or
 * a named member's private lorebook, validating `sourceShortTermIds` + evidence.
 */
fun upsertLorebookEntryForCharacter(
    character: Character,
    proposal: LorebookProposal,
    sourceShortTermIds: List<String>,
    entryId: String = "",
    memberName: String? = null,
): Pair<Character, LorebookUpsertResult> {
    if (!resolveLorebookSources(character, sourceShortTermIds, proposal.content)) {
        return character to LorebookUpsertResult(false, "来源或证据无法对应")
    }
    if (!memberName.isNullOrBlank()) {
        val wanted = memberName.trim().lowercase()
        val member = character.members.firstOrNull { it.name.trim().lowercase() == wanted }
            ?: return character to LorebookUpsertResult(false, "找不到成员「$memberName」")
        val result = upsertLorebookEntry(member.lorebook, proposal, entryId)
        if (!result.ok) return character to result
        val members = character.members.map { if (it.id == member.id) it.copy(lorebook = result.list) else it }
        return character.copy(members = members) to result
    }
    val result = upsertLorebookEntry(character.lorebook, proposal, entryId)
    if (!result.ok) return character to result
    return character.copy(lorebook = result.list) to result
}

/** Local near-duplicate compaction: merges model entries, preserves user entries. */
fun dedupeLorebook(list: List<LorebookEntry>): List<LorebookEntry> {
    val kept = mutableListOf<LorebookEntry>()
    for (entry in list) {
        if (entry.origin != LorebookOrigin.Model) {
            kept += entry
            continue
        }
        val target = kept.indexOfFirst { it.origin == LorebookOrigin.Model && lorebookEntriesSimilar(it, entry) }
        if (target < 0) {
            kept += entry
        } else {
            kept[target] = mergeLorebookContentEntry(kept[target], entry)
        }
    }
    return kept
}

private fun mergeLorebookContentEntry(existing: LorebookEntry, incoming: LorebookEntry): LorebookEntry =
    mergeLorebookEntry(existing, incoming)

private fun findEntry(list: List<LorebookEntry>, entryId: String, name: String): LorebookEntry? {
    val id = entryId.trim()
    if (id.isNotEmpty()) list.firstOrNull { it.id == id }?.let { return it }
    val wanted = name.trim().lowercase()
    if (wanted.isEmpty()) return null
    return list.firstOrNull { it.name.trim().lowercase() == wanted }
}

private fun replaceEntry(list: List<LorebookEntry>, id: String, entry: LorebookEntry): List<LorebookEntry> =
    list.map { if (it.id == id) entry else it }
