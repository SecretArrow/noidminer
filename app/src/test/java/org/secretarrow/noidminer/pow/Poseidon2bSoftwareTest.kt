package org.secretarrow.noidminer.pow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-vector tests for the pure-Kotlin Poseidon2b engine.
 * The same vectors are validated against the native Rust core in
 * native-pow/tests/golden_vectors.rs — if both pass in CI, every backend
 * produces byte-identical digests.
 */
class Poseidon2bSoftwareTest {
    private val engine = Poseidon2bSoftware()

    @Test
    fun `golden vectors match the python reference`() {
        for ((name, fieldsHex, digests) in Poseidon2bConstants.GOLDEN_VECTORS) {
            for ((nonce, expected) in digests) {
                val digest = engine.powHash(fieldsHex, nonce)
                assertNotNull("$name nonce=$nonce", digest)
                assertEquals(
                    "$name nonce=$nonce", expected,
                    org.secretarrow.noidminer.util.Hex
                        .encode(digest!!)
                )
            }
        }
    }

    @Test
    fun `digest is deterministic and differs across nonces`() {
        val fields = Poseidon2bConstants.GOLDEN_VECTORS[0].second
        val a = engine.powHash(fields, 7L)!!
        val b = engine.powHash(fields, 7L)!!
        val c = engine.powHash(fields, 8L)!!
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(c))
    }

    @Test
    fun `bad template length returns null`() {
        assertEquals(null, engine.powHash("00", 0L))
        assertEquals(null, engine.powHash("zz".repeat(128), 0L))
    }

    @Test
    fun `le256 little endian ordering`() {
        val low = ByteArray(32)
        val high = ByteArray(32) { 0xFF.toByte() }
        val mid = ByteArray(32)
        mid[0] = 0x01 // LE: byte 0 is the least significant
        assertTrue(engine.le256Lt(low, mid))
        assertFalse(engine.le256Lt(mid, low))
        assertFalse(engine.le256Lt(high, high))
        assertTrue(engine.le256Lt(low, high))
    }

    @Test
    fun `permutation is a bijection on probe states`() {
        // Deterministic probe: different inputs must map to different outputs.
        val state1 = arrayOf(
            longArrayOf(1L, 0L),
            longArrayOf(0L, 0L),
            longArrayOf(1L, 0L),
            longArrayOf(0L, 0L),
        )
        val state2 = arrayOf(
            longArrayOf(2L, 0L),
            longArrayOf(0L, 0L),
            longArrayOf(1L, 0L),
            longArrayOf(0L, 0L),
        )
        engine.permute(state1)
        engine.permute(state2)
        assertFalse(state1.contentDeepEquals(state2))
    }
}
