package org.secretarrow.noidminer.data

import org.secretarrow.noidminer.data.rpc.SoloRpcClient
import org.secretarrow.noidminer.data.stratum.StratumClient
import org.secretarrow.noidminer.data.stratum.StratumCodec
import org.secretarrow.noidminer.data.stratum.difficultyToTarget
import org.secretarrow.noidminer.pow.JobSource
import org.secretarrow.noidminer.pow.MiningJob
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/** Well-known Parano1d pools (from the upstream miner README). */
data class PoolPreset(
    val label: String,
    val url: String,
    val tls: Boolean,
)

object PoolPresets {
    val ALL = listOf(
        PoolPreset("InnovLab EU (TLS)", "stratum+tls://eu.innovlab.cc:19601", tls = true),
        PoolPreset("RPlant EU (TLS)", "stratum+tls://stratum-eu.rplant.xyz:17185", tls = true),
        PoolPreset("RPlant Asia (TLS)", "stratum+tls://stratum-asia.rplant.xyz:17185", tls = true),
        PoolPreset("Alphapool EU", "stratum+tcp://eu1.alphapool.tech:19610", tls = false),
    )
}

/**
 * Builds the stratum authorization user from the bound wallet and worker:
 * `<o1-address>.<worker>` — the standard `wallet.worker` pool convention.
 * An explicit pool user typed on the Pools screen always wins.
 */
fun buildPoolUser(explicitUser: String, walletAddress: String, workerName: String): String {
    val explicit = explicitUser.trim()
    if (explicit.isNotBlank()) return explicit
    val wallet = walletAddress.trim()
    if (wallet.isNotBlank()) {
        val worker = workerName.trim().ifBlank { "noidminer" }
        return "$wallet.$worker"
    }
    return ""
}

/**
 * POOL source for the real "parano1d-stratum-v1" protocol (InnovLab, RPlant,
 * Alphapool): authorize with the payout address, mine object-shaped notify
 * jobs, submit `[job_id, nonce128, work_domain_id]`.
 */
class PoolJobSource(
    poolUrl: String,
    private val user: String,
    private val log: (String, String) -> Unit,
) : JobSource {
    private val client = StratumClient(poolUrl)
    private val pending = ConcurrentLinkedQueue<MiningJob>()
    private val sessionNamespace = AtomicReference("")

    @Volatile
    private var authorized = false

    @Volatile
    private var poolPaused: Boolean = false

    init {
        client.start { raw -> handleLine(raw) }
    }

    private fun handleLine(raw: String) {
        when (val event = StratumCodec.decode(raw)) {
            is StratumCodec.Event.SubscribedV1 -> {
                sessionNamespace.set(event.sessionNamespace)
                log(
                    "info",
                    "subscribed ${event.protocol} ns=${event.sessionNamespace} " +
                        "nonceBits=${event.nonceBits}",
                )
                // Re-authorize on EVERY subscribe — the flag goes stale
                // after a reconnect, and re-auth is idempotent for pools.
                if (user.isNotBlank()) {
                    authorized = client.authorize(user)
                    log(if (authorized) "info" else "warn", "authorize=$authorized")
                } else {
                    log("warn", "no payout address bound — authorize skipped")
                }
            }
            is StratumCodec.Event.Subscribed -> {
                log("info", "subscribed (SV1 legacy)")
                if (user.isNotBlank()) {
                    authorized = client.authorize(user)
                    log(if (authorized) "info" else "warn", "authorize=$authorized")
                }
            }
            is StratumCodec.Event.AuthResult -> {
                authorized = event.ok
                log(
                    if (event.ok) "info" else "error",
                    if (event.ok) "pool authorized: $user" else "pool authorize REJECTED",
                )
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
                    expiresAtMs = System.currentTimeMillis() +
                        (event.expiresInSeconds.coerceIn(5, 600) * 1000L),
                    laneHiHex = event.laneHiHex,
                    workDomainIdHex = event.workDomainIdHex,
                )
                if (event.cleanJobs) pending.clear()
                pending.add(job)
            }
            is StratumCodec.Event.Paused -> {
                poolPaused = true
                log("warn", "pool paused mining — ${event.reason}")
            }
            is StratumCodec.Event.Resumed -> {
                poolPaused = false
                log("info", "pool resumed mining")
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
        if (poolPaused) return null
        val job = pending.poll() ?: return null
        return job.copy(targetLeHex = job.targetLeHex.takeIf { it != StratumCodec.MAX_TARGET } ?: lastTarget)
    }

    override fun submit(job: MiningJob, nonceLeHex: String, hashHex: String): Boolean {
        val user0 = user.ifBlank { "noidminer.worker" }
        // parano1d-stratum-v1 submit: [job_id, nonce128hex, work_domain_id].
        // The nonce is the full 16-byte little-endian lane
        // (counter_le || session-prefix). Pools without a work_domain_id
        // (legacy SV1) fall back to the 5-param BTC shape.
        val domain = job.workDomainIdHex
        return if (domain != null) {
            client.submitV1(user0, job.templateId, nonceLeHex, domain)
        } else {
            client.submitShare(user0, job.templateId, "", "00000000", nonceLeHex.takeLast(8))
        }
    }

    override fun close() {
        client.stop()
        pending.clear()
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
