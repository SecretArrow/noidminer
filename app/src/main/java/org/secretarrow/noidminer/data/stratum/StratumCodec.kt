package org.secretarrow.noidminer.data.stratum

import org.secretarrow.noidminer.pow.MiningJob

/**
 * Stratum codec for the real NOID pool protocol ("parano1d-stratum-v1").
 *
 * Captured live from InnovLab / RPlant (2026-10, height 254k):
 *
 *  ← mining.subscribe result: {"nonce_bits":64,"protocol":"parano1d-stratum-v1",
 *                              "session_namespace":"<8-byte hex>"}
 *  → mining.authorize ["<o1 address>"]            ← result: true
 *  ← mining.notify  params:[{job_id, pow_fields_hex(512 hex = 256 bytes),
 *                            nonce_field_index, nonce_bits, nonce_prefix_hex,
 *                            work_domain_id, share_target_hex(64 hex LE),
 *                            block_target_hex, height, clean,
 *                            expires_in_seconds, assignment_id,
 *                            coinbase_value_micronoid}]
 *  ← mining.pause   params:[{reason}]             ← mining.resume params:[{…}]
 *  → mining.submit  [job_id, nonce128hex(32 chars = counter_le||prefix),
 *                    work_domain_id]              ← result true / error object
 *
 * Legacy SV1 array-shaped messages (BTC-style) are still decoded as a
 * fallback so private/alternative pools keep working.
 *
 * Uses a tiny pure-Kotlin JSON reader instead of org.json so the codec is
 * unit-testable on the JVM (no android.jar stubs) and identical on device.
 */
object StratumCodec {
    private val HEX_256B = Regex("^[0-9a-fA-F]{512}$") // 256-byte pow fields
    private val HEX_128B = Regex("^[0-9a-fA-F]{256}$") // legacy 128-byte fields
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

    /**
     * Extracts the body of `"key": { ... }` (object form) with brace-depth
     * awareness — used by parano1d-stratum-v1 notify/pause/resume params.
     */
    internal fun extractObject(line: String, key: String): String? {
        val token = "\"$key\""
        var idx = line.indexOf(token)
        while (idx != -1) {
            val rest = line.substring(idx + token.length)
            val colon = Regex("^\\s*:\\s*").find(rest)
            if (colon != null) {
                val after = rest.substring(colon.value.length)
                if (after.startsWith("{")) {
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
                            ch == '{' -> depth++
                            ch == '}' -> {
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

    /** Reads `"key":"value"` (string) or `"key":value` (raw) from an object body. */
    internal fun objString(body: String, key: String): String? {
        val token = "\"$key\""
        var idx = body.indexOf(token)
        while (idx != -1) {
            val rest = body.substring(idx + token.length)
            val colon = Regex("^\\s*:\\s*").find(rest)
            if (colon != null) {
                val after = rest.substring(colon.value.length).trimStart()
                if (after.startsWith("\"")) {
                    val sb = StringBuilder()
                    var i = 1
                    var escape = false
                    while (i < after.length) {
                        val ch = after[i]
                        if (escape) {
                            sb.append(ch)
                            escape = false
                        } else if (ch == '\\') {
                            escape = true
                        } else if (ch == '"') {
                            break
                        } else {
                            sb.append(ch)
                        }
                        i++
                    }
                    return sb.toString()
                }
                val m = Regex("^[^,}\\]]+").find(after)
                return m?.value?.trim()
            }
            idx = body.indexOf(token, idx + 1)
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
            // parano1d-stratum-v1: params[0] is a JSON object.
            val objBody = extractObject(line, "params")
            if (objBody != null) {
                return when (method) {
                    "mining.notify" -> notifyObject(objBody)
                        ?: Event.UnsupportedJob(method, objBody.take(200))
                    "mining.pause" -> Event.Paused(
                        objString(objBody, "reason") ?: "paused by pool",
                    )
                    "mining.resume" -> Event.Resumed
                    else -> Event.Ignored
                }
            }
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
        // Subscribe result object (parano1d-stratum-v1).
        val resultObj = extractObject(line, "result")
        if (resultObj != null && objString(resultObj, "protocol") != null) {
            return Event.SubscribedV1(
                sessionNamespace = objString(resultObj, "session_namespace").orEmpty(),
                nonceBits = objString(resultObj, "nonce_bits")?.toIntOrNull() ?: 64,
                protocol = objString(resultObj, "protocol").orEmpty(),
            )
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
        "mining.notify" -> notifyLegacyArray(params) ?: Event.UnsupportedJob(method, params.toString())
        "mining.set_difficulty" -> Event.Difficulty(params.firstOrNull()?.toDoubleOrNull() ?: 0.0)
        else -> Event.Ignored
    }

    /** parano1d-stratum-v1 notify — params[0] is an object. */
    private fun notifyObject(body: String): Event.Notify? {
        val jobId = objString(body, "job_id") ?: return null
        val fields = objString(body, "pow_fields_hex") ?: return null
        if (!HEX_256B.matches(fields) && !HEX_128B.matches(fields)) return null
        val shareTarget = objString(body, "share_target_hex")?.lowercase()
        val blockTarget = objString(body, "block_target_hex")?.lowercase()
        val target = listOf(shareTarget, blockTarget)
            .firstOrNull { it != null && HEX_64.matches(it) }
            ?: MAX_TARGET
        val clean = objString(body, "clean")?.lowercase() == "true"
        return Event.Notify(
            jobId = jobId,
            powFieldsHex = fields.lowercase(),
            targetLeHex = target,
            height = objString(body, "height")?.toLongOrNull() ?: 0L,
            cleanJobs = clean,
            laneHiHex = objString(body, "nonce_prefix_hex")?.lowercase()
                ?.takeIf { it.length == 16 },
            workDomainIdHex = objString(body, "work_domain_id")?.lowercase()
                ?.takeIf { HEX_64.matches(it) },
            expiresInSeconds = objString(body, "expires_in_seconds")?.toLongOrNull() ?: 60L,
        )
    }

    /** Legacy SV1 array-shaped notify (alternative pools). */
    private fun notifyLegacyArray(params: List<String>): Event.Notify? {
        if (params.size < 2) return null
        var fields: String? = null
        var target: String? = null
        for (raw in params) {
            val v = unquote(raw)
            if (fields == null && (HEX_256B.matches(v) || HEX_128B.matches(v))) fields = v.lowercase()
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
            laneHiHex = null,
            workDomainIdHex = null,
            expiresInSeconds = 60L,
        )
    }

    /** Default ultra-loose target when the pool sends none. */
    const val MAX_TARGET: String = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

    sealed interface Event {
        data object Ignored : Event

        /** parano1d-stratum-v1 subscribe result. */
        data class SubscribedV1(
            val sessionNamespace: String,
            val nonceBits: Int,
            val protocol: String,
        ) : Event

        /** Legacy SV1 subscribe result. */
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
            /** 8-byte session nonce prefix (hex) — high half of the 128-bit lane. */
            val laneHiHex: String?,
            /** work_domain_id echoed back in mining.submit (64 hex). */
            val workDomainIdHex: String?,
            val expiresInSeconds: Long,
        ) : Event

        data class Paused(
            val reason: String,
        ) : Event

        data object Resumed : Event

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
    laneHiHex = event.laneHiHex,
    workDomainIdHex = event.workDomainIdHex,
)
