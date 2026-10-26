package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.secretarrow.noidminer.data.MineMode
import org.secretarrow.noidminer.data.PoolPresets
import org.secretarrow.noidminer.data.stratum.StratumClient
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.util.Bech32m

/** Pool configuration + wallet binding. */
class PoolsViewModel : SettingsBackedViewModel() {
    private val _walletValid = MutableStateFlow<Boolean?>(null)
    val walletValid: StateFlow<Boolean?> = _walletValid.asStateFlow()

    private val _probe = MutableStateFlow("")
    val probe: StateFlow<String> = _probe.asStateFlow()

    private var probeClient: StratumClient? = null

    fun setMode(mode: MineMode) = viewModelScope.launch {
        ensureRepo().update { it.copy(mode = mode) }
    }

    fun setPoolUrl(url: String) = viewModelScope.launch {
        ensureRepo().update { it.copy(poolUrl = url) }
    }

    fun setPoolUser(user: String) = viewModelScope.launch {
        ensureRepo().update { it.copy(poolUser = user) }
        val ctx = ServiceLocator.appContext ?: return@launch
        runCatching { ServiceLocator.vault(ctx).poolUser = user }
    }

    fun setWorker(name: String) = viewModelScope.launch {
        ensureRepo().update { it.copy(workerName = name) }
    }

    fun setSoloRpc(url: String) = viewModelScope.launch {
        ensureRepo().update { it.copy(soloRpcUrl = url) }
    }

    fun setWallet(context: Context, address: String) = viewModelScope.launch {
        val trimmed = address.trim()
        val valid = Bech32m.isValidO1(trimmed)
        _walletValid.value = if (trimmed.isBlank()) null else valid
        if (valid) {
            ensureRepo(context).update { it.copy(walletAddress = trimmed) }
            runCatching { ServiceLocator.vault(context).walletAddress = trimmed }
        }
    }

    fun applyPreset(@Suppress("UNUSED_PARAMETER") context: Context, url: String) {
        setPoolUrl(url)
    }

    /** Quick connectivity probe: connects, reads the first stratum line. */
    fun probe(context: Context) {
        probeClient?.stop()
        val client = StratumClient(_settings.value.poolUrl)
        probeClient = client
        _probe.value = "connecting…"
        viewModelScope.launch {
            val line = withTimeoutOrNull(8000) {
                client.lines.first { true }
            }
            _probe.value = line?.raw?.take(180) ?: "no response in 8s — check URL / TLS"
            client.stop()
        }
    }

    override fun onCleared() {
        probeClient?.stop()
        super.onCleared()
    }

    companion object {
        val presets = PoolPresets.ALL
    }
}
