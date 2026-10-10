package dev.aigw.core.provider.antigravity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.provider.LoopbackOAuthSupport
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.queryParam
import dev.aigw.core.protocol.OpenAiGemini
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Google Antigravity（`agy` CLI 背后的统一网关）。
 *
 * 登录是标准 Google OAuth：WebView 打开授权页，拦截回调 URL（`http://localhost:51121/oauth-callback`）
 * 拿 code 换 token，再用 `loadCodeAssist` 取 project id。对话走 Gemini 风格协议，
 * 由 [OpenAiGemini] 做双向转换。
 */
class AntigravityProvider(
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val apiBase: String = API_BASE,
    private val quotaBase: String = QUOTA_BASE,
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
) : Provider, LoopbackOAuthSupport {

    override val id: String = ID
    override val displayName: String = "Antigravity"
    override val authKind: AuthKind = AuthKind.OAUTH_LOOPBACK

    // ------------------------------------------------------------------ 模型

    /** 云端拉取的模型清单缓存；null = 还没拉过或拉取失败。 */
    @Volatile
    private var fetchedModels: List<ProviderModel>? = null

    private var fetchedAtMillis = 0L

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val parsed = account?.let { runCatching { parse(it) }.getOrNull() }
        if (parsed != null) {
            val cached = fetchedModels
            if (cached != null && nowMillis() - fetchedAtMillis < MODEL_CACHE_TTL_MILLIS) {
                return ProviderModelCatalogView(models = cached, fromFallback = false, error = "")
            }
            // 不主动刷新（凭证策略同 openChat），直接用当前 Token；过期则走 fallback
            var lastErr = ""
            val fetched = try {
                fetchAvailableModels(parsed)
            } catch (e: Exception) {
                lastErr = e.message ?: "拉取失败"
                null
            }
            if (!fetched.isNullOrEmpty()) {
                fetchedModels = fetched
                fetchedAtMillis = nowMillis()
                return ProviderModelCatalogView(models = fetched, fromFallback = false, error = "")
            }
            // 若云端拉取失败但之前有成功缓存，继续回退使用之前的缓存，避免列表突然变空
            val previous = fetchedModels
            if (!previous.isNullOrEmpty()) {
                return ProviderModelCatalogView(models = previous, fromFallback = false, error = "")
            }
            return ProviderModelCatalogView(models = emptyList(), fromFallback = false, error = lastErr)
        }
        return ProviderModelCatalogView(models = emptyList(), fromFallback = false, error = "")
    }

    /**
     * 从云端拉取可用模型（优先 daily 端点，失败降级 prod，与 CLIProxyAPI 一致）：
     * `POST /v1internal:fetchAvailableModels`，响应 `models` 是
     * `{模型id: {displayName, maxTokens, maxOutputTokens}}` 的 map。
     * 跳过上游保留的内部/实验模型。
     */
    private fun fetchAvailableModels(credential: Credential): List<ProviderModel> {
        val payload = if (credential.projectId.isNotEmpty()) {
            JsonObject().apply { addProperty("project", credential.projectId) }.toString()
        } else {
            "{}"
        }
        val endpoints = listOf(
            "$quotaBase/$API_VERSION:fetchAvailableModels",
            "$apiBase/$API_VERSION:fetchAvailableModels",
        )
        var lastStatus = 0
        var lastBody = ""
        for (url in endpoints) {
            val (status, text) = try {
                postJson(url, credential.accessToken, payload, connectTimeout = 8_000, readTimeout = 12_000)
            } catch (e: Exception) {
                lastBody = e.message ?: "连接失败"
                continue
            }
            lastStatus = status
            lastBody = text
            if (status in 200..299) {
                val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                    ?: continue
                val models = root.get("models")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: continue
                val result = ArrayList<ProviderModel>()
                for ((id, value) in models.entrySet()) {
                    val modelId = id.trim()
                    if (modelId.isEmpty() || EXCLUDED_MODELS.contains(modelId)) continue
                    val meta = value as? JsonObject
                    val displayName = meta?.stringOrNull("displayName").orEmpty().ifEmpty { modelId }
                    val contextLength = meta?.longOrNull("maxTokens")?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_LENGTH
                    result.add(ProviderModel(modelId, displayName, contextLength))
                }
                if (result.isNotEmpty()) {
                    result.sortBy { it.id }
                    return result
                }
            }
        }
        throw IllegalStateException("从云端获取模型失败（HTTP $lastStatus）：${lastBody.take(160)}")
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim().removePrefix("models/").trim()
        val mapped = MODEL_ALIASES[model.lowercase()]
        if (mapped != null) return mapped
        if (model.isEmpty() || model.equals("auto", ignoreCase = true)) {
            return fetchedModels?.firstOrNull()?.id ?: "gemini-3.8-flash-high"
        }
        val lower = model.lowercase()
        return when {
            lower.contains("flash") -> "gemini-3.8-flash-high"
            lower.contains("pro") -> "gemini-pro-agent"
            lower.contains("claude") || lower.contains("sonnet") || lower.contains("opus") -> "claude-sonnet-4-6"
            else -> model
        }
    }

    /** 全部走 Google 域名（含 cloudcode-pa / oauth2 / www.googleapis.com）。 */
    override fun hosts(): List<String> = listOf("googleapis.com", "accounts.google.com")

    // ------------------------------------------------------------------ 对话

    /**
     * 对话凭证策略（回归 0.1.39 的稳定行为）：
     * provider 内部**绝不主动刷新 Token**——刷新由网关在请求前统一调 [refreshAccount]（有
     * settings.refreshSkewSeconds 控制频率，默认临期才刷）。每次对话前都打 OAuth 会触发
     * Google 风控吊销 refresh_token，这是「越修越频繁失效」的根因，严禁回退。
     */
    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        if (credential.projectId.isEmpty()) {
            return FailedChatCall(400, "该账号缺少 project id，请重新登录以获取")
        }
        val streaming = isStreaming(openAiBody)
        val model = resolveModel(requestedModel(openAiBody))
        val request = OpenAiGemini.toGeminiRequest(openAiBody)
        // 非 claude 模型删 maxOutputTokens（上游 gemini 系不接受该字段，会 400）
        val isClaude = model.contains("claude", ignoreCase = true)
        if (!isClaude) {
            request.objOrNull("generationConfig")?.remove("maxOutputTokens")
        }
        val body = OpenAiGemini.envelope(credential.projectId, model, request, OpenAiGemini.newRequestId())
        // 对话走 daily 端点（与 CLIProxyAPI v7 默认一致）：prod 端点对消费级账号（含 Gemini Pro 会员）
        // 会直接回 429 RESOURCE_EXHAUSTED，与额度无关
        hooks.onVerbose?.invoke("Antigravity 发送内容", "$quotaBase/$API_VERSION:streamGenerateContent?alt=sse\n$body")

        val conn = try {
            open("$quotaBase/$API_VERSION:streamGenerateContent?alt=sse", credential.accessToken, body)
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        if (status !in 200..299) {
            val errorBody = runCatching { readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }

        val translator = OpenAiGemini.SseTranslator(newCompletionId(), model)
        val transformed = LineTransformStream(
            source = conn.inputStream,
            transform = { line -> translator.translate(line) },
            onFinish = { translator.close() },
        )
        if (streaming) {
            return StreamingChatCall(status, transformed) { conn.disconnect() }
        }
        val aggregated = try {
            OpenAiSseAggregator.aggregate(transformed, model)
        } finally {
            conn.disconnect()
        }
        return AggregatedChatCall(status, aggregated)
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = extractMessage(body).ifEmpty { "上游 HTTP $status" }
        val lower = message.lowercase()
        val kind = when {
            // 与 0.1.39 语义一致：凭证失效明确报出需要重新登录，不隐藏。
            // 注意不要把 401/403 降级成短冷却——那会导致反复触发 OAuth 刷新，
            // 加速 Google 风控吊销 refresh_token（「越修越频繁失效」的根因）。
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 404 -> ErrorKind.CLIENT // 模型不存在属于客户端入参错误，不连累账号被禁用
            status == 429 && (lower.contains("quota") || lower.contains("capacity")) -> ErrorKind.QUOTA
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message)
    }

    /**
     * 额度信息：先读 `loadCodeAssist` 里的订阅层级（paidTier，Gemini 会员 = Google AI Pro）
     * 与 Google One 积分；没有积分条目时回退到 `retrieveUserQuotaSummary` 的模型组配额，
     * detail 里始终标注订阅层级，避免把会员配额误认为免费额度。
     * 凭证策略：不主动刷新（避免 OAuth 高频刷新触发风控），额度刷新失败时只报状态。
     */
    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = parse(account) ?: return null
        val (paidStatus, paidBody) = try {
            loadPaidCredits(credential.accessToken)
        } catch (e: Exception) {
            return CreditInfo(0, known = false, detail = "查询积分失败：${e.message}")
        }
        when {
            paidStatus == 401 || paidStatus == 403 ->
                return CreditInfo(0, known = false, detail = "登录状态已失效，需要重新登录")
            paidStatus !in 200..299 ->
                return CreditInfo(0, known = false, detail = "查询积分失败（HTTP $paidStatus）：${paidBody.take(200)}")
        }
        val assist = parsePaidAssist(paidBody)
        if (assist?.credits != null) {
            return CreditInfo(assist.credits, known = true, detail = "${assist.tierLabel} · ${assist.creditsDetail}")
        }
        return quotaCreditInfo(credential, assist?.tierLabel ?: "订阅未知")
    }

    override fun creditPacks(account: ProviderAccount): List<QuotaPack> {
        val credential = parse(account) ?: return emptyList()
        val assist = try {
            val (status, body) = loadPaidCredits(credential.accessToken)
            if (status in 200..299) parsePaidAssist(body) else null
        } catch (_: Exception) {
            null
        }
        val tierLabel = assist?.tierLabel ?: "订阅未知"
        val packs = mutableListOf<QuotaPack>()
        // 模型组配额：所有账号都有（会员的订阅配额 / 免费版的基础配额）
        try {
            val (status, body) = loadQuotaSummary(credential.accessToken, credential.projectId)
            if (status in 200..299) packs += quotaPacks(body, tierLabel)
        } catch (_: Exception) {
        }
        // Google One 积分：仅订阅用户有
        if (assist?.credits != null) {
            packs += QuotaPack(name = "Google One AI 积分", group = tierLabel, remain = assist.credits)
        }
        return packs
    }

    // ------------------------------------------------------------------ 凭证

    private val refreshLock = Any()

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = parse(account) ?: throw IllegalStateException("凭证无法解析，请重新登录")
        val nowSec = nowMillis() / 1000
        val hasExpiry = credential.expiresAt > 0
        val isExpired = hasExpiry && credential.expiresAt <= nowSec
        val isNearExpiry = hasExpiry && (credential.expiresAt - nowSec <= REQUEST_SAFETY_WINDOW_SECONDS)

        if (!isExpired && !isNearExpiry) return null

        synchronized(refreshLock) {
            val refreshed = refreshAccessToken(credential)
            if (refreshed != null) {
                val updated = toProviderAccount(refreshed, account)
                hooks.onAccountUpdated(updated)
                return updated
            }
        }
        if (isExpired) {
            throw IllegalStateException("凭证已过期且刷新失败，稍后重试或重新登录")
        }
        return null
    }

    // ------------------------------------------------------------------ 登录

    override fun buildAuthUrl(): String {
        val state = UUID.randomUUID().toString().replace("-", "")
        return buildString {
            append(AUTH_ENDPOINT)
            append("?client_id=").append(encode(CLIENT_ID))
            append("&redirect_uri=").append(encode(redirectUri()))
            append("&response_type=code")
            append("&scope=").append(encode(SCOPES.joinToString(" ")))
            append("&access_type=offline")
            append("&prompt=consent")
            append("&state=").append(state)
        }
    }

    override fun exchangeCode(code: String): ProviderAccount {
        val tokens = exchangeCodeForTokens(code)
        val email = fetchEmail(tokens.accessToken)
        val projectId = fetchProjectId(tokens.accessToken)
        val credential = Credential(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = tokens.expiresAt,
            email = email,
            projectId = projectId,
        )
        return toProviderAccount(credential)
    }

    /** 从回调 URL 里解析 code 并换凭证。 */
    fun completeFromCallback(callbackUrl: String): ProviderAccount {
        val code = queryParam(callbackUrl, "code")
            ?: throw IllegalStateException("回调里没有 code 参数")
        return exchangeCode(code)
    }

    // ------------------------------------------------------------------ HTTP

    private fun exchangeCodeForTokens(code: String): Tokens {
        val form = mapOf(
            "code" to code,
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "redirect_uri" to redirectUri(),
            "grant_type" to "authorization_code",
        )
        val (status, body) = postForm(tokenEndpoint, form)
        if (status !in 200..299) throw IllegalStateException("换取 token 失败（HTTP $status）：${body.take(200)}")
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("token 响应不是合法 JSON")
        val access = obj.get("access_token")?.asString.orEmpty()
        if (access.isEmpty()) throw IllegalStateException("token 响应缺少 access_token")
        val expiresIn = obj.get("expires_in")?.asLong ?: 3600L
        return Tokens(
            accessToken = access,
            refreshToken = obj.get("refresh_token")?.asString.orEmpty(),
            expiresAt = nowMillis() / 1000 + expiresIn,
        )
    }

    private fun refreshAccessToken(credential: Credential): Credential? {
        if (credential.refreshToken.isEmpty()) return null
        val form = mapOf(
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "refresh_token" to credential.refreshToken,
            "grant_type" to "refresh_token",
        )
        val (status, body) = try {
            postForm(tokenEndpoint, form)
        } catch (e: Exception) {
            hooks.onLog("Antigravity 刷新 Token 网络异常：${e.message}")
            return null
        }
        if (status !in 200..299) {
            hooks.onLog("Antigravity 刷新 Token 失败（HTTP $status）：${body.take(160)}")
            return null
        }
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val access = obj.get("access_token")?.asString.orEmpty()
        if (access.isEmpty()) return null
        val expiresIn = obj.get("expires_in")?.asLong ?: 3600L
        // Google 可能轮换 refresh_token（此时旧值作废），不回存会导致下次刷新 invalid_grant
        val nextRefresh = obj.get("refresh_token")?.asString?.takeIf { it.isNotEmpty() }
        return credential.copy(
            accessToken = access,
            refreshToken = nextRefresh ?: credential.refreshToken,
            expiresAt = nowMillis() / 1000 + expiresIn,
        )
    }

    private fun fetchEmail(accessToken: String): String {
        val (status, body) = try {
            get("https://www.googleapis.com/oauth2/v2/userinfo?alt=json", accessToken)
        } catch (_: Exception) {
            return ""
        }
        if (status !in 200..299) return ""
        return runCatching { JsonParser.parseString(body).asJsonObject.get("email")?.asString.orEmpty() }
            .getOrDefault("")
    }

    /** `loadCodeAssist` 取 project id；字段名以响应为准，做宽容提取。 */
    private fun fetchProjectId(accessToken: String): String {
        val payload = JsonObject().apply {
            add("metadata", JsonObject().apply {
                addProperty("ideType", "IDE_UNSPECIFIED")
                addProperty("platform", "PLATFORM_UNSPECIFIED")
                addProperty("pluginType", "GEMINI")
            })
        }.toString()
        val (status, body) = try {
            postJson("$apiBase/$API_VERSION:loadCodeAssist", accessToken, payload)
        } catch (_: Exception) {
            return ""
        }
        if (status !in 200..299) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        for (key in listOf("cloudaicompanionProject", "project", "projectId")) {
            obj.get(key)?.let { value ->
                when {
                    value.isJsonPrimitive -> value.asString.takeIf { it.isNotEmpty() }?.let { return it }
                    value.isJsonObject -> value.asJsonObject.get("id")?.asString
                        ?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }
        return ""
    }

    private fun open(url: String, accessToken: String, body: String?): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        return conn
    }

    private fun get(url: String, accessToken: String): Pair<Int, String> {
        val conn = open(url, accessToken, null)
        return try {
            val status = conn.responseCode
            val body = if (status in 200..299) readLimited(conn.inputStream) else readLimited(conn.errorStream)
            status to body
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(url: String, accessToken: String, body: String, connectTimeout: Int = CONNECT_TIMEOUT_MS, readTimeout: Int = READ_TIMEOUT_MS): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            instanceFollowRedirects = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", USER_AGENT)
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            outputStream.use { it.write(bytes) }
        }
        return try {
            val status = conn.responseCode
            val response = if (status in 200..299) readLimited(conn.inputStream) else readLimited(conn.errorStream)
            status to response
        } finally {
            conn.disconnect()
        }
    }

    private fun postForm(url: String, form: Map<String, String>): Pair<Int, String> {
        val encoded = form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Host", "oauth2.googleapis.com")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "Go-http-client/2.0")
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            val bytes = encoded.toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val body = if (status in 200..299) readLimited(conn.inputStream) else readLimited(conn.errorStream)
            status to body
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun parse(account: ProviderAccount): Credential? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: return null
        val access = obj.get("accessToken")?.asString.orEmpty()
        if (access.isEmpty()) return null
        return Credential(
            accessToken = access,
            refreshToken = obj.get("refreshToken")?.asString.orEmpty(),
            expiresAt = obj.get("expiresAt")?.asLong ?: 0L,
            email = obj.get("email")?.asString.orEmpty(),
            projectId = obj.get("projectId")?.asString.orEmpty(),
        )
    }

    private fun toProviderAccount(credential: Credential, existingAccount: ProviderAccount? = null): ProviderAccount {
        val secret = JsonObject().apply {
            addProperty("accessToken", credential.accessToken)
            addProperty("refreshToken", credential.refreshToken)
            addProperty("expiresAt", credential.expiresAt)
            addProperty("email", credential.email)
            addProperty("projectId", credential.projectId)
        }.toString()
        // 稳定 UID：已有账号严格沿用旧 UID，绝不因 Token 刷新而漂移；新账号优先 email，无 email 用稳定 refreshToken
        val uid = existingAccount?.uid?.takeIf { it.isNotEmpty() }
            ?: credential.email.takeIf { it.isNotEmpty() }
            ?: ("antigravity-" + credential.refreshToken.hashCode().toUInt().toString(16))
        val nickname = credential.email.takeIf { it.isNotEmpty() }
            ?: existingAccount?.nickname.orEmpty().ifEmpty { uid }
        return ProviderAccount(id, uid, nickname, secret)
    }

    private fun redirectUri(): String = "http://localhost:$CALLBACK_PORT$CALLBACK_PATH"

    private fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.objOrNull("error")?.let { error ->
            error.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        obj.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun readLimited(input: InputStream?): String {
        if (input == null) return ""
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_BODY_BYTES) {
                out.write(buffer, 0, MAX_BODY_BYTES - out.size())
                break
            }
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private fun requestedModel(body: String): String =
        runCatching { JsonParser.parseString(body).asJsonObject.get("model")?.asString.orEmpty() }
            .getOrDefault("")

    /** CLIProxyAPI 同款请求体：只要 ideType=ANTIGRAVITY，响应里带 paidTier 的积分明细。 */
    private fun loadPaidCredits(accessToken: String): Pair<Int, String> {
        val payload = JsonObject().apply {
            add("metadata", JsonObject().apply { addProperty("ideType", "ANTIGRAVITY") })
        }.toString()
        return postJson("$apiBase/$API_VERSION:loadCodeAssist", accessToken, payload)
    }

    /**
     * 模型组配额（桌面 App 展示的「Weekly Limit / Five Hour Limit」）。必须走 daily- 前缀域名：
     * 非前缀域名对 Gemini 组永远返回 remainingFraction=1（错误数据，见 agy 语言服务器日志）。
     */
    private fun loadQuotaSummary(accessToken: String, projectId: String): Pair<Int, String> {
        val payload = if (projectId.isEmpty()) {
            "{}"
        } else {
            JsonObject().apply { addProperty("project", projectId) }.toString()
        }
        return postJson("$quotaBase/$API_VERSION:retrieveUserQuotaSummary", accessToken, payload)
    }

    /** 无积分可显示时，账号卡片余额取最紧窗口的剩余百分比，detail 标注订阅层级。 */
    private fun quotaCreditInfo(credential: Credential, tierLabel: String): CreditInfo {
        val (status, body) = try {
            loadQuotaSummary(credential.accessToken, credential.projectId)
        } catch (e: Exception) {
            return CreditInfo(0, known = false, detail = "$tierLabel · 查询配额失败：${e.message}")
        }
        if (status == 401 || status == 403) {
            return CreditInfo(0, known = false, detail = "登录状态已失效，需要重新登录")
        }
        if (status !in 200..299) {
            return CreditInfo(0, known = false, detail = "$tierLabel · 查询配额失败（HTTP $status）：${body.take(200)}")
        }
        val buckets = parseQuotaBuckets(body)
        if (buckets.isEmpty()) {
            return CreditInfo(0, known = false, detail = "$tierLabel · 上游没有返回模型组配额：${body.take(200)}")
        }
        val tightest = buckets.minBy { it.remainingPct }
        val pct = tightest.remainingPct.roundToLong()
        val windows = buckets.joinToString(" · ") { bucket ->
            "${bucket.groupName} ${bucket.windowName} ${bucket.remainingPct.roundToLong()}%"
        }
        return CreditInfo(
            pct,
            known = true,
            detail = "$tierLabel · 组配额剩余 $pct%（最紧：${tightest.groupName} ${tightest.windowName}）；全部窗口：$windows",
        )
    }

    private fun quotaPacks(body: String, tierLabel: String): List<QuotaPack> =
        parseQuotaBuckets(body).map { bucket ->
            QuotaPack(
                name = bucket.groupName,
                group = "$tierLabel · ${bucket.windowName}",
                limit = 100,
                used = bucket.usedPct.roundToLong(),
                remain = bucket.remainingPct.roundToLong(),
                expireAt = bucket.resetAtSeconds,
            )
        }

    /** `loadCodeAssist` 解析结果：订阅层级 + Google One 积分（可能为空）。 */
    private data class PaidAssist(
        val tierLabel: String,
        val credits: Long?,
        val creditsDetail: String,
    )

    /**
     * 解析订阅层级与积分：`paidTier` 是 Google One 订阅（Gemini 会员 = Google AI Pro），
     * 缺失时用 `currentTier`（consumer 恒为 free-tier）。积分只认 GOOGLE_ONE_AI 条目，
     * 字符串/数字都按浮点解析；余额低于 minimumCreditAmountForUsage 记为不可用。
     */
    private fun parsePaidAssist(body: String): PaidAssist? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val tierLabel = tierLabelOf(obj)
        val credits = obj.objOrNull("paidTier")
            ?.get("availableCredits")?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.firstNotNullOfOrNull { element ->
                val credit = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@firstNotNullOfOrNull null
                if (!credit.get("creditType")?.asString.orEmpty().equals("GOOGLE_ONE_AI", ignoreCase = true)) {
                    return@firstNotNullOfOrNull null
                }
                val amount = runCatching { credit.get("creditAmount")?.asString?.trim()?.toFloat() }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                val minimum = runCatching { credit.get("minimumCreditAmountForUsage")?.asString?.trim()?.toFloat() }
                    .getOrNull() ?: 0f
                if (amount >= minimum) {
                    amount.toLong().coerceAtLeast(0) to "Google One AI 积分"
                } else {
                    0L to "Google One AI 积分（余额 $amount 已低于可用下限 $minimum）"
                }
            }
        return PaidAssist(tierLabel, credits?.first, credits?.second ?: "")
    }

    /** 订阅层级展示名（Cli-Proxy-API-Management-Center #318 同款映射）。 */
    private fun tierLabelOf(obj: JsonObject): String {
        val paidTier = obj.objOrNull("paidTier")
        val paidId = paidTier?.get("id")?.asString?.trim().orEmpty()
        val paidName = paidTier?.get("name")?.asString?.trim().orEmpty()
        if (paidId.isNotEmpty() || paidName.isNotEmpty()) {
            return when (paidId) {
                "g1-pro-tier" -> "Google AI Pro"
                "g1-ultra-tier" -> "Google AI Ultra"
                "g1-ultra-lite-tier" -> "Google AI Ultra Lite"
                else -> paidName.ifEmpty { paidId }
            }
        }
        val currentId = obj.objOrNull("currentTier")?.get("id")?.asString?.trim().orEmpty()
        val currentName = obj.objOrNull("currentTier")?.get("name")?.asString?.trim().orEmpty()
        return when {
            currentId.equals("free-tier", ignoreCase = true) -> "免费版"
            currentId.isNotEmpty() -> currentName.ifEmpty { currentId }
            else -> "订阅未知"
        }
    }

    private data class QuotaBucket(
        val groupName: String,
        val windowName: String,
        val remainingPct: Double,
        val usedPct: Double,
        val resetAtSeconds: Long,
    )

    /**
     * 解析 `retrieveUserQuotaSummary`：`groups[]` 每组（如 Gemini Models）带若干窗口
     * bucket（周/5 小时），`remainingFraction` 0-1（1 = 未用），`resetTime` ISO 时间。
     */
    private fun parseQuotaBuckets(body: String): List<QuotaBucket> {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        val groups = obj.get("groups")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val result = mutableListOf<QuotaBucket>()
        for (groupElement in groups.asJsonArray) {
            val group = groupElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val groupName = group.get("displayName")?.asString?.takeIf { it.isNotEmpty() } ?: "Models"
            val buckets = group.get("buckets")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (bucketElement in buckets.asJsonArray) {
                val bucket = bucketElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val fraction = runCatching { bucket.get("remainingFraction")?.asString?.trim()?.toDouble() }
                    .getOrNull() ?: continue
                val windowName = bucket.get("displayName")?.asString?.takeIf { it.isNotEmpty() }
                    ?: bucket.get("bucketId")?.asString?.takeIf { it.isNotEmpty() }
                    ?: "Limit"
                val bounded = fraction.coerceIn(0.0, 1.0)
                result += QuotaBucket(
                    groupName = groupName,
                    windowName = windowName,
                    remainingPct = bounded * 100.0,
                    usedPct = (1.0 - bounded) * 100.0,
                    resetAtSeconds = parseIsoSeconds(bucket.get("resetTime")?.asString),
                )
            }
        }
        return result
    }

    private fun parseIsoSeconds(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { OffsetDateTime.parse(value.trim()).toEpochSecond() }.getOrDefault(0L)
    }

    private fun isStreaming(body: String): Boolean =
        runCatching { JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean ?: false }
            .getOrDefault(false)

    private fun newCompletionId(): String =
        "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class Tokens(val accessToken: String, val refreshToken: String, val expiresAt: Long)

    private data class Credential(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val email: String,
        val projectId: String,
    )

    companion object {
        const val ID = "antigravity"

        const val CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        // 这是 Google 为「已安装应用」（installed-app）OAuth 客户端下发的公开 client_secret：
        // 按 OAuth 2.0 规范，这类客户端的 secret 无法保密、Google 也不当它作凭证，公开在客户端里是正常做法。
        // 不要因为它长得像密钥就删掉——删了 OAuth 换 token 会直接失败。
        const val CLIENT_SECRET = "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"
        const val CALLBACK_PORT = 51121

        /** Google 客户端注册的 redirect_uri 固定路径，本地回调服务按它接收。 */
        const val CALLBACK_PATH = "/oauth-callback"

        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        /** 与 CLIProxyAPI antigravityRequestTokenSafetyWindow 一致：临期 5 分钟才刷新。 */
        const val REQUEST_SAFETY_WINDOW_SECONDS = 5L * 60
        const val API_BASE = "https://cloudcode-pa.googleapis.com"
        const val API_VERSION = "v1internal"

        /**
         * daily- 域名：对话与配额查询都走它。prod（API_BASE）对消费级账号的对话请求
         * 会回 429 RESOURCE_EXHAUSTED（与额度无关），只留作 loadCodeAssist 等账号类接口。
         */
        const val QUOTA_BASE = "https://daily-cloudcode-pa.googleapis.com"

        /** 上游保留的内部/实验模型，不对外暴露（清单与 CLIProxyAPI fetch_antigravity_models 一致）。 */
        private val EXCLUDED_MODELS = setOf(
            "chat_20706", "chat_23310", "tab_flash_lite_preview", "tab_jump_flash_lite_preview",
            "gemini-2.5-flash-thinking", "gemini-2.5-pro",
        )

        private const val MODEL_CACHE_TTL_MILLIS = 10L * 60 * 1000
        private const val DEFAULT_CONTEXT_LENGTH = 1_048_576L

        const val USER_AGENT = "antigravity/hub/2.9.1 darwin/arm64"

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        )

        /** 智能别名映射：允许客户端请求简写（如 gemini-3.8-flash 映射到 gemini-3.8-flash-high）。 */
        val MODEL_ALIASES: Map<String, String> = mapOf(
            "gemini-3.8-flash" to "gemini-3.8-flash-high",
            "gemini-3.7-flash" to "gemini-3.7-flash-high",
            "gemini-3.6-flash" to "gemini-3.6-flash-high",
            "gemini-3.5-flash" to "gemini-3.5-flash-lite",
            "gemini-3.1-flash" to "gemini-3.1-flash-lite",
            "gemini-3.1-pro" to "gemini-pro-agent",
            "gemini-3-pro" to "gemini-pro-agent",
            "gemini-pro" to "gemini-pro-agent",
            "gemini-2.5-pro" to "gemini-pro-agent",
            "gemini-2.0-pro" to "gemini-pro-agent",
            "gemini-2.5-flash" to "gemini-3.8-flash-high",
            "gemini-2.5-flash-preview" to "gemini-3.8-flash-high",
            "gemini-2.0-flash" to "gemini-3.8-flash-high",
            "gemini-2.0-flash-exp" to "gemini-3.8-flash-high",
            "gemini-1.5-flash" to "gemini-3.8-flash-high",
            "gemini-1.5-flash-latest" to "gemini-3.8-flash-high",
            "gemini-flash" to "gemini-3.8-flash-high",
            "gemini-flash-1.5" to "gemini-3.8-flash-high",
            "gemini-flash-2.0" to "gemini-3.8-flash-high",
            "claude-opus" to "claude-opus-4-6-thinking",
            "claude-3-opus" to "claude-opus-4-6-thinking",
            "claude-sonnet" to "claude-sonnet-4-6",
            "claude-3-7-sonnet" to "claude-sonnet-4-6",
            "claude-3-7-sonnet-20250219" to "claude-sonnet-4-6",
            "claude-3-5-sonnet" to "claude-sonnet-4-6",
            "claude-3-5-sonnet-20241022" to "claude-sonnet-4-6",
            "claude-3-5-sonnet-latest" to "claude-sonnet-4-6",
            "claude-3-5-haiku" to "gemini-3.5-flash-lite",
            "claude-3-haiku" to "gemini-3.5-flash-lite",
            "gpt-oss" to "gpt-oss-120b-medium",
        )

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
        private const val MAX_BODY_BYTES = 1 shl 20
    }
}
