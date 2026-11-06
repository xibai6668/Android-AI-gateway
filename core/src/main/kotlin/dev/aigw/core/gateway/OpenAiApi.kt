package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.aigw.core.provider.RoutedModel

/** OpenAI 兼容层的报文拼装。 */
internal object OpenAiApi {

    fun errorBody(code: String, message: String): String = JsonObject().apply {
        add("error", JsonObject().apply {
            addProperty("message", message)
            addProperty("type", "api_error")
            addProperty("code", code)
        })
    }.toString()

    /**
     * 模型列表。对外 id 带 `provider/` 前缀（如 `trae/Seed-2.1-Code`），
     * 客户端据此在同一个端点下选择任意供应商的模型。
     */
    fun modelList(models: List<RoutedModel>, pricing: ((String) -> dev.aigw.core.usage.ModelPrice?)? = null): String {
        val data = JsonArray()
        for (routed in models) {
            data.add(JsonObject().apply {
                addProperty("id", routed.fullId)
                addProperty("object", "model")
                addProperty("created", CREATED)
                addProperty("owned_by", routed.providerId)
                addProperty("display_name", routed.model.name.ifEmpty { routed.model.id })
                if (routed.model.contextWindow > 0) addProperty("context_length", routed.model.contextWindow)
                // 能力/推理档位来自各供应商目录的 extra（各家自由 KV），只输出标准化的那几项：
                // 客户端据此决定是否显示「思考强度」选项，多余的私有字段不外泄。
                addProperty("supports_reasoning", routed.model.extra["reasoning"] == "1")
                addProperty("supports_tools", routed.model.extra["tools"] == "1")
                addProperty("supports_vision", routed.model.extra["vision"] == "1")
                addProperty("supports_audio", routed.model.extra["audio"] == "1")
                // 价格按「每百万 token 美元」输出，与价格表同口径；
                // 未配价格的模型**不输出 pricing 字段**（不拿 0 假装免费）
                pricing?.invoke(routed.fullId)?.let { price ->
                    add("pricing", JsonObject().apply {
                        addProperty("prompt", price.promptPerMillion)
                        addProperty("completion", price.completionPerMillion)
                        addProperty("cache_read", price.cacheReadPerMillion)
                        addProperty("cache_write", price.cacheWritePerMillion)
                        addProperty("unit", "usd_per_million_tokens")
                    })
                }
            })
        }
        return JsonObject().apply {
            addProperty("object", "list")
            add("data", data)
        }.toString()
    }

    const val CREATED = 1_753_600_000L
}
