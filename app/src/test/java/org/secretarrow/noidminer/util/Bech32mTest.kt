package org.secretarrow.noidminer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bech32m validator regression test.
 *
 * The previous implementation mixed two checksum variants (padded polymod
 * XOR BECH32M_CONST compared against checksum bytes) and rejected every
 * valid bech32m address — including the production payout address used by
 * the real-pool-proof workflow. The current implementation uses the
 * standard BIP-350 form: `polymod(hrp_expand + data) == BECH32M_CONST`.
 *
 * The address `o1z6...` below was verified against the Python BIP-350
 * reference implementation and is the same address used in
 * `real-pool-proof.yml` and `VaultFallbackTest`.
 */
class Bech32mTest {
    private val realPayout =
        "o1z6nlslj0hu9c0qpnmgr4cfzm3kr2cn7k70svte6ph3uujg3nzmqswhmprn"

    @Test
    fun `real parano1d payout address validates`() {
        assertTrue(Bech32m.isValidO1(realPayout))
    }

    @Test
    fun `case-insensitive — uppercase form also validates`() {
        assertTrue(Bech32m.isValidO1(realPayout.uppercase()))
    }

    @Test
    fun `single-char corruption fails the checksum`() {
        val corrupted = realPayout.dropLast(1) +
            if (realPayout.last() == 'n') 'q' else 'n'
        assertFalse(Bech32m.isValidO1(corrupted))
    }

    @Test
    fun `wrong payload length fails`() {
        assertFalse(Bech32m.isValidO1(realPayout.dropLast(1)))
        assertFalse(Bech32m.isValidO1(realPayout + "x"))
    }

    @Test
    fun `wrong HRP fails`() {
        // Bitcoin bech32m address — HRP is "bc", not "o".
        assertFalse(Bech32m.isValidO1("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
    }

    @Test
    fun `garbage input never throws`() {
        assertFalse(Bech32m.isValidO1(""))
        assertFalse(Bech32m.isValidO1("garbage"))
        assertFalse(Bech32m.isValidO1("o1"))
        assertFalse(Bech32m.isValidO1("o1z"))
        assertFalse(Bech32m.isValidO1("   "))
        assertFalse(Bech32m.isValidO1("o1" + "z".repeat(100)))
    }

    @Test
    fun `whitespace is trimmed before validation`() {
        assertTrue(Bech32m.isValidO1("  $realPayout  "))
        assertTrue(Bech32m.isValidO1("\n$realPayout\t"))
    }
}
