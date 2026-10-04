package com.deeptalking.domain.memory

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.LorebookEntry
import com.deeptalking.core.model.ShortTermMemory

/**
 * Memory facade consumed by the agent layer. The implementation lives in this
 * module; the agent only depends on this interface.
 */
interface MemoryService {

    /** Keyword/relevance retrieval over a character's long-term store. */
    suspend fun retrieve(character: Character, query: String, limit: Int = 6): List<LongTermMemory>

    /**
     * Enumerates long-term memories by category/keyword (no relevance ranking),
     * backing the `list_memories` tool. A blank category returns every category;
     * a blank keyword returns all entries in the selected categories.
     */
    fun listMemories(
        character: Character,
        category: String = "",
        keyword: String = "",
        memberName: String? = null,
        limit: Int = 50,
    ): List<LongTermMemory>

    /** Lorebook entries to inject for the current turn (always-active + keyword hits). */
    fun selectLorebook(character: Character, recentText: String): List<LorebookEntry>

    /**
     * Applies a finished turn's memory deltas onto a copy of the character and
     * returns it. [memberLongTerm] keys are member names; a null memberName in
     * the model means the group-shared store.
     */
    fun applyTurn(
        character: Character,
        shortTerm: List<ShortTermMemory>,
        longTerm: List<LongTermMemory>,
        memberLongTerm: Map<String, List<LongTermMemory>> = emptyMap(),
    ): Character

    /** Deletes a long-term memory by id from the shared or a member store. */
    fun deleteMemory(character: Character, id: String, memberName: String? = null): Character

    /** Patches an existing long-term memory by id. */
    fun updateMemory(character: Character, id: String, memberName: String? = null, patch: (LongTermMemory) -> LongTermMemory): Character

    /** Applies a pending recall request. */
    fun setPendingRecall(character: Character, category: String?, tags: List<String>, memberName: String? = null): Character
}
