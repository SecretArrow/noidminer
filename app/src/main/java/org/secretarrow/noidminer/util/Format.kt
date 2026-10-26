package org.secretarrow.noidminer.util

/** Formatting helpers for hashrates, durations, and compact numbers. */
object Format {
    fun hashrate(hps: Double): String = when {
        hps >= 1_000_000.0 -> "%.2f MH/s".format(hps / 1_000_000.0)
        hps >= 1_000.0 -> "%.2f kH/s".format(hps / 1_000.0)
        hps > 0.0 -> "%.1f H/s".format(hps)
        else -> "0 H/s"
    }

    fun duration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "%dh %02dm".format(h, m)
            m > 0 -> "%dm %02ds".format(m, s)
            else -> "%ds".format(s)
        }
    }

    fun shortAddress(address: String, keep: Int = 10): String =
        if (address.length <= keep * 2 + 3) {
            address
        } else {
            "${address.take(keep)}…${address.takeLast(4)}"
        }

    fun compact(value: Long): String = when {
        value >= 1_000_000_000 -> "%.1fB".format(value / 1_000_000_000.0)
        value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
        value >= 1_000 -> "%.1fk".format(value / 1_000.0)
        else -> value.toString()
    }
}
