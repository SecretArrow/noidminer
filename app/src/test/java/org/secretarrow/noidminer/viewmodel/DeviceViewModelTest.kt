package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.secretarrow.noidminer.di.ServiceLocator

/**
 * Testing-setup skill (android/skills): unit tests for the Device screen
 * business logic. The GPU probe must degrade gracefully when the native
 * core is unavailable (JVM/unsupported ABI) — never crash, never hang.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DeviceViewModelTest {
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
    fun `attach populates hardware inventory synchronously`() {
        val vm = DeviceViewModel()
        vm.attach(context)
        val hw = vm.hardware.value
        assertTrue(hw.coreCount >= 1 || hw.socName.isNotBlank() || hw.abi.isNotBlank())
    }

    @Test
    fun `gpu probe settles to a terminal status without crashing`() {
        val vm = DeviceViewModel()
        vm.attach(context)

        // In the JVM the native core cannot load — the probe must resolve
        // to a human-readable fallback status rather than "probing…".
        val status = awaitValue(probe = { vm.gpu.value.status }) { it != "probing…" }
        assertTrue(
            "unexpected terminal status: $status",
            status.contains("software") || status.contains("fallback") || status == "aktif",
        )
    }

    @Test
    fun `benchmark produces a result line`() {
        val vm = DeviceViewModel()
        vm.attach(context)
        vm.runBenchmark()
        val result = awaitValue(timeoutMs = 30_000, probe = { vm.benchmark.value.result }) {
            it != null
        }
        assertEquals(
            true,
            result!!.contains("H/s") ||
                result.contains("kH/s") ||
                result.contains("MH/s") ||
                result.contains("GH/s")
        )
        assertEquals(false, vm.benchmark.value.running)
    }
}
