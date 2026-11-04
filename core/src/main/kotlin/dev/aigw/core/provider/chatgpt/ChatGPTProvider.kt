package dev.aigw.core.provider.chatgpt

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
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.queryParam
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.newCompletionId
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * ChatGPT（Sign in with ChatGPT / Codex 订阅）供应商。
 *
 * 生命周期与其它 provider 完全一致：引擎在请求前调 [refreshAccount] 续期、
 * 失败后按 [classify] 的结论换号。provider 自己**不**在 [openChat] 里重试，
 * 401 只上报分类，避免与引擎的重试链重复放大请求。
 */
class ChatGPTProvider(
    private val hooks: ProviderHooks? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, LoopbackOAuthSupport {

    override val id: String get() = ChatGPTConstants.ID
    override val displayName: String get() = ChatGPTConstants.DISPLAY_NAME
    override val authKind: AuthKind get() = AuthKind.OAUTH_LOOPBACK

    override val callbackPort: Int get() = ChatGPTConstants.CALLBACK_PORT
    override val callbackPath: String get() = ChatGPTConstants.CALLBACK_PATH

    private val authClient = ChatGPTAuthClient(nowMillis)
    private val modelCache = AtomicReference<ModelCache?>(null)
    private val lastRefreshed = ConcurrentHashMap<String, Long>()
    private val refreshCooldownUntil = ConcurrentHashMap<String, Long>()
    private val revokedAccessTokens = ConcurrentHashMap.newKeySet<String>()
    private val refreshLock = Any()

    @Volatile
    private var pendingLogin: PendingLogin? = null

    private data class PendingLogin(val state: String, val verifier: String, val createdAt: Long)
    private data class ModelCache(val models: List<ProviderModel>, val at: Long)

    // ---------- 登录 ----------

    override fun buildAuthUrl(): String {
        val pkce = authClient.newPkce()
        val state = authClient.newState()
        pendingLogin = PendingLogin(state, pkce.verifier, nowMillis())
        return authClient.buildAuthUrl(state, pkce)
    }

    /**
     * 用完整回调 URL 完成登录。
     *
     * 比 [exchangeCode] 多的两件事：校验 state（防把别人的回调塞进来）、一次性消费
     * （同一个 code 不可能被重放）。verifier 只存在内存里，进程被杀就作废——宁可重登，
     * 也不把 PKCE 秘密落盘。
     */
    override fun completeCallback(callbackUrl: String): ProviderAccount {
        val pending = pendingLogin ?: throw IllegalStateException("登录会话已失效，请重新发起登录")
        pendingLogin = null
        if (nowMillis() - pending.createdAt > ChatGPTConstants.LOGIN_TTL_MS) {
            throw IllegalStateException("登录会话已超时，请重新发起登录")
        }
        val state = queryParam(callbackUrl, "state")
        if (!state.isNullOrEmpty() && state != pending.state) {
            throw IllegalStateException("登录状态校验失败（state 不匹配），请重新发起登录")
        }
        val code = queryParam(callbackUrl, "code")
            ?: throw IllegalStateException("回调里没有 code 参数")
        return toAccount(authClient.exchangeCode(code, pending.verifier))
    }

    override fun exchangeCode(code: String): ProviderAccount {
        val pending = pendingLogin
        pendingLogin = null
        val verifier = pending?.verifier ?: throw IllegalStateException("登录会话已失效，请重新发起登录")
        return toAccount(authClient.exchangeCode(code, verifier))
    }

    private fun toAccount(credential: ChatGPTCredential): ProviderAccount {
        val uid = credential.accountId.ifEmpty {
            credential.email.ifEmpty { "chatgpt-unknown" }
        }
        val nickname = buildString {
            append(credential.email.ifEmpty { "ChatGPT 账号" })
            val plan = credential.planLabel()
            if (plan.isNotEmpty()) append(" · ").append(plan)
        }
        return ProviderAccount(id, uid, nickname, credential.toJson())
    }

    /** 粘贴凭证导入：兼容 `{access_token…}` 与 `{tokens:{access_token…}}` 两种形状。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val tokens = obj.objOrNull("tokens") ?: obj
        val access = tokens.stringOrNull("access_token") ?: tokens.stringOrNull("accessToken") ?: return null
        if (access.isBlank()) return null
        val credential = ChatGPTCredential(
            accessToken = access,
            refreshToken = tokens.stringOrNull("refresh_token") ?: tokens.stringOrNull("refreshToken").orEmpty(),
            expiresAtMillis = tokens.longOrNull("expires_at") ?: ChatGPTJwt.expiresAtMillis(access),
            accountId = tokens.stringOrNull("account_id") ?: ChatGPTJwt.accountId(access),
            email = tokens.stringOrNull("email") ?: ChatGPTJwt.email(null, access),
            plan = tokens.stringOrNull("plan") ?: ChatGPTJwt.planType(access),
        )
        if (credential.accountId.isEmpty() && credential.email.isEmpty()) return null
        return toAccount(credential)
    }

    // ---------- 模型目录 ----------

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        // 没有账号不算「失败」——登录前本就不该有模型，不能报红吓人（与其它供应商一致）。
        if (account == null) return ProviderModelCatalogView(emptyList(), fromFallback = false, error = "")
        val cached = modelCache.get()
        val now = nowMillis()
        if (cached != null && now - cached.at < ChatGPTConstants.MODEL_CACHE_TTL_MS) {
            return ProviderModelCatalogView(cached.models, false, "")
        }
        return try {
            val models = fetchModels(credentialOf(account))
            modelCache.set(ModelCache(models, now))
            ProviderModelCatalogView(models, false, "")
        } catch (e: Exception) {
            if (cached != null) {
                ProviderModelCatalogView(cached.models, true, e.message.orEmpty())
            } else {
                ProviderModelCatalogView(emptyList(), false, e.message ?: "模型列表拉取失败")
            }
        }
    }

    override fun invalidateModelCache() {
        modelCache.set(null)
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim().removePrefix("models/").trim()
        if (model.isEmpty() || model.equals("auto", ignoreCase = true)) {
            return modelCache.get()?.models?.firstOrNull()?.id ?: ChatGPTConstants.DEFAULT_MODEL
        }
        val cached = modelCache.get()?.models
        cached?.firstOrNull { it.id.equals(model, ignoreCase = true) }?.let { return it.id }
        return model
    }

    private fun fetchModels(credential: ChatGPTCredential): List<ProviderModel> {
        val url = "${ChatGPTConstants.CODEX_BASE}/models?client_version=${ChatGPTConstants.CLIENT_VERSION}"
        val conn = HttpSupport.open(
            url = url,
            method = "GET",
            headers = baseHeaders(credential),
            connectTimeoutMs = ChatGPTConstants.CONNECT_TIMEOUT_MS,
            readTimeoutMs = ChatGPTConstants.CONNECT_TIMEOUT_MS,
        )
        return try {
            val status = conn.responseCode
            val raw = HttpSupport.readBody(conn)
            if (status !in 200..299) {
                throw IllegalStateException("模型列表拉取失败（HTTP $status）：${raw.take(160)}")
            }
            OpenAiResponses.parseModels(raw)
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 对话 ----------

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = credentialOf(account)
        val model = resolveModel(requestedModelOf(openAiBody))
        val streaming = isStreamingBody(openAiBody)
        val payload = try {
            OpenAiResponses.toResponsesRequest(openAiBody, model)
        } catch (e: Exception) {
            return FailedChatCall(400, errorBody("请求体转换失败：${e.message}"))
        }
        hooks?.onVerbose?.invoke("chatgpt", payload)

        val conn = HttpSupport.open(
            url = "${ChatGPTConstants.CODEX_BASE}/responses",
            method = "POST",
            headers = baseHeaders(credential) + mapOf(
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
            ),
            body = payload,
            connectTimeoutMs = ChatGPTConstants.CONNECT_TIMEOUT_MS,
            readTimeoutMs = ChatGPTConstants.READ_TIMEOUT_MS,
        )
        val status = conn.responseCode
        if (status !in 200..299) {
            val body = HttpSupport.readBody(conn)
            conn.disconnect()
            return FailedChatCall(status, body)
        }

        val translator = OpenAiResponses.SseTranslator(newCompletionId(), model)
        val translated = LineTransformStream(
            source = conn.inputStream,
            transform = { line -> translator.translate(line) },
            onFinish = { translator.onFinish() },
        )
        return if (streaming) {
            StreamingChatCall(status, translated) { conn.disconnect() }
        } else {
            try {
                AggregatedChatCall(status, OpenAiSseAggregator.aggregate(translated, model))
            } finally {
                conn.disconnect()
            }
        }
    }

    // ---------- 凭证刷新 ----------

    /**
     * 临期刷新。策略与引擎里其它 OAuth 供应商一致（锁内复查 + 冷却 + 失效拉黑）：
     * - 距过期 > max(5 分钟, 引擎给的 skew) 不动；
     * - 同一账号 30 秒内刚刷过就复用，不重复打 auth 端点；
     * - 刷新请求不可被调用方取消打断（上游可能已轮换 refresh_token，丢了就要重登）。
     */
    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = runCatching { credentialOf(account) }.getOrNull() ?: return null
        if (!credential.hasRefreshToken) return null
        if (credential.accessToken in revokedAccessTokens) return null

        val uid = account.uid
        val now = nowMillis()
        val margin = maxOf(ChatGPTConstants.REFRESH_LEEWAY_MS, skewSeconds.coerceAtLeast(0) * 1000)
        if (!credential.isExpiringWithin(margin, now)) return null
        if (now < (refreshCooldownUntil[uid] ?: 0L)) return null

        synchronized(refreshLock) {
            val refreshedAt = lastRefreshed[uid] ?: 0L
            if (now - refreshedAt < ChatGPTConstants.REFRESH_REUSE_MS) return null
            return try {
                val fresh = authClient.refresh(credential.refreshToken)
                lastRefreshed[uid] = now
                val merged = fresh.copy(
                    accountId = fresh.accountId.ifEmpty { credential.accountId },
                    email = fresh.email.ifEmpty { credential.email },
                    plan = fresh.plan.ifEmpty { credential.plan },
                )
                hooks?.onLog?.invoke("ChatGPT 账号 $uid 凭证已刷新（${merged.planLabel().ifEmpty { "未知档位" }}）")
                val updated = ProviderAccount(id, uid, account.nickname, merged.toJson())
                // 轮换后的 refresh_token 必须落库：丢了就只能重新登录。
                hooks?.onAccountUpdated?.invoke(updated)
                updated
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                if (message.contains("invalid_grant", ignoreCase = true) ||
                    message.contains("refresh_token", ignoreCase = true)
                ) {
                    // refresh_token 失效是终态：拉黑这个 access token，避免每个请求都去撞 auth 端点。
                    revokedAccessTokens.add(credential.accessToken)
                    hooks?.onLog?.invoke("ChatGPT 账号 $uid 刷新凭证失效：$message")
                } else {
                    refreshCooldownUntil[uid] = now + ChatGPTConstants.REFRESH_COOLDOWN_MS
                    hooks?.onLog?.invoke("ChatGPT 账号 $uid 刷新失败（将退避）：$message")
                }
                null
            }
        }
    }

    // ---------- 错误分类 / 额度 ----------

    override fun classify(status: Int, body: String): UpstreamError {
        val message = upstreamMessage(body)
        return when {
            status == 401 -> UpstreamError(ErrorKind.SESSION_DEAD, "ChatGPT 登录已失效，请重新登录（$message）")
            status == 403 -> UpstreamError(ErrorKind.SESSION_DEAD, "ChatGPT 拒绝访问：$message")
            status == 429 -> {
                val limitHit = body.contains("limit_reached", true) ||
                    body.contains("usage_limit", true) ||
                    body.contains("usage limit", true)
                if (limitHit) {
                    UpstreamError(ErrorKind.QUOTA, "ChatGPT 订阅额度已用完：$message")
                } else {
                    UpstreamError(ErrorKind.SOFT_RATE, "ChatGPT 限流：$message")
                }
            }

            status in 500..599 -> UpstreamError(ErrorKind.SERVER, "ChatGPT 服务端错误（HTTP $status）")
            status == 404 -> UpstreamError(ErrorKind.NOT_FOUND, "ChatGPT 路由不存在：$message")
            status in 400..499 -> UpstreamError(ErrorKind.CLIENT, "ChatGPT 请求被拒（HTTP $status）：$message")
            else -> UpstreamError(ErrorKind.SERVER, "ChatGPT 未知错误（HTTP $status）")
        }
    }

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = runCatching { credentialOf(account) }.getOrNull() ?: return null
        val detail = listOfNotNull(
            credential.planLabel().takeIf { it.isNotEmpty() },
            credential.email.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        // 订阅额度不是「剩余次数」，余额未知；具体窗口走 creditPacks。
        return CreditInfo(balance = 0, known = false, detail = detail)
    }

    override fun creditPacks(account: ProviderAccount): List<QuotaPack> = runCatching {
        val credential = credentialOf(account)
        OpenAiResponses.parseUsage(fetchUsage(credential))
    }.getOrElse { emptyList() }

    private fun fetchUsage(credential: ChatGPTCredential): String {
        val conn = HttpSupport.open(
            url = ChatGPTConstants.USAGE_ENDPOINT,
            method = "GET",
            headers = baseHeaders(credential),
            connectTimeoutMs = ChatGPTConstants.CONNECT_TIMEOUT_MS,
            readTimeoutMs = ChatGPTConstants.CONNECT_TIMEOUT_MS,
        )
        return try {
            val status = conn.responseCode
            val raw = HttpSupport.readBody(conn)
            if (status !in 200..299) {
                throw IllegalStateException("用量查询失败（HTTP $status）：${raw.take(160)}")
            }
            raw
        } finally {
            conn.disconnect()
        }
    }

    override fun hosts(): List<String> = listOf("chatgpt.com", "auth.openai.com", "api.openai.com")

    // ---------- 内部工具 ----------

    private fun credentialOf(account: ProviderAccount): ChatGPTCredential {
        val credential = runCatching { ChatGPTCredential.fromJson(account.secret) }.getOrNull()
        if (credential == null || credential.accessToken.isBlank()) {
            throw IllegalStateException("ChatGPT 账号凭证损坏，请重新登录")
        }
        return credential
    }

    private fun baseHeaders(credential: ChatGPTCredential): Map<String, String> {
        val headers = linkedMapOf(
            "Authorization" to "Bearer ${credential.accessToken}",
            "originator" to ChatGPTConstants.ORIGINATOR,
            "User-Agent" to ChatGPTConstants.USER_AGENT,
        )
        if (credential.accountId.isNotEmpty()) {
            headers["ChatGPT-Account-ID"] = credential.accountId
        }
        return headers
    }

    /** 从 OpenAI 风格错误体里抽一句能给用户看的话。 */
    private fun upstreamMessage(body: String): String {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        obj?.let { root ->
            root.objOrNull("error")?.let { error ->
                error.stringOrNull("message")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            root.stringOrNull("detail")?.takeIf { it.isNotBlank() }?.let { return it }
            root.stringOrNull("message")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return body.take(160)
    }

    private fun errorBody(message: String): String =
        JsonObject().apply {
            add("error", JsonObject().apply { addProperty("message", message) })
        }.toString()
}
