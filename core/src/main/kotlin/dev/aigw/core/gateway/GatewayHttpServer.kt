package dev.aigw.core.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.CallStatus
import dev.aigw.core.util.CappedStringBuilder
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 网关的 HTTP 层。
 *
 * 对 provider 无感知：解析模型名的 `provider/` 前缀 → 选号 → 调 `provider.openChat` →
 * 原样透传（流式）或直接返回（非流式）。协议差异全部封在各 provider 内部。
 */
class GatewayHttpServer(
    private val engine: GatewayEngine,
    host: String?,
    port: Int,
) : NanoHTTPD(host, port) {

    fun listeningAddress(): String = (hostname ?: "0.0.0.0") + ":" + listeningPort

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        val response = try {
            when {
                session.method == NanoHTTPD.Method.OPTIONS -> preflight()
                session.method == NanoHTTPD.Method.POST && uri == "/v1/chat/completions" -> chatCompletions(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/models" -> models(session)
                session.method == NanoHTTPD.Method.GET && uri == "/healthz" ->
                    NanoHTTPD.newFixedLengthResponse(SimpleStatus(200, "OK"), "text/plain; charset=utf-8", "ok")
                session.method == NanoHTTPD.Method.GET && uri == "/authorize" -> authorize(session)
                else -> errorResponse(404, "not_found", "未知路径：$uri")
            }
        } catch (e: Exception) {
            engine.logError("处理 $uri 失败：${e.message}")
            errorResponse(500, "internal_error", e.message ?: "网关内部错误")
        }
        return response.withCors()
    }

    /**
     * 浏览器里跑的客户端（各种 Web UI）会先发 OPTIONS 预检，不答应就会被浏览器拦掉，
     * 界面上看起来就是「连不上」。
     */
    private fun preflight(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(SimpleStatus(204, "No Content"), "text/plain; charset=utf-8", "")

    /** 流式响应自己写头（已含 CORS），这里的 addHeader 对它无效但无害。 */
    private fun NanoHTTPD.Response.withCors(): NanoHTTPD.Response = apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "*")
    }

    // ------------------------------------------------------------------ 端点

    private fun models(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized()
        return json(200, OpenAiApi.modelList(engine.models()))
    }

    private fun chatCompletions(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized()

        val body = readBody(session)
        if (body.isEmpty()) return errorResponse(400, "invalid_request", "请求体为空")
        val peek = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return errorResponse(400, "invalid_request", "请求体不是合法 JSON")

        val settings = engine.settings()
        val streaming = peek.get("stream")?.asBoolean ?: false
        val requestedModel = peek.get("model")?.asString.orEmpty()

        val route = engine.resolveRoute(requestedModel)
            ?: return errorResponse(400, "invalid_request", "缺少 model 参数")
        val provider = engine.registry.get(route.providerId)
            ?: return errorResponse(400, "invalid_request", "未知供应商：${route.providerId}")
        // 目录里查不到也放行，交给上游判定；避免目录拉取失败时误伤合法模型名
        val resolvedModel = provider.resolveModel(route.model)
        val prepared = withModel(body, resolvedModel)

        val startedAt = System.currentTimeMillis()
        val tried = HashSet<String>()
        var lastError = ""

        var attempt = 0
        while (attempt < settings.maxRotate.coerceAtLeast(1)) {
            attempt++
            val picked = engine.pool.pick(route.providerId, tried) ?: break
            tried += picked.uid

            var account = picked
            try {
                val refreshed = provider.refreshAccount(account, settings.refreshSkewSeconds)
                if (refreshed != null) {
                    engine.pool.saveAccount(refreshed)
                    account = refreshed
                }
            } catch (e: Exception) {
                engine.pool.noteError(route.providerId, account.uid, settings.errorThreshold, settings.errorCooldownMillis)
                lastError = e.message ?: "刷新凭证失败"
                continue
            }

            val call: ChatCall = try {
                provider.openChat(account, prepared)
            } catch (e: Exception) {
                engine.pool.noteError(route.providerId, account.uid, settings.errorThreshold, settings.errorCooldownMillis)
                lastError = e.message ?: "上游连接失败"
                continue
            }

            // 流内业务错误（如 Loomy 的 HTTP 200 + 业务码）
            val failure = call.failure
            if (failure != null) {
                call.close()
                if (failure.kind == ErrorKind.CLIENT) {
                    log("请求被上游拒绝（${route.providerId}/${account.nickname}）：${failure.message}")
                    return errorResponse(400, "upstream_rejected", failure.message)
                }
                engine.applyCooling(route.providerId, account.uid, failure.kind, failure.message)
                lastError = failure.message
                continue
            }

            if (call.status >= 400) {
                val error = provider.classify(call.status, call.errorBody)
                call.close()
                if (error.kind == ErrorKind.CLIENT) {
                    // 客户端问题（模型名/参数不对）不该连累账号，直接回给调用方
                    log("请求被上游拒绝（${route.providerId}/${account.nickname}）：${error.message}")
                    return errorResponse(400, "upstream_rejected", error.message)
                }
                engine.applyCooling(route.providerId, account.uid, error.kind, "上游 HTTP ${call.status}")
                lastError = error.message
                continue
            }

            if (streaming) {
                val stream = call.stream
                if (stream == null) {
                    call.close()
                    lastError = "上游未返回流"
                    continue
                }
                engine.pool.noteSuccess(route.providerId, account.uid)
                return streamResponse(stream, call, route.providerId, resolvedModel, account.uid, account.nickname, startedAt, body)
            }

            val aggregated = call.aggregated
            call.close()
            if (aggregated == null) {
                lastError = "上游返回的内容为空"
                continue
            }
            engine.pool.noteSuccess(route.providerId, account.uid)
            return aggregatedResponse(aggregated, route.providerId, resolvedModel, account.uid, account.nickname, startedAt, body)
        }

        val reason = if (lastError.isEmpty()) "账号池中没有可用账号（冷却中或已停用）" else "全部账号不可用：$lastError"
        return errorResponse(503, "no_healthy_account", reason)
    }

    // ------------------------------------------------------------------ 响应

    /** 流式：逐行透传上游已转好的 OpenAI SSE，同时抽取 usage 用于记录。 */
    private fun streamResponse(
        stream: InputStream,
        call: ChatCall,
        providerId: String,
        model: String,
        uid: String,
        nickname: String,
        startedAt: Long,
        requestBody: String,
    ): NanoHTTPD.Response {
        val id = newCompletionId()
        return SseResponse { writer ->
            // 边收边截：记录只留前 MAX_LOGGED_BODY 字符，避免长回复把整段内容留在堆里
            val collected = CappedStringBuilder(MAX_LOGGED_BODY)
            var usage: JsonObject? = null
            var pointsConsumed = 0L
            var status = CallStatus.SUCCESS
            var failure = ""
            try {
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        // 先把行原样透传，再看一眼统计：解析绝不能影响转发。
                        // 上游 chunk 里 `"usage": null` 这类字段用 Gson 裸转换是会抛异常的，
                        // 一旦抛在转发循环里，整条流就断了，客户端只看到「连接断开」。
                        writer.write(line)
                        writer.write("\n")
                        val chunk = runCatching { parseChunk(line) }.getOrNull() ?: continue
                        chunk.usage?.let { usage = it }
                        if (chunk.pointsConsumed > 0) pointsConsumed = chunk.pointsConsumed
                        chunk.error?.let {
                            status = CallStatus.FAILED
                            failure = it
                        }
                        collected.append(chunk.content)
                    }
                }
            } catch (e: IOException) {
                // 客户端中途断开是最常见原因，不算账号故障
                status = CallStatus.ABORTED
                failure = e.message ?: "连接中断"
            } catch (e: Exception) {
                // 网关自己处理流出错：不能混进「客户端断开」里，否则线上完全看不到根因
                status = CallStatus.FAILED
                failure = "网关转发流出错：${e.message}"
                engine.logError("转发 $providerId 的流失败：$e")
            } finally {
                call.close()
                if (status == CallStatus.SUCCESS) {
                    engine.pool.noteSuccess(providerId, uid)
                    engine.refreshCreditsSoon(providerId, uid)
                }
                recordCall(id, providerId, model, uid, nickname, true, status, usage, pointsConsumed, failure, collected.content(), requestBody, startedAt)
            }
        }
    }

    /** 非流式：provider 已聚合完毕，直接返回其 completion。 */
    private fun aggregatedResponse(
        aggregated: String,
        providerId: String,
        model: String,
        uid: String,
        nickname: String,
        startedAt: Long,
        requestBody: String,
    ): NanoHTTPD.Response {
        val payload = runCatching { JsonParser.parseString(aggregated).asJsonObject }.getOrNull()
        val usage = payload?.objOrNull("usage")
        val content = payload?.arrayOrNull("choices")?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?.objOrNull("message")?.get("content")?.asString.orEmpty()
        recordCall(
            newCompletionId(), providerId, model, uid, nickname, false,
            CallStatus.SUCCESS, usage, pointsConsumedOf(payload ?: JsonObject(), usage), "", content, requestBody, startedAt,
        )
        engine.refreshCreditsSoon(providerId, uid)
        return json(200, aggregated)
    }

    private fun authorize(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val query = session.queryParameterString.orEmpty()
        val callbackUrl = "http://127.0.0.1:${engine.settings().port}/authorize" +
            if (query.isEmpty()) "" else "?$query"
        val result = engine.completeWebLogin(TRAE_PROVIDER_ID, callbackUrl)
        val html = if (result.ok) {
            callbackPage("登录成功", "账号 ${result.nickname.ifEmpty { result.uid }} 已添加，可关闭本页面。")
        } else {
            callbackPage("登录失败", result.error)
        }
        return NanoHTTPD.newFixedLengthResponse(SimpleStatus(200, "OK"), "text/html; charset=utf-8", html)
    }

    // ------------------------------------------------------------------ 工具

    private fun authorized(session: NanoHTTPD.IHTTPSession): Boolean {
        val settings = engine.settings()
        if (settings.allowNoKey) return true
        val expected = settings.apiKey
        if (expected.isEmpty()) return false
        val header = session.headers["authorization"] ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        return MessageDigest.isEqual(
            header.substring(7).toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun unauthorized(): NanoHTTPD.Response {
        val settings = engine.settings()
        val message = if (settings.apiKey.isEmpty()) {
            "网关已关闭「无 Key 调用」但尚未设置 API Key，请先在 App 里设置"
        } else {
            "缺少或错误的 API Key"
        }
        return errorResponse(401, "invalid_api_key", message)
    }

    /**
     * 读取请求体。
     *
     * NanoHTTPD 自身不处理 `Transfer-Encoding: chunked` 的请求体，如果按「读到 EOF」去读会一直
     * 阻塞到连接关闭，所以这里自己解分块帧。
     */
    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val input: InputStream = session.inputStream
        val out = ByteArrayOutputStream()
        val transferEncoding = session.headers["transfer-encoding"].orEmpty()
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull() ?: -1L

        when {
            transferEncoding.contains("chunked", ignoreCase = true) -> readChunked(input, out)
            declared > 0 -> readExactly(input, out, declared)
            else -> Unit
        }
        return out.toString("UTF-8")
    }

    private fun readExactly(input: InputStream, out: ByteArrayOutputStream, length: Long) {
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0 && out.size() < MAX_BODY_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    /** 解 `大小\r\n数据\r\n` 直到 0 号结束块。 */
    private fun readChunked(input: InputStream, out: ByteArrayOutputStream) {
        val buffer = ByteArray(8192)
        while (out.size() < MAX_BODY_BYTES) {
            val header = readAsciiLine(input) ?: return
            // 允许 `1a;ext=1` 这种带扩展的块头
            val size = header.substringBefore(';').trim().toIntOrNull(16) ?: return
            if (size <= 0) return
            var remaining = size
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) return
                out.write(buffer, 0, read)
                remaining -= read
            }
            readAsciiLine(input)
        }
    }

    /** 逐字节读一行（不能预读，否则会把后续块数据吞掉）。 */
    private fun readAsciiLine(input: InputStream): String? {
        val line = StringBuilder(128)
        while (line.length <= 1024) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
        return null
    }

    /** 从一行 SSE 里取统计信息。纯旁路：解析失败返回 null 即可，转发已经先做完了。 */
    private fun parseChunk(line: String): ChunkInfo? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        val obj = JsonParser.parseString(payload).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val delta = obj.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()?.objOrNull("delta")
        val usage = obj.objOrNull("usage")
        return ChunkInfo(
            usage = usage,
            error = obj.objOrNull("error")?.stringOrNull("message"),
            content = delta?.stringOrNull("content").orEmpty(),
            // 绝大多数 chunk 不带积分字段：字符串预检跳过十几次 JSON 查找
            pointsConsumed = if (payload.contains("points")) pointsConsumedOf(obj, usage) else 0L,
        )
    }

    private data class ChunkInfo(val usage: JsonObject?, val error: String?, val content: String, val pointsConsumed: Long)

    /**
     * 从流 chunk 里取「本次积分消耗」；字段清单照官方 Web 客户端的 turnPoints 解析
     * （顶层与 usage 里的 points_consumed / cost_points / consumed_points / points / total_points，
     * 先字段后来源，取第一个可解析的）。其余供应商无此字段，返回 0。
     */
    internal fun pointsConsumedOf(chunk: JsonObject, usage: JsonObject?): Long {
        for (key in listOf("points_consumed", "cost_points", "consumed_points", "points", "total_points")) {
            for (source in listOfNotNull(chunk, usage)) {
                val v = source.get(key) ?: continue
                if (v.isJsonNull) continue
                val n = runCatching { v.asLong }.getOrNull() ?: continue
                return n
            }
        }
        return 0L
    }

    private fun withModel(body: String, model: String): String {
        if (model.isEmpty()) return body
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return body
        obj.addProperty("model", model)
        return obj.toString()
    }

    private fun json(status: Int, body: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(SimpleStatus(status, statusText(status)), "application/json; charset=utf-8", body)

    private fun errorResponse(status: Int, code: String, message: String): NanoHTTPD.Response =
        json(status, OpenAiApi.errorBody(code, message))

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private fun newCompletionId(): String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)

    private fun recordCall(
        id: String,
        providerId: String,
        model: String,
        uid: String,
        nickname: String,
        streaming: Boolean,
        status: CallStatus,
        usage: JsonObject?,
        pointsConsumed: Long,
        error: String,
        responseText: String,
        requestBody: String,
        startedAt: Long,
    ) {
        engine.callLogStore.add(
            CallRecord(
                id = id,
                startedAtMillis = startedAt,
                providerId = providerId,
                model = model,
                accountUid = uid,
                accountNickname = nickname,
                streaming = streaming,
                status = status,
                httpStatus = 200,
                promptTokens = usage?.get("prompt_tokens")?.asLong ?: 0L,
                completionTokens = usage?.get("completion_tokens")?.asLong ?: 0L,
                totalTokens = usage?.get("total_tokens")?.asLong ?: 0L,
                pointsConsumed = pointsConsumed,
                durationMillis = System.currentTimeMillis() - startedAt,
                error = error,
                requestBody = requestBody.take(MAX_LOGGED_BODY),
                responseBody = responseText.take(MAX_LOGGED_BODY),
            ),
        )
        // 每写入一条就顺带检查跳天清理：网关可能持续只收少量请求，
        // 单靠启动那次清理会在长期挂机时漏掉。
        engine.autoPurgeIfDue()
    }

    private fun log(text: String) = engine.log(text)

    companion object {
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_LOGGED_BODY = 8 * 1024

        /** `/authorize` 回调固定归 Trae（其它供应商的登录不经过网关端口）。 */
        const val TRAE_PROVIDER_ID = "trae"
    }
}

