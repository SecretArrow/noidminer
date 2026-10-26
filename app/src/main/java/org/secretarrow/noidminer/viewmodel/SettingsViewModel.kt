package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.secretarrow.noidminer.data.BackendPref
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.BootReceiver

/** Settings screen state + persistence. */
class SettingsViewModel : SettingsBackedViewModel() {
    fun suggestedThreads(): Int = ServiceLocator.hardwareInfo().suggestedThreads()

    fun setThreads(count: Int) = viewModelScope.launch {
        ensureRepo().update { it.copy(threads = count) }
    }

    fun setBackend(pref: BackendPref) = viewModelScope.launch {
        ensureRepo().update { it.copy(backendPref = pref) }
    }

    fun setPauseAtBattery(pct: Int) =
        viewModelScope.launch { ensureRepo().update { it.copy(pauseAtBatteryPct = pct) } }

    fun setOnlyCharging(value: Boolean) =
        viewModelScope.launch { ensureRepo().update { it.copy(onlyWhenCharging = value) } }

    fun setRespectSaver(value: Boolean) =
        viewModelScope.launch { ensureRepo().update { it.copy(respectBatterySaver = value) } }

    fun setKeepAwake(value: Boolean) =
        viewModelScope.launch { ensureRepo().update { it.copy(keepCpuAwake = value) } }

    fun setAutoBoot(context: Context, value: Boolean) = viewModelScope.launch {
        ensureRepo(context).update { it.copy(autoStartOnBoot = value) }
        runCatching { BootReceiver.setEnabled(context, value) }
    }
}
