package dev.aigw.core.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Gson 的 `getAsJsonObject(name)` / `getAsJsonArray(name)` 是裸强转（`(JsonObject) members.get(name)`），
 * 字段存在但值为 `null`（JsonNull）或类型不符时会抛 ClassCastException；
 * `JsonNull.asString` 之类同样会抛 UnsupportedOperationException。
 *
 * 上游很常见这类数据：标准 OpenAI 兼容服务的流式 chunk 每个都带 `"usage": null`。
 * 取字段一律走这里，别用 Gson 的裸转换——在流式转发路径上抛异常会直接掐断整条流。
 */

fun JsonObject.objOrNull(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.arrayOrNull(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

fun JsonObject.longOrNull(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive }?.asLong

fun JsonObject.intOrNull(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.asInt

fun JsonObject.boolOrNull(name: String): Boolean? = get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean

/** 数组元素的安全取值。 */
fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

fun JsonElement.asStringOrNull(): String? = takeIf { it.isJsonPrimitive }?.asString

/** 取字符串字段，缺字段/JsonNull/类型不符时回退 [fallback]。 */
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

fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asBoolean }.getOrDefault(fallback)
}
