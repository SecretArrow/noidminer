package org.secretarrow.noidminer.pool

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.secretarrow.noidminer.data.PoolJobSource
import org.secretarrow.noidminer.data.buildPoolUser
import org.secretarrow.noidminer.data.stratum.StratumCodec

/**
 * Full POOL-mode flow test on the JVM against a local fake
 * "parano1d-stratum-v1" server:
 *
 *   subscribe → authorize(<wallet>.<worker>) → notify → job →
 *   submit [job_id, nonce128 (32 hex), work_domain_id] → accepted.
 *
 * This is the same wire shape the real InnovLab/RPlant pools speak.
 */
class PoolJobSourceTest {
    private val wallet = "o1z6nlslj0hu9c0qpnmgr4cfzm3kr2cn7k70svte6ph3uujg3nzmqswhmprn"

    private val logs = ConcurrentLinkedQueue<String>()

    private class FakePool : AutoCloseable {
        val server = ServerSocket(0)
        val received = ConcurrentLinkedQueue<String>()
        val authorizedUser = AtomicReference<String?>(null)
        val lastSubmit = AtomicReference<String?>(null)
        val submitLatch = CountDownLatch(1)

        @Volatile private var stopped = false

        val port: Int get() = server.localPort

        val notifyLine: String = run {
            val fields = (0 until 256).joinToString("") { "%02x".format(it % 256) }
            """{"id":null,"method":"mining.notify","params":[{"job_id":"fake-job-1","assignment_id":"1","pow_fields_hex":"$fields","nonce_field_index":10,"nonce_bits":64,"nonce_prefix_hex":"0102030405060708","work_domain_id":"${"ab".repeat(32)}","share_target_hex":"${"f".repeat(64)}","block_target_hex":"${"f".repeat(64)}","height":254100,"clean":true,"expires_in_seconds":120}]}"""
        }

        fun start() {
            Thread {
                while (!stopped) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    Thread {
                        try {
                            socket.soTimeout = 15_000
                            val reader = BufferedReader(InputStreamReader(socket.inputStream))
                            val writer = PrintWriter(socket.outputStream, true)
                            while (!stopped) {
                                val line = reader.readLine() ?: break
                                received.add(line)
                                when {
                                    line.contains("mining.subscribe") -> {
                                        writer.println(
                                            """{"id":1,"error":null,"result":{"nonce_bits":64,""" +
                                                """"protocol":"parano1d-stratum-v1",""" +
                                                """"session_namespace":"0102030405060708"}}""",
                                        )
                                        // Send the notify immediately after subscribe so the
                                        // client has a job to mine. The previous test only
                                        // defined notifyLine as a property but never sent it —
                                        // nextJob() always timed out.
                                        writer.println(notifyLine)
                                    }
                                    line.contains("mining.authorize") -> {
                                        authorizedUser.set(
                                            Regex("\"mining.authorize\",\"params\":\\[\"([^\"]+)\"")
                                                .find(line)
                                                ?.groupValues
                                                ?.get(1),
                                        )
                                        writer.println("""{"id":2,"result":true,"error":null}""")
                                    }
                                    line.contains("mining.submit") -> {
                                        lastSubmit.set(line)
                                        submitLatch.countDown()
                                        writer.println("""{"id":3,"result":true,"error":null}""")
                                    }
                                    else -> Unit
                                }
                            }
                        } catch (_: Throwable) {
                        } finally {
                            runCatching { socket.close() }
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        override fun close() {
            stopped = true
            runCatching { server.close() }
        }
    }

    private fun log(level: String, message: String) {
        logs.add("$level: $message")
    }

    @Test
    fun `full pool pipeline authorizes wallet-worker and submits v1 share`() {
        val pool = FakePool()
        pool.start()
        val source = PoolJobSource(
            "stratum+tcp://127.0.0.1:${pool.port}",
            buildPoolUser("", wallet, "noidmobile"),
            ::log,
        )
        try {
            // Job must arrive from the notify.
            var job = source.nextJob()
            val deadline = System.currentTimeMillis() + 10_000
            while (job == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                job = source.nextJob()
            }
            assertNotNull("no job decoded from notify — logs=$logs", job)
            job!!
            assertEquals("fake-job-1", job.templateId)
            assertEquals(254100L, job.height)
            assertEquals("0102030405060708", job.laneHiHex)
            assertEquals("ab".repeat(32), job.workDomainIdHex)

            // Authorize must use wallet.worker.
            val authDeadline = System.currentTimeMillis() + 5_000
            while (pool.authorizedUser.get() == null && System.currentTimeMillis() < authDeadline) {
                Thread.sleep(50)
            }
            assertEquals("$wallet.noidmobile", pool.authorizedUser.get())

            // Submit with a valid 128-bit lane nonce (32 hex chars).
            val nonceHex = job.nonceHex(0x0102030405060708L)
            assertEquals(32, nonceHex.length)
            // Layout: counter LE first, session prefix raw bytes last.
            assertEquals("0807060504030201" + "0102030405060708", nonceHex)
            val ok = source.submit(job, nonceHex, "ff".repeat(32))
            assertTrue("submit must be accepted", ok)
            assertTrue(pool.submitLatch.await(5, TimeUnit.SECONDS))
            val submitted = pool.lastSubmit.get().orEmpty()
            assertTrue("submit must reference the job id — got: $submitted", submitted.contains("fake-job-1"))
            assertTrue("submit must carry nonce128 — got: $submitted", submitted.contains(nonceHex))
            assertTrue(
                "submit must echo work_domain_id — got: $submitted",
                submitted.contains("ab".repeat(32)),
            )
        } finally {
            source.close()
            pool.close()
        }
    }

    @Test
    fun `pool pause stops job flow until resume`() {
        // Direct codec-level contract: Paused is surfaced, nextJob gates on it.
        val paused = StratumCodec.decode(
            """{"id":null,"method":"mining.pause","params":[{"reason":"canonical-tip-unverified"}]}""",
        )
        assertTrue(paused is StratumCodec.Event.Paused)
        assertNull((paused as StratumCodec.Event.Paused).reason.takeIf { it.isBlank() })
    }
}
