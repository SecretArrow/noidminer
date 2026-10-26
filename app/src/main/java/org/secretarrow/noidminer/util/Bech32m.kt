package org.secretarrow.noidminer.util

/**
 * Bech32m address validation for Parano1d `o1…` addresses.
 * Pure Kotlin mirror of the native validator — used as a fallback when the
 * native core is unavailable (e.g. x86_64 emulator builds).
 */
object Bech32m {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M_CONST = 0x2bc830a3

    private fun polymod(values: List<Int>): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in gen.indices) {
                if ((b shr i) and 1 == 1) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    /** Validates a lowercase `o1…` bech32m address (checksum must match). */
    fun isValidO1(address: String): Boolean {
        if (address.length < 8) return false
        if (address != address.lowercase()) return false
        if (address.any { it.code > 126 }) return false
        val sep = address.lastIndexOf('1')
        if (sep <= 0 || sep + 7 > address.length) return false
        val hrp = address.substring(0, sep)
        if (hrp != "o1") return false
        val data = mutableListOf<Int>()
        for (c in address.substring(sep + 1)) {
            val idx = CHARSET.indexOf(c)
            if (idx < 0) return false
            data.add(idx)
        }
        if (data.size < 7) return false
        val checksum = data.takeLast(6)
        val values = hrpExpand(hrp) + data.dropLast(6) + listOf(0, 0, 0, 0, 0, 0)
        val computed = polymod(values) xor BECH32M_CONST
        for (i in checksum.indices) {
            if ((computed shr (5 * (5 - i))) and 0x1f != checksum[i]) return false
        }
        return true
    }
}
