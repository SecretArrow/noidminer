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

/** Wallet screen state — reuses the settings repository. */
class WalletViewModel : ViewModel() {

    private lateinit var repo: MinerSettingsRepository

    private val _settings = MutableStateFlow(MinerSettings())
    val settings: StateFlow<MinerSettings> = _settings.asStateFlow()

    private val _walletValid = MutableStateFlow<Boolean?>(null)
    val walletValid: StateFlow<Boolean?> = _walletValid.asStateFlow()

    fun attach(context: Context) {
        if (this::repo.isInitialized) return
        repo = ServiceLocator.settingsRepository(context)
        viewModelScope.launch {
            repo.settings.collect { _settings.value = it }
        }
    }

    fun setWallet(context: Context, address: String) {
        val trimmed = address.trim()
        if (trimmed.isBlank()) {
            _walletValid.value = null
            return
        }
        val valid = org.secretarrow.noidminer.util.Bech32m.isValidO1(trimmed)
        _walletValid.value = valid
        if (valid) {
            viewModelScope.launch {
                repo.update { it.copy(walletAddress = trimmed) }
                ServiceLocator.vault(context).walletAddress = trimmed
            }
        }
    }

    fun totalShares(): Long = ServiceLocator.minerCore().state.value.sharesAccepted
}
