package org.secretarrow.noidminer.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.secretarrow.noidminer.di.ServiceLocator

/**
 * Testing-setup skill (android/skills): unit tests for the Mine screen
 * business logic — pause/resume must drive the shared MinerCore state
 * that both the UI and the foreground service observe.
 */
class MineViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() {
        ServiceLocator.resetForTest()
    }

    @Test
    fun `pause and resume drive the shared core state`() {
        val core = ServiceLocator.minerCore()
        val vm = MineViewModel()

        vm.pause("user")
        assertTrue(core.paused.get())

        vm.resume()
        assertFalse(core.paused.get())
    }

    @Test
    fun `state collector mirrors core state`() {
        val core = ServiceLocator.minerCore()
        val vm = MineViewModel()

        core.pause("thermal")
        awaitTrue { vm.state.value.paused }
        awaitTrue { vm.state.value.pausedReason == "thermal" }

        core.resume()
        awaitTrue { !vm.state.value.paused }
        vm.pause("user")
        awaitTrue { vm.state.value.paused && vm.state.value.pausedReason == "user" }
    }

    @Test
    fun `pause-resume roundtrip is idempotent`() {
        val core = ServiceLocator.minerCore()
        val vm = MineViewModel()
        repeat(3) {
            vm.pause("user")
            vm.resume()
        }
        assertFalse(core.paused.get())
    }
}
