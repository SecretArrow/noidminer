package org.secretarrow.noidminer.util

/** Hex helpers shared across engines and protocol code. */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4])
            sb.append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** True when [value] is a non-empty hex string of even length. */
    fun isValidHex(value: String): Boolean =
        value.isNotEmpty() &&
            value.length % 2 == 0 &&
            value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
