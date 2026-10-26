package org.secretarrow.noidminer.pow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.secretarrow.noidminer.data.stratum.StratumCodec
import org.secretarrow.noidminer.data.stratum.difficultyToTarget
import org.secretarrow.noidminer.util.Bech32m
import org.secretarrow.noidminer.util.Hex

/** Protocol-layer unit tests: stratum codec, difficulty math, bech32m, hex. */
class ProtocolTest {
    @Test
    fun `stratum codec finds pow_fields and target in notify params`() {
        val fields = "ab".repeat(128) // 256 hex chars
        val target = "cd".repeat(32) // 64 hex chars
        val line = """{"id":null,"method":"mining.notify","params":["job1","$fields","$target",true]}"""
        val event = StratumCodec.decode(line)
        assertTrue(event is StratumCodec.Event.Notify)
        event as StratumCodec.Event.Notify
        assertEquals("job1", event.jobId)
        assertEquals(fields, event.powFieldsHex)
        assertEquals(target, event.targetLeHex)
        assertTrue(event.cleanJobs)
    }

    @Test
    fun `stratum codec flags unsupported job shapes`() {
        val line = """{"id":null,"method":"mining.notify","params":["j1","deadbeef"]}"""
        val event = StratumCodec.decode(line)
        assertTrue(event is StratumCodec.Event.UnsupportedJob)
    }

    @Test
    fun `stratum codec parses subscribe and auth results`() {
        val sub = StratumCodec.decode("""{"id":1,"result":[["mining.notify","a"],"0102",4],"error":null}""")
        assertTrue(sub is StratumCodec.Event.Subscribed)
        sub as StratumCodec.Event.Subscribed
        assertEquals("0102", sub.extraNonce1)
        assertEquals(4, sub.extraNonce2Size)

        val auth = StratumCodec.decode("""{"id":2,"result":true,"error":null}""")
        assertTrue(auth is StratumCodec.Event.AuthResult)
        assertTrue((auth as StratumCodec.Event.AuthResult).ok)
    }

    @Test
    fun `difficulty converts to a 64-hex target`() {
        val t1 = difficultyToTarget(1.0)
        assertEquals(64, t1.length)
        assertTrue(t1.all { it in '0'..'9' || it in 'a'..'f' })
        // Higher difficulty must yield a smaller (harder) target.
        val t10 = difficultyToTarget(10.0)
        assertTrue(compareLe(Hex.decode(t10)!!, Hex.decode(t1)!!) < 0)
    }

    private fun compareLe(a: ByteArray, b: ByteArray): Int {
        for (i in 31 downTo 0) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return 0
    }

    @Test
    fun `bech32m rejects malformed and foreign addresses`() {
        assertFalse(Bech32m.isValidO1(""))
        assertFalse(Bech32m.isValidO1("garbage"))
        assertFalse(Bech32m.isValidO1("BC1QAR0SRRR7XFKVY5L643LYDNW9RE59GTZZWF5MDQ"))
        // BTC bech32 (not bech32m) checksum with wrong HRP must fail.
        assertFalse(Bech32m.isValidO1("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
    }

    @Test
    fun `hex helpers roundtrip`() {
        val bytes = byteArrayOf(0x00, 0x7F, (0x80).toByte(), (0xFF).toByte())
        val encoded = Hex.encode(bytes)
        assertEquals("007f80ff", encoded)
        assertArrayEquals(bytes, Hex.decode(encoded)!!)
        assertEquals(null, Hex.decode("abc"))
        assertEquals(null, Hex.decode("zz"))
        assertTrue(Hex.isValidHex("00ff"))
        assertFalse(Hex.isValidHex("0fz"))
    }

    @Test
    fun `guard policy pausing rules`() {
        val config = Guards.PolicyConfig(
            pauseAtThermal = 4,
            pauseAtBatteryPct = 15,
            onlyWhenCharging = false,
            respectBatterySaver = true,
        )
        val healthy = Guards.GuardSnapshot(thermalStatus = 0, batteryPct = 80, charging = false, batterySaver = false)
        assertTrue(Guards.evaluate(healthy, config) is Guards.Decision.KeepMining)

        val hot = healthy.copy(thermalStatus = 4)
        val hotDecision = Guards.evaluate(hot, config)
        assertTrue(hotDecision is Guards.Decision.Pause)

        val warm = healthy.copy(thermalStatus = 2)
        assertTrue(Guards.evaluate(warm, config) is Guards.Decision.Throttle)

        val lowBattery = healthy.copy(batteryPct = 10)
        assertTrue(Guards.evaluate(lowBattery, config) is Guards.Decision.Pause)

        val saver = healthy.copy(batterySaver = true)
        assertTrue(Guards.evaluate(saver, config) is Guards.Decision.Pause)

        val chargeOnly = config.copy(onlyWhenCharging = true)
        assertTrue(Guards.evaluate(healthy, chargeOnly) is Guards.Decision.Pause)
    }
}
