package org.secretarrow.noidminer.pow

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.secretarrow.noidminer.util.Hex
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One mining template — identical shape across Pool (Stratum) / Solo RPC.
 *
 * @param laneHiHex optional 8-byte session prefix (hex). When present the
 *   128-bit nonce lane is `(laneHi << 64) | counter` and submissions carry
 *   the full 16-byte lane; when null the lane is a free 128-bit value
 *   (solo/RPC mining, high half stays random-per-start).
 * @param workDomainIdHex optional 64-hex value echoed in mining.submit.
 */
data class MiningJob(
    val templateId: String,
    val powFieldsHex: String,
    val targetLeHex: String,
    val height: Long,
    val expiresAtMs: Long,
    val laneHiHex: String? = null,
    val workDomainIdHex: String? = null,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    /**
     * Builds the 32-hex-char nonce (16 bytes little-endian) for a counter:
     * counter LE bytes first, then the raw session prefix bytes — exactly
     * the layout the parano1d pools validate ("work_domain_id"/namespace
     * check reads bytes 8..16).
     */
    fun nonceHex(counter: Long): String {
        val bytes = ByteArray(16)
        var v = counter
        for (i in 0 until 8) {
            bytes[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        val hi = laneHiHex
        if (hi != null) {
            val hiBytes = Hex.decode(hi)
            if (hiBytes != null && hiBytes.size == 8) {
                hiBytes.copyInto(bytes, 8)
            }
        }
        return Hex.encode(bytes)
    }

    /** laneHi as a little-endian u64 (the engine's high word of the lane). */
    fun laneHiValue(): Long {
        val hi = laneHiHex ?: return 0L
        val bytes = Hex.decode(hi) ?: return 0L
        var v = 0UL
        for (i in 0 until minOf(8, bytes.size)) {
            v = v or ((bytes[i].toULong() and 0xFFUL) shl (8 * i))
        }
        return v.toLong()
    }
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

data class LogLine(
    val ts: Long,
    val level: String,
    val message: String,
) {
    // PERF (jetpack-compose-performance skill): stable unique identity so
    // LazyColumn items(keys = seq) can diff streamed log lines instead of
    // re-diffing the whole list on every line. Assigned at construction.
    val seq: Long = LogLine.nextSeq()

    companion object {
        private val COUNTER = java.util.concurrent.atomic
            .AtomicLong(0)

        fun nextSeq(): Long = COUNTER.incrementAndGet()
    }
}

/**
 * Threaded mining core. N worker threads pull batches through the selected
 * [HashEngine]; pause/resume is cooperative via an AtomicBoolean so the
 * guards (thermal/battery) can throttle instantly.
 *
 * Nonce management: workers scan the 64-bit counter space; the 128-bit lane
 * is `(job.laneHi << 64) | counter` for pool jobs (session prefix) and a
 * random free lane for solo jobs. Worker ranges never overlap (stride 2^32).
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
        // CRITICAL: set the AtomicBoolean BEFORE the worker threads start.
        // workerLoop checks `while (running.get())` — if this is false, the
        // workers exit immediately and no shares are ever found. The previous
        // code only updated the StateFlow's `running` field, leaving the
        // AtomicBoolean false — every lifecycle test timed out.
        running.set(true)
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
            _state.update { it.copy(paused = true, pausedReason = reason) }
            log("warn", "paused${reason?.let { " — $it" } ?: ""}")
        }
    }

    fun resume() {
        if (paused.getAndSet(false)) {
            _state.update { it.copy(paused = false, pausedReason = null) }
            log("info", "resumed")
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        paused.set(false)
        threads.forEach { it.interrupt() }
        // ANR FIX (anr-audit-and-fix skill): close the job source BEFORE
        // joining. A worker blocked in nextJob() on a plain Socket.read does
        // not react to interrupt() — only closing the socket unblocks it.
        // The old order (join then close) made stop() wait the full 1.5s
        // budget per stuck worker, and stopMining() used to run this on the
        // main thread.
        jobSource?.close()
        threads.forEach { it.join(1500) }
        threads = emptyList()
        jobSource = null
        currentJob = null
        _state.update { it.copy(running = false, paused = false, hashrateHps = 0.0) }
        log("info", "stopped — ${accepted.get()} accepted, ${rejected.get()} rejected")
    }

    /** Records a share outcome coming from the active [JobSource]. */
    fun recordShare(ok: Boolean) {
        if (ok) {
            accepted.incrementAndGet()
            _state.update { it.copy(sharesAccepted = accepted.get()) }
        } else {
            rejected.incrementAndGet()
            _state.update { it.copy(sharesRejected = rejected.get()) }
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
                val job = currentJob
                val activeJob = job ?: source.nextJob()
                if (activeJob == null) {
                    Thread.sleep(JOB_RETRY_MS)
                    continue
                }
                if (currentJob == null) {
                    currentJob = activeJob
                    log("info", "job h=${activeJob.height} id=${activeJob.templateId.take(12)}")
                    _state.update { it.copy(currentHeight = activeJob.height) }
                }
                if (activeJob.isExpired()) {
                    currentJob = null
                    continue
                }
                val engine = engineResult?.engine ?: return
                val count = BATCH_SIZE
                val laneHi = activeJob.laneHiValue()
                val digests = engine.batchHashLanes(activeJob.powFieldsHex, laneHi, nonce, count)
                if (digests == null) {
                    // Backend hiccup — small backoff, keep the worker alive.
                    Thread.sleep(BACKOFF_MS)
                    continue
                }
                for (i in 0 until count) {
                    val digest = digests.copyOfRange(i * 32, (i + 1) * 32)
                    if (engine.checkTarget(digest, activeJob.targetLeHex)) {
                        val hashHex = Hex.encode(digest)
                        val nonceLeHex = activeJob.nonceHex(nonce + i)
                        log("info", "share found nonce=${nonce + i} hash=${hashHex.take(16)}…")
                        val ok = try {
                            source.submit(activeJob, nonceLeHex, hashHex)
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
        _state.update {
            it.copy(
                hashrateHps = hashes.get().toDouble() / elapsedSec,
                hashesTried = hashes.get(),
            )
        }
    }

    private fun log(level: String, message: String) {
        _log.tryEmit(LogLine(System.currentTimeMillis(), level, message))
    }

    companion object {
        /**
         * Batch size for `batchHashLanes`. Production uses 256 (good throughput
         * on ARM PMULL). Tests override via the `noidminer.batchSize` system
         * property to make per-hash latency observable inside the 30s deadline
         * (the pure-Kotlin software engine is ~50-200ms/hash on CI JVMs, so a
         * 256-hash batch can exceed the test budget before the worker ever
         * gets a chance to check the first digest).
         */
        val BATCH_SIZE: Int =
            System.getProperty("noidminer.batchSize")?.toIntOrNull()?.coerceIn(1, 4096) ?: 256
        const val MAX_THREADS = 16
        private const val STRIDE = 1L shl 32
        private const val PAUSE_POLL_MS = 400L
        private const val JOB_RETRY_MS = 1500L
        private const val BACKOFF_MS = 250L
        private const val REPORT_EVERY_BATCHES = 4
    }
}
