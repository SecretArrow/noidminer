package org.secretarrow.noidminer.pow

import android.util.Log

/**
 * JNI surface to the native cores:
 *  - `libnoid_pow.so`   — Rust core wrapping upstream `noid_poseidon2b`
 *  - `libnoid_jni.so`   — C++ bridge + optional OpenCL GPU backend
 *
 * Loading is lazy and failure-tolerant: on unsupported ABIs the app falls
 * back to [Poseidon2bSoftware] without crashing.
 */
object NativePow {
    private const val TAG = "NativePow"

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    private val loadLock = Any()

    /** Loads the native libraries once; returns true when usable. */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(loadLock) {
            if (loaded) return true
            try {
                System.loadLibrary("noid_pow")
                System.loadLibrary("noid_jni")
                loaded = true
                Log.i(TAG, "native cores loaded")
            } catch (t: UnsatisfiedLinkError) {
                loadError = t.message
                Log.w(TAG, "native core unavailable: ${t.message}")
            } catch (t: Throwable) {
                loadError = t.message
                Log.w(TAG, "native core failed to load: ${t.message}")
            }
            return loaded
        }
    }

    /**
     * Batch-hash 128-bit lanes over the template. Lane i =
     * `(laneHi shl 64) or (laneLoStart + i)` — pool stratum passes the
     * session prefix as `laneHi`. Returns `count * 32` bytes or null.
     */
    external fun batchHashLaneNative(
        powFieldsHex: String,
        laneHi: Long,
        laneLoStart: Long,
        count: Int,
    ): ByteArray?

    external fun checkTargetNative(hash: ByteArray, targetLeHex: String): Boolean

    external fun le256LtNative(a: ByteArray, b: ByteArray): Boolean

    external fun validateAddressNative(address: String): Boolean

    external fun benchNative(powFieldsHex: String, nonceStart: Long, iters: Int): Long

    external fun openclAvailableNative(): Boolean

    external fun openclDeviceNameNative(): String?

    /** GPU batch over 128-bit lanes — same lane semantics as [batchHashLaneNative]. */
    external fun openclBatchHashLaneNative(
        powFieldsHex: String,
        laneHi: Long,
        laneLoStart: Long,
        count: Int,
    ): ByteArray?

    external fun releaseOpenclNative()
}