/** 自建状态对象，避免依赖 NanoHTTPD 枚举里是否存在某个状态码。 */
internal class SimpleStatus(private val code: Int, private val text: String) : NanoHTTPD.Response.IStatus {
    override fun getRequestStatus(): Int = code
    override fun getDescription(): String = "$code $text"
}

/** 分块编码写出器，每次写出后立即 flush，保证 SSE 实时到达客户端。 */
internal class ChunkWriter(private val out: OutputStream) {

    private var finished = false

    fun write(text: String) = write(text.toByteArray(StandardCharsets.UTF_8))

    /** 心跳线程与读上游的主线程会并发写，加锁避免分块帧被交错打断。 */
    @Synchronized
    fun write(bytes: ByteArray) {
        if (finished || bytes.isEmpty()) return
        out.write(Integer.toHexString(bytes.size).toByteArray(StandardCharsets.US_ASCII))
        out.write(CRLF)
        out.write(bytes)
        out.write(CRLF)
        out.flush()
    }

    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        out.write(ZERO_CHUNK)
        out.flush()
    }

    companion object {
        private val CRLF = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        private val ZERO_CHUNK = "0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    }
}

/** 流式响应：自己做分块编码，避免 NanoHTTPD 默认实现不逐块 flush 导致 SSE 卡顿。 */
internal class SseResponse(
    private val produce: (ChunkWriter) -> Unit,
) : NanoHTTPD.Response(SimpleStatus(200, "OK"), "text/event-stream; charset=utf-8", null, -1) {

    init {
        setKeepAlive(false)
        closeConnection(true)
    }

    public override fun send(outputStream: OutputStream) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Accel-Buffering: no\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        outputStream.write(header.toByteArray(StandardCharsets.US_ASCII))
        outputStream.flush()
        val writer = ChunkWriter(outputStream)

        // 上游长思考时可能几十秒不出任何 chunk，客户端会读超时并把连接判成断开；
        // 定时发 SSE 注释行（按规范客户端会忽略）把空闲连接续上。
        val beats = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "gateway-sse-heartbeat").apply { isDaemon = true }
        }
        beats.scheduleAtFixedRate(
            { runCatching { writer.write(HEARTBEAT) } },
            HEARTBEAT_SECONDS,
            HEARTBEAT_SECONDS,
            TimeUnit.SECONDS,
        )
        try {
            produce(writer)
        } finally {
            beats.shutdownNow()
            runCatching { writer.finish() }
            runCatching { outputStream.flush() }
        }
    }

    private companion object {
        const val HEARTBEAT_SECONDS = 15L
        val HEARTBEAT = ": keep-alive\n\n".toByteArray(StandardCharsets.US_ASCII)
    }
}

private fun callbackPage(title: String, message: String): String = """
    <!doctype html>
    <html lang="zh-CN"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>$title</title>
    <style>
      body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
           font-family:system-ui,-apple-system,"PingFang SC",sans-serif;background:#f6f8fb;color:#1b1c1e}
      .card{max-width:22rem;padding:2rem;border-radius:1.75rem;background:#fff;text-align:center;
            box-shadow:0 8px 30px rgba(0,0,0,.06)}
      h1{font-size:1.25rem;margin:0 0 .75rem}
      p{font-size:.95rem;line-height:1.6;color:#5a5f66;margin:0}
    </style></head>
    <body><div class="card"><h1>$title</h1><p>$message</p></div></body></html>
""".trimIndent()
