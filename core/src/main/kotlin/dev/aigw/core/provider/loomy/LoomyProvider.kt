package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.provider.SmsLoginSupport
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError

/**
 * Loomy（讯飞）免费积分通道。
 *
 * 上游 `/api/v1/chat/completions` 本身就是 OpenAI 兼容协议，所以这里几乎不做协议改写：
 * 选号 → 补认证/追踪头 → 转发 → 原样回传 SSE。需要注意上游对鉴权失败返回的是
 * **HTTP 200 + 业务码**（不是 4xx），必须按业务码判定，否则会把错误 JSON 当正常响应透传。
 */
class LoomyProvider(
    private val defaultModel: () -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pointsClient: LoomyPointsClient = LoomyPointsClient(),
) : Provider, SmsLoginSupport {

    override val id: String = ID
    override val displayName: String = "Loomy"
    override val authKind: AuthKind = AuthKind.SMS_CODE
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.TASKS)

    private val authClient = LoomyAuthClient(nowMillis = nowMillis)
    private val chatClient = LoomyChatClient()
    private val taskClient = LoomyTaskClient()
    private val catalog = LoomyModelCatalog(nowMillis = nowMillis)

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val session = account?.let { parseOrNull(it)?.session }
        val models = catalog.models(session)
        return ProviderModelCatalogView(
            models = models.map {
                ProviderModel(
                    id = it.id,
                    name = it.name,
                    contextWindow = it.contextLength,
                    extra = buildMap {
                        if (it.reasoning) put("reasoning", "1")
                        if (it.toolCall) put("tool_call", "1")
                        if (it.attachment) put("attachment", "1")
                    },
                )
            },
            fromFallback = catalog.fromFallback,
            error = catalog.lastError,
        )
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return defaultModel()
        // 去掉 `provider/model` 里的 provider 前缀
        return model.substringAfter('/').trim().ifEmpty { model }
    }

    override fun hosts(): List<String> = listOf("xfinfr.com", "xunfei.cn")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val loomy = parseOrNull(account) ?: return FailedChatCall(401, "账号凭证无法解析")
        val model = resolveModel(requestedModelOf(openAiBody))
        val streaming = isStreamingBody(openAiBody)

        val call = try {
            chatClient.openStream(loomy, openAiBody)
        } catch (e: LoomyApiException) {
            return FailedChatCall(e.status, e.body)
        }
        if (call.status !in 200..299) return FailedChatCall(call.status, call.errorBody)

        // 上游回 JSON：可能是正常非流式响应，也可能是「HTTP 200 + 业务错误码」
        if (!call.isEventStream) {
            val text = runCatching { call.stream?.use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrNull() ?: call.errorBody
            call.close()
            val code = LoomyErrors.extractCode(text)
            if (code.isNotEmpty() && code != LoomyConstants.CODE_OK) {
                val kind = LoomyErrors.fromStatus(200, text)
                return StreamFailureChatCall(
                    200,
                    UpstreamError(kind.toErrorKind(), LoomyErrors.extractMessage(text), 0),
                )
            }
            return AggregatedChatCall(200, text)
        }

        if (streaming) {
            return StreamingChatCall(200, call.stream!!) { call.close() }
        }
        val aggregated = try {
            OpenAiSseAggregator.aggregate(call.stream!!, model)
        } finally {
            call.close()
        }
        return AggregatedChatCall(200, aggregated)
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val kind = LoomyErrors.fromStatus(status, body)
        val exception = LoomyApiException(status, kind, body, LoomyErrors.extractCode(body))
        return UpstreamError(kind.toErrorKind(), exception.message ?: "上游错误")
    }

    // ------------------------------------------------------------------ 额度 / 动作

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val loomy = parseOrNull(account)
            ?: throw IllegalStateException("账号凭证无法解析，请重新登录")
        return try {
            val snapshot = pointsClient.balance(loomy.session)
            // 对话扣的是当日赠送账本（records 的 dailyRemainingPoints，实测会随消耗下降），
            // 而 balance 字段是永久账本不动；可用总余额 = 主账本 + 当日剩余，两者都来自 records。
            // （Web 版的 /api/auth/points-summary 在桌面端 base 下实测 404，不要再用。）
            val daily = snapshot.dailyRemaining.coerceAtLeast(0)
            CreditInfo(
                balance = snapshot.balance + daily,
                known = snapshot.known,
                detail = when {
                    snapshot.known -> "${snapshot.source}=${snapshot.balance}，当日剩余=${formatOrDash(snapshot.dailyRemaining)}"
                    // 两个账本都没拿到数：带响应片段，便于区分 session 失效与接口口径变化
                    else -> "未取到余额（响应片段：${snapshot.personalRaw.take(120)}）"
                },
            )
        } catch (e: LoomyApiException) {
            CreditInfo(0, known = false, detail = e.message ?: "刷新积分失败")
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "刷新积分失败")
        }
    }

    /** Loomy 没有「额度包」，但有两个账本（个人/团队）与当日赠送额度，都列出来。 */
    override fun creditPacks(account: ProviderAccount): List<QuotaPack> {
        val loomy = parseOrNull(account)
            ?: throw IllegalStateException("账号凭证无法解析，请重新登录")
        return try {
            val snapshot = pointsClient.balance(loomy.session)
            buildList {
                if (snapshot.personal >= 0) {
                    add(QuotaPack(name = "个人积分", group = "个人", remain = snapshot.personal))
                }
                if (snapshot.team >= 0) {
                    add(QuotaPack(name = "团队积分", group = "团队", remain = snapshot.team))
                }
                // 官方 Web 版读的是 dailyBalance；桌面 records 可能只回 dailyRemainingPoints，两者任一存在都列出
                val dailyRemain = when {
                    snapshot.dailyRemaining >= 0 -> snapshot.dailyRemaining
                    snapshot.dailyBalance >= 0 -> snapshot.dailyBalance
                    else -> -1L
                }
                if (dailyRemain >= 0) {
                    add(
                        QuotaPack(
                            name = "当日赠送",
                            group = "每日",
                            limit = snapshot.dailyLimit.coerceAtLeast(0),
                            used = snapshot.dailyConsumed.coerceAtLeast(0),
                            remain = dailyRemain,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult {
        val loomy = parseOrNull(account) ?: return ProviderActionResult.failure("账号凭证无法解析")
        return try {
            when (action) {
                ACTION_REDEEM_INVITE -> {
                    val code = payload.get("inviteCode")?.asString.orEmpty().trim()
                    if (code.isEmpty()) return ProviderActionResult.failure("邀请码不能为空")
                    pointsClient.activate(loomy.session, code)
                    ProviderActionResult.success("邀请码兑换成功")
                }

                ACTION_REDEEM_CODE -> {
                    val code = payload.get("code")?.asString.orEmpty().trim()
                    if (code.isEmpty()) return ProviderActionResult.failure("兑换码不能为空")
                    pointsClient.redeemCode(loomy.session, code)
                    ProviderActionResult.success("兑换码兑换成功")
                }

                ACTION_COMPLETE_TASK -> {
                    val key = payload.get("key")?.asString.orEmpty()
                    val result = taskClient.complete(loomy.session, key)
                    val title = LoomyTaskClient.titleOf(key)
                    if (result.alreadyCompleted) {
                        ProviderActionResult.success("$title：此前已完成")
                    } else {
                        ProviderActionResult.success("$title：已完成")
                    }
                }

                ACTION_COMPLETE_ALL_TASKS -> {
                    val pending = taskClient.tasks(loomy.session).tasks.filter { !it.completed }
                    if (pending.isEmpty()) return ProviderActionResult.success("所有任务都已完成")
                    val lines = pending.map { task ->
                        val result = taskClient.complete(loomy.session, task.key)
                        "${task.title}：${if (result.alreadyCompleted) "此前已完成" else "已完成"}"
                    }
                    ProviderActionResult.success(lines.joinToString("；"))
                }

                else -> ProviderActionResult.unsupported(action)
            }
        } catch (e: LoomyApiException) {
            ProviderActionResult.failure(e.message ?: "操作失败")
        } catch (e: Exception) {
            ProviderActionResult.failure(e.message ?: "操作失败")
        }
    }

    // ------------------------------------------------------------------ 登录

    override fun sendSmsCode(phone: String): String {
        val normalized = phone.trim()
        if (!PHONE_PATTERN.matches(normalized)) throw IllegalArgumentException("手机号格式不正确")
        return authClient.sendSmsCode(normalized)
    }

    override fun loginBySmsCode(phone: String, code: String, msgid: String): ProviderAccount {
        val account = authClient.loginBySmsCode(phone.trim(), code.trim(), msgid)
        var nickname = ""
        runCatching { nickname = authClient.nicknameOf(authClient.getUserInfo(account.session)) }
        // 上游没给 userid 时退回手机号当 uid，账号至少可用且可重复登录覆盖
        val uid = account.uid.ifEmpty { account.phone }
        return toProviderAccount(account.copy(uid = uid, nickname = nickname))
    }

    // ------------------------------------------------------------------ 工具

    private fun toProviderAccount(account: LoomyAccount): ProviderAccount =
        ProviderAccount(id, account.uid, account.nickname, account.toJson().toString())

    private fun parseOrNull(account: ProviderAccount): LoomyAccount? =
        runCatching { LoomyAccount.parse(account.secret) }.getOrNull()

    companion object {
        const val ID = "loomy"

        /** Loomy 的 provider 专属动作，UI 组件与 provider 约定一致。 */
        const val ACTION_REDEEM_INVITE = "redeem_invite"
        const val ACTION_REDEEM_CODE = "redeem_code"
        const val ACTION_COMPLETE_TASK = "complete_task"
        const val ACTION_COMPLETE_ALL_TASKS = "complete_all_tasks"

        /** 中国大陆手机号，发短信前先校验。 */
        val PHONE_PATTERN = Regex("1[3-9]\\d{9}")
    }

}

internal fun LoomyErrorKind.toErrorKind(): ErrorKind = when (this) {
    LoomyErrorKind.SESSION_DEAD -> ErrorKind.SESSION_DEAD
    LoomyErrorKind.QUOTA -> ErrorKind.QUOTA
    LoomyErrorKind.SOFT_RATE -> ErrorKind.SOFT_RATE
    LoomyErrorKind.NOT_FOUND -> ErrorKind.NOT_FOUND
    LoomyErrorKind.CLIENT -> ErrorKind.CLIENT
    LoomyErrorKind.SERVER -> ErrorKind.SERVER
    LoomyErrorKind.NETWORK -> ErrorKind.NETWORK
}

/** 当日剩余额度未知（-1 哨兵值）时显示破折号。 */
private fun formatOrDash(value: Long): String = if (value < 0) "—" else value.toString()
