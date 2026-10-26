package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.secretarrow.noidminer.data.MinerSettings
import org.secretarrow.noidminer.data.MinerSettingsRepository
import org.secretarrow.noidminer.data.MineMode
import org.secretarrow.noidminer.data.PoolPresets
import org.secretarrow.noidminer.data.stratum.StratumClient
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.util.Bech32m

/** Pool configuration + wallet binding. */
class PoolsViewModel : ViewModel() {

    private lateinit var repo: MinerSettingsRepository

    private val _settings = MutableStateFlow(MinerSettings())
    val settings: StateFlow<MinerSettings> = _settings.asStateFlow()

    private val _walletValid = MutableStateFlow<Boolean?>(null)
    val walletValid: StateFlow<Boolean?> = _walletValid.asStateFlow()

    private val _probe = MutableStateFlow("")
    val probe: StateFlow<String> = _probe.asStateFlow()

    private var probeClient: StratumClient? = null

    fun attach(context: Context) {
        if (this::repo.isInitialized) return
        repo = ServiceLocator.settingsRepository(context)
        viewModelScope.launch {
            repo.settings.collect { _settings.value = it }
        }
    }

    fun setMode(mode: MineMode) = viewModelScope.launch { repo.update { it.copy(mode = mode) } }

    fun setPoolUrl(url: String) = viewModelScope.launch { repo.update { it.copy(poolUrl = url) } }

    fun setPoolUser(user: String) = viewModelScope.launch {
        repo.update { it.copy(poolUser = user) }
        ServiceLocator.vault(probeContext()).poolUser = user
    }

    fun setWorker(name: String) = viewModelScope.launch { repo.update { it.copy(workerName = name) } }

    fun setSoloRpc(url: String) = viewModelScope.launch { repo.update { it.copy(soloRpcUrl = url) } }

    fun setWallet(context: Context, address: String) = viewModelScope.launch {
        val valid = Bech32m.isValidO1(address.trim())
        _walletValid.value = if (address.isBlank()) null else valid
        if (valid) {
            repo.update { it.copy(walletAddress = address.trim()) }
            ServiceLocator.vault(context).walletAddress = address.trim()
        }
    }

    fun applyPreset(context: Context, url: String) {
        setPoolUrl(url)
    }

    /** Quick connectivity probe: connects, reads the first stratum line. */
    fun probe(context: Context) {
        probeClient?.stop()
        val client = StratumClient(_settings.value.poolUrl)
        probeClient = client
        _probe.value = "connecting…"
        viewModelScope.launch {
            val line = kotlinx.coroutines.withTimeoutOrNull(8000) {
                client.lines.first { true }
            }
            _probe.value = line?.raw?.take(180) ?: "no response in 8s — check URL / TLS"
            client.stop()
        }
    }

    private fun probeContext(): Context =
        ServiceLocator.appContext ?: throw IllegalStateException("attach() first")

    override fun onCleared() {
        probeClient?.stop()
        super.onCleared()
    }

    companion object {
        val presets = PoolPresets.ALL
    }
}
