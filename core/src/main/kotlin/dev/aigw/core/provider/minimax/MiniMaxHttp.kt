package dev.aigw.core.provider.minimax

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 统一请求层：按 MiniMax 网页端的方式拼伪装 query 与签名头。
 *
 * 每条请求 = `API_BASE + endpoint + "?" + 伪装query`，头里带 `token` + 三个签名头。
 * query 的字段顺序参与签名（对齐 xiaoY233 国内版实现），不要调整。
 */
internal object MiniMaxHttp {

    const val CONNECT_TIMEOUT_MS = 30_000
    const val READ_TIMEOUT_MS = 300_000
    const val MAX_BODY_BYTES = 1 shl 20

    const val HEADER_TOKEN = "token"
    const val HEADER_TIMESTAMP = "x-timestamp"
    const val HEADER_SIGNATURE = "x-signature"
    const val HEADER_YY = "yy"

    /** 伪装 query；`deviceId` 为空时跳过该参数（device_id 参与 query 的有无也在签名范围内）。 */
    fun queryString(account: MiniMaxAccount, nowMillis: Long): String {
        val params = linkedMapOf(
            "device_platform" to MiniMaxConstants.DEVICE_PLATFORM,
            "biz_id" to MiniMaxConstants.BIZ_ID,
            "app_id" to MiniMaxConstants.APP_ID,
            "version_code" to MiniMaxConstants.VERSION_CODE,
            "uuid" to account.userId,
            "device_id" to account.deviceId,
            "os_name" to MiniMaxConstants.OS_NAME,
            "browser_name" to MiniMaxConstants.BROWSER_NAME,
            "device_memory" to MiniMaxConstants.DEVICE_MEMORY,
            "cpu_core_num" to MiniMaxConstants.CPU_CORE_NUM,
            "browser_language" to MiniMaxConstants.BROWSER_LANGUAGE,
            "browser_platform" to MiniMaxConstants.BROWSER_PLATFORM,
            "user_id" to account.userId,
            "screen_width" to MiniMaxConstants.SCREEN_WIDTH,
            "screen_height" to MiniMaxConstants.SCREEN_HEIGHT,
            "unix" to nowMillis.toString(),
            "lang" to MiniMaxConstants.LANG,
            "token" to account.token,
        )
        return params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { (key, value) ->
                "${MiniMaxSign.encodeURIComponent(key)}=${MiniMaxSign.encodeURIComponent(value)}"
            }
    }

    fun signedHeaders(account: MiniMaxAccount, pathWithQuery: String, bodyJson: String, nowMillis: Long): Map<String, String> {
        val ts = (nowMillis / 1000).toString()
        return mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Cache-Control" to "no-cache",
            "Content-Type" to "application/json",
            "Origin" to MiniMaxConstants.API_BASE,
            "Referer" to "${MiniMaxConstants.API_BASE}/",
            "User-Agent" to
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
            HEADER_TOKEN to account.token,
            HEADER_TIMESTAMP to ts,
            HEADER_SIGNATURE to MiniMaxSign.xSignature(ts, bodyJson),
            HEADER_YY to MiniMaxSign.yy(pathWithQuery, bodyJson, ts),
        )
    }

    /** 普通请求（GET/POST/DELETE），一次性读完响应体。返回 (status, body)。 */
    fun request(
        apiBase: String,
        account: MiniMaxAccount,
        method: String,
        endpoint: String,
        bodyJson: String?,
        nowMillis: Long,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): Pair<Int, String> {
        val query = queryString(account, nowMillis)
        val pathWithQuery = "$endpoint?$query"
        val url = "$apiBase$pathWithQuery"
        val headers = signedHeaders(account, pathWithQuery, bodyJson ?: "{}", nowMillis)
        val conn = try {
            open(url, method, headers, bodyJson, readTimeoutMs)
        } catch (e: Exception) {
            throw MiniMaxApiException(0, e.message.orEmpty())
        }
        return try {
            val status = conn.responseCode
            status to readBody(conn)
        } catch (e: Exception) {
            throw MiniMaxApiException(0, e.message.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    /** 发起流式请求（SSE）。非 2xx 时 [MiniMaxStreamCall.stream] 为 null，错误体在 [errorBody]。 */
    fun openStream(
        apiBase: String,
        account: MiniMaxAccount,
        endpoint: String,
        bodyJson: String,
        nowMillis: Long,
    ): MiniMaxStreamCall {
        val query = queryString(account, nowMillis)
        val pathWithQuery = "$endpoint?$query"
        val url = "$apiBase$pathWithQuery"
        val headers = signedHeaders(account, pathWithQuery, bodyJson, nowMillis) +
            mapOf("Accept" to "text/event-stream")
        val conn = try {
            open(url, "POST", headers, bodyJson, READ_TIMEOUT_MS)
        } catch (e: Exception) {
            throw MiniMaxApiException(0, e.message.orEmpty())
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            throw MiniMaxApiException(0, e.message.orEmpty())
        }
        val contentType = conn.contentType.orEmpty()
        if (status !in 200..299) {
            val errorBody = runCatching { readBody(conn) }.getOrDefault("")
            conn.disconnect()
            return MiniMaxStreamCall(status, errorBody, null, contentType, conn)
        }
        return MiniMaxStreamCall(status, "", conn.inputStream, contentType, conn)
    }

    private fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyJson: String?,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            // SSE/JSON 做了 gzip 会让流式解析错乱，显式要求不压缩
            setRequestProperty("Accept-Encoding", "identity")
        }
        for ((key, value) in headers) conn.setRequestProperty(key, value)
        if (bodyJson != null) {
            conn.doOutput = true
            val bytes = bodyJson.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        return conn
    }

    fun readBody(conn: HttpURLConnection, limit: Int = MAX_BODY_BYTES): String {
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream ?: return ""
        }
        return stream.use { readLimited(it, limit) }
    }

    fun readLimited(input: InputStream, limit: Int): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (buffer.size() + read > limit) {
                buffer.write(chunk, 0, limit - buffer.size())
                break
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString("UTF-8")
    }
}

/** 上游调用异常：HTTP 层失败（含网络连不上）都包成它，message 可直接展示。 */
class MiniMaxApiException(val status: Int, message: String) : Exception(message)

/**
 * 一次流式请求的连接。`contentType` 用于区分 SSE 与「HTTP 200 + 业务错误 JSON」。
 */
class MiniMaxStreamCall internal constructor(
    val status: Int,
    val errorBody: String,
    val stream: InputStream?,
    val contentType: String,
    private val connection: HttpURLConnection,
) {
    val isEventStream: Boolean get() = contentType.contains("text/event-stream", ignoreCase = true)

    fun close() {
        runCatching { stream?.close() }
        runCatching { connection.disconnect() }
    }
}
