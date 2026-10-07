package com.deeptalking.engine.cosyvoice

import java.io.File

/**
 * Picks how many CPU threads the ggml backend should use.
 *
 * The runtime defaults to `hardware_concurrency()` (every core, including the
 * little cluster). On big.LITTLE SoCs that spreads the work evenly across a
 * fast and a slow cluster, so the fast cores finish early and idle while the
 * slow ones straggle — a well-known llama.cpp/ggml Android regression. Feeding
 * only the performance cluster is consistently faster for the autoregressive
 * LLM + flow + vocoder pipeline.
 *
 * The heuristic is intentionally conservative: cores whose `cpuinfo_max_freq`
 * is at least [PERF_RATIO] of the fastest core count as "performance", but we
 * never drop below half of the reported cores (a 2+6 topology must still keep
 * enough threads to saturate memory bandwidth).
 */
internal object CpuTopology {

    /** A core counts as "performance" when its max frequency is ≥ this share of the fastest core. */
    private const val PERF_RATIO = 0.7

    private val CPU_DIR = Regex("cpu\\d+")

    fun performanceCoreCount(
        maxFreqs: List<Long> = readMaxFreqs(),
        fallback: Int = Runtime.getRuntime().availableProcessors(),
    ): Int {
        val total = fallback.coerceAtLeast(1)
        if (maxFreqs.size < 2) return total
        val fastest = maxFreqs.max()
        if (fastest <= 0L) return total
        val threshold = (fastest * 7) / 10
        val perf = maxFreqs.count { it >= threshold }
        // Never over-subscribe, and keep at least half the cores for bandwidth.
        return maxOf(perf, total / 2).coerceIn(1, total)
    }

    /** Reads `cpuinfo_max_freq` (kHz) for every present CPU; empty when unavailable. */
    private fun readMaxFreqs(): List<Long> = runCatching {
        File("/sys/devices/system/cpu").listFiles()
            ?.filter { it.isDirectory && CPU_DIR.matches(it.name) }
            ?.mapNotNull { cpu ->
                val f = File(cpu, "cpufreq/cpuinfo_max_freq")
                if (f.canRead()) f.readText().trim().toLongOrNull() else null
            }
            .orEmpty()
    }.getOrDefault(emptyList())
}
