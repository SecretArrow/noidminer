package org.secretarrow.noidminer.pow

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.secretarrow.noidminer.util.Hex
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One mining template — identical shape across Demo / Stratum / Solo RPC. */
data class MiningJob(
    val templateId: String,
    val powFieldsHex: String,
    val targetLeHex: String,
    val height: Long,
    val expiresAtMs: Long,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

/** Provides mining templates and accepts share submissions. */
interface JobSource {
    /** Blocking fetch of the next job; null means "no template yet, retry". */
    fun nextJob(): MiningJob?

    /** Submits a found share; returns true when accepted. */
    fun submit(job: MiningJob, nonceLeHex: String, hashHex: String): Boolean

    fun close()
}

/** Live counters surfaced on the Mine screen and notification. */
data class MinerState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val backend: String = "",
    val gpuAvailable: Boolean = false,
    val hashrateHps: Double = 0.0,
    val sharesAccepted: Long = 0,
    val sharesRejected: Long = 0,
    val hashesTried: Long = 0,
    val currentHeight: Long = 0,
    val startedAtMs: Long = 0,
    val pausedReason: String? = null,
)

data class LogLine(val ts: Long, val level: String, val message: String)

/**
 * Threaded mining core. N worker threads pull batches through the selected
 * [HashEngine]; pause/resume is cooperative via an AtomicBoolean so the
 * guards (thermal/battery) can throttle instantly.
 *
 * All engine exceptions are contained — a crashing worker logs and exits
 * without taking down the service; other workers keep mining.
 */
