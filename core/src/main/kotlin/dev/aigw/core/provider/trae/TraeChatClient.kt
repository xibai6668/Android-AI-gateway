package dev.aigw.core.provider.trae

import java.io.InputStream

/** 一次对话调用的上游响应：2xx 时 [stream] 非空，否则 [errorBody] 非空。 */
class TraeChatCall(
    val status: Int,
    val stream: InputStream?,
    val errorBody: String,
) : AutoCloseable {
    override fun close() {
        stream?.close()
    }
}

/** SOLO 对话通道客户端。请求体改写交给 [TraePayload]。 */
class TraeChatClient(
    private val version: TraeVersion,
    private val host: String = TraeConstants.AGENT_HOST,
    private val idleTimeoutMs: Int = TraeHttp.STREAM_IDLE_TIMEOUT_MS,
) {

    /**
     * 发起 `llm_utils_chat`。返回的对象里 [TraeChatCall.stream] 是 SOLO 的 SSE 原始字节流，
     * 调用方负责解析与关闭。
     *
     * 上游 200 但没回 event-stream（签名失效时常直接回 JSON 错误体）时，
     * 不能把非 SSE 字节流交给 SSE 解析器——那会被静默忽略成「成功但零输出」。
     */
    fun openStream(account: TraeAccount, openAiBody: String): TraeChatCall {
        val payload = TraePayload.prepare(openAiBody)
        val conn = TraeHttp.post(
            url = host + TraeConstants.EP_CHAT,
            body = payload,
            connectTimeoutMs = TraeHttp.CONNECT_TIMEOUT_MS,
            readTimeoutMs = idleTimeoutMs,
        ) { TraeHeaders.solo(it, account, version, stream = true) }

        val status = conn.responseCode
        if (status >= 400) {
            val raw = TraeHttp.readBody(conn)
            return TraeChatCall(status, null, raw)
        }
        val contentType = conn.contentType.orEmpty()
        if (!contentType.contains("event-stream", ignoreCase = true)) {
            val raw = TraeHttp.readBody(conn)
            return TraeChatCall(
                status,
                null,
                "上游未返回 SSE 流（Content-Type: ${contentType.ifEmpty { "—" }}）：${raw.trim().take(200).ifEmpty { "<空响应>" }}",
            )
        }
        return TraeChatCall(status, conn.inputStream, "")
    }

    /** 拉取模型目录（`get_detail_param`）。 */
    fun fetchModels(account: TraeAccount): List<TraeModel> {
        val body = TraePayloadModels.requestBody()
        val conn = TraeHttp.post(
            url = host + TraeConstants.EP_MODELS,
            body = body,
            connectTimeoutMs = TraeHttp.CONNECT_TIMEOUT_MS,
            readTimeoutMs = TraeHttp.READ_TIMEOUT_MS,
        ) { TraeHeaders.solo(it, account, version, stream = false) }

        val status = conn.responseCode
        val raw = TraeHttp.readBody(conn)
        if (status >= 400) {
            throw TraeHttpException(status, TraeErrors.fromStatus(status, raw), raw, TraeErrors.extractCode(raw))
        }
        return TraePayloadModels.parse(raw)
    }
}
