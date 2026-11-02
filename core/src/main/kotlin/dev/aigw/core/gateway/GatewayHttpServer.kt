package dev.aigw.core.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.SSE_DONE
import dev.aigw.core.usage.CallLogStore
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.CallStatus
import dev.aigw.core.util.CappedStringBuilder
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.newCompletionId
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

    /** 有界的请求执行器；"/v1/health" 用它报线程与排队现状。 */
    private val asyncRunner = BoundedAsyncRunner(onReject = { engine.logWarn(it) })

    init {
        // 默认的 thread-per-request 无上限；换成有界线程池（见 BoundedAsyncRunner 注释）
        setAsyncRunner(asyncRunner)
    }

    /** 当前「在途 / 排队」请求数，供状态页展示与测试断言。 */
    fun queueSnapshot(): Pair<Int, Int> = asyncRunner.snapshot()

    fun listeningAddress(): String = (hostname ?: "0.0.0.0") + ":" + listeningPort

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        val response = try {
            when {
                session.method == NanoHTTPD.Method.OPTIONS -> run { drainBody(session); preflight() }
                session.method == NanoHTTPD.Method.POST && uri == "/v1/chat/completions" -> chatCompletions(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/models" -> models(session)
                session.method == NanoHTTPD.Method.GET && (uri == "/v1/credits" || uri == "/credits" || uri == "/v1/v1/credits") -> credits(session)
                session.method == NanoHTTPD.Method.GET && uri == "/healthz" ->
                    NanoHTTPD.newFixedLengthResponse(SimpleStatus(200, "OK"), "text/plain; charset=utf-8", "ok")
                session.method == NanoHTTPD.Method.GET && uri == "/authorize" -> authorize(session)
                else -> run {
                    // 错误响应前先消费掉请求体：残留字节会被 keep-alive 上的下一个请求当成请求行，
                    // 客户端看到的就是 NanoHTTPD 的「BAD REQUEST: Missing URI. Usage: GET /example/file.html」
                    drainBody(session)
                    errorResponse(404, "not_found", "未知路径：$uri")
                }
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
        if (!authorized(session)) return unauthorized(session)
        // 只对外暴露开放名单内的模型（未启用过滤时等价于全量）
        return json(200, OpenAiApi.modelList(engine.exposedModels()))
    }

    /** 聚合额度与配额查询端点：包含各供应商剩余额度及 Antigravity 的四个进度条指标。 */
    private fun credits(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
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
                // 给每个上游加超时：各家读超时差异极大（Raccoon 300s），
                // 无限等会让一个半挂的上游把本机请求线程拖住几分钟。
                providerArray.add(future.get(CREDITS_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } catch (_: Exception) {
                future.cancel(true)
            }
        }

        root.add("providers", providerArray)
        return json(200, root.toString())
    }

    private fun chatCompletions(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)

        val (body, readResult) = readBody(session)
        if (readResult == BodyReadResult.OVER_LIMIT) {
            return errorResponse(413, "payload_too_large", "请求体超过网关 ${MAX_BODY_BYTES / (1024 * 1024)}MB 上限")
        }
        if (body.isEmpty()) {
            logInvalidBody(session, body, "请求体为空")
            return errorResponse(400, "invalid_request", "请求体为空")
        }
        val peek = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        if (peek == null) {
            if (readResult == BodyReadResult.INCOMPLETE) {
                return errorResponse(400, "invalid_request", "连接中断，请求体未接收完整（已收 ${body.length} 字节）")
            }
            logInvalidBody(session, body, "JSON 解析失败")
            return errorResponse(400, "invalid_request", "请求体不是合法 JSON")
        }

        val settings = engine.settings()
        val streaming = peek.get("stream")?.asBoolean ?: false
        val requestedModel = peek.get("model")?.asString.orEmpty()

        val primaryRoute = engine.resolveRoute(requestedModel)
            ?: return errorResponse(400, "invalid_request", "缺少 model 参数")

        val startedAt = System.currentTimeMillis()
        // 最后一次上游响应的 HTTP 状态：成功/上游失败填 call.status，网络层失败填 0，
        // 供最终 503 的调用记录如实展示（原先 recordCall 硬编码 200，失败永远显示 200）。
        var lastHttpStatus = 0
        // 是否真正向某个上游发起过请求（用于决定 503 要不要记一条调用记录）
        var attemptedUpstream = false
        // 本次请求所有失败尝试的分类：最终按主因决定给客户端的状态码与错误码
        // （从前一律 503 no_healthy_account，额度耗尽/凭证失效也报「没有可用账号」）
        val failureKinds = ArrayList<dev.aigw.core.provider.ErrorKind>()
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

        // 循环级许可兜底：异常沿着存盘/日志等调用逃逸出循环时（如加密存储写失败），
        // 各分支的显式 release 来不及执行；Handle.release 幂等，这里重复释放无副作用。
        var pendingGate: dev.aigw.core.failover.ConcurrencyGate.Handle? = null

        // 在途请求打点（仅状态展示）：每次上游尝试算一笔，id 记在 activeRequestId，
        // 由 finishActiveRequest 在任意退出路径收尾；置空后重复调用无副作用（幂等）。
        var activeRequestId: String? = null
        fun finishActiveRequest() {
            val id = activeRequestId ?: return
            activeRequestId = null
            engine.activeRequests.finish(id)
        }

        try {
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
            val modelBreaker = engine.modelBreakerOf(providerId, resolvedModel)

            // 1b. 模型级熔断：该模型在这家供应商上连续失败达标，静默期内直接跳过这个候选。
            // 与供应商级熔断分开：只封住这个模型，不影响同一家的其它模型。
            if (!modelBreaker.allowRequest()) {
                val skipReason = "$providerId/$resolvedModel(模型级熔断中，直接跳过)"
                log("模型 [$resolvedModel] 在 [$providerId] 处于模型级熔断，跳过此候选...")
                routeErrors.add(skipReason)
                val next = candidateRoutes()
                    .firstOrNull { it.providerId !in triedProviders && engine.pool.pick(it.providerId) != null }
                if (next != null) {
                    currentRoute = next
                    continue
                }
                break
            }

            // 1c. 模型级冷却：该 (供应商, 模型) 刚被判为「模型不可用」（如 6004/1005），
            // 换号必然再撞同一道墙，直接转下一候选供应商。
            if (engine.modelCooldownOf().isCooling(providerId, resolvedModel)) {
                val skipReason = "$providerId/$resolvedModel(模型级冷却中，直接跳过)"
                log("模型 [$resolvedModel] 在 [$providerId] 处于模型级冷却中，跳过此候选...")
                routeErrors.add(skipReason)
                val next = candidateRoutes()
                    .firstOrNull { it.providerId !in triedProviders && engine.pool.pick(it.providerId) != null }
                if (next != null) {
                    currentRoute = next
                    continue
                }
                break
            }

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
                // 兜底：上一笔上游尝试若在 continue 路径未显式收尾，这里先结束掉
                finishActiveRequest()
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

                // 上游并发闸门：在发起上游调用前取许可，会话结束后释放；取不到时本次换号重试不再尝试该账号
                val gateHandle = engine.concurrencyGate.tryAcquire("${currentRoute.providerId}/${picked.uid}")
                if (gateHandle != null) pendingGate = gateHandle
                if (gateHandle == null) {
                    val waitedSecs = failoverCfg.concurrency.queueTimeoutMs / 1000.0
                    providerLastError = "并发已达上限（单账号=${failoverCfg.concurrency.maxPerAccount}，全局=${failoverCfg.concurrency.maxGlobal}，等待 ${waitedSecs}s 超时）"
                    log("并发闸门拒入场（${currentRoute.providerId}/${picked.nickname.ifEmpty { picked.uid }}）：$providerLastError")
                    continue
                }

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
                val refreshStartedAt = System.currentTimeMillis()
                try {
                    val refreshed = provider.refreshAccount(account, settings.refreshSkewSeconds)
                    if (refreshed != null) {
                        engine.pool.saveAccount(refreshed)
                        account = refreshed
                    }
                } catch (e: Exception) {
                    providerLastError = e.message ?: "刷新凭证失败"
                    cb.recordFailure()
                    modelBreaker.recordFailure()
                    pMetrics.record(false, System.currentTimeMillis() - startedAt, "token_refresh_failed")
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", "刷新凭证异常：$providerLastError")
                    gateHandle.release()
                    continue
                }

                // 凭证刷新的条件判断若失效（expiresAt 未知），每个请求都会多一次上游往返，
                // 直接体现在首字延迟上；verbose 下单独记出来便于定位
                val refreshMs = System.currentTimeMillis() - refreshStartedAt
                if (refreshMs > 200 && engine.verboseLogging()) {
                    log("凭证刷新耗时（${currentRoute.providerId}/${account.nickname}）：${refreshMs}ms")
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

                // 在途请求打点：账号已选定、即将向上游发请求；换号重试/跳家各算一笔，不复用 id
                val activeId = engine.activeRequests.begin(
                    requestedModel,
                    currentRoute.providerId,
                    account.uid,
                    account.nickname,
                )
                activeRequestId = activeId

                val attemptStart = System.currentTimeMillis()
                attemptedUpstream = true
                val call: ChatCall = try {
                    provider.openChat(account, sanitized)
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    providerLastError = e.message ?: "上游连接失败"
                    lastHttpStatus = 0
                    cb.recordFailure()
                    modelBreaker.recordFailure()
                    pMetrics.record(false, elapsed, "connect_exception")
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", "openChat 异常：$providerLastError")
                    finishActiveRequest()
                    gateHandle.release()
                    continue
                }

                // “上游已应答”= 拿到响应头（含建连/握手/上游排队），与首字出站之间的差就是模型的思考时间
                if (engine.verboseLogging()) {
                    log("上游已应答（${currentRoute.providerId}）：${System.currentTimeMillis() - attemptStart}ms")
                }
                engine.activeRequests.update(activeId, ActiveRequest.Phase.UPSTREAM_ANSWERED)

                // 流内业务错误（如 Loomy 的 HTTP 200 + 业务码）
                val failure = call.failure
                if (failure != null) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    call.close()
                    // 客户端问题（模型名/参数不对）不该连累供应商健康度，
                    // 所以先判定 CLIENT 早返回，再决定是否计入熔断与指标。
                    if (failure.kind == ErrorKind.CLIENT) {
                        lastHttpStatus = call.status
                        log("请求被上游拒绝（${currentRoute.providerId}/${account.nickname}）：${failure.message}")
                        if (engine.verboseLogging()) engine.logVerbose("返回原文", call.errorBody)
                        recordCall(
                            newCompletionId(), currentRoute.providerId, requestedModel, account.uid, account.nickname,
                            streaming, CallStatus.FAILED, call.status, null, 0L, failure.message, call.errorBody, body, startedAt,
                        )
                        finishActiveRequest()
                        gateHandle.release()
                        // 客户端侧被拒：状态码与错误码按真实分类给（不再统一写成 upstream_rejected）
                        val mapped = GatewayErrorMapping.map(listOf(failure.kind), attemptedUpstream = true)
                        return errorResponse(mapped.status, mapped.code, failure.message)
                    }
                    if (failure.kind == ErrorKind.SESSION_DEAD) {
                        engine.pool.disable(currentRoute.providerId, account.uid, failure.message)
                        log("凭证失效，已禁用账号（${currentRoute.providerId}/${account.nickname}）：${failure.message}")
                        // 凭证失效是账号级问题，不代表供应商故障，不计入熔断
                        providerLastError = failure.message
                        failureKinds.add(ErrorKind.SESSION_DEAD)
                        finishActiveRequest()
                        gateHandle.release()
                        break
                    }
                    // 模型级不可用：只冷却该模型，不换号、不计熔断（换号撞同一道墙，
                    // 且它不代表供应商故障）。直接跳出账号循环转下一候选供应商。
                    if (failure.kind == ErrorKind.MODEL_LIMIT) {
                        val cooldownMs = failoverCfg.modelCooldown.cooldownMs
                        engine.modelCooldownOf().markCooling(currentRoute.providerId, resolvedModel, cooldownMs)
                        log("模型级不可用，冷却 $cooldownMs ms（${currentRoute.providerId}/$resolvedModel）：${failure.message}")
                        providerLastError = failure.message
                        lastHttpStatus = call.status
                        failureKinds.add(ErrorKind.MODEL_LIMIT)
                        finishActiveRequest()
                        gateHandle.release()
                        break
                    }
                    // 到这里才是上游侧错误：计入熔断与健康指标
                    cb.recordFailure()
                    modelBreaker.recordFailure()
                    // 上游刚报错：把这个账号晾一会儿，避免下一个请求立刻又选中它
                    engine.pool.coolDown(currentRoute.providerId, account.uid, failoverCfg.accountCooldownMs)
                    pMetrics.record(false, elapsed, failure.kind.name)
                    providerLastError = failure.message
                    failureKinds.add(failure.kind)
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", call.errorBody)
                    finishActiveRequest()
                    gateHandle.release()
                    continue
                }

                if (call.status == 0) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    val err = call.errorBody.ifEmpty { "连接上游失败（请检查网络连接或代理设置）" }
                    call.close()
                    lastHttpStatus = 0
                    cb.recordFailure()
                    modelBreaker.recordFailure()
                    pMetrics.record(false, elapsed, "network_status_0")
                    providerLastError = err
                    failureKinds.add(ErrorKind.NETWORK)
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", err)
                    finishActiveRequest()
                    gateHandle.release()
                    continue
                }

                if (call.status >= 400) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    val error = provider.classify(call.status, call.errorBody)

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
                        // 400/模型名错/内容审核：是客户端问题，健康供应商不应被计入失败计数，
                        // 否则连续几个坏请求就会把正常供应商误判进熔断隔离。
                        lastHttpStatus = call.status
                        log("请求被上游拒绝（${currentRoute.providerId}/${account.nickname}）：${error.message}")
                        recordCall(
                            newCompletionId(), currentRoute.providerId, requestedModel, account.uid, account.nickname,
                            streaming, CallStatus.FAILED, call.status, null, 0L, error.message, call.errorBody, body, startedAt,
                        )
                        finishActiveRequest()
                        gateHandle.release()
                        val mappedAbort = GatewayErrorMapping.map(listOf(error.kind), attemptedUpstream = true)
                        return errorResponse(mappedAbort.status, mappedAbort.code, error.message)
                    }

                    if (error.kind == ErrorKind.SESSION_DEAD || decision == dev.aigw.core.failover.FailoverDecision.SWITCH_NEXT_PROVIDER) {
                        if (error.kind == ErrorKind.SESSION_DEAD) {
                            engine.pool.disable(currentRoute.providerId, account.uid, error.message)
                            log("凭证失效，已禁用账号（${currentRoute.providerId}/${account.nickname}）：${error.message}")
                        }
                        providerLastError = error.message
                        failureKinds.add(error.kind)
                        // 不可恢复错误：直接退出当前供应商重试，迅速切换下一家候选
                        finishActiveRequest()
                        gateHandle.release()
                        break
                    }

                    // 模型级不可用（如 CodeBuddy 429/6004、Trae 1005）：只冷却该模型，
                    // 不换号、不计熔断，直接转下一候选供应商。
                    if (error.kind == ErrorKind.MODEL_LIMIT) {
                        val cooldownMs = failoverCfg.modelCooldown.cooldownMs
                        engine.modelCooldownOf().markCooling(currentRoute.providerId, resolvedModel, cooldownMs)
                        log("模型级不可用，冷却 $cooldownMs ms（${currentRoute.providerId}/$resolvedModel）：${error.message}")
                        providerLastError = error.message
                        lastHttpStatus = call.status
                        failureKinds.add(ErrorKind.MODEL_LIMIT)
                        finishActiveRequest()
                        gateHandle.release()
                        break
                    }

                    // 剩余为上游侧可重试错误（5xx/429/网络/额度/404）：计入熔断与健康指标
                    cb.recordFailure()
                    modelBreaker.recordFailure()
                    pMetrics.record(false, elapsed, "http_${call.status}")
                    providerLastError = error.message
                    lastHttpStatus = call.status
                    failureKinds.add(error.kind)
                    finishActiveRequest()
                    gateHandle.release()
                    continue
                }

                // 客户端要流式但上游只回了非流式结果：包成 SSE 再发
                val aggregated = call.aggregated
                if (aggregated != null && OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    call.close()
                    cb.recordFailure()
                    modelBreaker.recordFailure()
                    pMetrics.record(false, elapsed, "empty_completion")
                    log("${currentRoute.providerId} 返回了零内容回复，已尝试其他账号或供应商")
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", aggregated)
                    providerLastError = "上游返回了空回复"
                    failureKinds.add(ErrorKind.SERVER)
                    finishActiveRequest()
                    gateHandle.release()
                    continue
                }

                // 成功链路：记录熔断器成功与度量指标，模型统一归一化为客户端请求的名称
                cb.recordSuccess()
                modelBreaker.recordSuccess()
                engine.modelCooldownOf().clear(currentRoute.providerId, resolvedModel)
                engine.pool.clearCooldown(currentRoute.providerId, account.uid)
                pMetrics.record(true, System.currentTimeMillis() - attemptStart)
                val transparentModel = requestedModel

                if (streaming && aggregated != null) {
                    val sse = OpenAiSseAggregator.completionAsSse(aggregated).byteInputStream(Charsets.UTF_8)
                    // 非流式包 SSE：上游响应已读入内存，许可与在途打点在这里一起收尾
                    finishActiveRequest()
                    gateHandle.release()
                    return streamResponse(sse, call, currentRoute.providerId, transparentModel, account.uid, account.nickname, startedAt, body, call.status)
                }

                if (streaming) {
                    val stream = call.stream
                    if (stream == null) {
                        val elapsed = System.currentTimeMillis() - attemptStart
                        call.close()
                        cb.recordFailure()
                        modelBreaker.recordFailure()
                        pMetrics.record(false, elapsed, "stream_null")
                        providerLastError = "上游未返回流"
                        failureKinds.add(ErrorKind.SERVER)
                        finishActiveRequest()
                        gateHandle.release()
                        continue
                    }
                    // 流式透传：在途许可要覆盖整个读流转发阶段，所有权交给 onClosed（流结束后释放），
                    // 先清空 pendingGate 以免循环级 finally 在 return 时提前释放。
                    pendingGate = null
                    // 在途打点的所有权同样交给这条流：首字出站时转 STREAMING，流真正结束时收尾；
                    // 置空 activeRequestId 以免循环级 finally 提前结束它。
                    activeRequestId = null
                    return streamResponse(
                        stream, call, currentRoute.providerId, transparentModel, account.uid, account.nickname, startedAt, body, call.status,
                        onFirstChunk = { engine.activeRequests.update(activeId, ActiveRequest.Phase.STREAMING) },
                        onFinished = {
                            gateHandle.release()
                            engine.activeRequests.finish(activeId)
                        },
                    )
                }

                if (aggregated != null) {
                    if (engine.verboseLogging()) {
                        log("上游返回 2xx（$providerId/${account.nickname}，非流式）")
                        engine.logVerbose("返回原文", aggregated)
                    }
                    finishActiveRequest()
                    gateHandle.release()
                    return aggregatedResponse(aggregated, currentRoute.providerId, transparentModel, account.uid, account.nickname, startedAt, body, call.status)
                }

                call.close()
                providerLastError = "上游返回的内容为空"
                failureKinds.add(ErrorKind.SERVER)
                finishActiveRequest()
                gateHandle.release()
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
        } finally {
            pendingGate?.release()
            // 异常逃逸出循环（如存盘/日志抛错）时兜底收尾，避免在途请求残留
            finishActiveRequest()
        }

        val allErrors = routeErrors.joinToString("；")
        val mappedFailure = GatewayErrorMapping.map(
            kinds = failureKinds,
            attemptedUpstream = attemptedUpstream,
            poolSummary = runCatching {
                val summary = engine.pool.summary(primaryRoute.providerId)
                "总数 ${summary.total}，可用 ${summary.usable}，停用 ${summary.disabled}"
            }.getOrDefault(""),
        )
        // 对客消息 = 「真实主因 + 逐家原因」：agent 端能直接看出是额度、凭证还是网络，
        // 而不用去猜一句笼统的「没有可用账号」
        val reason = if (allErrors.isEmpty()) mappedFailure.headline else "${mappedFailure.headline}；$allErrors"
        if (engine.verboseLogging()) {
            log("全部尝试完毕返回 ${mappedFailure.status}（${mappedFailure.code}）：已试供应商 [${triedProviders.joinToString(", ")}]")
            routeErrors.forEachIndexed { index, error -> log("  原因 ${index + 1}：$error") }
        } else {
            log("全部供应商尝试完毕，返回 ${mappedFailure.status}（${mappedFailure.code}）：$reason")
        }
        // 全部候选耗尽：记一条失败记录，httpStatus 取最后一次上游状态（网络层失败为 0）。
        // 未真正发起过上游请求（如无可用账号）就不记，避免污染调用记录。
        if (attemptedUpstream) {
            recordCall(
                newCompletionId(), primaryRoute.providerId, requestedModel, "", "",
                streaming, CallStatus.FAILED, lastHttpStatus, null, 0L, reason, "", body, startedAt,
            )
        }
        return errorResponse(mappedFailure.status, mappedFailure.code, reason)
    }

    // ------------------------------------------------------------------ 响应

    /**
     * 流式：逐行透传上游已转好的 OpenAI SSE，同时抽取 usage 用于记录。
     * [onFirstChunk] 在首个 `data:` 行出站时回调一次（用于标记「开始输出」等阶段），
     * [onFinished] 在流结束后回调一次（包括客户端断开导致的关闭）。
     */
    private fun streamResponse(
        stream: InputStream,
        call: ChatCall,
        providerId: String,
        model: String,
        uid: String,
        nickname: String,
        startedAt: Long,
        requestBody: String,
        httpStatus: Int,
        onFirstChunk: () -> Unit = {},
        onFinished: () -> Unit = {},
    ): NanoHTTPD.Response {
        val id = newCompletionId()
        // 转发结果快照：由 produce 在结束时写入。header 写出失败时 produce 不执行，
        // onClosed 仍要据此释放并发许可并写一条失败记录（快照为 null 即「没进转发」）。
        var snapshot: RecordedSnapshot? = null
        return SseResponse(
            engine.schedulerExecutor(),
            onClosed = {
                onFinished()
                val result = snapshot
                if (result == null) {
                    recordCall(
                        id, providerId, model, uid, nickname, true,
                        CallStatus.FAILED, httpStatus, null, 0L, "连接在响应开始前中断", "", requestBody, startedAt,
                    )
                } else {
                    recordCall(
                        id, providerId, model, uid, nickname, true,
                        result.status, httpStatus, result.usage, result.pointsConsumed,
                        result.failure, result.response, requestBody, startedAt, result.raw,
                        firstByteMillis = result.firstByteMillis,
                    )
                }
            },
        ) { writer ->
            // 边收边截：摘要只留前 CallLogStore.MAX_SUMMARY_CHARS 字符（进加密存储）；完整原文由
            // CappedStringBuilder 全量统计后另行溢出存盘（见 recordCall），不留在堆里。
            // 上限设为溢出单字段上限：正文远超它时多出的尾部不再保留。
            val collected = CappedStringBuilder(CallLogStore.MAX_SPILL_CHARS)
            val rawLines = CappedStringBuilder(CallLogStore.MAX_SPILL_CHARS)
            var usage: JsonObject? = null
            var pointsConsumed = 0L
            var status = CallStatus.SUCCESS
            var failure = ""
            var sawData = false
            var firstChunkLogged = false
            // 首字出站耗时（毫秒）：非 -1 表示已经出过首字，落进调用记录做 TTFB 归因
            var firstByteMillis = -1L
            var sawPayload = false
            // 上游是否已自己给出 [DONE]：正常结束但没给时要补一个，否则客户端只看到截断流
            var sawDone = false
            // 网关自身转发异常（非客户端断开）：需补一个错误帧 + [DONE]，
            // 否则客户端只看到一条没有 [DONE] 的截断流，会误判为网络异常。
            var gatewayError = false
            // 每个非空行即时写出（输出仍是「每行补 \n\n」的规范帧）。
            // 曾把同一事件的多行攒到 8KB 再 flush 以减少小包，但那会让不发空行分隔的上游
            // 迟迟不出首字（客户端要等积满 8KB）——首字延迟优先级高于节省小包。
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
                        if (line.startsWith("data:")) {
                            sawData = true
                            if (line.removePrefix("data:").trim() == "[DONE]") sawDone = true
                        }
                        rawLines.append(line + "\n")
                        batch.append(line).append("\n\n")
                        flushBatch(batch, writer)
                        if (!firstChunkLogged && line.startsWith("data:")) {
                            firstChunkLogged = true
                            firstByteMillis = System.currentTimeMillis() - startedAt
                            log("首字出站（$providerId/$model）：${firstByteMillis}ms")
                            onFirstChunk()
                        }
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
                // 客户端中途断开是最常见原因，不算账号故障。
                // 但读上游超时/被上游重置也走这里：若不发终止帧，客户端只能一直等到
                // 它自己的超时（表现为「没有响应」）。所以尽最大努力补一个错误帧 +
                // [DONE]；若其实是客户端已断开，写入会失败并被忽略。
                status = CallStatus.ABORTED
                failure = e.message ?: "连接中断"
                runCatching {
                    writer.write("data: " + OpenAiApi.errorBody("upstream_interrupted", failure) + "\n\n")
                    writer.write(SSE_DONE)
                }
            } catch (e: Exception) {
                // 网关自己处理流出错：不能混进「客户端断开」里，否则线上完全看不到根因
                status = CallStatus.FAILED
                gatewayError = true
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
                        writer.write(SSE_DONE)
                    }
                } else if (status == CallStatus.SUCCESS && sawData && !sawPayload) {
                    // 有 data 行但全是元数据（role/finish/usage），零实际内容：同样不能算成功
                    status = CallStatus.FAILED
                    failure = "上游流式响应没有任何内容"
                    engine.logError("$providerId 的流式响应零内容，已向客户端报错")
                    runCatching {
                        writer.write("data: " + OpenAiApi.errorBody("upstream_empty", failure) + "\n\n")
                        writer.write(SSE_DONE)
                    }
                } else if (gatewayError) {
                    // 网关转发异常：补错误帧 + [DONE]，让客户端看到可解析的结束信号而不是截断流
                    runCatching {
                        writer.write("data: " + OpenAiApi.errorBody("internal_error", failure) + "\n\n")
                        writer.write(SSE_DONE)
                    }
                } else if (status == CallStatus.SUCCESS && !sawDone) {
                    // 上游正常结束（EOF）但没发 [DONE]：原样透传类供应商常见。
                    // 走到这里说明 sawData/sawPayload 均已满足（前两个分支已排除），
                    // 补一个结束标记，避免客户端把截断流误判为网络异常。本链保证最多补一次。
                    runCatching { writer.write(SSE_DONE) }
                }
                call.close()
                runCatching { onFinished() }
                if (status == CallStatus.SUCCESS) {
                    engine.refreshCreditsSoon(providerId, uid)
                }
                if (engine.verboseLogging()) {
                    log("流式转发结束（$providerId/$model）：$status，耗时 ${System.currentTimeMillis() - startedAt}ms" +
                        if (failure.isNotEmpty()) "，失败：$failure" else "")
                    engine.logVerbose("返回原文", rawLines.content())
                }
                // 快照交给 onClosed 统一写记录：send 的 finally 无论 produce 是否执行都会跑。
                snapshot = RecordedSnapshot(
                    status, usage, pointsConsumed, failure, collected.content(), rawLines.content(), firstByteMillis,
                )
            }
        }
    }

    /** 流式转发结束时供 onClosed 写调用记录的结果快照。 */
    private data class RecordedSnapshot(
        val status: CallStatus,
        val usage: JsonObject?,
        val pointsConsumed: Long,
        val failure: String,
        val response: String,
        val raw: String,
        /** 首字出站耗时（毫秒）；流全程无首字时为 -1。 */
        val firstByteMillis: Long = -1,
    )

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
        httpStatus: Int,
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
            CallStatus.SUCCESS, httpStatus, usage, pointsConsumedOf(payload ?: JsonObject(), usage), "", content, requestBody, startedAt, normalized,
            // 非流式没有「首字」概念：有内容就把总耗时当首字耗时记下，否则留 -1
            firstByteMillis = if (content.isNotEmpty()) System.currentTimeMillis() - startedAt else -1L,
        )
        engine.refreshCreditsSoon(providerId, uid)
        return json(200, normalized)
    }

    /**
     * 近期健康快照（供应商 × 模型）：样本数、成功率、耗时与首字延迟分位数、失败原因 Top-N。
     *
     * 与 `/v1/credits` 同权限：只给本机看，不往局域网暴露。数据全在内存里，重启即清零。
     */
    private fun health(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!isLocalhost(session) && !authorized(session)) return unauthorized(session)
        val root = JsonObject()
        root.addProperty("queue", "${queueSnapshot().first} 在途 / ${queueSnapshot().second} 排队")
        val window = JsonObject()
        engine.healthWindowOf().snapshot().forEach { (key, summary) ->
            val obj = JsonObject()
            summary.forEach { (k, v) ->
                when (v) {
                    is Int -> obj.addProperty(k, v)
                    is Long -> obj.addProperty(k, v)
                    is String -> obj.addProperty(k, v)
                    is Map<*, *> -> obj.add(k, JsonObject().apply {
                        v.forEach { (ek, ev) -> if (ev is Number) addProperty(ek.toString(), ev) }
                    })
                    else -> obj.addProperty(k, v.toString())
                }
            }
            window.add(key, obj)
        }
        root.add("models", window)
        return json(200, root.toString())
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

    private fun unauthorized(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        drainBody(session)
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
    private fun readBody(session: NanoHTTPD.IHTTPSession): Pair<String, BodyReadResult> {
        val input: InputStream = session.inputStream
        val out = ByteArrayOutputStream()
        val transferEncoding = session.headers["transfer-encoding"].orEmpty()
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull() ?: -1L

        val result = when {
            transferEncoding.contains("chunked", ignoreCase = true) -> readChunked(input, out)
            declared > 0 -> readExactly(input, out, declared)
            else -> BodyReadResult.COMPLETE
        }
        // Gson 不接受 UTF-8 BOM，客户端带上时合法 JSON 会被误判
        return out.toString("UTF-8").removePrefix("\uFEFF") to result
    }

    /** 把请求体读掉但不使用，避免 keep-alive 连接上的残留 body 污染下一个请求。 */
    private fun drainBody(session: NanoHTTPD.IHTTPSession) {
        val input: InputStream = session.inputStream
        val sink = ByteArrayOutputStream()
        val transferEncoding = session.headers["transfer-encoding"].orEmpty()
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull() ?: -1L
        try {
            when {
                transferEncoding.contains("chunked", ignoreCase = true) -> readChunked(input, sink)
                declared > 0 -> readExactly(input, sink, declared)
            }
        } catch (_: IOException) {}
    }

    private fun readExactly(input: InputStream, out: ByteArrayOutputStream, length: Long): BodyReadResult {
        val buffer = ByteArray(8192)
        var remaining = length
        var discarded = false
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return if (discarded) BodyReadResult.OVER_LIMIT else BodyReadResult.INCOMPLETE
            // 超限后继续读但丢弃，保证 Content-Length 范围内的字节被消费完，连接上不留残留
            if (out.size() < MAX_BODY_BYTES) out.write(buffer, 0, read) else discarded = true
            remaining -= read
        }
        return if (discarded) BodyReadResult.OVER_LIMIT else BodyReadResult.COMPLETE
    }

    /** 解 `大小\r\n数据\r\n` 直到 0 号结束块。 */
    private fun readChunked(input: InputStream, out: ByteArrayOutputStream): BodyReadResult {
        val buffer = ByteArray(8192)
        var discarded = false
        while (true) {
            val header = readAsciiLine(input) ?: return BodyReadResult.INCOMPLETE
            // 允许 `1a;ext=1` 这种带扩展的块头
            val size = header.substringBefore(';').trim().toIntOrNull(16) ?: return BodyReadResult.INCOMPLETE
            if (size <= 0) return if (discarded) BodyReadResult.OVER_LIMIT else BodyReadResult.COMPLETE
            var remaining = size
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) return BodyReadResult.INCOMPLETE
                if (out.size() < MAX_BODY_BYTES) out.write(buffer, 0, read) else discarded = true
                remaining -= read
            }
            readAsciiLine(input)
        }
    }

    private enum class BodyReadResult { COMPLETE, INCOMPLETE, OVER_LIMIT }

    /** 非法请求体的诊断现场：摘要行含关键 header；详细日志开启时另分段记录完整原文。 */
    private fun logInvalidBody(session: NanoHTTPD.IHTTPSession, body: String, reason: String) {
        val headers = listOf("content-length", "transfer-encoding", "content-type", "content-encoding", "connection")
            .mapNotNull { key -> session.headers[key]?.let { "$key=$it" } }
            .joinToString(" ")
        log("请求体异常（$reason）：$headers 已收 ${body.length} 字符，前 256 字节=${body.take(256).escapeControl()}")
        engine.logVerbose("请求原文（$reason）", body)
    }

    private fun String.escapeControl(): String = buildString {
        for (ch in this@escapeControl) {
            when {
                ch.code in 0x20..0x7e -> append(ch)
                ch.code < 0x20 || ch.code == 0x7f -> append("\\x").append(ch.code.toString(16))
                else -> append(ch)
            }
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
        // 否则上游只回元数据时会被当成成功的空回复。
        // images 也算负载：图像型号可能只回图不回文本，漏了它会把纯图响应判成空并报错。
        val hasPayload = content.isNotEmpty() ||
            delta?.has("tool_calls") == true ||
            delta?.has("images") == true ||
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
     * 从 usage 里取「命中前缀缓存的输入 token」。
     *
     * 三家口径都认（先 OpenAI，再 DeepSeek/腾讯系的平铺字段，最后 Anthropic 风格）：
     * - OpenAI：`prompt_tokens_details.cached_tokens`（Gemini 路径由 [OpenAiGemini] 映射过来）
     * - DeepSeek / Hunyuan：`prompt_cache_hit_tokens`
     * - Anthropic：`cache_read_input_tokens`
     * 上游没报就是 0（不估算）。
     */
    internal fun cachedTokensOf(usage: JsonObject?): Long {
        val obj = usage ?: return 0L
        for (key in listOf("prompt_cache_hit_tokens", "cache_read_input_tokens")) {
            obj.get(key)?.takeIf { it.isJsonPrimitive }?.asLong?.let { if (it > 0) return it }
        }
        return obj.objOrNull("prompt_tokens_details")?.longOrNull("cached_tokens")?.coerceAtLeast(0L) ?: 0L
    }

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
        json(status, OpenAiApi.errorBody(code, message)).apply {
            // 错误响应一律关闭连接：请求体的消费状态未知时，残留字节会污染 keep-alive 连接
            addHeader("Connection", "close")
        }

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

    private fun recordCall(
        id: String,
        providerId: String,
        model: String,
        uid: String,
        nickname: String,
        streaming: Boolean,
        status: CallStatus,
        httpStatus: Int,
        usage: JsonObject?,
        pointsConsumed: Long,
        error: String,
        responseText: String,
        requestBody: String,
        startedAt: Long,
        rawResponse: String = "",
        /** 首字延迟（毫秒）；-1 表示本次没有产生首字（失败 / 空回复 / 非流式）。 */
        firstByteMillis: Long = -1,
    ) {
        // 调用记录开关：关闭时不写任何记录（原文动辄几 KB，是存储的主要来源）；
        // 记录一 failover 链路的语义不变，只是不留痕。
        // 跳天自动清理必须照常执行：它是存量记录（含旧版本积压）的唯一运行期回收点，
        // 放在开关短路之后会让「关归档」变成「永不清理」。
        engine.autoPurgeIfDue()
        // 健康窗口：不论调用记录开关是否打开都要记（它是内存里的排障视图，不占存储）
        engine.healthWindowOf().record(
            providerId = providerId,
            model = model,
            ok = status == CallStatus.SUCCESS,
            durationMillis = System.currentTimeMillis() - startedAt,
            firstByteMillis = firstByteMillis,
            error = error,
        )
        if (!engine.settings().callLoggingEnabled) return
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
                httpStatus = httpStatus,
                promptTokens = usage?.get("prompt_tokens")?.asLong ?: 0L,
                completionTokens = usage?.get("completion_tokens")?.asLong ?: 0L,
                totalTokens = usage?.get("total_tokens")?.asLong ?: 0L,
                cachedTokens = cachedTokensOf(usage),
                pointsConsumed = pointsConsumed,
                firstByteMillis = firstByteMillis,
                durationMillis = System.currentTimeMillis() - startedAt,
                error = error,
                requestBody = requestBody,
                responseBody = responseText,
                rawResponse = rawResponse,
            ),
        )
    }

    private fun log(text: String) = engine.log(text)

    companion object {
        const val MAX_BODY_BYTES = 8 * 1024 * 1024

        /** `/v1/credits` 并行拉取的单家预算；上游半挂时不能让请求线程无限等。 */
        private const val CREDITS_FETCH_TIMEOUT_SECONDS = 20L

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
internal class ChunkWriter(
    private val out: OutputStream,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private var finished = false

    /** 最近一次真正写出数据的时刻；心跳线程据此判断是否已静默，避免活跃流期间发冗余心跳。 */
    @Volatile
    private var lastWriteAtMillis: Long = nowMillis()

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
        lastWriteAtMillis = nowMillis()
    }

    /** 距最近一次写出数据的毫秒数，供心跳判断活跃/静默。 */
    fun idleMillis(): Long = nowMillis() - lastWriteAtMillis

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
    private val heartbeatIdleSeconds: Long = HEARTBEAT_IDLE_SECONDS,
    private val heartbeatCheckSeconds: Long = HEARTBEAT_CHECK_SECONDS,
    /** 流生命周期结束（含 header 写出失败等 produce 未执行的场景）后回调，且仅回调一次。 */
    private val onClosed: () -> Unit = {},
    private val produce: (ChunkWriter) -> Unit,
) : NanoHTTPD.Response(SimpleStatus(200, "OK"), "text/event-stream; charset=utf-8", null, -1) {

    init {
        setKeepAlive(false)
        closeConnection(true)
    }

    public override fun send(outputStream: OutputStream) {
        try {
            sendInternal(outputStream)
        } finally {
            // header 写出阶段就可能抛 IOException（客户端已断开）；此时 produce 从未执行，
            // 若不在这里统一回调，流式并发许可会永久泄漏、调用记录也不会写入。
            onClosed()
        }
    }

    /** 实际的发送逻辑；header 写出与 produce 全部完成后才正常返回。 */
    private fun sendInternal(outputStream: OutputStream) {
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
        // 只在「已静默 ≥ heartbeatIdleSeconds」时才发：活跃流期间不制造冗余心跳，
        // 也不再无条件掩盖客户端自身的读超时。检查周期 heartbeatCheckSeconds，
        // 故最坏 keepalive 间隔 ≈ idle + check，仍远小于常见客户端读超时。
        // 复用引擎的共享定时器，只取消本流的这一个周期任务，不额外创建/销毁线程。
        val idleThresholdMillis = TimeUnit.SECONDS.toMillis(heartbeatIdleSeconds)
        val beat = scheduler.scheduleAtFixedRate(
            {
                if (writer.idleMillis() >= idleThresholdMillis) {
                    runCatching { writer.write(HEARTBEAT) }
                }
            },
            heartbeatCheckSeconds,
            heartbeatCheckSeconds,
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
        /** 静默多久后开始补心跳。 */
        const val HEARTBEAT_IDLE_SECONDS = 15L
        /** 心跳检查周期：最坏 keepalive 间隔 ≈ IDLE + CHECK = 20s。 */
        const val HEARTBEAT_CHECK_SECONDS = 5L
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
