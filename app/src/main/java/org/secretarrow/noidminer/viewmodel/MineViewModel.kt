package org.secretarrow.noidminer.viewmodel

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.Guards
import org.secretarrow.noidminer.pow.MinerState

/** Drives the Mine screen from the singleton [MinerCore] state. */
class MineViewModel : ViewModel() {
    private val core = ServiceLocator.minerCore()

    private val _state = MutableStateFlow(MinerState())
    val state: StateFlow<MinerState> = _state.asStateFlow()

    private val _thermal = MutableStateFlow("none")
    val thermal: StateFlow<String> = _thermal.asStateFlow()

    private var appContext: Context? = null

    init {
        viewModelScope.launch {
            core.state.collect { _state.value = it }
        }
        viewModelScope.launch {
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
    }

    fun startMining(context: Context) {
        appContext = context.applicationContext
        context.startForegroundService(
            android.content.Intent(context, org.secretarrow.noidminer.pow.MinerService::class.java),
        )
    }

    fun pause() = core.pause("user")

    fun resume() = core.resume()

    fun stopMining(context: Context) {
        context.startService(
            android.content
                .Intent(context, org.secretarrow.noidminer.pow.MinerService::class.java)
                .setAction(org.secretarrow.noidminer.pow.MinerService.ACTION_STOP),
        )
    }
}
