package org.secretarrow.noidminer.data

import org.secretarrow.noidminer.data.rpc.SoloRpcClient
import org.secretarrow.noidminer.data.stratum.StratumClient
import org.secretarrow.noidminer.data.stratum.StratumCodec
import org.secretarrow.noidminer.data.stratum.difficultyToTarget
import org.secretarrow.noidminer.pow.JobSource
import org.secretarrow.noidminer.pow.MiningJob
import org.secretarrow.noidminer.util.Hex
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue

/** Well-known Parano1d pools (from the upstream miner README). */
data class PoolPreset(val label: String, val url: String, val tls: Boolean)

object PoolPresets {
    val ALL = listOf(
        PoolPreset("Alphapool EU", "stratum+tcp://eu1.alphapool.tech:19610", tls = false),
        PoolPreset("InnovLab EU (TLS)", "stratum+tls://eu.innovlab.cc:19601", tls = true),
        PoolPreset("RPlant EU (TLS)", "stratum+tls://stratum-eu.rplant.xyz:17185", tls = true),
        PoolPreset("RPlant Asia (TLS)", "stratum+tls://stratum-asia.rplant.xyz:17185", tls = true),
    )
}

/**
 * DEMO source: self-contained templates with an easy target so the app can
 * be exercised end-to-end (and on the emulator) without network access.
 */
class DemoJobSource(
    private val onShare: (MiningJob, String) -> Boolean = { _, _ -> true },
) : JobSource {

    override fun nextJob(): MiningJob? {
        val rng = SecureRandom()
        val fields = ByteArray(256).also(rng::nextBytes)
        fields[160] = 0 // nonce lane placeholder (replaced per hash anyway)
        return MiningJob(
            templateId = "demo-${System.currentTimeMillis() / 1000}",
            powFieldsHex = Hex.encode(fields),
            targetLeHex = EASY_TARGET,
            height = (System.currentTimeMillis() / 10_000) % 100_000,
            expiresAtMs = System.currentTimeMillis() + 120_000,
        )
    }

    override fun submit(job: MiningJob, nonceLeHex: String, hashHex: String): Boolean =
        onShare(job, hashHex)

    override fun close() = Unit

    companion object {
        /** Looseness makes a share land every few seconds on demo hardware. */
        const val EASY_TARGET: String =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffff0fffffff"
    }
}

/**
 * POOL source: wraps [StratumClient], decodes notify events into jobs and
 * submits shares with the standard SV1 argument order.
 */
class PoolJobSource(
    poolUrl: String,
    private val user: String,
    private val log: (String, String) -> Unit,
) : JobSource {

    private val client = StratumClient(poolUrl)
    private val pending = ConcurrentLinkedQueue<MiningJob>()
    private var authorized = false
    private var extraNonce1 = ""
    private var extraNonce2Size = 4

    init {
        client.start { raw -> handleLine(raw) }
    }

    private fun handleLine(raw: String) {
        when (val event = StratumCodec.decode(raw)) {
            is StratumCodec.Event.Subscribed -> {
                extraNonce1 = event.extraNonce1
                extraNonce2Size = event.extraNonce2Size
                log("info", "subscribed, extraNonce1=$extraNonce1")
                if (user.isNotBlank() && !authorized) {
                    authorized = client.authorize(user)
                    log(if (authorized) "info" else "warn", "authorize=$authorized")
                }
            }
            is StratumCodec.Event.AuthResult -> {
                authorized = event.ok
                log(if (event.ok) "info" else "error", "pool authorize: ${event.ok}")
            }
            is StratumCodec.Event.Difficulty -> {
                pending.clear()
                lastTarget = difficultyToTarget(event.difficulty)
                log("info", "difficulty=${event.difficulty}")
            }
            is StratumCodec.Event.Notify -> {
                val job = MiningJob(
                    templateId = event.jobId,
                    powFieldsHex = event.powFieldsHex,
                    targetLeHex = event.targetLeHex,
                    height = event.height,
                    expiresAtMs = System.currentTimeMillis() + JOB_TTL_MS,
                )
                if (event.cleanJobs) pending.clear()
                pending.add(job)
            }
            is StratumCodec.Event.UnsupportedJob ->
                log("warn", "unrecognized ${event.method} params — pool codec may need tuning")
            is StratumCodec.Event.Error -> log("error", "pool error: ${event.message.take(160)}")
            else -> Unit
        }
    }

    @Volatile
    private var lastTarget: String = StratumCodec.MAX_TARGET

    override fun nextJob(): MiningJob? {
        val job = pending.poll() ?: return null
        return job.copy(targetLeHex = job.targetLeHex.takeIf { it != StratumCodec.MAX_TARGET } ?: lastTarget)
    }

    override fun submit(job: MiningJob, nonceLeHex: String, hashHex: String): Boolean {
        val user0 = user.ifBlank { "noidminer.worker" }
        // Standard SV1 share: (user.jobId, extraNonce2, nTime, nonce). Pools
        // that accept Parano1d-style raw nonces get the full 32-hex nonce in
        // the nonce slot; nTime is zeroed (POWHDR templates carry time in the
        // header fields themselves).
        val nonce2 = extraNonce2(extraNonce2Size)
        return client.submitShare(user0, job.templateId, nonce2, "00000000", nonceLeHex.takeLast(8))
    }

    private fun extraNonce2(size: Int): String {
        val bytes = ByteArray(size.coerceIn(0, 8))
        SecureRandom().nextBytes(bytes)
        return Hex.encode(bytes)
    }

    override fun close() {
        client.stop()
        pending.clear()
    }

    private companion object {
        const val JOB_TTL_MS = 90_000L
    }
}

/** SOLO source: pulls templates straight from a Parano1d node via JSON-RPC. */
class SoloJobSource(
    rpcUrl: String,
    token: String,
    private val minerAddress: String,
    private val log: (String, String) -> Unit,
) : JobSource {

    private val rpc = SoloRpcClient(rpcUrl, token)

    override fun nextJob(): MiningJob? = try {
        val t = rpc.getBlockTemplate(minerAddress)
        MiningJob(
            templateId = t.templateId,
            powFieldsHex = t.powFieldsHex,
            targetLeHex = t.difficultyTargetHex,
            height = t.height,
            expiresAtMs = System.currentTimeMillis() + (t.expiresInSec - 1).coerceAtLeast(5) * 1000,
        )
    } catch (e: SoloRpcClient.RpcException) {
        if (e.stale) log("warn", "stale share") else log("error", "template: ${e.message}")
        null
    }

    override fun submit(job: MiningJob, nonceLeHex: String, hashHex: String): Boolean = try {
        rpc.submitBlock(job.templateId, nonceLeHex)
        log("info", "block accepted h=${job.height}")
        true
    } catch (e: SoloRpcClient.RpcException) {
        log(if (e.stale) "warn" else "error", "submit: ${e.message}")
        false
    }

    override fun close() = Unit
}
