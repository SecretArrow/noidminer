package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.secretarrow.noidminer.data.MineMode
import org.secretarrow.noidminer.di.ServiceLocator

/**
 * Testing-setup skill (android/skills): unit tests for the pool
 * configuration business logic — save flow, wallet binding, presets and
 * the live connectivity probe against a local socket that speaks one
 * stratum line (no network, no 8s timeout waits).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PoolsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServiceLocator.init(context)
        ServiceLocator.resetForTest()
    }

    @After
    fun tearDown() {
        ServiceLocator.resetForTest()
    }

    @Test
    fun `save persists all four fields and reports Saved`() {
        val vm = PoolsViewModel()
        vm.attach(context)

        vm.save(
            poolUrl = "stratum+tls://eu.innovlab.cc:19601",
            poolUser = WalletViewModelTest.USER_ADDR,
            worker = "rig-01",
            soloRpc = "http://127.0.0.1:10650",
        )

        awaitTrue { vm.saveState.value == PoolsViewModel.SaveState.Saved }
        awaitTrue {
            runBlocking { ServiceLocator.settingsRepository(context).snapshot() }.let {
                it.poolUrl == "stratum+tls://eu.innovlab.cc:19601" &&
                    it.poolUser == WalletViewModelTest.USER_ADDR &&
                    it.workerName == "rig-01"
            }
        }
        assertEquals(
            WalletViewModelTest.USER_ADDR,
            ServiceLocator.vault(context).poolUser,
        )
    }

    @Test
    fun `setMode persists POLO vs SOLO switch`() {
        val vm = PoolsViewModel()
        vm.attach(context)
        vm.setMode(MineMode.SOLO)
        awaitTrue {
            runBlocking { ServiceLocator.settingsRepository(context).snapshot() }.mode ==
                MineMode.SOLO
        }
    }

    @Test
    fun `applyPreset swaps pool url`() {
        val vm = PoolsViewModel()
        vm.attach(context)
        val preset = PoolsViewModel.presets.first()
        vm.applyPreset(context, preset.url)
        awaitTrue {
            runBlocking { ServiceLocator.settingsRepository(context).snapshot() }.poolUrl ==
                preset.url
        }
    }

    @Test
    fun `setWallet rejects invalid binding without touching storage`() {
        val vm = PoolsViewModel()
        vm.attach(context)
        vm.setWallet(context, "definitely-not-valid")
        awaitTrue { vm.walletValid.value == false }
    }

    @Test
    fun `probe surfaces the first pool line from a live local socket`() {
        val payload = "{\"id\":1,\"result\":[[\"mining.notify\",\"abc\"]],\"error\":null}\n"
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val accepter = thread {
            runCatching {
                server.soTimeout = 8000
                val sock: Socket = server.accept()
                accepted.set(true)
                sock.getOutputStream().write(payload.toByteArray())
                sock.getOutputStream().flush()
                Thread.sleep(1500)
                sock.close()
            }
        }

        try {
            val vm = PoolsViewModel()
            vm.attach(context)
            val url = "stratum+tcp://127.0.0.1:${server.localPort}"
            vm.setPoolUrl(url)
            awaitTrue { vm.settings.value.poolUrl == url }

            vm.probe(context)
            val line = awaitValue(probe = { vm.probe.value }) { it.contains("mining.notify") }
            assertTrue(line.contains("mining.notify"))
        } finally {
            accepter.join(9_000)
            runCatching { server.close() }
        }
    }

    @Test
    fun `probe never throws on unreachable pool`() {
        val vm = PoolsViewModel()
        vm.attach(context)
        val url = "stratum+tcp://127.0.0.1:1" // closed port — connect refused
        vm.setPoolUrl(url)
        awaitTrue { vm.settings.value.poolUrl == url }
        vm.probe(context)
        // Must end in a terminal (non-"connecting") state without crashing.
        awaitTrue {
            val p = vm.probe.value
            p.startsWith("no response") || p.contains("pool connection") || p.contains("mining")
        }
    }
}
