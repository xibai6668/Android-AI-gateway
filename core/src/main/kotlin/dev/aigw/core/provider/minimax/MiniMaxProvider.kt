package dev.aigw.core.provider.minimax

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.RegionAwareSupport
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * MiniMax 聚合供应商。
 *
 * 登录体系：
 * - 核心：遵循官方 RFC 8628 Device Authorization 流（浏览器打开授权，后台自动轮询拾取）；
 * - 兼容：手动导入或粘贴 JSON / 凭证串。
 *
 * 调用体系：
 * - 优先直连官方标准 OpenAI 兼容接口 `/v1/chat/completions` 与 Token Plan 额度接口 `/v1/token_plan/remains`；
 * - 兼顾备用 Agent 网页端私有通道。
 */
class MiniMaxProvider(
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val authClient: MiniMaxAuthClient = MiniMaxAuthClient(),
    private val chatClient: MiniMaxChatClient = MiniMaxChatClient(),
    private val userClient: MiniMaxUserClient = MiniMaxUserClient(),
    private val deviceIdLock: Any = Any(),
    private val apiBaseProvider: (String) -> String = { MiniMaxConstants.apiBase(it) },
) : Provider, DeviceCodeSupport, RegionAwareSupport {

    override val id: String = ID
    override val displayName: String = "MiniMax"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CREDIT_REFRESH)

    // ------------------------------------------------------------------ 区域支持

    override fun regionOf(account: ProviderAccount): String? =
        parseOrNull(account)?.region

    override fun regions(): List<String> = listOf(MiniMaxConstants.REGION_CN, MiniMaxConstants.REGION_GLOBAL)

    // ------------------------------------------------------------------ 设备码授权

    override fun startDeviceAuth(region: String): DeviceAuthTicket =
        authClient.startDeviceAuth(region)

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll =
        authClient.pollDeviceAuth(state, region)

    // ------------------------------------------------------------------ 模型目录

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView = ProviderModelCatalogView(
        models = listOf(
            ProviderModel(id = "MiniMax-M3", name = "MiniMax-M3 (百万上下文/推理代码)", contextWindow = 1_000_000),
            ProviderModel(id = "MiniMax-M2.7", name = "MiniMax-M2.7 (高品质自迭代)", contextWindow = 204_800),
            ProviderModel(id = "MiniMax-M2.7-highspeed", name = "MiniMax-M2.7-highspeed (极速版)", contextWindow = 204_800),
            ProviderModel(id = "MiniMax-M2.5", name = "MiniMax-M2.5 (高性价比旗舰)", contextWindow = 204_800),
            ProviderModel(id = "MiniMax-M2.5-highspeed", name = "MiniMax-M2.5-highspeed (极速版)", contextWindow = 204_800),
            ProviderModel(id = "MiniMax-M2.1", name = "MiniMax-M2.1 (多语言编程增强)", contextWindow = 204_800),
            ProviderModel(id = "MiniMax-M2", name = "MiniMax-M2 (高效编码)", contextWindow = 204_800),
            ProviderModel(id = MODEL_LIGHTNING, name = "Lightning (快速对话别名)", contextWindow = 204_800),
            ProviderModel(id = MODEL_PRO, name = "Pro (Agent 思考别名)", contextWindow = 1_000_000),
        ),
        fromFallback = false,
        error = "",
    )

    override fun resolveModel(requested: String): String {
        val model = requested.trim().substringAfter('/').trim()
        if (model.isEmpty() || model.equals("auto", ignoreCase = true)) return "MiniMax-M3"
        return when (model) {
            MODEL_LIGHTNING -> "MiniMax-M2.5-highspeed"
            MODEL_PRO -> "MiniMax-M3"
            else -> model
        }
    }

    override fun hosts(): List<String> = listOf("minimaxi.com", "minimax.io", "minimax.cn")

    // ------------------------------------------------------------------ 对话调用

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val mini = parseOrNull(account) ?: return FailedChatCall(401, "账号凭证无法解析，请重新登录")
        val effectiveAccount = ensureValidToken(account, mini)

        // 路径 1: 官方 OAuth Token 或 API Key（标准 OpenAI 接口直通）
        if (effectiveAccount.isOAuth || effectiveAccount.token.startsWith("sk-") || effectiveAccount.token.startsWith("ey")) {
            return openChatOfficial(effectiveAccount, openAiBody)
        }

        // 路径 2: 备用网页端私有通道
        return openChatWebAgent(effectiveAccount, openAiBody)
    }

    /** 官方标准接口调用 (/v1/chat/completions) */
    private fun openChatOfficial(mini: MiniMaxAccount, openAiBody: String): ChatCall {
        val apiBase = apiBaseProvider(mini.region)
        val url = "$apiBase${MiniMaxConstants.PATH_CHAT_COMPLETIONS}"
        val streaming = isStreamingBody(openAiBody)
        val model = resolveModel(requestedModelOf(openAiBody))

        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 300_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Accept", "text/event-stream, application/json")
                setRequestProperty("Authorization", "Bearer ${mini.activeToken}")
            }
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接 MiniMax 官方网关失败")
        }

        try {
            val bytes = openAiBody.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "发送请求数据失败")
        }

        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "获取响应状态码失败")
        }

        if (status !in 200..299) {
            val errorBody = runCatching {
                (conn.errorStream ?: conn.inputStream).bufferedReader().readText()
            }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }

        val contentType = conn.contentType.orEmpty()
        val stream = conn.inputStream
        if (contentType.contains("text/event-stream", ignoreCase = true)) {
            if (streaming) {
                return StreamingChatCall(status, stream) { conn.disconnect() }
            }
            val aggregated = try {
                OpenAiSseAggregator.aggregate(stream, model)
            } finally {
                conn.disconnect()
            }
            if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                return StreamFailureChatCall(
                    status,
                    UpstreamError(ErrorKind.CLIENT, "上游返回了空回复，请检查提示词或更换模型"),
                )
            }
            return AggregatedChatCall(status, aggregated)
        }

        val text = runCatching { stream.use { it.bufferedReader().readText() } }.getOrDefault("")
        conn.disconnect()
        if (text.isBlank()) {
            return StreamFailureChatCall(status, UpstreamError(ErrorKind.CLIENT, "上游返回了空响应 (HTTP $status)"))
        }
        if (streaming) {
            return StreamingChatCall(status, OpenAiSseAggregator.completionAsSse(text).byteInputStream(Charsets.UTF_8))
        }
        return AggregatedChatCall(status, text)
    }

    /** 备用网页端接口调用 */
    private fun openChatWebAgent(mini: MiniMaxAccount, openAiBody: String): ChatCall {
        val ready = try {
            ensureDeviceId(mini)
        } catch (e: Exception) {
            return FailedChatCall(0, "设备注册失败：${e.message}")
        }
        val requested = requestedModelOf(openAiBody).trim().substringAfter('/').ifEmpty { MODEL_LIGHTNING }
        val model = resolveModel(requested)
        val chatType = if (requested == MODEL_PRO || model == "MiniMax-M3") MiniMaxConstants.CHAT_TYPE_PRO else MiniMaxConstants.CHAT_TYPE_LIGHTNING

        val call = try {
            chatClient.openStream(ready, openAiBody, chatType, nowMillis())
        } catch (e: MiniMaxApiException) {
            return FailedChatCall(e.status, e.message ?: "连接失败")
        }
        if (call.status !in 200..299) return FailedChatCall(call.status, call.errorBody)

        if (!call.isEventStream) {
            val text = runCatching { call.stream?.use { MiniMaxHttp.readLimited(it, MiniMaxHttp.MAX_BODY_BYTES) } }
                .getOrNull().orEmpty()
            call.close()
            val code = MiniMaxErrors.extractCode(text)
            if (code != null && code != 0L) {
                return StreamFailureChatCall(200, MiniMaxErrors.fromStatus(200, text))
            }
            return StreamFailureChatCall(
                200,
                UpstreamError(ErrorKind.CLIENT, "上游未返回流式响应（Content-Type: ${call.contentType.ifEmpty { "—" }}）：${text.trim().take(120)}"),
            )
        }

        val parser = MiniMaxSseParser()
        val translator = MiniMaxOpenAiTranslator(requested)
        val translated = LineTransformStream(
            call.stream!!,
            { line -> parser.feed(line)?.let { translator.translate(it) }.orEmpty() },
            {
                listOfNotNull(parser.flush()).flatMap { translator.translate(it) } + translator.close()
            },
        )

        return if (isStreamingBody(openAiBody)) {
            StreamingChatCall(200, translated) {
                userClient.deleteConversation(ready, translator.chatId, nowMillis())
                call.close()
            }
        } else {
            val aggregated = try {
                OpenAiSseAggregator.aggregate(translated, requested)
            } finally {
                userClient.deleteConversation(ready, translator.chatId, nowMillis())
                call.close()
            }
            if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                return StreamFailureChatCall(
                    200,
                    UpstreamError(ErrorKind.CLIENT, "上游返回了空回复（会话已清理，可重试）"),
                )
            }
            AggregatedChatCall(200, aggregated)
        }
    }

    override fun classify(status: Int, body: String): UpstreamError = MiniMaxErrors.fromStatus(status, body)

    // ------------------------------------------------------------------ 额度刷新

    override fun creditInfo(account: ProviderAccount): CreditInfo {
        val mini = parseOrNull(account)
            ?: return CreditInfo(0, known = false, detail = "账号凭证无法解析，请重新登录")

        // 优先查询官方 Token Plan 残额 (/v1/token_plan/remains)
        if (mini.isOAuth || mini.token.startsWith("sk-") || mini.token.startsWith("ey")) {
            val officialQuota = fetchOfficialQuota(mini)
            if (officialQuota != null) return officialQuota
        }

        // 备用网页端 membership 查询
        return try {
            val membership = userClient.membership(mini, nowMillis())
            CreditInfo(
                balance = membership?.remainCredit ?: 0,
                known = membership?.remainCredit != null,
                detail = if (membership == null) {
                    "未取到余额"
                } else {
                    buildString {
                        if (membership.planName.isNotEmpty()) append("Plan=").append(membership.planName)
                        if (membership.remainCredit == null) {
                            if (isNotEmpty()) append("，")
                            append("剩余积分未知")
                        }
                    }
                },
            )
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "查询余额失败")
        }
    }

    private fun fetchOfficialQuota(mini: MiniMaxAccount): CreditInfo? {
        val apiBase = apiBaseProvider(mini.region)
        val url = "$apiBase${MiniMaxConstants.PATH_TOKEN_PLAN_REMAINS}"
        val conn = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer ${mini.activeToken}")
                setRequestProperty("Accept", "application/json")
            }
        }.getOrNull() ?: return null

        val status = runCatching { conn.responseCode }.getOrDefault(0)
        if (status !in 200..299) {
            conn.disconnect()
            return null
        }

        val text = runCatching { conn.inputStream.bufferedReader().readText() }.getOrDefault("")
        conn.disconnect()

        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val remains = json.objOrNull("data")?.longOrNull("total_remains_tokens")
            ?: json.longOrNull("total_remains_tokens")
            ?: json.longOrNull("remains")
        val plan = json.objOrNull("data")?.stringOrNull("plan_name")
            ?: json.stringOrNull("plan_name")
            ?: "Coding Plan"

        return CreditInfo(
            balance = remains ?: 0L,
            known = remains != null,
            detail = if (remains != null) "Plan: $plan (Token余量: $remains)" else "Plan: $plan",
        )
    }

    // ------------------------------------------------------------------ Token 自动轮换

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val mini = parseOrNull(account) ?: return null
        if (!mini.isOAuth || mini.refreshToken.isEmpty()) return null

        // 仅在 Token 临期 5 分钟内执行刷新
        val safetyWindowMs = 5 * 60 * 1000L
        val now = nowMillis()
        if (mini.expiresAt > 0 && mini.expiresAt - now > safetyWindowMs) {
            return null
        }

        val updated = authClient.refreshToken(mini) ?: return null
        return ProviderAccount(
            providerId = id,
            uid = account.uid,
            nickname = account.nickname,
            secret = updated.toJson().toString(),
        )
    }

    private fun ensureValidToken(account: ProviderAccount, mini: MiniMaxAccount): MiniMaxAccount {
        if (!mini.isOAuth || mini.refreshToken.isEmpty()) return mini
        val safetyWindowMs = 5 * 60 * 1000L
        if (mini.expiresAt > 0 && mini.expiresAt - nowMillis() <= safetyWindowMs) {
            val refreshed = authClient.refreshToken(mini)
            if (refreshed != null) {
                hooks.onAccountUpdated(
                    ProviderAccount(id, account.uid, account.nickname, refreshed.toJson().toString()),
                )
                return refreshed
            }
        }
        return mini
    }

    // ------------------------------------------------------------------ 导入兼容

    override fun importCredentials(raw: String): ProviderAccount? {
        val account = MiniMaxAccount.import(raw) ?: return null
        val uid = account.userId.ifEmpty { "mmx-" + account.token.hashCode().toUInt().toString(16) }
        return ProviderAccount(id, uid, "MiniMax (手动导入)", account.toJson().toString())
    }

    private fun parseOrNull(account: ProviderAccount): MiniMaxAccount? =
        runCatching { MiniMaxAccount.parse(account.secret) }.getOrNull()

    private fun ensureDeviceId(mini: MiniMaxAccount): MiniMaxAccount {
        if (mini.deviceId.isNotEmpty()) return mini
        return synchronized(deviceIdLock) {
            if (mini.deviceId.isNotEmpty()) {
                mini
            } else {
                val deviceId = userClient.registerDevice(mini, nowMillis())
                val updated = mini.withDeviceId(deviceId)
                hooks.onAccountUpdated(
                    ProviderAccount(id, updated.userId, "", updated.toJson().toString()),
                )
                updated
            }
        }
    }

    companion object {
        const val ID = "minimax"
        const val MODEL_LIGHTNING = "Lightning"
        const val MODEL_PRO = "Pro"
    }
}
