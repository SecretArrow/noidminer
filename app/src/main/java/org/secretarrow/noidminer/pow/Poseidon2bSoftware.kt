package org.secretarrow.noidminer.pow

import org.secretarrow.noidminer.util.Hex

/**
 * Pure-Kotlin Poseidon2b PoW engine (GF(2^128), GCM flat basis).
 *
 * Byte-for-byte identical to the native Rust core — both are validated
 * against the same golden vectors (see [Poseidon2bConstants.GOLDEN_VECTORS]).
 * Slower than the PMULL-accelerated native path, but always available:
 * unit tests, x86_64 emulators, and devices without the native library
 * all use this engine.
 */
class Poseidon2bSoftware {
    private val rc = Poseidon2bConstants.RC_FLAT
    private val mdsFull = Poseidon2bConstants.MDS_FULL_FLAT
    private val mdsPartial = Poseidon2bConstants.MDS_PARTIAL_FLAT
    private val mdsFullOne = Poseidon2bConstants.MDS_FULL_IS_ONE
    private val mdsPartialOne = Poseidon2bConstants.MDS_PARTIAL_IS_ONE
    private val iv = Poseidon2bConstants.IV_FLAT
    private val towerToFlat = Poseidon2bConstants.TOWER_TO_FLAT
    private val flatToTower = Poseidon2bConstants.FLAT_TO_TOWER

    /** XOR of two 128-bit lanes stored as [lo, hi]. */
    private fun xor(a: LongArray, b: LongArray): LongArray =
        longArrayOf(a[0] xor b[0], a[1] xor b[1])

    /** Multiply by x with GCM reduction (x^128 → x^7+x^2+x+1 = 0x87). */
    private fun xtime(a: LongArray): LongArray {
        val carry = a[1] ushr 63
        return longArrayOf(
            (a[0] shl 1) xor (if (carry != 0L) 0x87L else 0L),
            (a[1] shl 1) or (a[0] ushr 63),
        )
    }

    /** Carry-less multiply reduced modulo the GCM polynomial. */
    private fun gcmMul(a: LongArray, b: LongArray): LongArray {
        var acc = longArrayOf(0L, 0L)
        var prod = a
        var mul = b
        for (i in 0 until 128) {
            if (mul[0] and 1L != 0L) acc = xor(acc, prod)
            prod = xtime(prod)
            mul = longArrayOf((mul[0] ushr 1) or (mul[1] shl 63), mul[1] ushr 1)
        }
        return acc
    }

    private fun square(a: LongArray): LongArray = gcmMul(a, a)

    /** x^7 S-box: x^7 = x^4 · x^2 · x computed as (x·x^2)·x^4. */
    private fun sbox(x: LongArray): LongArray {
        val x2 = square(x)
        val x4 = square(x2)
        val x6 = gcmMul(x, x2)
        return gcmMul(x6, x4)
    }

    private fun applyMds(state: Array<LongArray>, mat: Array<Array<LongArray>>, one: Array<BooleanArray>) {
        val input = arrayOf(state[0], state[1], state[2], state[3])
        for (i in 0 until 4) {
            var acc = longArrayOf(0L, 0L)
            for (j in 0 until 4) {
                val term = if (one[i][j]) input[j] else gcmMul(input[j], mat[i][j])
                acc = xor(acc, term)
            }
            state[i] = acc
        }
    }

    /** Apply a 128×128 GF(2) matrix to a lane ([lo, hi]). */
    private fun applyMatrix(matrix: Array<LongArray>, v: LongArray): LongArray {
        var resLo = 0UL
        var resHi = 0UL
        var curLo = v[0].toULong()
        var curHi = v[1].toULong()
        for (i in 0 until 128) {
            if (curLo and 1UL != 0UL) {
                resLo = resLo xor matrix[i][0].toULong()
                resHi = resHi xor matrix[i][1].toULong()
            }
            val nextLo = (curLo shr 1) or (curHi shl 63)
            curHi = curHi shr 1
            curLo = nextLo
        }
        return longArrayOf(resLo.toLong(), resHi.toLong())
    }

