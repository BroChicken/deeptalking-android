package com.deeptalking.domain.memory

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.LongTermMemory
import com.deeptalking.core.model.ShortTermMemory

/** Extracts structured memories from a finished turn (ported in M3). */
interface MemoryExtractor {
    suspend fun extract(character: Character, turn: String): ExtractionResult
}

data class ExtractionResult(
    val shortTerm: List<ShortTermMemory> = emptyList(),
    val longTerm: List<LongTermMemory> = emptyList(),
)

/** Relevance ranking over the long-term store (keyword now, embeddings later). */
interface MemoryRetriever {
    suspend fun retrieve(character: Character, query: String, limit: Int = 6): List<LongTermMemory>
}
