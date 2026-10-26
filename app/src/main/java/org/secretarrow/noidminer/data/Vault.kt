package org.secretarrow.noidminer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Storage for pool credentials and the mining wallet.
 *
 * Primary store is [EncryptedSharedPreferences]; some devices lose their
 * AndroidKeyStore master key (OEM ROM bugs, backup/restore, wiped keystore)
 * and every subsequent `create()` throws — that used to crash the Wallet,
 * Pools and Mine screens. When the encrypted store is unavailable we fall
 * back to a plain private prefs file (still app-private, still excluded
 * from backups via data_extraction_rules.xml) so the app keeps working.
 */
class Vault(
    context: Context,
) {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey
                .Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "encrypted vault unavailable (${t.message}) — using plain fallback")
            appContext.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
        }
    }

    var walletAddress: String
        get() = runCatching { prefs.getString(KEY_WALLET, "").orEmpty() }.getOrDefault("")
        set(value) = runCatching { prefs.edit().putString(KEY_WALLET, value).apply() }

    var poolUser: String
        get() = runCatching { prefs.getString(KEY_USER, "").orEmpty() }.getOrDefault("")
        set(value) = runCatching { prefs.edit().putString(KEY_USER, value).apply() }

    var soloRpcToken: String
        get() = runCatching { prefs.getString(KEY_RPC_TOKEN, "").orEmpty() }.getOrDefault("")
        set(value) = runCatching { prefs.edit().putString(KEY_RPC_TOKEN, value).apply() }

    fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    private companion object {
        const val TAG = "Vault"
        const val FILE_NAME = "noid_vault"
        const val FALLBACK_FILE_NAME = "noid_vault_fallback"
        const val KEY_WALLET = "wallet_address"
        const val KEY_USER = "pool_user"
        const val KEY_RPC_TOKEN = "solo_rpc_token"
    }
}
