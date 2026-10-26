package org.secretarrow.noidminer.pow

import android.util.Log
import org.secretarrow.noidminer.util.Hex

/** Hashing backend contract used by the mining threads. */
interface HashEngine {
    /** Human-readable backend label shown on the Mine screen. */
    val name: String

    /**
     * Batch-hash [count] consecutive nonces starting at [nonceStart] with a
     * zero high lane word (free 128-bit lane). Returns `count * 32` bytes,
     * or null when the backend failed — callers must treat null as "no
     * solutions" and keep going (or fall back).
     */
    fun batchHash(powFieldsHex: String, nonceStart: Long, count: Int): ByteArray? =
        batchHashLanes(powFieldsHex, 0L, nonceStart, count)

    /**
     * Batch-hash [count] consecutive 128-bit lanes. Lane i =
     * `(laneHi << 64) | (laneLoStart + i)` — field 10 of the template is
     * replaced by that lane before hashing. Returns `count * 32` bytes.
     */
    fun batchHashLanes(powFieldsHex: String, laneHi: Long, laneLoStart: Long, count: Int): ByteArray?

    /** True when hash < target (256-bit little-endian). */
    fun checkTarget(hash: ByteArray, targetLeHex: String): Boolean

    /** Validates an `o1…` Parano1d address. */
    fun validateAddress(address: String): Boolean
}

/** Native CPU engine (Rust, PMULL-accelerated on ARMv8). */
class NativeEngine : HashEngine {
    override val name: String = "CPU · native (PMULL)"

    override fun batchHashLanes(
        powFieldsHex: String,
        laneHi: Long,
        laneLoStart: Long,
        count: Int,
    ): ByteArray? = try {
        NativePow.batchHashLaneNative(powFieldsHex, laneHi, laneLoStart, count)
    } catch (t: Throwable) {
        Log.w(TAG, "native batch failed: ${t.message}")
        null
    }

    override fun checkTarget(hash: ByteArray, targetLeHex: String): Boolean =
        try {
            NativePow.checkTargetNative(hash, targetLeHex)
        } catch (t: Throwable) {
            val target = Hex.decode(targetLeHex) ?: return false
            SoftwareEngine.software.le256Lt(hash, target)
        }

    override fun validateAddress(address: String): Boolean =
        try {
            NativePow.validateAddressNative(address)
        } catch (t: Throwable) {
            softwareAddressCheck(address)
        }

    private fun softwareAddressCheck(address: String): Boolean =
        org.secretarrow.noidminer.util.Bech32m
            .isValidO1(address)

    companion object {
        private const val TAG = "NativeEngine"
    }
}

/** Pure-Kotlin fallback engine — always available, slower. */
class SoftwareEngine : HashEngine {
    override val name: String = "CPU · software"

    override fun batchHashLanes(
        powFieldsHex: String,
        laneHi: Long,
        laneLoStart: Long,
        count: Int,
    ): ByteArray? {
        val out = ByteArray(count * 32)
        val software = Poseidon2bSoftware.INSTANCE
        for (i in 0 until count) {
            val digest = software.powHashLane(powFieldsHex, laneLoStart + i, laneHi) ?: return null
            digest.copyInto(out, i * 32)
        }
        return out
    }

    override fun checkTarget(hash: ByteArray, targetLeHex: String): Boolean {
        val target = Hex.decode(targetLeHex) ?: return false
        return software.le256Lt(hash, target)
    }

    override fun validateAddress(address: String): Boolean =
        org.secretarrow.noidminer.util.Bech32m
            .isValidO1(address)

    companion object {
        val software = Poseidon2bSoftware.INSTANCE
    }
}

/** OpenCL GPU engine — only constructed after the self-check passes. */
class GpuEngine(
    private val deviceName: String,
) : HashEngine {
    override val name: String = "GPU · OpenCL ($deviceName)"

    override fun batchHashLanes(
        powFieldsHex: String,
        laneHi: Long,
        laneLoStart: Long,
        count: Int,
    ): ByteArray? = try {
        NativePow.openclBatchHashLaneNative(powFieldsHex, laneHi, laneLoStart, count)
    } catch (t: Throwable) {
        Log.w(TAG, "gpu batch failed: ${t.message}")
        null
    }

    override fun checkTarget(hash: ByteArray, targetLeHex: String): Boolean =
        NativeEngine().checkTarget(hash, targetLeHex)

    override fun validateAddress(address: String): Boolean =
        NativeEngine().validateAddress(address)

    companion object {
        private const val TAG = "GpuEngine"
    }
}

/**
 * Picks and verifies the hashing backend.
 *
 * The GPU path is gated by a runtime self-check: the GPU must reproduce the
 * native CPU digests on a fixed probe batch before it is ever used for real
 * mining. Any mismatch permanently (per process) disables the GPU.
 */
object EngineSelector {
    sealed interface Selection {
        val engine: HashEngine
        val gpuAvailable: Boolean
        val gpuDevice: String?
        val gpuSelfCheckPassed: Boolean
    }

    data class Result(
        override val engine: HashEngine,
        override val gpuAvailable: Boolean,
        override val gpuDevice: String?,
        override val gpuSelfCheckPassed: Boolean,
    ) : Selection

