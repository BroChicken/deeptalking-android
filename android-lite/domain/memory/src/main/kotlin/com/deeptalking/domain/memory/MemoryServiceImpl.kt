package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.MemoryCategory
import com.deeptalking.core.model.ShortTermMemory
import java.time.Instant

/**
 * Default [MemoryService] implementation for milestone M3.
 *
 * It composes the pure helpers ([TimeUtils], [LorebookMatcher], [Retrieval],
 * [MemoryPolicy]) and performs only list-shape transformations, so the same
 * predictable rules as the legacy `src/js/memory` modules apply.
 *
 * The pending recall request is now persisted on [Character.pendingRecall] (the
 * legacy `memory.pendingRecall`), so it survives process restarts. The native
 * [PendingRecall] only carries the category/tags request, not a member scope;
 * member-scoped recall is therefore applied to the member store at request time
 * while the persisted request is character-level.
 */
class MemoryServiceImpl : MemoryService {

    override suspend fun retrieve(character: Character, query: String, limit: Int): List<LongTermMemory> {
        if (limit <= 0) return emptyList()
        val now = System.currentTimeMillis()
        // Legacy `retrieveRelevantMemories`: the persisted pending-recall items are
        // merged first (score 999) and never crowded out by keyword hits.
        val pending = character.pendingRecall
        val pendingIds = pending.map { it.id }.toSet()
        val scored = topK(query, character.longTerm.filter { it.id !in pendingIds }, limit, now)
        return (pending + scored).distinctBy { it.id }.take(limit)
    }

    override fun listMemories(
        character: Character,
        category: String,
        keyword: String,
        memberName: String?,
        limit: Int,
    ): List<LongTermMemory> {
        if (limit <= 0) return emptyList()
        val store = longTermStoreOf(character, memberName)
        val targetCategory = categoryFrom(category)
        val needle = keyword.trim().lowercase()
        return store
            .filter { item ->
                (targetCategory == null || item.category == targetCategory) &&
                    (needle.isEmpty() || searchableText(item).contains(needle))
            }
            .take(limit)
    }

    override fun selectLorebook(character: Character, recentText: String): List<LorebookEntry> {
        val entries = mutableListOf<LorebookEntry>()
        entries += character.lorebook
        if (character.isGroup) {
            character.members.forEach { entries += it.lorebook }
        }
        val recentMessages = character.instant
            .filter { !it.isLoading }
            .takeLast(LorebookLimits().scanMessages)
            .map { it.content }
        return select(entries, recentText, recentMessages, LorebookLimits(), orderOf = { it.order })
    }

    override fun applyTurn(
        character: Character,
        shortTerm: List<ShortTermMemory>,
        longTerm: List<LongTermMemory>,
        memberLongTerm: Map<String, List<LongTermMemory>>,
    ): Character {
        val mergedShortTerm = trimShortTerm(dedupeShortTerm(character.shortTerm + shortTerm))
        val mergedLongTerm = mergeStores(character.longTerm, longTerm)
        val members = if (character.members.isEmpty()) {
            character.members
        } else {
            character.members.map { member ->
                val incoming = memberLongTerm.entries
                    .firstOrNull { it.key.equals(member.name, ignoreCase = true) }
                    ?.value
                if (incoming.isNullOrEmpty()) member else member.copy(longTerm = mergeStores(member.longTerm, incoming))
            }
        }
        return character.copy(shortTerm = mergedShortTerm, longTerm = mergedLongTerm, members = members)
    }

    override fun deleteMemory(character: Character, id: String, memberName: String?): Character =
        withLongTermStore(character, memberName) { list -> list.filterNot { it.id == id } }

    override fun updateMemory(
        character: Character,
        id: String,
        memberName: String?,
        patch: (LongTermMemory) -> LongTermMemory,
    ): Character = withLongTermStore(character, memberName) { list ->
        list.map { if (it.id == id) patch(it) else it }
    }

