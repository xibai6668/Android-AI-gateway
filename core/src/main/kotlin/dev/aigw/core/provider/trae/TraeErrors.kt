package dev.aigw.core.provider.trae

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.long
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

/** 上游返回的业务错误分类，决定账号是长冷却、短冷却还是直接禁用。 */
enum class TraeErrorKind {
    /** 1005 / 4008：权益或额度不足，等待重置或签到。 */
    PLAN_LIMIT,

    /** 4011 / 429：触发限流，短冷却。 */
    SOFT_RATE,

    /** 1001 / HTTP 401：凭证失效，必须重新登录。 */
    SESSION_DEAD,

    /** HTTP 404：上游路由不存在，短冷却且不累计错误次数。 */
    NOT_FOUND,

    /** 参数或模型不合法（如 4001），通常是客户端问题。 */
    CLIENT,

    /** 上游 5xx。 */
    SERVER,
}

/** SSE 流内的 `event:error` 业务错误。 */
class SoloStreamError(val code: Long, val message: String) {
    val kind: TraeErrorKind
        get() = when (code) {
            1001L -> TraeErrorKind.SESSION_DEAD
            1005L, 4008L -> TraeErrorKind.PLAN_LIMIT
            4011L -> TraeErrorKind.SOFT_RATE
            else -> TraeErrorKind.CLIENT
        }

    override fun toString(): String = "solo error code=$code msg=$message"
}

/**
 * 上游 HTTP 层的错误。
 *
 * `message` 刻意写成**用户能直接看懂**的一句话（上游原话优先，否则按状态码分类），
 * 因为上层会把它直接当提示文案用。曾经这里是 `upstream http 200 kind=CLIENT body=...`
 * 这种调试格式，结果原样弹给了用户（“upstream http 200 kind=CLIENT body=活动暂不可用”）。
 * 调试细节仍可通过 [debugSummary] / [body] / [kind] 拿到。
 */
class TraeHttpException(
    val status: Int,
    val kind: TraeErrorKind,
    val body: String,
    val upstreamCode: Long = 0L,
) : Exception(userMessage(status, kind, body, upstreamCode)) {

    /** 写日志用的单行摘要，包含分类与响应片段。 */
    fun debugSummary(): String = "upstream http $status kind=$kind " +
        (if (upstreamCode != 0L) "code=$upstreamCode " else "") +
        "body=${body.take(500)}"

    private companion object {
        /**
         * 优先用上游返回的人类可读文案（`message`/`msg`/`error`），
         * 拿不到再回退到按分类的中文说明。
         */
        fun userMessage(status: Int, kind: TraeErrorKind, body: String, code: Long): String {
            val upstream = TraeErrors.extractMessage(body).trim()
            // 只接受「看起来像人话」的上游消息。extractMessage 找不到 message/msg 字段时
            // 会回退返回原始 body（那是给日志用的），直接展示会把 {"code":4008} 这种 JSON 弹给用户。
            if (isHumanReadable(upstream)) return upstream
            return when (code) {
                1001L -> "凭证已失效，需要重新登录"
                1005L, 4008L -> "额度已耗尽，等每日重置或签到"
                4011L -> "请求过于频繁，已被上游限流"
                9074L -> "签到被拒：设备指纹未通过校验"
                4001L -> "模型或客户端版本不被上游接受"
                else -> when (kind) {
                    TraeErrorKind.SESSION_DEAD -> "凭证已失效，需要重新登录"
                    TraeErrorKind.PLAN_LIMIT -> "额度已耗尽，等每日重置或签到"
                    TraeErrorKind.SOFT_RATE -> "请求过于频繁，已被上游限流"
                    TraeErrorKind.NOT_FOUND -> "上游接口不存在"
                    TraeErrorKind.SERVER -> "上游服务异常（HTTP $status）"
                    TraeErrorKind.CLIENT -> "上游拒绝了本次请求（HTTP $status）"
                }
            }
        }

        /**
         * 判断一段文本是否适合直接展示给用户：
         * 非空、长度合理，且不是 JSON/HTML 这类结构化报文片段。
         */
        fun isHumanReadable(text: String): Boolean {
            if (text.isEmpty() || text.length > 200) return false
            if (text.startsWith("{") || text.startsWith("[") || text.startsWith("<")) return false
            return true
        }
    }
}

object TraeErrors {

    /** 按 HTTP 状态码 + 响应体里的业务 code 判断错误类别。 */
    fun fromStatus(status: Int, body: String): TraeErrorKind {
        val code = extractCode(body)
        return when {
            status == 401 -> TraeErrorKind.SESSION_DEAD
            status == 404 -> TraeErrorKind.NOT_FOUND
            status == 429 -> TraeErrorKind.SOFT_RATE
            status in 500..599 -> TraeErrorKind.SERVER
            status in 400..499 -> when (code) {
                1001L -> TraeErrorKind.SESSION_DEAD
                1005L, 4008L -> TraeErrorKind.PLAN_LIMIT
                4011L -> TraeErrorKind.SOFT_RATE
                else -> TraeErrorKind.CLIENT
            }
            else -> TraeErrorKind.CLIENT
        }
    }

    /** 从响应体里取业务 code，取不到返回 0。 */
    fun extractCode(body: String): Long {
        if (body.isEmpty()) return 0L
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return 0L
        val direct = obj.long("code")
        if (direct != 0L) return direct
        return obj.objOrNull("data")?.long("code") ?: 0L
    }

    /** 从响应体里取可读的错误消息。 */
    fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return body.take(300)
        for (key in listOf("message", "msg", "error_msg", "error")) {
            val v = obj.str(key)
            if (v.isNotEmpty()) return v
        }
        val data: JsonObject? = obj.objOrNull("data")
        if (data != null) {
            for (key in listOf("message", "msg")) {
                val v = data.str(key)
                if (v.isNotEmpty()) return v
            }
        }
        return body.take(300)
    }
}