    @Volatile
    private var gpuDisabled: Boolean = false

    /** Probe template — deterministic pseudo-random 512-hex string (256 bytes). */
    private val PROBE_FIELDS: String = buildString {
        var x = 0x9E3779B97F4A7C15UL
        repeat(256) {
            x = x * 6364136223846793005UL + 1442695040888963407UL
            val v = (x shr 32).toString(16).padStart(8, '0')
            append(v)
        }
    }

    fun select(preferGpu: Boolean): Result {
        val nativeAvailable = NativePow.ensureLoaded()
        val native = if (nativeAvailable) NativeEngine() else SoftwareEngine()

        var gpuAvailable = false
        var gpuDevice: String? = null
        var selfCheck = false
        if (nativeAvailable && !gpuDisabled && preferGpu) {
            try {
                gpuAvailable = NativePow.openclAvailableNative()
                gpuDevice = NativePow.openclDeviceNameNative()
                if (gpuAvailable) {
                    selfCheck = gpuSelfCheck(native)
                    if (!selfCheck) {
                        Log.w(TAG, "GPU self-check failed — disabling GPU for this session")
                        gpuDisabled = true
                        gpuAvailable = false
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "GPU probe failed: ${t.message}")
                gpuAvailable = false
            }
        }

        val engine = when {
            gpuAvailable && selfCheck && preferGpu -> GpuEngine(gpuDevice ?: "GPU")
            nativeAvailable -> native
            else -> SoftwareEngine()
        }
        return Result(engine, gpuAvailable, gpuDevice, selfCheck)
    }

    /**
     * Backend-aware selection — maps a [BackendPref] to a `preferGpu` flag and
     * delegates to [select]. Used by the new start-time backend picker so the
     * Mine screen can let the user explicitly choose CPU vs GPU before mining.
     *
     * - AUTO     → preferGpu=true  (try GPU, fall back to CPU on failure)
     * - CPU_ONLY → preferGpu=false (skip GPU entirely — user opted out)
     * - GPU_ONLY → preferGpu=true  (try GPU; if unavailable the engine falls
     *   back to CPU because GpuEngine is only constructed when selfCheck passes.
     *   The user is warned about the fallback via MineViewModel.backendState.)
     */
    fun select(backendPref: org.secretarrow.noidminer.data.BackendPref): Result =
        when (backendPref) {
            org.secretarrow.noidminer.data.BackendPref.AUTO -> select(preferGpu = true)
            org.secretarrow.noidminer.data.BackendPref.CPU_ONLY -> select(preferGpu = false)
            org.secretarrow.noidminer.data.BackendPref.GPU_ONLY -> select(preferGpu = true)
        }

    /**
     * Probe the device's hashing backends WITHOUT constructing an engine —
     * used by the backend picker dialog to show the user what's available
     * before they tap Start. Cheap: native loaded once, OpenCL probed once,
     * both cached by the JNI layer.
     */
    data class BackendProbe(
        val cpuLabel: String,
        val cpuAvailable: Boolean,
        val cpuCores: Int,
        val gpuLabel: String?,
        val gpuAvailable: Boolean,
        val gpuSelfCheckPassed: Boolean,
    )

    fun probe(): BackendProbe {
        val nativeAvailable = NativePow.ensureLoaded()
        val cores = Runtime.getRuntime().availableProcessors()
        val cpuLabel = if (nativeAvailable) {
            "Native (ARM PMULL)" // loaded by native core
        } else {
            "Software (pure Kotlin)"
        }
        var gpuAvail = false
        var gpuName: String? = null
        var selfCheck = false
        if (nativeAvailable && !gpuDisabled) {
            runCatching {
                gpuAvail = NativePow.openclAvailableNative()
                gpuName = NativePow.openclDeviceNameNative()
                if (gpuAvail) {
                    val native = NativeEngine()
                    selfCheck = gpuSelfCheck(native)
                    if (!selfCheck) {
                        gpuDisabled = true
                        gpuAvail = false
                    }
                }
            }.onFailure { Log.w(TAG, "GPU probe failed: ${it.message}") }
        }
        return BackendProbe(
            cpuLabel = cpuLabel,
            cpuAvailable = true, // CPU (native or software) is always available
            cpuCores = cores,
            gpuLabel = gpuName,
            gpuAvailable = gpuAvail,
            gpuSelfCheckPassed = selfCheck,
        )
    }

    /** GPU must match the native CPU digest-for-digest on the probe batch. */
    private fun gpuSelfCheck(cpu: HashEngine): Boolean {
        val count = 8
        val cpuOut = cpu.batchHashLanes(PROBE_FIELDS, 0x0123456789ABCDEFL, 1000L, count)
            ?: return false
        val gpuOut = try {
            NativePow.openclBatchHashLaneNative(PROBE_FIELDS, 0x0123456789ABCDEFL, 1000L, count)
                ?: return false
        } catch (t: Throwable) {
            return false
        }
        return cpuOut.contentEquals(gpuOut)
    }

    /** Used by unit tests to reset session state. */
    fun resetForTest() {
        gpuDisabled = false
    }

    private const val TAG = "EngineSelector"
}
