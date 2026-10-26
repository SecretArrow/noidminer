package org.secretarrow.noidminer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.secretarrow.noidminer.data.MineMode
import org.secretarrow.noidminer.data.MinerSettingsRepository

/**
 * End-to-end on the emulator — REAL mining, no demo, no simulated miner.
 *
 * The app runs in POOL mode against a tiny in-process Stratum (SV1) server
 * and must complete the actual protocol pipeline:
 * mining.subscribe → mining.authorize → mining.notify → hashing (software
 * engine on x86_64) → share found → mining.submit received by the server.
 */
@RunWith(AndroidJUnit4::class)
class MinerE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launch_navigateAllTabs() {
        compose.waitForIdle()
        compose.onNodeWithText("Mine").assertIsDisplayed()

        listOf("Pools", "Wallet", "Device", "Log").forEach { tab ->
            compose.onNodeWithText(tab).performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Mine").performClick()
        compose.waitForIdle()
    }

    @Test
    fun poolMode_realStratumPipeline_submitsShare() {
        val server = LocalStratumServer()
        server.start()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            MinerSettingsRepository(context).update {
                it.copy(
                    mode = MineMode.POOL,
                    poolUrl = "stratum+tcp://127.0.0.1:${server.port}",
                    poolUser = "o1e2e.worker01",
                    // Neutralise guards so emulator battery/thermal quirks
                    // can never pause the mining run mid-test.
                    pauseAtBatteryPct = 0,
                    onlyWhenCharging = false,
                    respectBatterySaver = false,
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("Start mining").performClick()

        // The service must connect, receive the notify and submit a real
        // share produced by the hashing engine.
        val submitted = server.awaitSubmit(SHARE_TIMEOUT_MS)
        assertTrue("no mining.submit received from the app", submitted)
        val line = server.lastSubmit().orEmpty()
        assertTrue(
            "submit must reference the notified job id — got: $line",
            line.contains("job-e2e-1"),
        )

        compose.waitForIdle()
        compose.onNodeWithText("Stop", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        server.close()
    }

    companion object {
        private const val SHARE_TIMEOUT_MS = 120_000L
    }
}

/**
 * Minimal SV1 server standing in for a pool. Difficulty 1 + an all-FF
 * target make every digest a valid share so the slow software engine on the
 * emulator still exercises the full submission path deterministically.
 */
private class LocalStratumServer {
    private val server = ServerSocket(0)
    private val lastSubmitRef = AtomicReference<String?>(null)
    private val submitLatch = CountDownLatch(1)

    @Volatile
    private var stopped = false

    val port: Int = server.localPort

    fun start() {
        Thread {
            while (!stopped) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread { serve(socket) }.apply {
                    isDaemon = true
                    start()
                }
            }
        }.apply {
            name = "e2e-stratum-server"
            isDaemon = true
            start()
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = PrintWriter(socket.outputStream, true)
            while (!stopped) {
                val line = reader.readLine() ?: break
                when {
                    line.contains("mining.subscribe") -> {
                        writer.println(SUBSCRIBE_RESULT)
                        writer.println(SET_DIFFICULTY)
                        writer.println(notifyLine())
                    }
                    line.contains("mining.authorize") -> writer.println(AUTH_RESULT)
                    line.contains("mining.submit") -> {
                        lastSubmitRef.set(line)
                        submitLatch.countDown()
                        writer.println(SUBMIT_RESULT)
                    }
                    else -> Unit
                }
            }
        } catch (_: Throwable) {
            // idle timeout or shutdown — the client reconnects if it needs to
        } finally {
            runCatching { socket.close() }
        }
    }

    fun awaitSubmit(timeoutMs: Long): Boolean =
        submitLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun lastSubmit(): String? = lastSubmitRef.get()

    fun close() {
        stopped = true
        runCatching { server.close() }
    }

    private fun notifyLine(): String {
        // 256-hex pow_fields + 64-hex target, as expected by StratumCodec.
        val fields = (0 until 128).joinToString("") { "%02x".format(it) }
        val target = "f".repeat(64)
        return "{\"method\":\"mining.notify\",\"params\":[\"job-e2e-1\",\"$fields\",\"$target\",\"true\"]}"
    }

    private companion object {
        const val SUBSCRIBE_RESULT =
            "{\"id\":1,\"result\":[[[\"mining.set_difficulty\",\"mining.notify\"]],\"01020304\",4],\"error\":null}"
        const val SET_DIFFICULTY = "{\"method\":\"mining.set_difficulty\",\"params\":[1]}"
        const val AUTH_RESULT = "{\"id\":2,\"result\":true,\"error\":null}"
        const val SUBMIT_RESULT = "{\"id\":3,\"result\":true,\"error\":null}"
    }
}
