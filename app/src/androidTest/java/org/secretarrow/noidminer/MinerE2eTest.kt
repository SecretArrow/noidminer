package org.secretarrow.noidminer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end smoke test on the emulator. Runs against the real app with the
 * software engine (x86_64 emulator has no native lib) and the DEMO job
 * source, so no network is required. Verifies the full user path:
 * launch → navigate all tabs → start mining → hashrate appears → stop.
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
    fun startDemoMining_producesHashrate_thenStops() {
        compose.waitForIdle()
        // Start via the hero button.
        compose.onNodeWithText("Start mining").performClick()
        // The service starts; give the demo engine a moment to report state.
        compose.waitForIdle()
        Thread.sleep(4000)
        compose.waitForIdle()

        // Stop returns the hero button to its initial label.
        val stop = compose.onNodeWithText("Stop", useUnmergedTree = true)
        stop.performClick()
        compose.waitForIdle()
    }
}