class MinerCore(
    private val engineProvider: () -> EngineSelector.Result,
) {
    private val _state = MutableStateFlow(MinerState())
    val state: StateFlow<MinerState> = _state.asStateFlow()

    private val _log = MutableSharedFlow<LogLine>(extraBufferCapacity = 512)
    val log: SharedFlow<LogLine> = _log.asSharedFlow()

    val running = AtomicBoolean(false)
    val paused = AtomicBoolean(false)

    @Volatile
    var currentJob: MiningJob? = null

    private val hashes = AtomicLong(0)
    private val accepted = AtomicLong(0)
    private val rejected = AtomicLong(0)
    private var threads: List<Thread> = emptyList()

    @Volatile
    private var jobSource: JobSource? = null

    @Volatile
    private var engineResult: EngineSelector.Result? = null

    private val rng = SecureRandom()

    /** Starts the workers. Safe to call repeatedly — no-op when running. */
    @Synchronized
    fun start(source: JobSource, threadCount: Int) {
        if (running.get()) return
        val selection = engineProvider()
        engineResult = selection
        jobSource = source
        hashes.set(0)
        accepted.set(0)
        rejected.set(0)
        paused.set(false)
        currentJob = null
        _state.value = MinerState(
            running = true,
            backend = selection.engine.name,
            startedAtMs = System.currentTimeMillis(),
            gpuAvailable = selection.gpuAvailable,
        )
        log("info", "backend=${selection.engine.name} threads=$threadCount")
        val safeThreads = threadCount.coerceIn(1, MAX_THREADS)
        val base = rng.nextLong() and 0x00FF_FFFF_FFFF_FFFFL
        threads = (0 until safeThreads).map { id ->
            Thread { workerLoop(id, base + id.toLong() * STRIDE) }.apply {
                name = "noid-worker-$id"
                isDaemon = true
                priority = Thread.NORM_PRIORITY
                start()
            }
        }
    }

    fun pause(reason: String?) {
        if (!paused.getAndSet(true)) {
            _state.value = _state.value.copy(paused = true, pausedReason = reason)
            log("warn", "paused${reason?.let { " — $it" } ?: ""}")
        }
    }

    fun resume() {
        if (paused.getAndSet(false)) {
            _state.value = _state.value.copy(paused = false, pausedReason = null)
            log("info", "resumed")
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        paused.set(false)
        threads.forEach { it.interrupt() }
        threads.forEach { it.join(1500) }
        threads = emptyList()
        jobSource?.close()
        jobSource = null
        currentJob = null
        _state.value = _state.value.copy(running = false, paused = false, hashrateHps = 0.0)
        log("info", "stopped — ${accepted.get()} accepted, ${rejected.get()} rejected")
    }

    /** Records a share outcome coming from the active [JobSource]. */
    fun recordShare(ok: Boolean) {
        if (ok) {
            accepted.incrementAndGet()
            _state.value = _state.value.copy(sharesAccepted = accepted.get())
        } else {
            rejected.incrementAndGet()
            _state.value = _state.value.copy(sharesRejected = rejected.get())
        }
    }

    private fun workerLoop(threadId: Int, startNonce: Long) {
        val source = jobSource ?: return
        var nonce = startNonce
        var batchesSinceReport = 0
        var localHashes = 0L
        try {
            while (running.get()) {
                if (paused.get()) {
                    Thread.sleep(PAUSE_POLL_MS)
                    continue
                }
                val job = currentJob ?: source.nextJob()?.also {
                    currentJob = it
                    log("info", "job h=${it.height} id=${it.templateId.take(12)}")
                    _state.value = _state.value.copy(currentHeight = it.height)
                } ?: run {
                    Thread.sleep(JOB_RETRY_MS)
                    continue
                }
                if (job.isExpired()) {
                    currentJob = null
                    continue
                }
                val engine = engineResult?.engine ?: return
                val count = BATCH_SIZE
                val digests = engine.batchHash(job.powFieldsHex, nonce, count)
                if (digests == null) {
                    // Backend hiccup — small backoff, keep the worker alive.
                    Thread.sleep(BACKOFF_MS)
                    continue
                }
                for (i in 0 until count) {
                    val digest = digests.copyOfRange(i * 32, (i + 1) * 32)
                    if (engine.checkTarget(digest, job.targetLeHex)) {
                        val hashHex = Hex.encode(digest)
                        val nonceLeHex = nonceHex(nonce + i)
                        log("info", "share found nonce=${nonce + i} hash=${hashHex.take(16)}…")
                        val ok = try {
                            source.submit(job, nonceLeHex, hashHex)
                        } catch (t: Throwable) {
                            log("error", "submit failed: ${t.message}")
                            false
                        }
                        recordShare(ok)
                        if (ok) {
                            currentJob = null
                            break
                        }
                    }
                }
                localHashes += count
                nonce += count
                batchesSinceReport++
                if (batchesSinceReport >= REPORT_EVERY_BATCHES) {
                    hashes.addAndGet(localHashes)
                    localHashes = 0
                    batchesSinceReport = 0
                    updateHashrate()
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown path.
        } catch (t: Throwable) {
            log("error", "worker-$threadId crashed: ${t.message}")
        } finally {
            if (localHashes > 0) hashes.addAndGet(localHashes)
        }
    }

    private fun updateHashrate() {
        val startedAt = _state.value.startedAtMs
        if (startedAt <= 0) return
        val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)
        _state.value = _state.value.copy(
            hashrateHps = hashes.get().toDouble() / elapsedSec,
            hashesTried = hashes.get(),
        )
    }

    private fun nonceHex(nonce: Long): String {
        val bytes = ByteArray(16)
        var v = nonce
        for (i in 0 until 8) {
            bytes[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return Hex.encode(bytes)
    }

    private fun log(level: String, message: String) {
        _log.tryEmit(LogLine(System.currentTimeMillis(), level, message))
    }

    companion object {
        const val BATCH_SIZE = 256
        const val MAX_THREADS = 16
        private const val STRIDE = 1L shl 32
        private const val PAUSE_POLL_MS = 400L
        private const val JOB_RETRY_MS = 1500L
        private const val BACKOFF_MS = 250L
        private const val REPORT_EVERY_BATCHES = 4
    }
}
