package dev.aigw.core.provider.minimax

import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
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
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf

/**
 * MiniMax（Agent 国内版网页客户端反代）。
 *
 * 上游是私有协议：OpenAI 多轮消息合并成单条文本发 `/matrix/api/v1/chat/send_msg`，
 * SSE 事件里的 `content` 是全量累积，差分后翻成 OpenAI SSE；`chat_type` 区分
 * Lightning / Pro 两档。凭证是网页端 `_token` + `user_id`（无登录流程，粘贴导入），
 * `device_id` 缺省时自动注册并回存。
 */
class MiniMaxProvider(
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val chatClient: MiniMaxChatClient = MiniMaxChatClient(),
    private val userClient: MiniMaxUserClient = MiniMaxUserClient(),
    private val deviceIdLock: Any = Any(),
) : Provider {

    override val id: String = ID
    override val displayName: String = "MiniMax"
    override val authKind: AuthKind = AuthKind.NONE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CREDIT_REFRESH)

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView = ProviderModelCatalogView(
        models = listOf(
            ProviderModel(id = MODEL_LIGHTNING, name = "Lightning（极速）"),
            ProviderModel(id = MODEL_PRO, name = "Pro（Agent 模式）"),
        ),
        fromFallback = false,
        error = "",
    )

    override fun resolveModel(requested: String): String {
        val model = requested.trim().substringAfter('/').trim()
        if (model.isEmpty() || model == "auto") return MODEL_LIGHTNING
        return model
    }

    override fun hosts(): List<String> = listOf("minimaxi.com")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val mini = parseOrNull(account) ?: return FailedChatCall(401, "账号凭证无法解析，请重新导入")
        val ready = try {
            ensureDeviceId(mini)
        } catch (e: Exception) {
            return FailedChatCall(0, "设备注册失败：${e.message}")
        }
        val model = resolveModel(requestedModelOf(openAiBody))
        val chatType = if (model == MODEL_PRO) MiniMaxConstants.CHAT_TYPE_PRO else MiniMaxConstants.CHAT_TYPE_LIGHTNING

        val call = try {
            chatClient.openStream(ready, openAiBody, chatType, nowMillis())
        } catch (e: MiniMaxApiException) {
            return FailedChatCall(e.status, e.message ?: "连接失败")
        }
        if (call.status !in 200..299) return FailedChatCall(call.status, call.errorBody)

        if (!call.isEventStream) {
            // HTTP 200 + JSON：要么业务错误，要么上游没按 SSE 回（同步模式），都无内容可透传
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
        val translator = MiniMaxOpenAiTranslator(model)
        val translated = LineTransformStream(
            call.stream!!,
            { line -> parser.feed(line)?.let { translator.translate(it) }.orEmpty() },
            {
                // EOF 时 flush 最后一个未完成事件（上游最后一帧可能缺终止空行），再补 [DONE]
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
                OpenAiSseAggregator.aggregate(translated, model)
            } finally {
                userClient.deleteConversation(ready, translator.chatId, nowMillis())
                call.close()
            }
            if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                return StreamFailureChatCall(
                    200,
                    UpstreamError(ErrorKind.CLIENT, "上游返回了空回复（会话已清理，可重试或改用 Lightning）"),
                )
            }
            AggregatedChatCall(200, aggregated)
        }
    }

    override fun classify(status: Int, body: String): UpstreamError = MiniMaxErrors.fromStatus(status, body)

    // ------------------------------------------------------------------ 额度

    override fun creditInfo(account: ProviderAccount): CreditInfo {
        val mini = parseOrNull(account)
            ?: return CreditInfo(0, known = false, detail = "账号凭证无法解析，请重新导入")
        return try {
            val membership = userClient.membership(mini, nowMillis())
            CreditInfo(
                balance = membership?.remainCredit ?: 0,
                known = membership?.remainCredit != null,
                detail = if (membership == null) {
                    "未取到余额（响应里没有 plan_name / total_remains_credit）"
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
        } catch (e: MiniMaxApiException) {
            CreditInfo(0, known = false, detail = e.message ?: "查询余额失败")
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "查询余额失败")
        }
    }

    // ------------------------------------------------------------------ 导入

    override fun importCredentials(raw: String): ProviderAccount? {
        val account = MiniMaxAccount.import(raw) ?: return null
        return ProviderAccount(id, account.userId, "", account.toJson().toString())
    }

    // ------------------------------------------------------------------ 内部

    private fun parseOrNull(account: ProviderAccount): MiniMaxAccount? =
        runCatching { MiniMaxAccount.parse(account.secret) }.getOrNull()

    /** `device_id` 缺省时自动注册并回存凭证；注册失败上抛异常（由 openChat 包错误信息）。 */
    private fun ensureDeviceId(mini: MiniMaxAccount): MiniMaxAccount {
        if (mini.deviceId.isNotEmpty()) return mini
        return synchronized(deviceIdLock) {
            if (mini.deviceId.isNotEmpty()) {
                mini
            } else {
                val deviceId = userClient.registerDevice(mini, nowMillis())
                val updated = mini.withDeviceId(deviceId)
                // 回存到账号池：下次请求不再重复注册
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
