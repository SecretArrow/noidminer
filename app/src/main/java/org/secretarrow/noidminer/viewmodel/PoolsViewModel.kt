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

/**
 * Pool configuration + wallet binding.
 *
 * CRASH-SAFE CONTRACT — every public method wraps its work in runCatching
 * so a flaky DataStore / EncryptedSharedPreferences / network stack can
 * never bring down the Save button. Failures are surfaced to the UI via
 * [probe] (a human-readable string) rather than propagated as exceptions.
 *
 * The previous implementation called StratumClient without `start()` —
 * probe hung for 8s then said "no response". The new probe starts the
 * client, awaits the first line, and stops cleanly even on timeout.
 */
class PoolsViewModel : SettingsBackedViewModel() {
    private val _walletValid = MutableStateFlow<Boolean?>(null)
    val walletValid: StateFlow<Boolean?> = _walletValid.asStateFlow()

    private val _probe = MutableStateFlow("")
    val probe: StateFlow<String> = _probe.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    @Volatile
    private var probeClient: StratumClient? = null

    fun setMode(mode: MineMode) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(mode = mode) } }
    }

    fun setPoolUrl(url: String) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(poolUrl = url) } }
    }

    fun setPoolUser(user: String) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(poolUser = user) } }
        val ctx = ServiceLocator.appContext ?: return@launch
        runCatching { ServiceLocator.vault(ctx).poolUser = user }
    }

    fun setWorker(name: String) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(workerName = name) } }
    }

    fun setSoloRpc(url: String) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(soloRpcUrl = url) } }
    }

    fun setWallet(context: Context, address: String) = viewModelScope.launch {
        val trimmed = address.trim()
        val valid = Bech32m.isValidO1(trimmed)
        _walletValid.value = if (trimmed.isBlank()) null else valid
        if (valid) {
            runCatching { ensureRepo(context).update { it.copy(walletAddress = trimmed) } }
            runCatching { ServiceLocator.vault(context).walletAddress = trimmed }
        }
    }

    /**
     * Save button handler — persists all four fields atomically and reports
     * a [SaveState] back to the UI. Never throws; surfaces errors as
     * [SaveState.Error] with a short human-readable message.
     */
    fun save(
        poolUrl: String,
        poolUser: String,
        worker: String,
        soloRpc: String,
    ) = viewModelScope.launch {
        _saveState.value = SaveState.Saving
        val result = runCatching {
            ensureRepo().update {
                it.copy(
                    poolUrl = poolUrl.trim(),
                    poolUser = poolUser.trim(),
                    workerName = worker.trim(),
                    soloRpcUrl = soloRpc.trim(),
                )
            }
            val ctx = ServiceLocator.appContext
            if (ctx != null && poolUser.isNotBlank()) {
                runCatching { ServiceLocator.vault(ctx).poolUser = poolUser.trim() }
            }
        }
        _saveState.value = result.fold(
            onSuccess = { SaveState.Saved },
            onFailure = { SaveState.Error(it.message ?: "unknown error") },
        )
    }

    /** Clears the save-state badge back to idle (called by the UI after display). */
    fun clearSaveState() {
        _saveState.value = SaveState.Idle
    }

    fun applyPreset(@Suppress("UNUSED_PARAMETER") context: Context, url: String) {
        setPoolUrl(url)
    }

    /**
     * Quick connectivity probe — starts the client, awaits the first stratum
     * line for up to 8 seconds, stops cleanly. Never throws; surfaces the
     * outcome (line preview / timeout / error) as a string in [probe].
     */
    fun probe(@Suppress("UNUSED_PARAMETER") context: Context) {
        runCatching { probeClient?.stop() }
        val url = settings.value.poolUrl
        val client = StratumClient(url)
        probeClient = client
        _probe.value = "connecting to $url …"
        viewModelScope.launch {
            // CRITICAL: client.start() must be called or no line will ever
            // arrive — the previous implementation forgot this and the probe
            // always timed out after 8s saying "no response".
            runCatching { client.start { /* lines flow through client.lines */ } }
            val line = withTimeoutOrNull(8000) {
                client.lines.first { true }
            }
            _probe.value = line?.raw?.take(180) ?: "no response in 8s — check URL / TLS"
            runCatching { client.stop() }
        }
    }

    override fun onCleared() {
        runCatching { probeClient?.stop() }
        super.onCleared()
    }

    /** UI-facing save state — Idle / Saving / Saved / Error(msg). */
    sealed interface SaveState {
        data object Idle : SaveState

        data object Saving : SaveState

        data object Saved : SaveState

        data class Error(
            val message: String,
        ) : SaveState
    }

    companion object {
        val presets = PoolPresets.ALL
    }
}
