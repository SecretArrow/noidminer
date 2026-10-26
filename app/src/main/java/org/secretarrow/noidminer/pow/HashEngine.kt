package org.secretarrow.noidminer.pow

import android.util.Log
import org.secretarrow.noidminer.util.Hex

/** Hashing backend contract used by the mining threads. */
interface HashEngine {
    /** Human-readable backend label shown on the Mine screen. */
    val name: String

    /**
     * Batch-hash [count] consecutive nonces starting at [nonceStart].
     * Returns `count * 32` bytes, or null when the backend failed — callers
     * must treat null as "no solutions" and keep going (or fall back).
     */
    fun batchHash(powFieldsHex: String, nonceStart: Long, count: Int): ByteArray?

    /** True when hash < target (256-bit little-endian). */
    fun checkTarget(hash: ByteArray, targetLeHex: String): Boolean

    /** Validates an `o1…` Parano1d address. */
    fun validateAddress(address: String): Boolean
}

/** Native CPU engine (Rust, PMULL-accelerated on ARMv8). */
class NativeEngine : HashEngine {
    override val name: String = "CPU · native (PMULL)"

    override fun batchHash(powFieldsHex: String, nonceStart: Long, count: Int): ByteArray? =
        try {
            NativePow.batchHashNative(powFieldsHex, nonceStart, count)
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

    override fun batchHash(powFieldsHex: String, nonceStart: Long, count: Int): ByteArray? {
        val out = ByteArray(count * 32)
        for (i in 0 until count) {
            val digest = software.powHash(powFieldsHex, nonceStart + i) ?: return null
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

    override fun batchHash(powFieldsHex: String, nonceStart: Long, count: Int): ByteArray? =
        try {
            NativePow.openclBatchHashNative(powFieldsHex, nonceStart, count)
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

    /** Probe template — deterministic pseudo-random 256-hex string. */
    private val PROBE_FIELDS: String = buildString {
        var x = 0x9E3779B97F4A7C15UL
        repeat(128) {
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

    /** GPU must match the native CPU digest-for-digest on the probe batch. */
    private fun gpuSelfCheck(cpu: HashEngine): Boolean {
        val count = 8
        val cpuOut = cpu.batchHash(PROBE_FIELDS, 1000L, count) ?: return false
        val gpuOut = try {
            NativePow.openclBatchHashNative(PROBE_FIELDS, 1000L, count) ?: return false
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
