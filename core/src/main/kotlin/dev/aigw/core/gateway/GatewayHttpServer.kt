package dev.aigw.core.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.OpenAiSseAggregator
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
import java.util.concurrent.ScheduledExecutorService
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
                session.method == NanoHTTPD.Method.POST && isGeminiGenerate(uri) -> geminiGenerate(session, uri)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/models" -> models(session)
                session.method == NanoHTTPD.Method.GET && isGeminiModels(uri) -> geminiModels(session)
                session.method == NanoHTTPD.Method.GET && (uri == "/v1/credits" || uri == "/credits" || uri == "/v1/v1/credits") -> credits(session)
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

    /** 聚合额度与配额查询端点：包含各供应商剩余额度及 Antigravity 的四个进度条指标。 */
    private fun credits(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized()
        val refresh = session.parms["refresh"] == "true"
        val providers = engine.registry.all().map { it.id }.distinct()
        val root = JsonObject()
        val providerArray = com.google.gson.JsonArray()

        // 使用并发并行拉取各供应商，避免阻塞 NanoHTTPD 单连接超时；复用引擎的共享线程池
        val executor = engine.ioExecutor()
        val futures = providers.map { pid ->
            executor.submit<JsonObject> {
                val pObj = JsonObject()
                pObj.addProperty("id", pid)
                val pDisplayName = engine.registry.get(pid)?.displayName ?: pid
                pObj.addProperty("displayName", pDisplayName)

                val accounts = engine.accounts(pid)
                val accArray = com.google.gson.JsonArray()
                var totalCredits = 0L
                var creditsKnown = false

                for (acc in accounts) {
                    if (refresh) {
                        try {
                            engine.refreshCredits(pid, acc.uid)
                        } catch (_: Exception) {}
                    }
                    val current = engine.pool.status(pid, acc.uid) ?: acc
                    val aObj = JsonObject()
                    aObj.addProperty("uid", current.uid)
                    aObj.addProperty("nickname", current.nickname)
                    aObj.addProperty("credits", current.credits)
                    aObj.addProperty("creditsKnown", current.creditsKnown)
                    aObj.addProperty("detail", current.detail)
                    aObj.addProperty("enabled", current.enabled)
                    aObj.addProperty("disabled", current.disabled)

                    if (current.creditsKnown) {
                        totalCredits += current.credits
                        creditsKnown = true
                    }

                    // 额度包 / QuotaPacks
                    val packs = try {
                        engine.creditPacks(pid, current.uid)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val packArray = com.google.gson.JsonArray()
                    for (pack in packs) {
                        val pkObj = JsonObject()
                        pkObj.addProperty("name", pack.name)
                        pkObj.addProperty("group", pack.group)
                        pkObj.addProperty("limit", pack.limit)
                        pkObj.addProperty("used", pack.used)
                        pkObj.addProperty("remain", pack.remain)
                        pkObj.addProperty("expireAt", pack.expireAt)
                        packArray.add(pkObj)
                    }
                    aObj.add("packs", packArray)
                    accArray.add(aObj)
                }
                pObj.addProperty("totalCredits", totalCredits)
                pObj.addProperty("creditsKnown", creditsKnown)
                pObj.add("accounts", accArray)
                pObj
            }
        }

        for (future in futures) {
            try {
                providerArray.add(future.get())
            } catch (_: Exception) {}
        }

        root.add("providers", providerArray)
        return json(200, root.toString())
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

        val primaryRoute = engine.resolveRoute(requestedModel)
            ?: return errorResponse(400, "invalid_request", "缺少 model 参数")

        val startedAt = System.currentTimeMillis()
        var lastError = ""
        val routeErrors = ArrayList<String>()
        val failoverCfg = engine.failoverSettings()
        if (engine.verboseLogging()) {
            log("收到请求 /v1/chat/completions：model=$requestedModel stream=$streaming，已解析路由 ${primaryRoute.providerId}/${primaryRoute.model}")
            engine.logVerbose("请求原文", body)
        }

        // 首选路由优先；失败后做同款模型 Failover
        val triedProviders = LinkedHashSet<String>()
        var currentRoute = primaryRoute

        // 候选路由惰性解析并缓存：仅当首选失败需要换家时才计算，且只算一次。
        // 内部会 flush 各供应商的模型目录（网络调用），放在 failover 循环里反复解析会让每次换家都多打一轮上游。
        var cachedCandidates: List<Route>? = null
        fun candidateRoutes(): List<Route> =
            cachedCandidates ?: engine.resolveCandidateRoutes(requestedModel).also { cachedCandidates = it }

        while (true) {
            val providerId = currentRoute.providerId
            triedProviders.add(providerId)
            val provider = engine.registry.get(providerId) ?: break

            // 1. 熔断检查：某供应商连续失败达标后，在静默期内直接跳过，避免拖慢整体响应
            val cb = engine.circuitBreakerOf(providerId)
            if (!cb.allowRequest()) {
                val skipReason = "$providerId(熔断隔离中，直接跳过)"
                log("供应商 [$providerId] 处于熔断隔离状态，跳过此候选...")
                routeErrors.add(skipReason)

                // 寻找下一个候选供应商
                val next = candidateRoutes()
                    .firstOrNull { it.providerId !in triedProviders && engine.pool.pick(it.providerId) != null }
                if (next != null) {
                    log("自动故障转移至下一候选供应商 [${next.providerId}]...")
                    currentRoute = next
                    continue
                }
                break
            }

            val pMetrics = engine.metricsOf(providerId)
            val resolvedModel = provider.resolveModel(currentRoute.model)
            val prepared = withModel(body, resolvedModel)
            val secSettings = engine.securitySettings()
            // 反审核脱敏：仅改写 system 消息内的敏感词（注入零宽空格），完全不触碰 user 输入
            val sanitized = engine.sanitizer.sanitizeOpenAiBody(prepared, resolvedModel, secSettings.sanitizeEnabled)

            val tried = HashSet<String>()
            var attempt = 0
            var providerLastError = ""
            val maxAttempts = if (failoverCfg.enabled) failoverCfg.retry.maxAttempts else settings.maxRotate.coerceAtLeast(1)

            while (attempt < maxAttempts) {
                attempt++
                var picked = engine.pickAccount(currentRoute.providerId, tried, currentRoute.region)
                if (picked == null) {
                    // 若池中有可用账号（如单账号或全部账号已轮过一轮），清空已试列表允许继续完成剩余重试配额
                    if (engine.pickAccount(currentRoute.providerId, emptySet(), currentRoute.region) != null) {
                        tried.clear()
                        picked = engine.pickAccount(currentRoute.providerId, tried, currentRoute.region)
                    }
                }
                if (picked == null) break
                tried += picked.uid

                // 单供应商内重试退避：非首次尝试时计算指数退避 + Jitter 抖动休眠，避免冲击上游
                if (attempt > 1 && failoverCfg.enabled) {
                    val backoff = failoverCfg.retry.computeBackoffMillis(attempt)
                    if (backoff > 0) {
                        if (engine.verboseLogging()) log("供应商 [$providerId] 执行第 $attempt 次尝试，指数退避休眠 ${backoff}ms...")
                        try { Thread.sleep(backoff) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                }

                if (engine.verboseLogging()) {
                    log("第 $attempt 次选号（$providerId）：${picked.nickname.ifEmpty { picked.uid }}，发送模型 $resolvedModel")
                    engine.logVerbose("转发内容", sanitized)
                }

                var account = picked
                try {
                    val refreshed = provider.refreshAccount(account, settings.refreshSkewSeconds)
                    if (refreshed != null) {
                        engine.pool.saveAccount(refreshed)
                        account = refreshed
                    }
                } catch (e: Exception) {
                    providerLastError = e.message ?: "刷新凭证失败"
                    lastError = providerLastError
                    cb.recordFailure()
                    pMetrics.record(false, System.currentTimeMillis() - startedAt, "token_refresh_failed")
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", "刷新凭证异常：$providerLastError")
                    continue
                }

                // 账号级限速与节拍抖动：控制同一账号调用频率，规避上游自动化行为特征检测
                val waitedMs = engine.rateLimiter.acquire(
                    currentRoute.providerId,
                    account.uid,
                    secSettings.minIntervalMillis,
                    secSettings.jitterMillis,
                )
                if (waitedMs > 0 && engine.verboseLogging()) {
                    log("账号限速生效（${currentRoute.providerId}/${account.nickname}）：节拍对齐等待 ${waitedMs}ms")
                }

                val attemptStart = System.currentTimeMillis()
                val call: ChatCall = try {
                    provider.openChat(account, sanitized)
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    providerLastError = e.message ?: "上游连接失败"
                    lastError = providerLastError
                    cb.recordFailure()
                    pMetrics.record(false, elapsed, "connect_exception")
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", "openChat 异常：$providerLastError")
                    continue
                }

                // 流内业务错误（如 Loomy 的 HTTP 200 + 业务码）
                val failure = call.failure
                if (failure != null) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    call.close()
                    cb.recordFailure()
                    pMetrics.record(false, elapsed, failure.kind.name)
                    if (failure.kind == ErrorKind.CLIENT) {
                        log("请求被上游拒绝（${currentRoute.providerId}/${account.nickname}）：${failure.message}")
                        if (engine.verboseLogging()) engine.logVerbose("返回原文", call.errorBody)
                        return errorResponse(400, "upstream_rejected", failure.message)
                    }
                    if (failure.kind == ErrorKind.SESSION_DEAD) {
                        engine.pool.disable(currentRoute.providerId, account.uid, failure.message)
                        log("凭证失效，已禁用账号（${currentRoute.providerId}/${account.nickname}）：${failure.message}")
                        // 凭证失效立即换供应商
                        providerLastError = failure.message
                        lastError = providerLastError
                        break
                    }
                    providerLastError = failure.message
                    lastError = providerLastError
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", call.errorBody)
                    continue
                }

                if (call.status == 0) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    val err = call.errorBody.ifEmpty { "连接上游失败（请检查网络连接或代理设置）" }
                    call.close()
                    cb.recordFailure()
                    pMetrics.record(false, elapsed, "network_status_0")
                    providerLastError = err
                    lastError = providerLastError
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", err)
                    continue
                }

                if (call.status >= 400) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    val error = provider.classify(call.status, call.errorBody)
                    cb.recordFailure()
                    pMetrics.record(false, elapsed, "http_${call.status}")

                    if (engine.verboseLogging()) {
                        log("上游返回 HTTP ${call.status}（$providerId/${account.nickname}）：${error.message}")
                        engine.logVerbose("返回原文", call.errorBody)
                    }
                    call.close()

                    // 错误决策：客户端不可重试错误（400/审核）立即中断返回客户端，不重试不换家
                    val decision = dev.aigw.core.failover.ClassifiedError(
                        statusCode = call.status,
                        errorCode = error.kind.name,
                        message = error.message,
                    ).decide()

                    if (error.kind == ErrorKind.CLIENT || decision == dev.aigw.core.failover.FailoverDecision.ABORT_IMMEDIATELY) {
                        log("请求被上游拒绝（${currentRoute.providerId}/${account.nickname}）：${error.message}")
                        return errorResponse(400, "upstream_rejected", error.message)
                    }

                    if (error.kind == ErrorKind.SESSION_DEAD || decision == dev.aigw.core.failover.FailoverDecision.SWITCH_NEXT_PROVIDER) {
                        if (error.kind == ErrorKind.SESSION_DEAD) {
                            engine.pool.disable(currentRoute.providerId, account.uid, error.message)
                            log("凭证失效，已禁用账号（${currentRoute.providerId}/${account.nickname}）：${error.message}")
                        }
                        providerLastError = error.message
                        lastError = error.message
                        // 不可恢复错误：直接退出当前供应商重试，迅速切换下一家候选
                        break
                    }

                    providerLastError = error.message
                    lastError = error.message
                    continue
                }

                // 客户端要流式但上游只回了非流式结果：包成 SSE 再发
                val aggregated = call.aggregated
                if (aggregated != null && OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    call.close()
                    cb.recordFailure()
                    pMetrics.record(false, elapsed, "empty_completion")
                    log("${currentRoute.providerId} 返回了零内容回复，已尝试其他账号或供应商")
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", aggregated)
                    providerLastError = "上游返回了空回复"
                    lastError = providerLastError
                    continue
                }

                // 成功链路：记录熔断器成功与度量指标，模型统一归一化为客户端请求的名称
                cb.recordSuccess()
                pMetrics.record(true, System.currentTimeMillis() - attemptStart)
                val transparentModel = requestedModel

                if (streaming && aggregated != null) {
                    val sse = OpenAiSseAggregator.completionAsSse(aggregated).byteInputStream(Charsets.UTF_8)
                    return streamResponse(sse, call, currentRoute.providerId, transparentModel, account.uid, account.nickname, startedAt, body)
                }

                if (streaming) {
                    val stream = call.stream
                    if (stream == null) {
                        val elapsed = System.currentTimeMillis() - attemptStart
                        call.close()
                        cb.recordFailure()
                        pMetrics.record(false, elapsed, "stream_null")
                        providerLastError = "上游未返回流"
                        lastError = providerLastError
                        continue
                    }
                    return streamResponse(stream, call, currentRoute.providerId, transparentModel, account.uid, account.nickname, startedAt, body)
                }

                if (aggregated != null) {
                    if (engine.verboseLogging()) {
                        log("上游返回 2xx（$providerId/${account.nickname}，非流式）")
                        engine.logVerbose("返回原文", aggregated)
                    }
                    return aggregatedResponse(aggregated, currentRoute.providerId, transparentModel, account.uid, account.nickname, startedAt, body)
                }

                call.close()
                providerLastError = "上游返回的内容为空"
                lastError = providerLastError
                continue
            }

            val summary = engine.pool.summary(currentRoute.providerId)
            val errDetail = if (providerLastError.isEmpty()) {
                "无可用账号（总数: ${summary.total}，可用: ${summary.usable}，停用: ${summary.disabled}）"
            } else {
                providerLastError
            }
            routeErrors.add("${currentRoute.providerId}($errDetail)")

            // 同款模型跨供应商 Failover：按优先级选下一个尚未尝试且有可用账号的供应商
            val next = candidateRoutes()
                .firstOrNull { it.providerId !in triedProviders && engine.pool.pick(it.providerId) != null }
            if (next != null) {
                log("[故障转移] 供应商 [${currentRoute.providerId}] 调用未成功（$errDetail），自动切换至备选供应商 [${next.providerId}]（对应模型: ${next.model}）...")
                currentRoute = next
                continue
            }
            break
        }

        val allErrors = routeErrors.joinToString("；")
        val reason = "所有候选供应商均不可用：$allErrors"
        if (engine.verboseLogging()) {
            log("全部尝试完毕返回 503：已试供应商 [${triedProviders.joinToString(", ")}]")
            routeErrors.forEachIndexed { index, error -> log("  原因 ${index + 1}：$error") }
        } else {
            log("全部供应商尝试完毕，返回 503：$reason")
        }
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
        return SseResponse(engine.schedulerExecutor()) { writer ->
            // 边收边截：记录只留前 MAX_LOGGED_BODY 字符，避免长回复把整段内容留在堆里
            val collected = CappedStringBuilder(MAX_LOGGED_BODY)
            // 返回原文：上游逐行原始报文（未做任何解析），排障时能还原上游到底回了什么
            val rawLines = CappedStringBuilder(MAX_LOGGED_BODY)
            var usage: JsonObject? = null
            var pointsConsumed = 0L
            var status = CallStatus.SUCCESS
            var failure = ""
            var sawData = false
            var sawPayload = false
            // 上游空行代表一个 SSE 事件结束。把同一事件的非空行攒在一起、遇空行才一次性写出，
            // 输出字节与“逐行补 \n\n”完全一致，但每个事件只 flush 一次，减少分块帧与网络小包。
            val batch = StringBuilder(1024)
            try {
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        // 先把行原样透传，再看一眼统计：解析绝不能影响转发。
                        // 上游 chunk 里 `"usage": null` 这类字段用 Gson 裸转换是会抛异常的，
                        // 一旦抛在转发循环里，整条流就断了，客户端只看到「连接断开」。
                        // 输出恒为规范帧：跳过上游的空行，每个非空行统一补 \n\n 结尾，
                        // 否则严格解析器会把整条流当成一个没结束的事件，客户端收到空回复。
                        if (line.isBlank()) {
                            flushBatch(batch, writer)
                            continue
                        }
                        if (line.startsWith("data:")) sawData = true
                        rawLines.append(line + "\n")
                        batch.append(line).append("\n\n")
                        if (batch.length >= FLUSH_THRESHOLD_CHARS) flushBatch(batch, writer)
                        val chunk = runCatching { parseChunk(line) }.getOrNull() ?: continue
                        chunk.usage?.let { usage = it }
                        if (chunk.pointsConsumed > 0) pointsConsumed = chunk.pointsConsumed
                        if (chunk.hasPayload) sawPayload = true
                        chunk.error?.let {
                            status = CallStatus.FAILED
                            failure = it
                        }
                        collected.append(chunk.content)
                    }
                    // 上游没以空行收尾时的尾批
                    flushBatch(batch, writer)
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
                // 上游一条 data 都没给：不能静默结束，否则客户端只看到「输出完成却空」
                if (status == CallStatus.SUCCESS && !sawData) {
                    status = CallStatus.FAILED
                    failure = "上游流式响应没有任何数据"
                    engine.logError("$providerId 的流式响应没有任何 data 行，已向客户端报错")
                    runCatching {
                        writer.write("data: " + OpenAiApi.errorBody("upstream_empty", failure) + "\n\n")
                        writer.write("data: [DONE]\n\n")
                    }
                } else if (status == CallStatus.SUCCESS && sawData && !sawPayload) {
                    // 有 data 行但全是元数据（role/finish/usage），零实际内容：同样不能算成功
                    status = CallStatus.FAILED
                    failure = "上游流式响应没有任何内容"
                    engine.logError("$providerId 的流式响应零内容，已向客户端报错")
                    runCatching {
                        writer.write("data: " + OpenAiApi.errorBody("upstream_empty", failure) + "\n\n")
                        writer.write("data: [DONE]\n\n")
                    }
                }
                call.close()
                if (status == CallStatus.SUCCESS) {
                    engine.refreshCreditsSoon(providerId, uid)
                }
                if (engine.verboseLogging()) {
                    log("流式转发结束（$providerId/$model）：$status，耗时 ${System.currentTimeMillis() - startedAt}ms" +
                        if (failure.isNotEmpty()) "，失败：$failure" else "")
                    engine.logVerbose("返回原文", rawLines.content())
                }
                recordCall(id, providerId, model, uid, nickname, true, status, usage, pointsConsumed, failure, collected.content(), requestBody, startedAt, rawLines.content())
            }
        }
    }

    /** 把累积的 SSE 行批量写出；非空则写出并清空缓冲，返回是否有内容。 */
    private fun flushBatch(batch: StringBuilder, writer: ChunkWriter): Boolean {
        if (batch.isEmpty()) return false
        writer.write(batch.toString())
        batch.setLength(0)
        return true
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
        // 对调用方透明：将响应中的 model 归一化为客户端请求的逻辑模型
        if (payload != null && model.isNotEmpty()) {
            payload.addProperty("model", model)
        }
        val normalized = payload?.toString() ?: aggregated
        val usage = payload?.objOrNull("usage")
        val content = payload?.arrayOrNull("choices")?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?.objOrNull("message")?.get("content")?.asString.orEmpty()
        recordCall(
            newCompletionId(), providerId, model, uid, nickname, false,
            CallStatus.SUCCESS, usage, pointsConsumedOf(payload ?: JsonObject(), usage), "", content, requestBody, startedAt, normalized,
        )
        engine.refreshCreditsSoon(providerId, uid)
        return json(200, normalized)
    }

    private fun isGeminiGenerate(uri: String): Boolean {
        val clean = uri.trimEnd('/')
        return (clean.startsWith("/v1beta/models/") || clean.startsWith("/v1/models/") || clean.startsWith("/models/")) &&
            (clean.endsWith(":streamGenerateContent") || clean.endsWith(":generateContent"))
    }

    private fun isGeminiModels(uri: String): Boolean {
        val clean = uri.trimEnd('/')
        return clean == "/v1beta/models" || clean == "/models"
    }

    /** 响应 Google Gemini 官方协议的 models 列表请求（GET /v1beta/models）。 */
    private fun geminiModels(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized()
        val provider = engine.registry.get("antigravity")
        val account = engine.pool.pick("antigravity")
        val catalog = provider?.listModels(account) ?: dev.aigw.core.provider.ProviderModelCatalogView(emptyList(), true, "")
        val array = com.google.gson.JsonArray()
        for (m in catalog.models) {
            array.add(JsonObject().apply {
                addProperty("name", "models/${m.id}")
                addProperty("version", "001")
                addProperty("displayName", m.name)
                addProperty("description", m.name)
                addProperty("inputTokenLimit", if (m.contextWindow > 0) m.contextWindow else 1048576)
                addProperty("outputTokenLimit", 65536)
                add("supportedGenerationMethods", com.google.gson.JsonArray().apply { add("generateContent") })
            })
        }
        return json(200, JsonObject().apply { add("models", array) }.toString())
    }

    /** 响应 Google Gemini 官方协议的 generateContent / streamGenerateContent。 */
    private fun geminiGenerate(session: NanoHTTPD.IHTTPSession, uri: String): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized()
        val cleanUri = uri.trimEnd('/')
        val rawModel = cleanUri.substringAfter("/models/").substringBefore(":")
        val isStream = cleanUri.endsWith(":streamGenerateContent") || session.parms["alt"] == "sse"

        val body = readBody(session)
        if (body.isEmpty()) return errorResponse(400, "invalid_request", "请求体为空")

        val provider = engine.registry.get("antigravity") as? dev.aigw.core.provider.GeminiNativeSupport
            ?: return errorResponse(503, "no_provider", "反重力供应商未就绪")

        val startedAt = System.currentTimeMillis()
        if (engine.verboseLogging()) {
            log("收到请求 $cleanUri（原生 Gemini 协议）：model=$rawModel stream=$isStream")
            engine.logVerbose("请求原文", body)
        }

        val secSettings = engine.securitySettings()
        val sanitized = engine.sanitizer.sanitizeGeminiNativeBody(body, rawModel, secSettings.sanitizeEnabled)

        val tried = HashSet<String>()
        val settings = engine.settings()
        var attempt = 0
        var lastError = ""

        while (attempt < settings.maxRotate.coerceAtLeast(1)) {
            attempt++
            val picked = engine.pool.pick("antigravity", tried) ?: break
            tried += picked.uid
            if (engine.verboseLogging()) {
                log("第 $attempt 次选号（antigravity）：${picked.nickname.ifEmpty { picked.uid }}，发送模型 $rawModel")
            }

            // 原生 Gemini 账号级限速
            val waitedMs = engine.rateLimiter.acquire(
                "antigravity",
                picked.uid,
                secSettings.minIntervalMillis,
                secSettings.jitterMillis,
            )
            if (waitedMs > 0 && engine.verboseLogging()) {
                log("账号限速生效（antigravity/${picked.nickname}）：节拍对齐等待 ${waitedMs}ms")
            }

            val call = try {
                provider.openGeminiNative(picked, rawModel, sanitized, isStream)
            } catch (e: Exception) {
                lastError = e.message ?: "连接失败"
                if (engine.verboseLogging()) engine.logVerbose("返回原文", "openGeminiNative 异常：$lastError")
                continue
            }

            if (call.status == 0) {
                val err = call.errorBody.ifEmpty { "连接上游失败" }
                call.close()
                lastError = err
                if (engine.verboseLogging()) engine.logVerbose("返回原文", err)
                continue
            }

            if (call.status >= 400) {
                val err = call.errorBody
                if (engine.verboseLogging()) {
                    log("上游返回 HTTP ${call.status}（antigravity/${picked.nickname}）")
                    engine.logVerbose("返回原文", err)
                }
                call.close()
                lastError = err
                continue
            }

            if (isStream) {
                val stream = call.stream
                if (stream == null) {
                    call.close()
                    lastError = "上游未返回流"
                    continue
                }
                return streamResponse(stream, call, "antigravity", rawModel, picked.uid, picked.nickname, startedAt, body)
            }

            val aggregated = call.aggregated
            call.close()
            if (aggregated != null) {
                if (engine.verboseLogging()) {
                    log("上游返回 2xx（antigravity/${picked.nickname}，非流式）")
                    engine.logVerbose("返回原文", aggregated)
                }
                return json(200, aggregated)
            }
            lastError = "上游返回内容为空"
        }

        val summary = engine.pool.summary("antigravity")
        val reason = if (lastError.isEmpty()) {
            "供应商 [antigravity] 账号池中没有可用账号（总数: ${summary.total}，可用: ${summary.usable}，停用: ${summary.disabled}）"
        } else {
            "供应商 [antigravity] 调用失败：$lastError"
        }
        if (engine.verboseLogging()) {
            log("全部尝试完毕返回 503（原生 Gemini 端点）：$reason")
        }
        return errorResponse(503, "no_healthy_account", reason)
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

    private fun isLocalhost(session: NanoHTTPD.IHTTPSession): Boolean {
        val remoteIp = session.remoteIpAddress ?: ""
        return remoteIp == "127.0.0.1" || remoteIp == "::1" || remoteIp == "localhost"
    }

    private fun authorized(session: NanoHTTPD.IHTTPSession): Boolean {
        // 本地回环地址发起额度/探活查询免 Key，方便本地 Dashboard 面板脚本在非对话时也能常驻刷新
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        if (isLocalhost(session) && (uri == "/v1/credits" || uri == "/credits" || uri == "/v1/v1/credits" || uri == "/healthz")) {
            return true
        }
        val settings = engine.settings()
        if (settings.allowNoKey) return true
        val expected = settings.apiKey
        if (expected.isEmpty()) return false

        val authHeader = session.headers["authorization"]
        val googKeyHeader = session.headers["x-goog-api-key"]
        val queryKey = session.parms["key"]

        val token = when {
            authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true) -> authHeader.substring(7).trim()
            authHeader != null -> authHeader.trim()
            googKeyHeader != null -> googKeyHeader.trim()
            queryKey != null -> queryKey.trim()
            else -> return false
        }
        return MessageDigest.isEqual(
            token.toByteArray(StandardCharsets.UTF_8),
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
        val content = delta?.stringOrNull("content").orEmpty()
        // 有实际负载才算「有内容」：纯 role/finish chunk 不算，
        // 否则上游只回元数据时会被当成成功的空回复
        val hasPayload = content.isNotEmpty() ||
            delta?.has("tool_calls") == true ||
            !delta?.stringOrNull("reasoning_content").isNullOrEmpty()
        return ChunkInfo(
            usage = usage,
            error = obj.objOrNull("error")?.stringOrNull("message"),
            content = content,
            hasPayload = hasPayload,
            // 绝大多数 chunk 不带积分字段：字符串预检跳过十几次 JSON 查找
            pointsConsumed = if (payload.contains("points")) pointsConsumedOf(obj, usage) else 0L,
        )
    }

    private data class ChunkInfo(
        val usage: JsonObject?,
        val error: String?,
        val content: String,
        val hasPayload: Boolean,
        val pointsConsumed: Long,
    )

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
        rawResponse: String = "",
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
                rawResponse = rawResponse.take(MAX_LOGGED_BODY),
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

        /** 上游迟迟不给空行事件边界时的兜底 flush 阈值（字符）。 */
        const val FLUSH_THRESHOLD_CHARS = 8 * 1024

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
    private val scheduler: ScheduledExecutorService,
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
        // 复用引擎的共享定时器，只取消本流的这一个周期任务，不额外创建/销毁线程。
        val beat = scheduler.scheduleAtFixedRate(
            { runCatching { writer.write(HEARTBEAT) } },
            HEARTBEAT_SECONDS,
            HEARTBEAT_SECONDS,
            TimeUnit.SECONDS,
        )
        try {
            produce(writer)
        } finally {
            runCatching { beat.cancel(false) }
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
