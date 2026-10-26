package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.secretarrow.noidminer.data.BackendPref
import org.secretarrow.noidminer.di.ServiceLocator

/**
 * Testing-setup skill (android/skills): unit tests for the Settings screen
 * business logic — every setter must persist without crashing even when
 * the underlying storage misbehaves (runCatching contract).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SettingsViewModelTest {
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
    fun `suggestedThreads returns at least one`() {
        val vm = SettingsViewModel()
        assertTrue(vm.suggestedThreads() >= 1)
    }

    @Test
    fun `setThreads persists thread count`() {
        val vm = SettingsViewModel()
        vm.attach(context)
        vm.setThreads(7)
        awaitTrue {
            runBlocking { ServiceLocator.settingsRepository(context).snapshot() }.threads == 7
        }
    }

    @Test
    fun `setBackend persists backend preference`() {
        val vm = SettingsViewModel()
        vm.attach(context)
        vm.setBackend(BackendPref.CPU_ONLY)
        awaitTrue {
            runBlocking { ServiceLocator.settingsRepository(context).snapshot() }.backendPref ==
                BackendPref.CPU_ONLY
        }
    }

    @Test
    fun `guard thresholds persist`() {
        val vm = SettingsViewModel()
        vm.attach(context)
        vm.setPauseAtBattery(25)
        vm.setOnlyCharging(true)
        vm.setRespectSaver(false)
        vm.setKeepAwake(false)
        awaitTrue {
            val s = runBlocking { ServiceLocator.settingsRepository(context).snapshot() }
            s.pauseAtBatteryPct == 25 && s.onlyWhenCharging &&
                !s.respectBatterySaver && !s.keepCpuAwake
        }
    }

    @Test
    fun `setAutoBoot persists and toggles the BootReceiver component`() {
        val vm = SettingsViewModel()
        vm.attach(context)
        vm.setAutoBoot(context, true)
        awaitTrue {
            runBlocking { ServiceLocator.settingsRepository(context).snapshot() }.autoStartOnBoot
        }
        val pm = context.packageManager
        val state = pm.getComponentEnabledSetting(
            android.content.ComponentName(context, org.secretarrow.noidminer.pow.BootReceiver::class.java),
        )
        assertEquals(
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            state,
        )
    }
}
