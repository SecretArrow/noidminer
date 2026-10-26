package org.secretarrow.noidminer.viewmodel

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.secretarrow.noidminer.data.BackendPref
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.EngineSelector
import org.secretarrow.noidminer.pow.Guards
import org.secretarrow.noidminer.pow.MinerState
import org.secretarrow.noidminer.pow.MinerService

/** Drives the Mine screen from the singleton [MinerCore] state. */
class MineViewModel : ViewModel() {
    private val core = ServiceLocator.minerCore()

    private val _state = MutableStateFlow(MinerState())
    val state: StateFlow<MinerState> = _state.asStateFlow()

    private val _thermal = MutableStateFlow("none")
    val thermal: StateFlow<String> = _thermal.asStateFlow()

    /**
     * Available hashing backends on this device — probed lazily on first
     * access. Used by the start-time backend picker dialog so the user can
     * see what's available (CPU cores, GPU device name, self-check status)
     * before tapping Start.
     */
    private val _backendProbe = MutableStateFlow<EngineSelector.BackendProbe?>(null)
    val backendProbe: StateFlow<EngineSelector.BackendProbe?> = _backendProbe.asStateFlow()

    private var appContext: Context? = null

    init {
        viewModelScope.launch {
            core.state.collect { _state.value = it }
        }
        // ANR FIX (anr-audit-and-fix skill): thermal polling reads
        // PowerManager.currentThermalStatus every 2s. While cheap, running
        // it on the Main dispatcher adds avoidable main-thread work. Move
        // to Dispatchers.Default so the main thread stays free for UI.
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                appContext?.let { ctx ->
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        _thermal.value = Guards.thermalLabel(pm.currentThermalStatus)
                    }
                }
                delay(2000)
            }
        }
    }

    fun attach(context: Context) {
        appContext = context.applicationContext
        // Probe backends once per ViewModel lifetime — the result is cached
        // by the JNI layer so subsequent probes are cheap, but we only need
        // to populate the dialog once. Run on Default to avoid blocking the
        // main thread during the OpenCL device-name query.
        if (_backendProbe.value == null) {
            viewModelScope.launch(Dispatchers.Default) {
                val probe = withContext(Dispatchers.Default) {
                    runCatching { EngineSelector.probe() }
                        .getOrNull()
                }
                if (probe != null) _backendProbe.value = probe
            }
        }
    }

    /**
     * Start mining with the user's chosen backend. The [backendPref] is
     * passed as an Intent extra so [MinerService] can pick it up at start
     * time — this lets the user try GPU once without changing the saved
     * Settings.backendPref default.
     */
    fun startMining(context: Context, backendPref: BackendPref) {
        appContext = context.applicationContext
        val intent = Intent(context, MinerService::class.java).apply {
            putExtra(MinerService.EXTRA_BACKEND_PREF, backendPref.name)
        }
        context.startForegroundService(intent)
    }

    fun pause() = core.pause("user")

    fun resume() = core.resume()

    fun stopMining(context: Context) {
        context.startService(
            Intent(context, MinerService::class.java)
                .setAction(MinerService.ACTION_STOP),
        )
    }
}
