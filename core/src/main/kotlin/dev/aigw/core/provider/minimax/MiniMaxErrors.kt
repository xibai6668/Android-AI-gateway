package dev.aigw.core.provider.minimax

import dev.aigw.core.util.longOrNull
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import com.google.gson.JsonParser

/**
 * 上游错误提取与分类。
 *
 * MiniMax 的业务错误有两层包裹：老接口（`/v1/api/` 前缀）用 `statusInfo.{code,message}`，
 * matrix 接口用 `base_resp.{status_code,status_msg}`；HTTP 层则正常走 4xx/5xx。
 * 第一版不臆测业务码语义：非 0 一律按 CLIENT 如实上报（消息里带 code，便于抓包后细化）。
 */
internal object MiniMaxErrors {

    fun extractCode(body: String): Long? {
        if (body.isEmpty()) return null
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        obj.objOrNull("base_resp")?.longOrNull("status_code")?.let { return it }
        obj.objOrNull("statusInfo")?.longOrNull("code")?.let { return it }
        return obj.longOrNull("code")
    }

    fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.objOrNull("base_resp")?.stringOrNull("status_msg")?.takeIf { it.isNotEmpty() }?.let { return it }
        obj.objOrNull("statusInfo")?.stringOrNull("message")?.takeIf { it.isNotEmpty() }?.let { return it }
        for (key in listOf("message", "msg", "detail")) {
            obj.stringOrNull(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    fun fromStatus(status: Int, body: String): UpstreamError {
        val code = extractCode(body)
        val message = extractMessage(body)
            .ifEmpty { if (body.isNotEmpty()) body.trim().take(160) else "上游 HTTP $status" }
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 -> ErrorKind.QUOTA
            status == 429 -> ErrorKind.SOFT_RATE
            status == 404 -> ErrorKind.NOT_FOUND
            status in 500..599 -> ErrorKind.SERVER
            // HTTP 200 + 业务码：语义未抓包确认前不连累账号，如实上报
            status in 200..299 -> ErrorKind.CLIENT
            else -> ErrorKind.CLIENT
        }
        val detail = if (code != null && code != 0L) "业务码 $code：$message" else message
        return UpstreamError(kind, detail)
    }
}
