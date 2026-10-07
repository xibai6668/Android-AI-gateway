package dev.aigw.core.provider

import java.io.InputStream

/**
 * 一次对话调用的上游响应。
 *
 * - 2xx 且流式请求：[stream] 是 OpenAI SSE 字节流（含 `data: [DONE]`）。
 * - 2xx 且非流式请求：[aggregated] 是 OpenAI `chat.completion` JSON。
 * - 非 2xx：[errorBody] 非空，[stream] 与 [aggregated] 均为 null。
 */
interface ChatCall : AutoCloseable {
    val status: Int
    val errorBody: String
    val stream: InputStream?
    val aggregated: String?

    /**
     * 流内业务错误（HTTP 200 但流里报了错）。
     * 网关据此换号或切换供应商；非流式时 [aggregated] 为 null。
     */
    val failure: UpstreamError? get() = null
}

/** 上游拒绝（非 2xx）的调用结果。 */
class FailedChatCall(
    override val status: Int,
    override val errorBody: String,
) : ChatCall {
    override val stream: InputStream? get() = null
    override val aggregated: String? get() = null
    override fun close() = Unit
}

/** 流式调用：把已转成 OpenAI SSE 的字节流交给网关透传。 */
class StreamingChatCall(
    override val status: Int,
    override val stream: InputStream,
    private val onClose: () -> Unit = {},
) : ChatCall {
    override val errorBody: String get() = ""
    override val aggregated: String? get() = null
    override fun close() = onClose()
}

/** 流内业务错误：没有可用内容，网关应换号或切换供应商。 */
class StreamFailureChatCall(
    override val status: Int,
    override val failure: UpstreamError,
) : ChatCall {
    override val errorBody: String get() = failure.message
    override val stream: InputStream? get() = null
    override val aggregated: String? get() = null
    override fun close() = Unit
}

/** 非流式调用：provider 内部已聚合完毕。 */
class AggregatedChatCall(
    override val status: Int,
    override val aggregated: String,
) : ChatCall {
    override val errorBody: String get() = ""
    override val stream: InputStream? get() = null
    override fun close() = Unit
}
