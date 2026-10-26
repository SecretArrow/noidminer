package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.secretarrow.noidminer.data.BackendPref
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.BootReceiver

/**
 * Settings screen state + persistence.
 *
 * CRASH-SAFE: every setter wraps its persistence work in `runCatching`
 * so a flaky DataStore can never bring down the Settings screen. Toggles
 * visually flip back if the write fails (the next collect cycle restores
 * the stored value).
 */
class SettingsViewModel : SettingsBackedViewModel() {
    fun suggestedThreads(): Int = runCatching {
        ServiceLocator.hardwareInfo().suggestedThreads()
    }.getOrDefault(2)

    fun setThreads(count: Int) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(threads = count) } }
    }

    fun setBackend(pref: BackendPref) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(backendPref = pref) } }
    }

    fun setPauseAtBattery(pct: Int) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(pauseAtBatteryPct = pct) } }
    }

    fun setOnlyCharging(value: Boolean) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(onlyWhenCharging = value) } }
    }

    fun setRespectSaver(value: Boolean) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(respectBatterySaver = value) } }
    }

    fun setKeepAwake(value: Boolean) = viewModelScope.launch {
        runCatching { ensureRepo().update { it.copy(keepCpuAwake = value) } }
    }

    fun setAutoBoot(context: Context, value: Boolean) = viewModelScope.launch {
        runCatching { ensureRepo(context).update { it.copy(autoStartOnBoot = value) } }
        runCatching { BootReceiver.setEnabled(context, value) }
    }
}
