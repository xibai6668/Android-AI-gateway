package dev.aigw.core.provider.trae

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 上游 HTTP 的小工具：只用到 Android 上同样存在的 `HttpURLConnection`。 */
internal object TraeHttp {

    const val CONNECT_TIMEOUT_MS = 30_000

    /** 短 JSON 请求的总超时兜底。 */
    const val READ_TIMEOUT_MS = 120_000

    /** SSE 流按「两次读到内容之间的空闲时长」超时，而不是整流时长。 */
    const val STREAM_IDLE_TIMEOUT_MS = 300_000

    /** 响应体读取上限，防止上游异常时撑爆内存。 */
    const val MAX_BODY_BYTES = 1 shl 20

    fun post(
        url: String,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        applyHeaders: (HttpURLConnection) -> Unit,
    ): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            // 上游若对 SSE/JSON 做 gzip，流式解析会错乱，显式要求不压缩
            setRequestProperty("Accept-Encoding", "identity")
        }
        applyHeaders(conn)
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
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

    private fun readLimited(input: InputStream, limit: Int): String {
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

/** 把毫秒级时间戳归一化为 Unix 秒：1.7e12 量级判定为毫秒。 */
internal fun normalizeExpireAt(raw: Long): Long = if (raw > 1e12) raw / 1000 else raw

internal fun nowSeconds(): Long = System.currentTimeMillis() / 1000
