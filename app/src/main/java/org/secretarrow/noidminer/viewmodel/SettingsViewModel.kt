package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.secretarrow.noidminer.data.BackendPref
import org.secretarrow.noidminer.data.MinerSettings
import org.secretarrow.noidminer.data.MinerSettingsRepository
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.BootReceiver

/** Settings screen state + persistence. */
class SettingsViewModel : ViewModel() {
    private lateinit var repo: MinerSettingsRepository

    private val _settings = MutableStateFlow(MinerSettings())
    val settings: StateFlow<MinerSettings> = _settings.asStateFlow()

    fun attach(context: Context) {
        if (this::repo.isInitialized) return
        repo = ServiceLocator.settingsRepository(context)
        viewModelScope.launch {
            repo.settings.collect { _settings.value = it }
        }
    }

    fun suggestedThreads(): Int = ServiceLocator.hardwareInfo().suggestedThreads()

    fun setThreads(count: Int) = viewModelScope.launch { repo.update { it.copy(threads = count) } }

    fun setBackend(pref: BackendPref) = viewModelScope.launch { repo.update { it.copy(backendPref = pref) } }

    fun setPauseAtBattery(pct: Int) =
        viewModelScope.launch { repo.update { it.copy(pauseAtBatteryPct = pct) } }

    fun setOnlyCharging(value: Boolean) =
        viewModelScope.launch { repo.update { it.copy(onlyWhenCharging = value) } }

    fun setRespectSaver(value: Boolean) =
        viewModelScope.launch { repo.update { it.copy(respectBatterySaver = value) } }

    fun setKeepAwake(value: Boolean) =
        viewModelScope.launch { repo.update { it.copy(keepCpuAwake = value) } }

    fun setAutoBoot(context: Context, value: Boolean) = viewModelScope.launch {
        repo.update { it.copy(autoStartOnBoot = value) }
        BootReceiver.setEnabled(context, value)
    }
}