    override fun setPendingRecall(
        character: Character,
        category: String?,
        tags: List<String>,
        memberName: String?,
    ): Character {
        val store = longTermStoreOf(character, memberName)
        val targetCategory = categoryFrom(category)
        val tagList = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        // Legacy `handleRecall`: a blank category selects nothing.
        val selected = if (targetCategory == null) {
            emptyList()
        } else {
            store.filter { item ->
                item.category == targetCategory &&
                    (tagList.isEmpty() || tagList.any { tag -> searchableText(item).contains(tag) })
            }.sortedByDescending { memorySortScore(it) }
                .take(AppLimits.Memory.PENDING_RECALL)
        }
        if (selected.isEmpty()) return character

        val nowIso = Instant.now().toString()
        val ids = selected.map { it.id }.toSet()
        val bumped = selected.map {
            it.copy(
                lastRecalled = nowIso,
                recallCount = it.recallCount + 1,
                usageCount = it.usageCount + 1,
                lastUsageAt = nowIso,
            )
        }
        val updated = withLongTermStore(character, memberName) { list ->
            list.map { item -> if (item.id in ids) bumped.first { it.id == item.id } else item }
        }
        // Legacy persists the full recalled items on the character (or member) store.
        if (memberName == null) return updated.copy(pendingRecall = bumped)
        val wanted = memberName.trim().lowercase()
        return updated.copy(
            members = updated.members.map { member ->
                if (member.name.trim().lowercase() == wanted) member.copy(pendingRecall = bumped) else member
            },
        )
    }

    private fun mergeStores(existing: List<LongTermMemory>, incoming: List<LongTermMemory>): List<LongTermMemory> {
        if (incoming.isEmpty()) return existing
        val combined = existing + incoming
        return MemoryCategory.entries.flatMap { category ->
            val forCategory = combined.filter { it.category == category }
            pruneLongTerm(dedupeLongTerm(forCategory, category))
        }
    }

    private fun dedupeShortTerm(items: List<ShortTermMemory>): List<ShortTermMemory> {
        val indexByIdentity = mutableMapOf<String, Int>()
        val result = mutableListOf<ShortTermMemory>()
        for (item in items) {
            val identity = item.content.trim().lowercase() + "|" + eventIdentity(item.eventTime)
            val existingIndex = indexByIdentity[identity]
            if (existingIndex == null) {
                indexByIdentity[identity] = result.size
                result += item
            } else {
                val existing = result[existingIndex]
                result[existingIndex] = existing.copy(
                    sourceMessageIds = (existing.sourceMessageIds + item.sourceMessageIds).distinct()
                        .take(AppLimits.Memory.SUMMARY_SOURCES),
                    sourceRoles = (existing.sourceRoles + item.sourceRoles).distinct(),
                    userEvidence = (existing.userEvidence + item.userEvidence).distinct()
                        .take(AppLimits.Memory.SUMMARY_SOURCES),
                )
            }
        }
        return result
    }

    private fun longTermStoreOf(character: Character, memberName: String?): List<LongTermMemory> {
        if (memberName == null) return character.longTerm
        return memberOf(character, memberName)?.longTerm ?: emptyList()
    }

    private fun withLongTermStore(
        character: Character,
        memberName: String?,
        transform: (List<LongTermMemory>) -> List<LongTermMemory>,
    ): Character {
        if (memberName == null) return character.copy(longTerm = transform(character.longTerm))
        val wanted = memberName.trim().lowercase()
        return character.copy(
            members = character.members.map { member ->
                if (member.name.trim().lowercase() == wanted) {
                    member.copy(longTerm = transform(member.longTerm))
                } else {
                    member
                }
            },
        )
    }

    private fun memberOf(character: Character, memberName: String): GroupMember? {
        val wanted = memberName.trim().lowercase()
        return character.members.firstOrNull { it.name.trim().lowercase() == wanted }
    }

    private fun searchableText(item: LongTermMemory): String =
        "${item.key} ${item.value} ${item.tags.joinToString(" ")}".lowercase()

    private fun categoryFrom(value: String?): MemoryCategory? {
        return when (value?.trim()?.lowercase()) {
            "userprofile", "user_profile" -> MemoryCategory.UserProfile
            "relationship" -> MemoryCategory.Relationship
            "events" -> MemoryCategory.Events
            "promises" -> MemoryCategory.Promises
            "habits" -> MemoryCategory.Habits
            else -> null
        }
    }

    private companion object {
        const val PENDING_RECALL_BOOST = 100.0
    }
}
