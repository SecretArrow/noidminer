package org.secretarrow.noidminer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.secretarrow.noidminer.di.ServiceLocator

/**
 * Vault must NEVER crash, even when EncryptedSharedPreferences is unusable
 * (corrupted AndroidKeyStore on OEM ROMs, backup/restore edge cases). The
 * fallback path swaps in a plain private prefs file — the wallet still
 * round-trips and the app keeps working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class VaultFallbackTest {
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
    fun `vault roundtrips wallet and pool user`() {
        val vault = ServiceLocator.vault(context)
        vault.walletAddress = "o1z6nlslj0hu9c0qpnmgr4cfzm3kr2cn7k70svte6ph3uujg3nzmqswhmprn"
        vault.poolUser = "worker-1"
        assertEquals(
            "o1z6nlslj0hu9c0qpnmgr4cfzm3kr2cn7k70svte6ph3uujg3nzmqswhmprn",
            ServiceLocator.vault(context).walletAddress,
        )
        assertEquals("worker-1", ServiceLocator.vault(context).poolUser)
        vault.clear()
        assertEquals("", ServiceLocator.vault(context).walletAddress)
    }

    @Test
    fun `vault singleton is cached`() {
        assertTrue(ServiceLocator.vault(context) === ServiceLocator.vault(context))
    }

    @Test
    fun `vault get/set never throws on the fallback store`() {
        // On Robolectric the encrypted store may work or fall back — either
        // way every access must complete without throwing.
        val vault = ServiceLocator.vault(context)
        vault.soloRpcToken = "token-123"
        assertEquals("token-123", vault.soloRpcToken)
        vault.clear()
        assertEquals("", vault.soloRpcToken)
    }
}
