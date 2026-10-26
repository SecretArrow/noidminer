package org.secretarrow.noidminer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for pool credentials and the mining wallet.
 * Kept separate from the plain settings so secrets never land in
 * cloud backups (see data_extraction_rules.xml).
 */
class Vault(
    context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey
            .Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "noid_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var walletAddress: String
        get() = prefs.getString(KEY_WALLET, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WALLET, value).apply()

    var poolUser: String
        get() = prefs.getString(KEY_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    var soloRpcToken: String
        get() = prefs.getString(KEY_RPC_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_RPC_TOKEN, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_WALLET = "wallet_address"
        const val KEY_USER = "pool_user"
        const val KEY_RPC_TOKEN = "solo_rpc_token"
    }
}
