package org.secretarrow.noidminer.data.rpc

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal JSON-RPC client for a Parano1d node (SOLO mining).
 * Methods: `paranoid_getBlockTemplate(miner_address)` and
 * `paranoid_submitBlock(template_id, nonce_le_hex)` — identical to the
 * upstream reference miner.
 */
class SoloRpcClient(
    private val rpcUrl: String,
    private val token: String = "",
) {
    data class Template(
        val templateId: String,
        val powFieldsHex: String,
        val nonceFieldIndex: Int,
        val difficultyTargetHex: String,
        val height: Long,
        val expiresInSec: Long,
        val nTxs: Int,
    )

    private var nextId: Long = 1

    /** Blocking call — invoke from a worker thread. */
    @Throws(RpcException::class)
    fun getBlockTemplate(minerAddress: String): Template {
        val params = JSONArray().put(minerAddress)
        val response = call("paranoid_getBlockTemplate", params)
        if (response.has("error") && !response.isNull("error")) {
            throw RpcException(response.optJSONObject("error")?.optString("message").orEmpty())
        }
        val result = response.optJSONObject("result")
            ?: throw RpcException("empty result")
        return Template(
            templateId = result.optString("template_id"),
            powFieldsHex = result.optString("pow_fields_hex"),
            nonceFieldIndex = result.optInt("nonce_field_index", 10),
            difficultyTargetHex = result.optString("difficulty_target_hex"),
            height = result.optLong("height", 0L),
            expiresInSec = result.optLong("expires_in_seconds", 30L),
            nTxs = result.optInt("n_txs", 0),
        )
    }

    /** Blocking call — invoke from a worker thread. */
    @Throws(RpcException::class)
    fun submitBlock(templateId: String, nonceLeHex: String): String {
        val params = JSONArray().put(templateId).put(nonceLeHex)
        val response = call("paranoid_submitBlock", params)
        if (response.has("error") && !response.isNull("error")) {
            val message = response.optJSONObject("error")?.optString("message").orEmpty()
            throw RpcException(message, stale = message.contains("stale", ignoreCase = true))
        }
        return response.optString("result", "ok")
    }

    private fun call(method: String, params: JSONArray): JSONObject {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId++)
            .put("method", method)
            .put("params", params)
        val connection = URL(rpcUrl).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (code !in 200..299) {
                throw RpcException("HTTP $code: ${body.take(200)}")
            }
            JSONObject(body)
        } catch (e: RpcException) {
            throw e
        } catch (t: Throwable) {
            throw RpcException("rpc transport: ${t.message}")
        } finally {
            connection.disconnect()
        }
    }

    class RpcException(
        message: String,
        val stale: Boolean = false,
    ) : Exception(message)
}
