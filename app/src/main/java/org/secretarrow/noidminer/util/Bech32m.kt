package org.secretarrow.noidminer.util

/**
 * Bech32m address validation for Parano1d `o1…` addresses.
 *
 * The upstream encoder (`noid_poseidon2b/primitives.rs`) defines
 * `ADDRESS_HRP = "o"` — the visible "o1" prefix is actually the single-char
 * HRP `o` followed by the bech32 separator `1`. The payload is always a
 * 32-byte value, so the data part is exactly 52 chars + 6 checksum chars.
 *
 * Pure Kotlin mirror of the native validator — used as a fallback when the
 * native core is unavailable (e.g. x86_64 emulator builds).
 */
object Bech32m {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M_CONST = 0x2bc830a3

    /** Upstream `ADDRESS_HRP`. */
    const val HRP: String = "o"

    /** 52 payload chars (32 bytes) + 6 checksum chars after the separator. */
    const val O1_ADDRESS_LENGTH: Int = HRP.length + 1 + 52 + 6

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

    /** Extracts (hrp, 5-bit data) from a bech32 string; null when malformed. */
    private fun decode(address: String): Pair<String, List<Int>>? {
        if (address.length < 8) return null
        if (address.any { it.code < 33 || it.code > 126 }) return null
        val lower = address.lowercase()
        val upper = address.uppercase()
        if (address != lower && address != upper) return null
        val addr = lower
        val sep = addr.lastIndexOf('1')
        if (sep <= 0 || sep + 7 > addr.length) return null
        val hrp = addr.substring(0, sep)
        val data = mutableListOf<Int>()
        for (c in addr.substring(sep + 1)) {
            val idx = CHARSET.indexOf(c)
            if (idx < 0) return null
            data.add(idx)
        }
        return hrp to data
    }

    private fun verifyChecksum(hrp: String, data: List<Int>): Boolean {
        if (data.size < 7) return false
        val checksum = data.takeLast(6)
        val values = hrpExpand(hrp) + data.dropLast(6) + listOf(0, 0, 0, 0, 0, 0)
        val computed = polymod(values) xor BECH32M_CONST
        for (i in checksum.indices) {
            if ((computed shr (5 * (5 - i))) and 0x1f != checksum[i]) return false
        }
        return true
    }

    /**
     * Validates a Parano1d payout address: bech32m checksum, HRP `o`
     * (i.e. the address reads `o1…`), 32-byte payload.
     */
    fun isValidO1(address: String): Boolean {
        val trimmed = address.trim()
        val (hrp, data) = decode(trimmed) ?: return false
        if (hrp != HRP) return false
        if (data.size != 58) return false
        return verifyChecksum(hrp, data)
    }
}
