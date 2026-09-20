package dev.aigw.core.provider.loomy

/**
 * 对话客户端。
 *
 * 上游 `/api/v1/chat/completions` **本身就是 OpenAI 兼容协议**（官方客户端的
 * `@ai-sdk/openai-compatible` provider 直连它），所以网关这一层只做：
 * 换账号 → 补认证/追踪头 → 转发 → 原样回传 SSE。
 *
 * 认证走裸 `token` 头（session），同时按官方客户端再带一个 `Authorization: Bearer`。
 */
class LoomyChatClient(
    private val apiBase: String = LoomyConstants.API_BASE,
    private val clientVersion: String = LoomyConstants.CLIENT_VERSION,
) {

    /** 发起一次流式对话，返回尚未读取的连接。 */
    fun openStream(account: LoomyAccount, body: String): LoomyStreamCall {
        val headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${account.session}",
            LoomyConstants.TOKEN_HEADER to account.session,
            LoomyConstants.TRACEPARENT_HEADER to LoomyTrace.newTraceparent(),
            LoomyConstants.VERSION_HEADER to clientVersion,
        )
        return openStreamCall("$apiBase${LoomyConstants.PATH_CHAT_COMPLETIONS}", headers, body)
    }
}
