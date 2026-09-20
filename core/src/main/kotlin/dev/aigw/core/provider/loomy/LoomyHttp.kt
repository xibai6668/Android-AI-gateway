package dev.aigw.core.provider.loomy

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 上游 HTTP 的小工具：只用到 Android 上同样存在的 `HttpURLConnection`。 */
internal object LoomyHttp {

    const val CONNECT_TIMEOUT_MS = 30_000
    const val READ_TIMEOUT_MS = 120_000

    /** 响应体读取上限，防止上游异常时撑爆内存。 */
    const val MAX_BODY_BYTES = 1 shl 20

    fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            // 上游若对 SSE/JSON 做 gzip，流式解析会错乱，显式要求不压缩
            setRequestProperty("Accept-Encoding", "identity")
        }
        for ((key, value) in headers) conn.setRequestProperty(key, value)
        if (body != null) {
            conn.doOutput = true
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        return conn
    }

    /** 读取响应体（非 2xx 时自动落到 errorStream）。 */
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

/**
 * 一次普通 JSON 请求的结果。
 *
 * 网络层失败（连不上/超时）也会被包成 [LoomyApiException]（kind = NETWORK），
 * 让上层只需要处理一种异常类型。
 */
internal fun httpJson(
    url: String,
    method: String,
    headers: Map<String, String>,
    body: String? = null,
    connectTimeoutMs: Int = LoomyHttp.CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = LoomyHttp.READ_TIMEOUT_MS,
): Pair<Int, String> {
    val conn = try {
        LoomyHttp.open(url, method, headers, body, connectTimeoutMs, readTimeoutMs)
    } catch (e: Exception) {
        throw LoomyApiException(0, LoomyErrorKind.NETWORK, e.message.orEmpty())
    }
    return try {
        val status = conn.responseCode
        status to LoomyHttp.readBody(conn)
    } catch (e: Exception) {
        throw LoomyApiException(0, LoomyErrorKind.NETWORK, e.message.orEmpty())
    } finally {
        conn.disconnect()
    }
}

/**
 * 一次流式请求的连接。
 *
 * `status` 非 2xx 时 `stream` 为 null，错误体在 [errorBody] 里。
 * [contentType] 用来识别「HTTP 200 但其实是业务错误 JSON」的情况：
 * 上游对鉴权失败就是 200 + `{"code":"100002"}`，不是 4xx。
 */
class LoomyStreamCall internal constructor(
    val status: Int,
    val errorBody: String,
    val stream: InputStream?,
    val contentType: String,
    private val connection: HttpURLConnection,
) {
    /** 是否是真正的 SSE 流。 */
    val isEventStream: Boolean get() = contentType.contains("text/event-stream", ignoreCase = true)

    fun close() {
        runCatching { stream?.close() }
        runCatching { connection.disconnect() }
    }
}

internal fun openStreamCall(
    url: String,
    headers: Map<String, String>,
    body: String,
    connectTimeoutMs: Int = LoomyHttp.CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = LoomyHttp.READ_TIMEOUT_MS,
): LoomyStreamCall {
    val conn = try {
        LoomyHttp.open(url, "POST", headers, body, connectTimeoutMs, readTimeoutMs)
    } catch (e: Exception) {
        throw LoomyApiException(0, LoomyErrorKind.NETWORK, e.message.orEmpty())
    }
    val status = try {
        conn.responseCode
    } catch (e: Exception) {
        conn.disconnect()
        throw LoomyApiException(0, LoomyErrorKind.NETWORK, e.message.orEmpty())
    }
    val contentType = conn.contentType.orEmpty()
    if (status !in 200..299) {
        val errorBody = runCatching { LoomyHttp.readBody(conn) }.getOrDefault("")
        conn.disconnect()
        return LoomyStreamCall(status, errorBody, null, contentType, conn)
    }
    return LoomyStreamCall(status, "", conn.inputStream, contentType, conn)
}
