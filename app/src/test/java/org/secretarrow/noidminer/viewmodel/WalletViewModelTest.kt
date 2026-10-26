package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.secretarrow.noidminer.data.MinerSettingsRepository
import org.secretarrow.noidminer.di.ServiceLocator

/**
 * Testing-setup skill (android/skills): unit tests for the wallet-binding
 * business logic. Uses the user's REAL payout address as the canonical
 * valid fixture — regression guard for the HRP "o" validator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class WalletViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context

    companion object {
        // Real production address (authorized=true on live pools).
        const val USER_ADDR = "o1z6nlslj0hu9c0qpnmgr4cfzm3kr2cn7k70svte6ph3uujg3nzmqswhmprn"
    }

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
    fun `setWallet accepts the real user address and persists it`() {
        val vm = WalletViewModel()
        vm.setWallet(context, USER_ADDR)

        assertEquals(true, awaitValue(probe = { vm.walletValid.value }) { it == true })

        val stored = awaitValue(
            probe = { runBlocking { ServiceLocator.settingsRepository(context).snapshot() } },
        ) { it.walletAddress == USER_ADDR }
        assertEquals(USER_ADDR, stored.walletAddress)
        assertEquals(USER_ADDR, ServiceLocator.vault(context).walletAddress)
    }

    @Test
    fun `setWallet with blank input resets validity to null`() {
        val vm = WalletViewModel()
        vm.setWallet(context, "   ")
        assertNull(vm.walletValid.value)
    }

    @Test
    fun `setWallet rejects garbage and does not persist`() {
        val vm = WalletViewModel()
        vm.setWallet(context, "bc1qnotanoidaddress")

        assertEquals(false, awaitValue(probe = { vm.walletValid.value }) { it == false })

        val stored = runBlocking {
            ServiceLocator.settingsRepository(context).snapshot()
        }
        assertFalse(stored.walletAddress == "bc1qnotanoidaddress")
    }

    @Test
    fun `totalShares reflects MinerCore accepted counter`() {
        val vm = WalletViewModel()
        val before = vm.totalShares()
        ServiceLocator.minerCore().recordShare(true)
        assertEquals(before + 1, vm.totalShares())
        ServiceLocator.minerCore().recordShare(false)
        assertEquals(before + 1, vm.totalShares())
    }
}
