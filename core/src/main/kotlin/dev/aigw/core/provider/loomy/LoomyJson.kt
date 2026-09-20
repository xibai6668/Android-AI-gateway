package dev.aigw.core.provider.loomy

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** JsonObject 取值的小工具，缺字段/类型不符时回退到默认值。 */
fun JsonObject.str(key: String, fallback: String = ""): String {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asString }.getOrDefault(fallback)
}

fun JsonObject.long(key: String, fallback: Long = 0L): Long {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asLong }.getOrDefault(fallback)
}

fun JsonObject.int(key: String, fallback: Int = 0): Int {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asInt }.getOrDefault(fallback)
}

fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asBoolean }.getOrDefault(fallback)
}

fun JsonObject.obj(key: String): JsonObject? {
    val v = get(key) ?: return null
    if (!v.isJsonObject) return null
    return v.asJsonObject
}

fun JsonObject.array(key: String): JsonArray? {
    val v = get(key) ?: return null
    if (!v.isJsonArray) return null
    return v.asJsonArray
}

/**
 * 解析上游的统一响应壳 `{code, desc, data, trace_id}`。
 *
 * 业务码非 `000000` 时抛 [LoomyApiException]（携带 code 供上层分类），
 * 这样调用方只需要处理一种异常类型。
 */
internal fun decodeEnvelope(status: Int, body: String): JsonObject {
    val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        ?: throw LoomyApiException(status, LoomyErrors.fromStatus(status, body), body)
    val code = obj.str("code")
    if (code.isNotEmpty() && code != LoomyConstants.CODE_OK) {
        throw LoomyApiException(status, LoomyErrors.fromStatus(status, body), body, code)
    }
    return obj
}
