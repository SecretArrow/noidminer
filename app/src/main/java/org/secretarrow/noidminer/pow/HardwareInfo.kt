package org.secretarrow.noidminer.pow

import android.os.Build
import java.io.File

/**
 * Hardware inventory for the Device screen: SoC, CPU clusters (big.LITTLE),
 * ABI, RAM, and the selected hashing backend. Everything is read lazily and
 * cached — values never change while the process lives.
 */
data class HardwareInfo(
    val socName: String,
    val abi: String,
    val coreCount: Int,
    val clusters: List<Cluster>,
    val ramTotalMb: Long,
    val hasNeonPmull: Boolean,
) {
    data class Cluster(
        val label: String,
        val cores: Int,
        val maxFreqKHz: Long,
    )

    /** Suggested worker count: performance cores when detectable, else n-2. */
    fun suggestedThreads(): Int {
        val big = clusters.maxOfOrNull { it.cores } ?: 0
        return when {
            big >= 2 -> big
            coreCount >= 6 -> coreCount - 2
            coreCount > 1 -> coreCount - 1
            else -> 1
        }.coerceIn(1, MinerCore.MAX_THREADS)
    }
}

object HardwareReader {
    fun read(): HardwareInfo {
        val cores = Runtime.getRuntime().availableProcessors()
        return HardwareInfo(
            socName = socName(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            coreCount = cores,
            clusters = readClusters(cores),
            ramTotalMb = readRamMb(),
            hasNeonPmull = abiSupportsPmull(),
        )
    }

    private fun socName(): String = runCatching {
        val hw = java.io
            .BufferedReader(java.io.InputStreamReader(ProcessBuilder("getprop", "ro.hardware").start().inputStream))
            .readLine()
            ?.trim()
        val model = Build.HARDWARE.ifBlank { hw ?: "" }
        val soc = runCatching {
            java.io
                .BufferedReader(java.io.InputStreamReader(ProcessBuilder("getprop", "ro.soc.model").start().inputStream))
                .readLine()
                ?.trim()
        }.getOrNull()
        listOf(soc.orEmpty(), model).firstOrNull { it.isNotBlank() } ?: "SoC"
    }.getOrDefault("SoC")

    /**
     * Group cores by max frequency from sysfs — the portable big.LITTLE
     * heuristic on Android. Missing files yield a single cluster.
     */
    private fun readClusters(cores: Int): List<HardwareInfo.Cluster> {
        val freqs = mutableMapOf<Int, Long>()
        for (cpu in 0 until cores) {
            val f = runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText()
                    .trim()
                    .toLong()
            }.getOrNull() ?: continue
            freqs[cpu] = f
        }
        if (freqs.isEmpty()) {
            return listOf(HardwareInfo.Cluster("CPU", cores, 0L))
        }
        return freqs.entries
            .groupBy { it.value }
            .entries
            .sortedByDescending { it.key }
            .mapIndexed { index, (_, members) ->
                val label = if (index == 0) {
                    "Prime/Big"
                } else if (index == 1) {
                    "Mid"
                } else {
                    "LITTLE"
                }
                HardwareInfo.Cluster(label, members.size, members.first().value)
            }
    }

    private fun readRamMb(): Long = runCatching {
        val lines = File("/proc/meminfo").readLines()
        val totalKb = lines
            .firstOrNull { it.startsWith("MemTotal") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull() ?: 0L
        totalKb / 1024
    }.getOrDefault(0L)

    /** All arm64-v8a SoCs ship PMULL; report per-ABI capability. */
    private fun abiSupportsPmull(): Boolean =
        Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true
}
