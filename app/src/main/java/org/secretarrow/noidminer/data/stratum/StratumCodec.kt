package org.secretarrow.noidminer.data.stratum

import org.json.JSONArray
import org.json.JSONObject
import org.secretarrow.noidminer.pow.MiningJob

/**
 * Converts Stratum (SV1) messages into NoidMiner [MiningJob]s.
 *
 * Parano1d pools are expected to carry a 256-hex `pow_fields` blob plus a
 * 64-hex difficulty target somewhere in `mining.notify` params. The codec:
 *  1. scans every param for a 256-char hex string → pow_fields
 *  2. scans for a 64-char hex string → target
 *  3. if neither exists, logs the raw params (debug) and returns null so
 *     the UI can surface a "pool protocol not recognized" hint.
 */
object StratumCodec {
    private val HEX_256 = Regex("^[0-9a-fA-F]{256}$")
    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")

    data class Notify(
        val jobId: String,
        val powFieldsHex: String,
        val targetLeHex: String,
        val height: Long,
        val cleanJobs: Boolean,
    )

    /** Parses a JSON line; returns typed events the engine cares about. */
    fun decode(line: String): Event = try {
        val obj = JSONObject(line)
        when {
            obj.has("method") -> methodEvent(obj.optString("method"), obj.optJSONArray("params"))
            obj.has("result") -> resultEvent(obj)
            else -> Event.Ignored
        }
    } catch (t: Throwable) {
        Event.Ignored
    }

    private fun methodEvent(method: String, params: JSONArray?): Event {
        if (params == null) return Event.Ignored
        return when (method) {
            "mining.notify" -> notify(params) ?: Event.UnsupportedJob(method, params.toString())
            "mining.set_difficulty" -> Event.Difficulty(params.optDouble(0, 0.0))
            "mining.set_extranonce" -> Event.Ignored
            else -> Event.Ignored
        }
    }

    private fun notify(params: JSONArray): Event.Notify? {
        if (params.length() < 2) return null
        val jobId = params.optString(0).orEmpty()
        var fields: String? = null
        var target: String? = null
        for (i in 0 until params.length()) {
            val v = params.optString(i).orEmpty()
            if (fields == null && HEX_256.matches(v)) fields = v.lowercase()
            if (target == null && HEX_64.matches(v)) target = v.lowercase()
        }
        fields ?: return null
        val clean = params.optBoolean(params.length() - 1, false)
        return Event.Notify(
            jobId = jobId,
            powFieldsHex = fields,
            targetLeHex = target ?: MAX_TARGET,
            height = 0L,
            cleanJobs = clean,
        )
    }

    private fun resultEvent(obj: JSONObject): Event {
        val id = obj.opt("id")
        val result = obj.opt("result")
        val error = obj.optJSONArray("error")
        return when {
            error != null && error.length() > 0 -> Event.Error(id?.toString().orEmpty(), error.toString())
            result is Boolean -> Event.AuthResult(id?.toString().orEmpty(), result)
            result is JSONArray && result.length() >= 3 -> Event.Subscribed(
                extraNonce1 = result.optString(1),
                extraNonce2Size = result.optInt(2, 4),
            )
            else -> Event.ResultOk(id?.toString().orEmpty())
        }
    }

    /** Default ultra-loose target when the pool sends none. */
    const val MAX_TARGET: String = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

    sealed interface Event {
        data object Ignored : Event

        data class Subscribed(
            val extraNonce1: String,
            val extraNonce2Size: Int,
        ) : Event

        data class AuthResult(
            val id: String,
            val ok: Boolean,
        ) : Event

        data class Difficulty(
            val difficulty: Double,
        ) : Event

        data class Notify(
            val jobId: String,
            val powFieldsHex: String,
            val targetLeHex: String,
            val height: Long,
            val cleanJobs: Boolean,
        ) : Event

        data class UnsupportedJob(
            val method: String,
            val raw: String,
        ) : Event

        data class Error(
            val id: String,
            val message: String,
        ) : Event

        data class ResultOk(
            val id: String,
        ) : Event
    }
}

/** Difficulty → 32-byte LE target (standard bitcoin scaling). */
fun difficultyToTarget(difficulty: Double): String {
    val d = difficulty.coerceAtLeast(1e-9)
    // max_target = 2^256-1; target = max_target / difficulty
    val max = java.math.BigInteger.ONE
        .shiftLeft(256)
        .subtract(java.math.BigInteger.ONE)
    val target = max.divide(
        java.math
            .BigDecimal(d)
            .toBigInteger()
            .coerceAtLeast(java.math.BigInteger.ONE)
    )
    return target
        .toString(16)
        .lowercase()
        .padStart(64, '0')
        .takeLast(64)
}

/** Convenience used by the pool job source. */
fun stratumEventToJob(event: StratumCodec.Event.Notify, expiryMs: Long): MiningJob = MiningJob(
    templateId = event.jobId,
    powFieldsHex = event.powFieldsHex,
    targetLeHex = event.targetLeHex,
    height = event.height,
    expiresAtMs = expiryMs,
)
