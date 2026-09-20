package dev.aigw.core.provider.trae

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.WebLoginSupport
import dev.aigw.core.provider.WebLoginTicket
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.queryParam
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.store.KeyValueStore
import java.util.UUID

/**
 * Trae 国内版 SOLO 通道。
 *
 * 上游是私有协议（`llm_utils_chat` + 自定义 SSE 事件），所以这里承担最重的转换：
 * 请求体由 [TraePayload] 改写，响应流由 [SoloSseParser] + [OpenAiSseTranslator] 翻译成 OpenAI SSE，
 * 非流式则由 [OpenAiAggregator] 聚合。对网关来说它和别的 provider 没有区别。
 */
class TraeProvider(
    private val store: KeyValueStore,
    private val version: () -> TraeVersion,
    private val defaultModel: () -> String,
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, WebLoginSupport {

    override val id: String = ID
    override val displayName: String = "Trae"
    override val authKind: AuthKind = AuthKind.WEBVIEW_CALLBACK
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN)

    private var authClient = TraeAuthClient(version())
    private var chatClient = TraeChatClient(version())
    private var checkinClient = TraeCheckinClient(version())
    private var catalog = TraeModelCatalog(TraeChatClient(version()), nowMillis)

    /** 客户端版本号变更后重建各客户端，并让模型目录缓存失效。 */
    fun reconfigure() {
        authClient = TraeAuthClient(version())
        chatClient = TraeChatClient(version())
        checkinClient = TraeCheckinClient(version())
        catalog = TraeModelCatalog(TraeChatClient(version()), nowMillis)
    }

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val trae = account?.let { parseOrNull(it) }
        val models = catalog.models(trae)
        return ProviderModelCatalogView(
            models = models.map { ProviderModel(it.id, it.name, it.contextWindow) },
            fromFallback = catalog.fromFallback,
            error = catalog.lastError,
        )
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return defaultModel()
        // 去掉 `xxx__dev` 这类内部后缀；查不到目录就原样交给上游判定
        return model.substringBefore("__")
    }

    override fun isInternalModel(id: String): Boolean = TraeModelCatalog.isInternal(id)

    override fun hosts(): List<String> = listOf("trae-api-cn.mchost.guru", "api.trae.com.cn", "api.trae.cn")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val trae = parseOrNull(account) ?: return FailedChatCall(401, "账号凭证无法解析")
        val model = resolveModel(requestedModelOf(openAiBody))
        val call = try {
            chatClient.openStream(trae, openAiBody)
        } catch (e: TraeHttpException) {
            return FailedChatCall(e.status, e.body)
        }
        if (call.status >= 400) return FailedChatCall(call.status, call.errorBody)
        val stream = call.stream ?: return FailedChatCall(call.status, call.errorBody)

        if (isStreamingBody(openAiBody)) {
            val parser = SoloSseParser()
            val translator = OpenAiSseTranslator(newCompletionId(), model)
            val transformed = LineTransformStream(
                source = stream,
                transform = transform@{ line ->
                    val event = parser.feed(line) ?: return@transform emptyList()
                    report(event, trae.uid)
                    translator.translate(event)
                },
                onFinish = { translator.close() },
            )
            return StreamingChatCall(call.status, transformed) { call.close() }
        }

        val parser = SoloSseParser()
        val aggregator = OpenAiAggregator(newCompletionId(), model)
        try {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val event = parser.feed(line) ?: continue
                    aggregator.accept(event)
                }
            }
        } catch (e: Exception) {
            return FailedChatCall(200, e.message ?: "读取上游流失败")
        } finally {
            call.close()
        }
        aggregator.soloCredits?.let { hooks.onBilling(id, trae.uid, it) }
        val failure = aggregator.failure
        if (failure != null) {
            return StreamFailureChatCall(
                200,
                UpstreamError(failure.kind.toErrorKind(), failure.message.ifEmpty { "上游流内错误" }, failure.code),
            )
        }
        return AggregatedChatCall(200, aggregator.build().toString())
    }

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val trae = parseOrNull(account) ?: return null
        val refreshed = authClient.refreshIfNeeded(trae, skewSeconds) ?: return null
        return toProviderAccount(refreshed)
    }

    /** 粘贴 JSON 凭证导入：补齐设备指纹后换 token 并拉取用户信息。 */
    override fun importCredentials(raw: String): ProviderAccount {
        var account = ensureDeviceIds(TraeAccount.parse(raw))
        if (account.refreshToken.isNotEmpty()) account = authClient.exchangeToken(account)
        account = authClient.getUserInfo(account)
        if (account.uid.isEmpty()) throw IllegalStateException("无法确定账号 uid，请确认凭证完整")
        return toProviderAccount(account)
    }

    // ------------------------------------------------------------------ 设备指纹

    /** 当前账号用于 `x-device-id` 的设备指纹。 */
    fun deviceIdOf(account: ProviderAccount): String = parseOrNull(account)?.deviceId.orEmpty()

    /**
     * 校验设备 ID 格式；返回空串表示合法。
     *
     * 官方客户端的 `x-device-id` 是纯数字（15~16 位），非数字值会被上游以 9074 拒绝签到。
     */
    fun validateDeviceId(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "设备 ID 不能为空"
        if (!TraeLogin.looksLikeDeviceId(trimmed)) {
            return "设备 ID 应该是纯数字（15~16 位）。你填的看起来不是 device_id，" +
                "注意别把它和 64 位的 machine_id 搞混。"
        }
        return ""
    }

    /** 用新的设备 ID 生成新 secret（保留机器码）。 */
    fun withDeviceId(account: ProviderAccount, deviceId: String): String {
        val trae = parseOrNull(account) ?: return account.secret
        return trae.copy(deviceId = deviceId.trim()).toJson().toString()
    }

    /** 重新生成一套官方格式的指纹（machine_id 64 位 hex + device_id 纯数字），返回新 secret。 */
    fun regenerateDeviceIds(account: ProviderAccount): String {
        val trae = parseOrNull(account) ?: return account.secret
        return trae.copy(
            machineId = TraeLogin.newMachineId(),
            deviceId = TraeLogin.randomDeviceId(),
        ).toJson().toString()
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val kind = TraeErrors.fromStatus(status, body)
        val exception = TraeHttpException(status, kind, body, TraeErrors.extractCode(body))
        return UpstreamError(kind.toErrorKind(), exception.message ?: "上游错误", exception.upstreamCode)
    }

    // ------------------------------------------------------------------ 额度 / 动作

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val trae = parseOrNull(account) ?: return null
        return try {
            val usage = checkinClient.entUsage(trae)
            if (usage.parsed) {
                CreditInfo(usage.remain, known = true, detail = "权益包剩余 ${usage.remain} / ${usage.limit}")
            } else {
                CreditInfo(0, known = false, detail = "上游未返回可解析的额度")
            }
        } catch (e: TraeHttpException) {
            CreditInfo(0, known = false, detail = e.message ?: "刷新额度失败")
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "刷新额度失败")
        }
    }

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult = when (action) {
        ACTION_CHECKIN -> checkin(account)
        else -> ProviderActionResult.unsupported(action)
    }

    /** Trae 的额度包明细（账单口径，不随 SOLO 消耗变化）。 */
    override fun creditPacks(account: ProviderAccount): List<QuotaPack> {
        val trae = parseOrNull(account) ?: return emptyList()
        return try {
            checkinClient.entUsage(trae).details.map {
                QuotaPack(
                    name = it.name,
                    group = it.group,
                    limit = it.limit,
                    used = it.used,
                    remain = it.remain,
                    expireAt = it.expireAt,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 单个账号签到：先查状态 → 未签到则领取 → 9074（设备指纹/限流被拒）退避后重试一次。
     * 额度刷新由网关在动作成功后另行调用 [creditInfo] 完成。
     */
    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val trae = parseOrNull(account) ?: return ProviderActionResult.failure("账号凭证无法解析")
        return try {
            val status = checkinClient.status(trae)
            if (status.checkedIn) return ProviderActionResult.success("今日已签到")
            val outcome = claimWithRetry(trae)
            if (outcome == ClaimOutcome.ALREADY_CHECKED_IN) {
                ProviderActionResult.success("今日已签到")
            } else {
                ProviderActionResult.success("签到成功")
            }
        } catch (e: TraeHttpException) {
            ProviderActionResult.failure(traeErrorMessage(e))
        } catch (e: Exception) {
            ProviderActionResult.failure(e.message ?: "签到失败")
        }
    }

    private fun claimWithRetry(account: TraeAccount): ClaimOutcome = try {
        checkinClient.claim(account)
    } catch (e: TraeHttpException) {
        if (e.upstreamCode != CHECKIN_BUSY_CODE) throw e
        Thread.sleep(CHECKIN_RETRY_DELAY_MS)
        checkinClient.claim(account)
    }

    // ------------------------------------------------------------------ 登录

    /** 进行中的登录 traceId → 设备指纹；同一进程内回调直接命中，限容防止长期累积。 */
    private val pending = object : LinkedHashMap<String, Pair<String, String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>): Boolean =
            size > MAX_PENDING
    }

    /** pending 指纹的持久化键；进程被杀后由 [resolveMachine] 从 store 找回。 */
    private fun pendingKey(traceId: String): String = "login/pending/trae/$traceId"

    override fun beginWebLogin(callbackUrl: String): WebLoginTicket {
        // 对齐官方客户端指纹格式：machine_id 是 64 位 hex，device_id 是纯数字
        val machineId = TraeLogin.newMachineId()
        val deviceId = TraeLogin.randomDeviceId()
        val traceId = TraeLogin.machineTraceId(machineId, deviceId)
        synchronized(pending) {
            pending[traceId] = machineId to deviceId
        }
        // 同时落盘：进程被杀或回调晚到时，仍能拿回与登录一致的设备指纹
        store.write(
            pendingKey(traceId),
            JsonObject().apply {
                addProperty("machineId", machineId)
                addProperty("deviceId", deviceId)
                addProperty("at", nowMillis())
            }.toString(),
        )
        return WebLoginTicket(traceId, TraeLogin.buildLoginUrl(machineId, deviceId, callbackUrl), callbackUrl)
    }

    override fun completeWebLogin(callbackUrl: String): ProviderAccount {
        val callback = TraeLogin.parseCallback(callbackUrl)
        val traceId = queryParam(callbackUrl, "loginTraceID")
        val machine = resolveMachine(traceId)
        var account = TraeAccount(
            uid = callback.uid,
            accessToken = callback.accessToken,
            refreshToken = callback.refreshToken,
            expiresAt = callback.expiresAt,
            nickname = callback.nickname,
            enterpriseId = callback.enterpriseId,
            machineId = machine?.first ?: TraeLogin.newMachineId(),
            deviceId = machine?.second ?: TraeLogin.randomDeviceId(),
        )
        if (account.refreshToken.isNotEmpty()) account = authClient.exchangeToken(account)
        account = authClient.getUserInfo(account)
        if (account.uid.isEmpty()) throw IllegalStateException("无法确定账号 uid，请确认登录已完成")
        return toProviderAccount(account)
    }

    /**
     * 找回登录时用过的设备指纹。
     * 找不回来意味着凭证的设备指纹与注册设备不一致（UG 接口会以 9074 拒绝签到），
     * 所以只做查找，不静默换新的。
     */
    private fun resolveMachine(traceId: String?): Pair<String, String>? {
        if (traceId.isNullOrEmpty()) return null
        synchronized(pending) { pending[traceId] }?.let { return it }
        val raw = store.read(pendingKey(traceId)) ?: return null
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val machineId = obj.get("machineId")?.asString.orEmpty()
        val deviceId = obj.get("deviceId")?.asString.orEmpty()
        return if (machineId.isEmpty() || deviceId.isEmpty()) null else machineId to deviceId
    }

    // ------------------------------------------------------------------ 工具

    private fun report(event: SoloEvent, uid: String) {
        when (event) {
            is SoloEvent.NotifyUsage -> event.ideCredits?.let { hooks.onBilling(id, uid, it) }
            is SoloEvent.Failure -> {
                val kind = SoloStreamError(event.code, event.message).kind
                hooks.onAccountError(
                    id,
                    uid,
                    UpstreamError(kind.toErrorKind(), event.message.ifEmpty { "上游流内错误" }, event.code),
                )
            }
            else -> Unit
        }
    }

    private fun toProviderAccount(account: TraeAccount): ProviderAccount =
        ProviderAccount(id, account.uid, account.nickname, account.toJson().toString())

    private fun parseOrNull(account: ProviderAccount): TraeAccount? =
        runCatching { TraeAccount.parse(account.secret) }.getOrNull()

    private fun newCompletionId(): String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)

    companion object {
        const val ID = "trae"

        /** 「签到」这个 provider 专属动作的名字，UI 组件与 provider 约定一致。 */
        const val ACTION_CHECKIN = dev.aigw.core.provider.ACTION_CHECKIN

        private const val MAX_PENDING = 40
        private const val CHECKIN_BUSY_CODE = 9074L
        private const val CHECKIN_RETRY_DELAY_MS = 8_000L
    }
}

