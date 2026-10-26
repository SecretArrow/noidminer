package org.secretarrow.noidminer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.EngineSelector
import org.secretarrow.noidminer.pow.Guards
import org.secretarrow.noidminer.pow.HardwareInfo
import org.secretarrow.noidminer.pow.NativePow
import org.secretarrow.noidminer.util.Format

/** Device screen state: hardware inventory, GPU probe, battery, benchmark. */
class DeviceViewModel : ViewModel() {
    data class GpuState(
        val device: String?,
        val status: String,
        val selfCheck: String,
    )

    data class BatteryState(
        val pct: Int = 100,
        val charging: Boolean = false,
        val saver: Boolean = false,
        val thermal: String = "none",
    )

    data class BenchState(
        val running: Boolean = false,
        val result: String? = null,
    )

    private val _hardware = MutableStateFlow(
        HardwareInfo("…", "…", 0, emptyList(), 0, false),
    )
    val hardware: StateFlow<HardwareInfo> = _hardware.asStateFlow()

    private val _gpu = MutableStateFlow(GpuState(null, "probing…", "—"))
    val gpu: StateFlow<GpuState> = _gpu.asStateFlow()

    private val _battery = MutableStateFlow(BatteryState())
    val battery: StateFlow<BatteryState> = _battery.asStateFlow()

    private val _benchmark = MutableStateFlow(BenchState())
    val benchmark: StateFlow<BenchState> = _benchmark.asStateFlow()

    fun attach(context: Context) {
        val app = context.applicationContext
        _hardware.value = ServiceLocator.hardwareInfo()
        viewModelScope.launch(Dispatchers.Default) {
            val selection = EngineSelector.select(preferGpu = true)
            _gpu.value = GpuState(
                device = selection.gpuDevice,
                status = when {
                    !NativePow.ensureLoaded() -> "native core tidak tersedia (mode software)"
                    selection.gpuAvailable -> "aktif"
                    else -> "tidak dipakai (fallback CPU)"
                },
                selfCheck = when {
                    selection.gpuAvailable -> if (selection.gpuSelfCheckPassed) "PASSED" else "FAILED"
                    else -> "—"
                },
            )
        }
        viewModelScope.launch {
            while (true) {
                val snapshot = Guards.snapshot(app)
                _battery.value = BatteryState(
                    pct = snapshot.batteryPct,
                    charging = snapshot.charging,
                    saver = snapshot.batterySaver,
                    thermal = Guards.thermalLabel(snapshot.thermalStatus),
                )
                delay(3000)
            }
        }
    }

    /** Short single-thread benchmark on the active engine (software fallback OK). */
    fun runBenchmark() {
        if (_benchmark.value.running) return
        _benchmark.value = BenchState(running = true)
        viewModelScope.launch(Dispatchers.Default) {
            val result = withContext(Dispatchers.Default) {
                val engine = EngineSelector.select(preferGpu = false).engine
                val fields = "00".repeat(256)
                val start = System.nanoTime()
                var hashes = 0L
                val deadline = start + 3_000_000_000L
                while (System.nanoTime() < deadline) {
                    engine.batchHash(fields, hashes + 1, 64) ?: break
                    hashes += 64
                }
                val elapsed = (System.nanoTime() - start).coerceAtLeast(1)
                Format.hashrate(hashes.toDouble() / (elapsed / 1e9))
            }
            _benchmark.value = BenchState(running = false, result = result)
        }
    }
}