    /** Full Poseidon2b permutation on a flat-basis state (t=4, 8F+58P rounds). */
    fun permute(state: Array<LongArray>) {
        applyMds(state, mdsFull, mdsFullOne)
        val half = Poseidon2bConstants.F_ROUNDS / 2
        for (r in 0 until Poseidon2bConstants.N_ROUNDS) {
            if (r < half || r >= half + Poseidon2bConstants.P_ROUNDS) {
                for (i in 0 until 4) {
                    state[i] = sbox(xor(state[i], rc[i][r]))
                }
                applyMds(state, mdsFull, mdsFullOne)
            } else {
                state[0] = sbox(xor(state[0], rc[0][r]))
                applyMds(state, mdsPartial, mdsPartialOne)
            }
        }
    }

    /**
     * PoW digest: 16 tower-basis fields (256-byte LE template), field 10 =
     * nonce. All lanes are converted tower→flat, absorbed 2 at a time
     * through the flat sponge with the POWHDR capacity IV, and the two
     * output lanes are converted flat→tower. Returns 32 bytes or null.
     */
    fun powHash(powFieldsHex: String, nonce: Long): ByteArray? {
        val fields = Hex.decode(powFieldsHex) ?: return null
        if (fields.size != Poseidon2bConstants.POW_FIELD_COUNT * 16) return null
        val state = arrayOf(
            longArrayOf(0L, 0L),
            longArrayOf(0L, 0L),
            iv[0].copyOf(),
            iv[1].copyOf(),
        )
        for (block in 0 until 8) {
            state[0] = xor(state[0], flatLane(fields, block * 2, nonce))
            state[1] = xor(state[1], flatLane(fields, block * 2 + 1, nonce))
            permute(state)
        }
        val out = ByteArray(32)
        laneToBytes(applyMatrix(flatToTower, state[0]), out, 0)
        laneToBytes(applyMatrix(flatToTower, state[1]), out, 16)
        return out
    }

    /**
     * Reads template lane [index] (tower bytes, LE) and converts it to the
     * flat basis, substituting the nonce (also tower→flat) at lane 10.
     */
    private fun flatLane(fields: ByteArray, index: Int, nonce: Long): LongArray {
        val tower = if (index == Poseidon2bConstants.POW_NONCE_FIELD_INDEX) {
            longArrayOf(nonce, 0L)
        } else {
            val base = index * 16
            var lo = 0UL
            var hi = 0UL
            for (i in 0 until 8) {
                lo = lo or ((fields[base + i].toULong() and 0xFFUL) shl (8 * i))
                hi = hi or ((fields[base + 8 + i].toULong() and 0xFFUL) shl (8 * i))
            }
            longArrayOf(lo.toLong(), hi.toLong())
        }
        return applyMatrix(towerToFlat, tower)
    }

    private fun laneToBytes(lane: LongArray, out: ByteArray, offset: Int) {
        var lo = lane[0].toULong()
        var hi = lane[1].toULong()
        for (i in 0 until 8) {
            out[offset + i] = (lo and 0xFFUL).toByte()
            lo = lo shr 8
            out[offset + 8 + i] = (hi and 0xFFUL).toByte()
            hi = hi shr 8
        }
    }

    /** 256-bit little-endian comparison: hash < target. */
    fun le256Lt(hash: ByteArray, target: ByteArray): Boolean {
        if (hash.size != 32 || target.size != 32) return false
        for (i in 31 downTo 0) {
            val a = hash[i].toInt() and 0xFF
            val b = target[i].toInt() and 0xFF
            if (a < b) return true
            if (a > b) return false
        }
        return false
    }

    companion object {
        /** Shared singleton — the engine is stateless per call. */
        val INSTANCE = Poseidon2bSoftware()
    }
}