/** 把不符合官方格式的设备指纹就地修正（machine_id 64 位 hex、device_id 纯数字）。 */
internal fun ensureDeviceIds(account: TraeAccount): TraeAccount {
    var result = account
    if (result.machineId.length != 64) result = result.copy(machineId = TraeLogin.newMachineId())
    if (!TraeLogin.looksLikeDeviceId(result.deviceId)) result = result.copy(deviceId = TraeLogin.randomDeviceId())
    return result
}

/** 把上游异常转成一句人话；少数码需要补充「怎么做」。 */
internal fun traeErrorMessage(e: TraeHttpException): String = when (e.upstreamCode) {
    9074L -> "签到被拒：设备指纹未通过校验，去账号详情点「重新生成」后重试"
    4001L -> "模型或客户端版本不被上游接受（可在供应商设置里上调版本号）"
    else -> e.message ?: "上游错误"
}

internal fun TraeErrorKind.toErrorKind(): ErrorKind = when (this) {
    TraeErrorKind.PLAN_LIMIT -> ErrorKind.QUOTA
    TraeErrorKind.SOFT_RATE -> ErrorKind.SOFT_RATE
    TraeErrorKind.NOT_FOUND -> ErrorKind.NOT_FOUND
    TraeErrorKind.SESSION_DEAD -> ErrorKind.SESSION_DEAD
    TraeErrorKind.SERVER -> ErrorKind.SERVER
    TraeErrorKind.CLIENT -> ErrorKind.CLIENT
}
