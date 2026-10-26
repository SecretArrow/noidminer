package org.secretarrow.noidminer.data.stratum

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocketFactory

/**
 * Stratum (SV1) client over TCP or TLS with auto-reconnect.
 *
 * Message flow:
 *  → mining.subscribe(agent)     ← subscriptions, extraNonce1, extraNonce2Size
 *  → mining.authorize(user)      ← true/false
 *  ← mining.notify(jobId, seed, ..., clean)
 *  ← mining.set_difficulty(difficulty)
 *  → mining.submit(user, jobId, extraNonce2, nTime, nonce)
 *
 * NOID-specific job decoding is intentionally forgiving: the notifier looks
 * for a 256-hex-char pow_fields blob inside the params (Parano1d-style) and
 * falls back to standard BTC-style field packing. Raw notifications are
 * logged at debug level so pool quirks are diagnosable from the Log screen.
 */
class StratumClient(
    private val url: String,
    private val userAgent: String = "NoidMiner/1.0",
) {
    data class Line(
        val raw: String,
    )

    private val _lines = MutableSharedFlow<Line>(extraBufferCapacity = 256)
    val lines: SharedFlow<Line> = _lines.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _authorized = MutableStateFlow(false)
    val authorized: StateFlow<Boolean> = _authorized.asStateFlow()

    private val idCounter = AtomicLong(1)
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    @Volatile
    private var shouldRun = false

    private var worker: Thread? = null

    val extraNonce1: AtomicHolder<String?> = AtomicHolder(null)
    val extraNonce2Size: AtomicHolder<Int> = AtomicHolder(4)

    class AtomicHolder<T>(
        @Volatile var value: T,
    )

    /** Starts the background connection loop. */
    fun start(onLine: (String) -> Unit) {
        if (shouldRun) return
        shouldRun = true
        worker = Thread {
            while (shouldRun) {
                try {
                    connectAndPump(onLine)
                } catch (t: Throwable) {
                    if (shouldRun) {
                        _connected.value = false
                        emitLine("error", "pool connection: ${t.message}")
                    }
                }
                if (shouldRun) {
                    try {
                        Thread.sleep(RECONNECT_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
        }.apply {
            name = "stratum-client"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        shouldRun = false
        runCatching { socket?.close() }
        worker?.interrupt()
        worker = null
        _connected.value = false
        _authorized.value = false
    }

    /** Sends mining.authorize for [user]. */
    fun authorize(user: String): Boolean = sendRequest(
        "mining.authorize",
        JSONArray().put(user),
    )

    /**
     * parano1d-stratum-v1 submit: `[job_id, nonce128hex, work_domain_id]`.
     * Verified live against InnovLab/RPlant — params[0] is the job id (the
     * connection is already authorized), the nonce is the full 16-byte
     * little-endian lane, and work_domain_id must echo the notify value.
     */
    fun submitV1(
        user: String,
        jobId: String,
        nonce128Hex: String,
        workDomainIdHex: String,
    ): Boolean = sendRequest(
        "mining.submit",
        JSONArray()
            .put(jobId)
            .put(nonce128Hex)
            .put(workDomainIdHex),
    )

    /** Sends mining.submit with the given share components. */
    fun submitShare(
        user: String,
        jobId: String,
        extraNonce2: String,
        nTime: String,
        nonce: String,
    ): Boolean = sendRequest(
        "mining.submit",
        JSONArray()
            .put(user)
            .put(jobId)
            .put(extraNonce2)
            .put(nTime)
            .put(nonce),
    )

    private fun sendRequest(method: String, params: JSONArray): Boolean {
        val id = idCounter.getAndIncrement()
        val payload = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params)
        return try {
            synchronized(this) {
                writer?.write(payload.toString())
                writer?.newLine()
                writer?.flush()
            }
            true
        } catch (t: Throwable) {
            emitLine("error", "send failed: ${t.message}")
            false
        }
    }

    private fun connectAndPump(onLine: (String) -> Unit) {
        val parsed = parseUrl(url)
        val sock = when (parsed.scheme) {
            "stratum+tls", "stratum+ssl", "tls" -> {
                val base = Socket()
                base.connect(InetSocketAddress(parsed.host, parsed.port), CONNECT_TIMEOUT_MS)
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(base, parsed.host, parsed.port, true)
            }
            else -> {
                val s = Socket()
                s.connect(InetSocketAddress(parsed.host, parsed.port), CONNECT_TIMEOUT_MS)
                s
            }
        }.apply {
            soTimeout = READ_TIMEOUT_MS
            keepAlive = true
            tcpNoDelay = true
        }
        socket = sock
        reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(sock.outputStream, Charsets.UTF_8))
        _connected.value = true

        // Handshake: subscribe then let the caller authorize when ready.
        sendRequest(
            "mining.subscribe",
            JSONArray().put(userAgent),
        )
        while (shouldRun && !sock.isClosed) {
            val line = reader?.readLine() ?: break
            if (line.isBlank()) continue
            _lines.tryEmit(Line(line))
            onLine(line)
        }
        _connected.value = false
        _authorized.value = false
    }

    private fun emitLine(level: String, message: String) {
        _lines.tryEmit(Line("""{"level":"$level","message":"$message"}"""))
    }

    data class ParsedUrl(
        val scheme: String,
        val host: String,
        val port: Int,
    )

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val RECONNECT_MS = 3_000L

        /** Parses `stratum+tcp://host:port`, `stratum+tls://…`, `host:port`. */
        fun parseUrl(url: String): ParsedUrl {
            val trimmed = url.trim()
            val schemeMatch = Regex("^([a-zA-Z0-9+]+)://").find(trimmed)
            val scheme = schemeMatch?.groupValues?.get(1) ?: "stratum+tcp"
            val rest = trimmed
                .removePrefix("${schemeMatch?.groupValues?.get(1) ?: ""}://")
                .removePrefix("://")
            val hostPort = rest.substringBefore('/')
            val host = hostPort.substringBefore(':').ifBlank { hostPort }
            val port = hostPort.substringAfter(':', "").toIntOrNull() ?: 3333
            return ParsedUrl(scheme, host, port.coerceIn(1, 65535))
        }
    }
}
