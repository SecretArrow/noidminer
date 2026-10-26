package org.secretarrow.noidminer.data.stratum

import org.secretarrow.noidminer.pow.MiningJob

/**
 * Converts Stratum (SV1) messages into NoidMiner [MiningJob]s.
 *
 * Uses a tiny pure-Kotlin JSON reader instead of org.json so the codec is
 * unit-testable on the JVM (no android.jar stubs) and identical on device.
 * Stratum messages are flat JSON — no nested objects — so a scanner over
 * the top-level members is sufficient and fast.
 *
 * Parano1d pools are expected to carry a 256-hex `pow_fields` blob plus a
 * 64-hex difficulty target inside `mining.notify` params. The codec scans
 * every param for both shapes; unrecognized pools surface as
 * [Event.UnsupportedJob] so the UI can hint that the codec needs tuning.
 */
object StratumCodec {
    private val HEX_256 = Regex("^[0-9a-fA-F]{256}$")
    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")

    private val METHOD_RE = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"")
    private val RESULT_BOOL_RE = Regex("\"result\"\\s*:\\s*(true|false)")

    /**
     * Extracts the body of `"key": [ ... ]` with bracket-depth awareness
     * (string-safe), so nested arrays like the subscribe result parse
     * correctly. Returns the content between the outer brackets.
     */
    internal fun extractArray(line: String, key: String): String? {
        val token = "\"$key\""
        var idx = line.indexOf(token)
        while (idx != -1) {
            val rest = line.substring(idx + token.length)
            val colon = Regex("^\\s*:\\s*").find(rest)
            if (colon != null) {
                val after = rest.substring(colon.value.length)
                if (after.startsWith("[")) {
                    var depth = 0
                    var inString = false
                    var escape = false
                    for (i in after.indices) {
                        val ch = after[i]
                        when {
                            escape -> escape = false
                            ch == '\\' && inString -> escape = true
                            ch == '"' -> inString = !inString
                            inString -> Unit
                            ch == '[' -> depth++
                            ch == ']' -> {
                                depth--
                                if (depth == 0) return after.substring(1, i)
                            }
                        }
                    }
                    return null
                }
            }
            idx = line.indexOf(token, idx + 1)
        }
        return null
    }

    /** Splits a flat JSON array body into raw items (strings/numbers/bools). */
    internal fun splitArrayItems(body: String): List<String> {
        val items = mutableListOf<String>()
        val sb = StringBuilder()
        var inString = false
        var escape = false
        for (ch in body) {
            when {
                escape -> {
                    sb.append(ch)
                    escape = false
                }
                ch == '\\' && inString -> {
                    sb.append(ch)
                    escape = true
                }
                ch == '"' -> {
                    inString = !inString
                    sb.append(ch)
                }
                ch == ',' && !inString -> {
                    items.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotBlank()) items.add(sb.toString().trim())
        return items
    }

    private fun unquote(item: String): String =
        item.trim().removePrefix("\"").removeSuffix("\"")

    /** Parses a JSON line; returns typed events the engine cares about. */
    fun decode(line: String): Event {
        val method = METHOD_RE.find(line)?.groupValues?.get(1)
        if (method != null) {
            val paramsBody = extractArray(line, "params")
            val params = paramsBody?.let { splitArrayItems(it) } ?: emptyList()
            return methodEvent(method, params)
        }
        val error = extractArray(line, "error")
        if (error != null && error.isNotBlank() && error != "null") {
            return Event.Error(error.take(200))
        }
        val resultBool = RESULT_BOOL_RE.find(line)
        if (resultBool != null) {
            return Event.AuthResult(resultBool.groupValues[1] == "true")
        }
        val resultArray = extractArray(line, "result")
        if (resultArray != null) {
            val items = splitArrayItems(resultArray)
            if (items.size >= 3) {
                return Event.Subscribed(
                    extraNonce1 = unquote(items[items.size - 2]),
                    extraNonce2Size = unquote(items[items.size - 1]).toIntOrNull() ?: 4,
                )
            }
            return Event.ResultOk
        }
        return Event.Ignored
    }

    private fun methodEvent(method: String, params: List<String>): Event = when (method) {
        "mining.notify" -> notify(params) ?: Event.UnsupportedJob(method, params.toString())
        "mining.set_difficulty" -> Event.Difficulty(params.firstOrNull()?.toDoubleOrNull() ?: 0.0)
        else -> Event.Ignored
    }

    private fun notify(params: List<String>): Event.Notify? {
        if (params.size < 2) return null
        var fields: String? = null
        var target: String? = null
        for (raw in params) {
            val v = unquote(raw)
            if (fields == null && HEX_256.matches(v)) fields = v.lowercase()
            if (target == null && HEX_64.matches(v)) target = v.lowercase()
        }
        val f = fields ?: return null
        val clean = params.lastOrNull()?.trim() == "true"
        return Event.Notify(
            jobId = unquote(params[0]),
            powFieldsHex = f,
            targetLeHex = target ?: MAX_TARGET,
            height = 0L,
            cleanJobs = clean,
        )
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
            val message: String,
        ) : Event

        data object ResultOk : Event
    }
}

/** Difficulty → 32-byte LE target (standard bitcoin scaling). */
fun difficultyToTarget(difficulty: Double): String {
    val d = difficulty.coerceAtLeast(1e-9)
    // max_target = 2^256-1; target = max_target / difficulty
    val max = java.math.BigInteger.ONE
        .shiftLeft(256)
        .subtract(java.math.BigInteger.ONE)
    val divisor = java.math
        .BigDecimal(d)
        .toBigInteger()
        .coerceAtLeast(java.math.BigInteger.ONE)
    val target = max.divide(divisor)
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
