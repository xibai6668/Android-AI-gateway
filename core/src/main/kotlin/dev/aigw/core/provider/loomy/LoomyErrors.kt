package dev.aigw.core.provider.loomy

import com.google.gson.JsonParser
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

/** 上游错误分类，决定重试策略、换号/切换供应商与硬禁用。 */
enum class LoomyErrorKind {
    /** token / session 失效，必须重新登录。 */
    SESSION_DEAD,

    /** 积分或当日额度耗尽，等待重置。 */
    QUOTA,

    /** 429：限流，可退避重试。 */
    SOFT_RATE,

    /** HTTP 404：路由不存在。 */
    NOT_FOUND,

    /** 参数或模型不合法，通常是客户端问题。 */
    CLIENT,

    /** 上游 5xx。 */
    SERVER,

    /** 网络层失败（连不上、超时）。 */
    NETWORK,
}

/**
 * 上游返回的错误。
 *
 * `message` 是**用户能直接看懂**的一句话：上游的 `desc` 优先，否则按分类给中文说明。
 * 调试细节通过 [debugSummary] 取，别把结构化报文弹给用户。
 */
class LoomyApiException(
    val status: Int,
    val kind: LoomyErrorKind,
    val body: String,
    val upstreamCode: String = "",
    /** 原始异常（网络层失败时保留，否则堆栈只能看到包装这一层，根因看不到）。 */
    cause: Throwable? = null,
) : Exception(userMessage(status, kind, body, upstreamCode), cause) {

    fun debugSummary(): String = "upstream http $status kind=$kind " +
        (if (upstreamCode.isNotEmpty()) "code=$upstreamCode " else "") +
        "body=${body.take(500)}"

    private companion object {
        fun userMessage(status: Int, kind: LoomyErrorKind, body: String, code: String): String {
            val upstream = LoomyErrors.extractMessage(body).trim()
            if (isHumanReadable(upstream)) return upstream
            return when (code) {
                LoomyConstants.CODE_TOKEN_INVALID -> "登录状态已失效，需要重新登录"
                LoomyConstants.CODE_SESSION_INVALID -> "登录状态已失效，需要重新登录"
                else -> when (kind) {
                    LoomyErrorKind.SESSION_DEAD -> "登录状态已失效，需要重新登录"
                    LoomyErrorKind.QUOTA -> "积分或当日额度已耗尽"
                    LoomyErrorKind.SOFT_RATE -> "请求过于频繁，已被上游限流"
                    LoomyErrorKind.NOT_FOUND -> "上游接口不存在"
                    LoomyErrorKind.SERVER -> "上游服务异常（HTTP $status）"
                    LoomyErrorKind.CLIENT -> "上游拒绝了本次请求（HTTP $status）"
                    LoomyErrorKind.NETWORK -> "网络连接失败，请检查网络后重试"
                }
            }
        }

        /** 非空、长度合理，且不是 JSON/HTML 报文片段时才适合直接展示。 */
        fun isHumanReadable(text: String): Boolean {
            if (text.isEmpty() || text.length > 200) return false
            if (text.startsWith("{") || text.startsWith("[") || text.startsWith("<")) return false
            return true
        }
    }
}

object LoomyErrors {

    /** 上游以英文原文标记、但状态码仍为 200 的会话失效信号。 */
    private val SESSION_DEAD_MARKERS = listOf(
        "token 已失效", "登录已失效", "session expired", "session is invalid",
        "invalid session", "not logged in", "unauthorized",
    )

    /** 上游以英文原文标记的「账号级配额不足」信号。 */
    private val QUOTA_MARKERS = listOf(
        "insufficient credits", "insufficient credit", "not enough credit",
        "credit exhausted", "quota exceeded",
    )

    /**
     * 错误分类：**先看正文 marker、后看状态码**。
     *
     * 上游鉴权失败与部分业务错误回的是 **HTTP 200 + 业务码**，只看状态码会把失效账号
     * 判成普通客户端错误——既不禁用也不冷却，问题被静默吃掉。参照实现
     * （`multi2api/internal/loomy/errorclassifier.go`、`Patrick130306/loomy2api`；
     * MIT，https://github.com/Practice019/multi2api 与 https://github.com/Patrick130306/loomy2api）都按
     * 「先正文、后状态码」的顺序分类，这里对齐。
     */
    fun fromStatus(status: Int, body: String): LoomyErrorKind {
        val code = extractCode(body)
        val lower = body.lowercase()

        // 1) 正文 marker 优先：200 + 业务码 也能正确归类
        if (code == LoomyConstants.CODE_TOKEN_INVALID || code == LoomyConstants.CODE_SESSION_INVALID) {
            return LoomyErrorKind.SESSION_DEAD
        }
        if (SESSION_DEAD_MARKERS.any { lower.contains(it) || body.contains(it) }) {
            return LoomyErrorKind.SESSION_DEAD
        }
        if (looksLikeQuota(body)) return LoomyErrorKind.QUOTA

        // 2) 再看状态码
        return when {
            status == 401 || status == 403 -> LoomyErrorKind.SESSION_DEAD
            status == 404 -> LoomyErrorKind.NOT_FOUND
            status == 429 -> LoomyErrorKind.SOFT_RATE
            status in 500..599 -> LoomyErrorKind.SERVER
            else -> LoomyErrorKind.CLIENT
        }
    }

    /** 上游对「积分不足」没有稳定错误码，按文案兜底识别（中英双语）。 */
    private fun looksLikeQuota(body: String): Boolean {
        val lower = body.lowercase()
        if (QUOTA_MARKERS.any { lower.contains(it) }) return true
        val text = body.lowercase()
        return listOf("积分不足", "积分已用完", "余额不足", "额度不足", "额度已用完", "quota", "insufficient").any { text.contains(it) }
    }

    /** 从响应体里取业务 code（顶层 `code`），取不到返回空串。 */
    fun extractCode(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        return obj.str("code")
    }

    /** 从响应体里取可读的错误消息：`desc` / `message` / `msg` / `error`。 */
    fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return body.take(300)
        for (key in listOf("desc", "message", "msg", "error")) {
            val v = obj.str(key)
            if (v.isNotEmpty()) return v
        }
        val data = obj.objOrNull("data")
        if (data != null) {
            for (key in listOf("desc", "message", "msg")) {
                val v = data.str(key)
                if (v.isNotEmpty()) return v
            }
        }
        return body.take(300)
    }
}
