package dev.aigw.core.provider

import com.google.gson.JsonParser
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.stringOrNull

/** 解析客户端发来的 OpenAI 请求体；不是合法 JSON 时返回 null，由调用方按各自回退处理。 */
private fun parseBody(body: String) =
    runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()

/** 客户端请求的模型名；解析不了返回空串，由 provider 的 resolveModel 落到默认模型。 */
fun requestedModelOf(body: String): String = parseBody(body)?.stringOrNull("model").orEmpty()

/** 客户端是否要求流式；缺字段或解析不了按非流式处理。 */
fun isStreamingBody(body: String): Boolean = parseBody(body)?.boolOrNull("stream") == true
