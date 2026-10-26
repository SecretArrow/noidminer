package org.secretarrow.noidminer.pow

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-feature regression tests for the lane-based PoW core:
 * nonce-lane layout, software engine lane semantics, and MinerCore
 * start/pause/stop lifecycle with a scripted JobSource.
 *
 * The lifecycle tests use [FakeEngine] — a deterministic instant engine —
 * because the real software Poseidon2b needs ~100ms/hash on a shared CI
 * vCPU, which would make a 256-hash batch exceed the whole test window.
 * Engine correctness is covered by the golden-vector suites.
 */
class MinerCoreLaneTest {
    private val fields512 = (0 until 256).joinToString("") { "%02x".format(it % 256) }

    // ------------------------------------------------- MiningJob nonce lane

    @Test
    fun `pool job nonce is counter-le followed by raw session prefix`() {
        val job = MiningJob(
            templateId = "j1",
            powFieldsHex = fields512,
            targetLeHex = "f".repeat(64),
            height = 1,
            expiresAtMs = Long.MAX_VALUE,
            laneHiHex = "0102030405060708",
            workDomainIdHex = "ab".repeat(32),
        )
        assertEquals("0807060504030201" + "0102030405060708", job.nonceHex(0x0102030405060708L))
        assertEquals(32, job.nonceHex(0).length)
        assertEquals("00000000000000000102030405060708", job.nonceHex(0))
    }

    @Test
    fun `solo job nonce is a free 128-bit lane`() {
        val job = MiningJob(
            templateId = "j2",
            powFieldsHex = fields512,
            targetLeHex = "f".repeat(64),
            height = 1,
            expiresAtMs = Long.MAX_VALUE,
        )
        assertEquals("08070605040302010000000000000000", job.nonceHex(0x0102030405060708L))
    }

    @Test
    fun `laneHiValue decodes session prefix little-endian`() {
        val job = MiningJob(
            templateId = "j",
            powFieldsHex = fields512,
            targetLeHex = "f".repeat(64),
            height = 1,
            expiresAtMs = Long.MAX_VALUE,
            laneHiHex = "0102030405060708",
        )
        // Bytes 01 02 03 04 05 06 07 08 as LE u64 = 0x0807060504030201.
        assertEquals(0x0807060504030201UL.toLong(), job.laneHiValue())
        assertEquals(0L, job.copy(laneHiHex = null).laneHiValue())
    }

    // -------------------------------------------------- software engine lanes

    @Test
    fun `software engine lane-hi zero matches legacy digest`() {
        val engine = Poseidon2bSoftware.INSTANCE
        val legacy = engine.powHash(fields512, 777L)!!
        val lane = engine.powHashLane(fields512, 777L, 0L)!!
        assertArrayEquals(legacy, lane)
    }

    @Test
    fun `software engine session prefix changes the digest`() {
        val engine = Poseidon2bSoftware.INSTANCE
        val zeroHi = engine.powHashLane(fields512, 42L, 0L)!!
        val poolHi = engine.powHashLane(fields512, 42L, 0x0807060504030201UL.toLong())!!
        assertNotEquals(zeroHi.toList(), poolHi.toList())
        // Deterministic across calls.
        assertArrayEquals(poolHi, engine.powHashLane(fields512, 42L, 0x0807060504030201UL.toLong())!!)
    }

    @Test
    fun `software engine batch of three lanes matches per-lane hashes`() {
        // Small batch (3, not 256) — stays fast even on a slow CI vCPU.
        val engine = SoftwareEngine()
        val laneHi = 0x0807060504030201UL.toLong()
        val start = 9000L
        val out = engine.batchHashLanes(fields512, laneHi, start, 3)!!
        assertEquals(96, out.size)
        for (i in 0 until 3) {
            val single = Poseidon2bSoftware.INSTANCE.powHashLane(fields512, start + i, laneHi)!!
            assertArrayEquals(single, out.copyOfRange(i * 32, (i + 1) * 32))
        }
    }

    // ------------------------------------------------------- MinerCore lifecycle

    /**
     * Deterministic instant engine: every digest is the little-endian lane
     * bytes repeated twice, and every digest passes the target check.
     */
    private class FakeEngine : HashEngine {
        override val name: String = "fake · instant"

