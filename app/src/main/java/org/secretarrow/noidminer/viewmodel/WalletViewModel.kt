package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.secretarrow.noidminer.data.MinerSettings
import org.secretarrow.noidminer.data.MinerSettingsRepository
import org.secretarrow.noidminer.di.ServiceLocator

/**
 * Base for settings-backed ViewModels.
 *
 * CRASH FIX: the screens used to reach `repo` before any `attach()` call,
 * throwing UninitializedPropertyAccessException on every Save/Bind tap
 * ("all features crash"). The repo is now resolved lazily — from an explicit
 * context, else from the cached application context — so no method can ever
 * hit an uninitialized property.
 */
abstract class SettingsBackedViewModel : ViewModel() {
    private val repoLock = Any()
    private var repo: MinerSettingsRepository? = null
    private var collecting = false

    private val _settings = MutableStateFlow(MinerSettings())
    val settings: StateFlow<MinerSettings> = _settings.asStateFlow()

    /** Idempotent attach — safe to call from every composition. */
    fun attach(context: Context) {
        val r = ensureRepo(context)
        synchronized(this) {
            if (collecting) return
            collecting = true
        }
        viewModelScope.launch {
            r.settings.collect { _settings.value = it }
        }
    }

    protected fun ensureRepo(context: Context? = null): MinerSettingsRepository {
        repo?.let { return it }
        synchronized(repoLock) {
            repo?.let { return it }
            val ctx = (context ?: ServiceLocator.appContext)?.applicationContext
                ?: error("MinerSettingsRepository requires a context — attach() was never called")
            return MinerSettingsRepository(ctx).also {
                repo = it
                ServiceLocator.init(ctx)
            }
        }
    }
}

/** Wallet screen state — reuses the settings repository. */
class WalletViewModel : SettingsBackedViewModel() {
    private val _walletValid = MutableStateFlow<Boolean?>(null)
    val walletValid: StateFlow<Boolean?> = _walletValid.asStateFlow()

    fun setWallet(context: Context, address: String) {
        val trimmed = address.trim()
        if (trimmed.isBlank()) {
            _walletValid.value = null
            return
        }
        val valid = org.secretarrow.noidminer.util.Bech32m
            .isValidO1(trimmed)
        _walletValid.value = valid
        if (valid) {
            viewModelScope.launch {
                ensureRepo(context).update { it.copy(walletAddress = trimmed) }
                runCatching {
                    ServiceLocator.vault(context).walletAddress = trimmed
                }
            }
        }
    }

    fun totalShares(): Long = ServiceLocator
        .minerCore()
        .state.value.sharesAccepted
}
