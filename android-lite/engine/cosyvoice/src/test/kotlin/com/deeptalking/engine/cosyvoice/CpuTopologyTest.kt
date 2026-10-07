package com.deeptalking.engine.cosyvoice

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuTopologyTest {

    @Test
    fun fourPlusFourKeepsOnlyTheBigCluster() {
        // 4×2.8GHz + 4×1.8GHz -> 4 performance cores.
        val freqs = List(4) { 2_800_000L } + List(4) { 1_800_000L }
        assertEquals(4, CpuTopology.performanceCoreCount(freqs, fallback = 8))
    }

    @Test
    fun threeTierPrimeGoldSilverKeepsTopTwoClusters() {
        // 1×3.2GHz + 3×2.8GHz + 4×2.0GHz -> 4 performance cores.
        val freqs = listOf(3_200_000L) + List(3) { 2_800_000L } + List(4) { 2_000_000L }
        assertEquals(4, CpuTopology.performanceCoreCount(freqs, fallback = 8))
    }

    @Test
    fun homogeneousSocUsesEveryCore() {
        val freqs = List(8) { 2_000_000L }
        assertEquals(8, CpuTopology.performanceCoreCount(freqs, fallback = 8))
    }

    @Test
    fun neverDropsBelowHalfTheCores() {
        // 2 big + 6 little would count 2, but bandwidth needs at least half.
        val freqs = List(2) { 3_000_000L } + List(6) { 1_500_000L }
        assertEquals(4, CpuTopology.performanceCoreCount(freqs, fallback = 8))
    }

    @Test
    fun missingFrequenciesFallsBackToAllCores() {
        assertEquals(6, CpuTopology.performanceCoreCount(emptyList(), fallback = 6))
        assertEquals(6, CpuTopology.performanceCoreCount(listOf(2_000_000L), fallback = 6))
    }

    @Test
    fun neverExceedsAvailableProcessors() {
        val freqs = List(8) { 2_500_000L }
        assertEquals(4, CpuTopology.performanceCoreCount(freqs, fallback = 4))
    }
}