        override fun batchHashLanes(
            powFieldsHex: String,
            laneHi: Long,
            laneLoStart: Long,
            count: Int,
        ): ByteArray? {
            val out = ByteArray(count * 32)
            for (i in 0 until count) {
                var lo = laneLoStart + i
                var hi = laneHi
                val base = i * 32
                for (b in 0 until 8) {
                    out[base + b] = lo.toByte()
                    lo = lo ushr 8
                    out[base + 8 + b] = hi.toByte()
                    hi = hi ushr 8
                }
                // Repeat the lane bytes so digests are 32 bytes of pattern.
                for (b in 0 until 16) {
                    out[base + 16 + b] = out[base + b]
                }
            }
            return out
        }

        override fun checkTarget(hash: ByteArray, targetLeHex: String): Boolean = true

        override fun validateAddress(address: String): Boolean = true
    }

    private class ScriptedSource : JobSource {
        val submitted = AtomicInteger(0)
        var nextAnswer: MiningJob? = null
        var acceptShares = true

        override fun nextJob(): MiningJob? = nextAnswer

        override fun submit(job: MiningJob, nonceLeHex: String, hashHex: String): Boolean {
            submitted.incrementAndGet()
            return acceptShares
        }

        override fun close() = Unit
    }

    private fun coreWithFakeEngine(): MinerCore =
        MinerCore { EngineSelector.Result(FakeEngine(), false, null, false) }

    @Test
    fun `miner core start stop lifecycle`() {
        val job = MiningJob(
            templateId = "life-1",
            powFieldsHex = fields512,
            targetLeHex = "f".repeat(64), // FakeEngine accepts every hash
            height = 10,
            expiresAtMs = Long.MAX_VALUE,
            laneHiHex = "0102030405060708",
        )
        val source = ScriptedSource().apply { nextAnswer = job }
        val core = coreWithFakeEngine()
        core.start(source, 1)
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (core.state.value.sharesAccepted == 0L && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue("no share accepted in 15s", core.state.value.sharesAccepted > 0)
            assertTrue(core.state.value.running)
            assertEquals("fake · instant", core.state.value.backend)
            assertTrue(source.submitted.get() > 0)
        } finally {
            core.stop()
        }
        assertTrue(!core.running.get())
        assertEquals(false, core.state.value.running)
    }

    @Test
    fun `miner core pause holds workers and resume continues`() {
        val job = MiningJob(
            templateId = "life-2",
            powFieldsHex = fields512,
            targetLeHex = "f".repeat(64),
            height = 11,
            expiresAtMs = Long.MAX_VALUE,
        )
        val source = ScriptedSource().apply { nextAnswer = job }
        val core = coreWithFakeEngine()
        core.start(source, 1)
        try {
            // Let the worker find at least one share, then pause.
            val deadline = System.currentTimeMillis() + 15_000
            while (core.state.value.sharesAccepted == 0L && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue(core.state.value.sharesAccepted > 0)
            core.pause("thermal test")
            Thread.sleep(400)
            assertTrue(core.paused.get())
            assertEquals("thermal test", core.state.value.pausedReason)
            val sharesBefore = core.state.value.sharesAccepted + core.state.value.sharesRejected
            Thread.sleep(400)
            val sharesAfter = core.state.value.sharesAccepted + core.state.value.sharesRejected
            assertEquals("workers must not hash while paused", sharesBefore, sharesAfter)
            core.resume()
            assertTrue(!core.paused.get())
        } finally {
            core.stop()
        }
    }

    @Test
    fun `job expiry forces refresh from source`() {
        val expired = MiningJob(
            templateId = "old",
            powFieldsHex = fields512,
            targetLeHex = "f".repeat(64),
            height = 1,
            expiresAtMs = System.currentTimeMillis() - 1,
        )
        val fresh = expired.copy(templateId = "new", expiresAtMs = Long.MAX_VALUE)
        val source = ScriptedSource()
        source.nextAnswer = fresh
        val core = coreWithFakeEngine()
        core.currentJob = expired
        core.start(source, 1)
        try {
            // start() clears currentJob; workers must mine the fresh job.
            val deadline = System.currentTimeMillis() + 15_000
            while (core.state.value.sharesAccepted == 0L &&
                core.state.value.sharesRejected == 0L &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }
            assertTrue(
                "core must mine the fresh job after expiry",
                core.state.value.sharesAccepted + core.state.value.sharesRejected > 0,
            )
        } finally {
            core.stop()
        }
    }
}
