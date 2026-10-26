package org.secretarrow.noidminer.pool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.secretarrow.noidminer.data.buildPoolUser
import org.secretarrow.noidminer.data.stratum.StratumCodec
import org.secretarrow.noidminer.util.Bech32m

/**
 * Codec tests against REAL captured pool traffic.
 *
 * Every line below was captured live from InnovLab / RPlant
 * (parano1d-stratum-v1, height ~254k, October 2026). If the codec decodes
 * these, it decodes the production protocol.
 */
class PoolProtocolTest {
    private val userAddress = "o1z6nlslj0hu9c0qpnmgr4cfzm3kr2cn7k70svte6ph3uujg3nzmqswhmprn"

    private val realNotify = """{"id":null,"method":"mining.notify","params":[{"job_id":"000000000000d68fb83e00000000bc9b","assignment_id":"1","pow_fields_hex":"c8d0dfdbfc4cd3f577d4f017e4e4db9259b052bb9418e79e42c8c2faa515c3991c978a3c89f50960d83b36c1fe04346c0adce534764473d2def33d5eb18aab43f97c177fa6b9ea64fe78c3f9aa3a510ccf5b9a1790a216bab8b8597fe1048d2ea019df6a00000000000000000000000054e003000000000000000000000000001c98c496a985fb4e3badf043c470f644cc35fcc72f77f0d78c82bfe30563770900000000000000000000000000000000bad59a27ad581f21ac7f31b3014509fc804bc1f043dc8d445af60d0000000000180000000000000000000000000000008a7b0100000000000000000000000000f5150a00000000000000000000000000","nonce_field_index":10,"nonce_bits":64,"block_target_hex":"bad59a27ad581f21ac7f31b3014509fc804bc1f043dc8d445af60d0000000000","share_target_hex":"8135bbb8ef4fbde1863daaf317815443333cc0f55ffb75ef068e5d1103000000","nonce_prefix_hex":"b83e00000000bc9b","work_domain_id":"6e79ef22393b8a7a23c2abb8dfd4d94c7dd2df7c562073aad1e600cd691b5179","clean":true,"height":254036,"coinbase_value_micronoid":14400000,"expires_in_seconds":96}]}"""

    private val realSubscribe = """{"error":null,"hashrate_report":["miner.stats","eth_submitHashrate","mining.hashrate"],"id":1,"result":{"nonce_bits":64,"protocol":"parano1d-stratum-v1","session_namespace":"b83e00000000bc9b"}}"""

    private val realAuthorize = """{"id":2,"result":true,"error":null}"""

    private val realPause = """{"id":null,"method":"mining.pause","params":[{"reason":"canonical-tip-unverified"}]}"""

    @Test
    fun `real subscribe result decodes to SubscribedV1`() {
        val event = StratumCodec.decode(realSubscribe)
        assertTrue(event is StratumCodec.Event.SubscribedV1)
        event as StratumCodec.Event.SubscribedV1
        assertEquals("parano1d-stratum-v1", event.protocol)
        assertEquals("b83e00000000bc9b", event.sessionNamespace)
        assertEquals(64, event.nonceBits)
    }

    @Test
    fun `real authorize result decodes to ok`() {
        val event = StratumCodec.decode(realAuthorize)
        assertTrue(event is StratumCodec.Event.AuthResult)
        assertTrue((event as StratumCodec.Event.AuthResult).ok)
    }

    @Test
    fun `real notify decodes with session prefix and work domain`() {
        val event = StratumCodec.decode(realNotify)
        assertTrue("expected Notify, got $event", event is StratumCodec.Event.Notify)
        event as StratumCodec.Event.Notify
        assertEquals("000000000000d68fb83e00000000bc9b", event.jobId)
        assertEquals(254036L, event.height)
        assertEquals(512, event.powFieldsHex.length)
        assertEquals("b83e00000000bc9b", event.laneHiHex)
        assertEquals(
            "6e79ef22393b8a7a23c2abb8dfd4d94c7dd2df7c562073aad1e600cd691b5179",
            event.workDomainIdHex,
        )
        assertEquals(
            "8135bbb8ef4fbde1863daaf317815443333cc0f55ffb75ef068e5d1103000000",
            event.targetLeHex,
        )
        assertTrue(event.cleanJobs)
        assertEquals(96L, event.expiresInSeconds)
        // The share target must be a valid 32-byte LE value.
        assertEquals(64, event.targetLeHex.length)
    }

    @Test
    fun `real pause decodes with reason`() {
        val event = StratumCodec.decode(realPause)
        assertTrue(event is StratumCodec.Event.Paused)
        assertEquals("canonical-tip-unverified", (event as StratumCodec.Event.Paused).reason)
    }

    @Test
    fun `resume decodes`() {
        val event = StratumCodec.decode("""{"id":null,"method":"mining.resume","params":[{}]}""")
        assertTrue(event is StratumCodec.Event.Resumed)
    }

    @Test
    fun `error object surfaces as Error event`() {
        val err = StratumCodec.decode(
            """{"id":10,"result":false,"error":{"code":-32000,"message":"nonce-length violation"}}""",
        )
        assertTrue(err is StratumCodec.Event.Error)
    }

    @Test
    fun `real payout address validates (HRP o, bech32m)`() {
        assertTrue(Bech32m.isValidO1(userAddress))
        // Uppercase is still a valid bech32 form (decoded case-insensitively).
        assertTrue(Bech32m.isValidO1(userAddress.uppercase()))
        // Any single-char corruption must fail the checksum.
        val corrupted = userAddress.dropLast(1) + if (userAddress.last() == 'n') 'q' else 'n'
        assertFalse(Bech32m.isValidO1(corrupted))
        // Wrong payload length must fail.
        assertFalse(Bech32m.isValidO1(userAddress.dropLast(1)))
        // Wrong HRP must fail.
        assertFalse(Bech32m.isValidO1("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
    }

    @Test
    fun `pool user prefers explicit then wallet-worker`() {
        assertEquals(
            "worker.custom",
            buildPoolUser("worker.custom", userAddress, "w1"),
        )
        assertEquals(
            "$userAddress.noidmobile",
            buildPoolUser("", userAddress, "noidmobile"),
        )
        assertEquals(
            "$userAddress.noidminer",
            buildPoolUser("", userAddress, "  "),
        )
        assertEquals("", buildPoolUser("", "", ""))
    }
}
