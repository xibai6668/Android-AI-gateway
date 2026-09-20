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
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.queryParam
import dev.aigw.core.protocol.OpenAiGemini
import dev.aigw.core.util.objOrNull
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
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val apiBase: String = API_BASE,
    private val quotaBase: String = QUOTA_BASE,
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
) : Provider, LoopbackOAuthSupport {

    override val id: String = ID
    override val displayName: String = "Antigravity"
    override val authKind: AuthKind = AuthKind.OAUTH_LOOPBACK

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView =
        ProviderModelCatalogView(
            models = FALLBACK_MODELS,
            fromFallback = true,
            error = "",
        )

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return FALLBACK_MODELS.first().id
        return model
    }

    /** 全部走 Google 域名（含 cloudcode-pa / oauth2 / www.googleapis.com）。 */
    override fun hosts(): List<String> = listOf("googleapis.com", "accounts.google.com")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        if (credential.projectId.isEmpty()) {
            return FailedChatCall(400, "该账号缺少 project id，请重新登录以获取")
        }
        val streaming = isStreaming(openAiBody)
        val model = resolveModel(requestedModel(openAiBody))
        val request = OpenAiGemini.toGeminiRequest(openAiBody)
        val body = OpenAiGemini.envelope(credential.projectId, model, request, OpenAiGemini.newRequestId())

        val conn = try {
            open("$apiBase/$API_VERSION:streamGenerateContent?alt=sse", credential.accessToken, body)
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
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 404 -> ErrorKind.NOT_FOUND
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

    /**
     * 覆盖全局 skew（默认 24h）：Google access token 寿命约 1 小时，若按全局 skew 判断，
     * 每次对话都会刷新一次，高频刷新会触发 Google 风控吊销 refresh_token（表现为账号
     * 登录后几分钟就失效）。与 CLIProxyAPI 一致，只在临近过期 5 分钟内才刷新。
     *
     * token 已过期且刷新失败时抛异常（而非返回 null 拿旧 token 硬闯 401——那会触发
     * SESSION_DEAD 硬禁用）：对话路径会捕获并换下一个账号，只记短冷却。
     */
    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = parse(account) ?: throw IllegalStateException("凭证无法解析，请重新登录")
        val expired = credential.expiresAt <= nowMillis() / 1000
        if (!expired && credential.expiresAt - nowMillis() / 1000 > REQUEST_SAFETY_WINDOW_SECONDS) return null
        synchronized(refreshLock) {
            val refreshed = refreshAccessToken(credential)
            if (refreshed != null) return toProviderAccount(refreshed)
        }
        if (expired) {
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
        } catch (_: Exception) {
            return null
        }
        if (status !in 200..299) return null
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
            setRequestProperty("Accept", "text/event-stream, application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("X-Goog-Api-Client", API_CLIENT)
            setRequestProperty("Client-Metadata", CLIENT_METADATA)
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

    private fun postJson(url: String, accessToken: String, body: String): Pair<Int, String> {
        val conn = open(url, accessToken, body)
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
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
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

    private fun toProviderAccount(credential: Credential): ProviderAccount {
        val secret = JsonObject().apply {
            addProperty("accessToken", credential.accessToken)
            addProperty("refreshToken", credential.refreshToken)
            addProperty("expiresAt", credential.expiresAt)
            addProperty("email", credential.email)
            addProperty("projectId", credential.projectId)
        }.toString()
        val uid = credential.email.ifEmpty { "antigravity-" + credential.accessToken.hashCode().toUInt().toString(16) }
        return ProviderAccount(id, uid, credential.email, secret)
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

        /** 配额查询专用域名：daily- 前缀才返回实时配额，非前缀域名 Gemini 组恒为 100% 剩余。 */
        const val QUOTA_BASE = "https://daily-cloudcode-pa.googleapis.com"

        const val USER_AGENT = "antigravity/1.15.8 windows/amd64"
        const val API_CLIENT = "google-cloud-sdk vscode_cloudshelleditor/0.1"
        const val CLIENT_METADATA = """{"ideType":"ANTIGRAVITY","platform":"MACOS","pluginType":"GEMINI"}"""

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        )

        /** 内置模型快照（来源：Antigravity API spec，实测可用）。 */
        val FALLBACK_MODELS: List<ProviderModel> = listOf(
            ProviderModel("claude-sonnet-4-6", "Claude Sonnet 4.6", 200_000),
            ProviderModel("claude-opus-4-6-thinking", "Claude Opus 4.6 Thinking", 200_000),
            ProviderModel("gemini-3-pro-high", "Gemini 3 Pro High", 1_000_000),
            ProviderModel("gemini-3-pro-low", "Gemini 3 Pro Low", 1_000_000),
            ProviderModel("gpt-oss-120b-medium", "GPT-OSS 120B Medium", 128_000),
        )

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
        private const val MAX_BODY_BYTES = 1 shl 20
    }
}
