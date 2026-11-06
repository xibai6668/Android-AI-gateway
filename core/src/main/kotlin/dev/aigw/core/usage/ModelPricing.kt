package dev.aigw.core.usage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.objOrNull

/**
 * 每百万 token 的美元单价。
 *
 * 四个维度必须齐全：只配其中几个会让其余部分按 0 计，总价被系统性低估。
 * 所以缺任何一项时整条价格视为无效，而不是「缺的当 0」。
 */
data class ModelPrice(
    val promptPerMillion: Double,
    val completionPerMillion: Double,
    val cacheReadPerMillion: Double,
    val cacheWritePerMillion: Double,
)

/**
 * 价格表：键为「网关对外模型 id」，也接受裸模型名（查全 id 不到时回落）。
 *
 * 为什么两套键都收：同一模型在不同供应商下 id 不同（`trae/gemini-3.8-flash`、
 * `antigravity/gemini-3.8-flash`），用户只想填一次裸名就能同时覆盖。
 */
data class PricingTable(val prices: Map<String, ModelPrice> = emptyMap()) {

    /** 查价：先精确匹配全 id，再匹配裸模型名（去掉 `/` 前的供应商段），都没有返回 null。 */
    fun priceOf(modelFullId: String): ModelPrice? {
        val id = modelFullId.trim()
        if (id.isEmpty()) return null
        prices[id]?.let { return it }
        val bare = id.substringAfterLast('/').trim()
        return if (bare.isNotEmpty() && bare != id) prices[bare] else null
    }

    fun withPrice(modelFullId: String, price: ModelPrice): PricingTable =
        copy(prices = prices + (modelFullId.trim() to price))

    fun withoutPrice(modelFullId: String): PricingTable = copy(prices = prices - modelFullId.trim())

    fun toJson(): JsonObject = JsonObject().apply {
        val obj = JsonObject()
        for ((model, price) in prices) {
            obj.add(model, JsonObject().apply {
                addProperty("prompt", price.promptPerMillion)
                addProperty("completion", price.completionPerMillion)
                addProperty("cacheRead", price.cacheReadPerMillion)
                addProperty("cacheWrite", price.cacheWritePerMillion)
            })
        }
        add("prices", obj)
    }

    companion object {
        const val STORE_KEY = "settings/model_pricing.json"

        fun fromJson(raw: String?): PricingTable {
            if (raw.isNullOrBlank()) return PricingTable()
            val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return PricingTable()
            val obj = root.objOrNull("prices") ?: return PricingTable()
            val prices = LinkedHashMap<String, ModelPrice>()
            for ((model, value) in obj.entrySet()) {
                val item = value.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val price = readPrice(item) ?: continue
                if (model.isNotBlank()) prices[model] = price
            }
            return PricingTable(prices)
        }

        /** 四个维度必须都是有效数字；任一缺失/非数字即整条作废（不拿 0 兜底）。 */
        private fun readPrice(obj: JsonObject): ModelPrice? {
            fun num(key: String): Double? {
                val v = obj.get(key) ?: return null
                if (!v.isJsonPrimitive) return null
                val d = runCatching { v.asDouble }.getOrNull() ?: return null
                return if (d.isFinite() && d >= 0.0) d else null
            }
            val prompt = num("prompt") ?: return null
            val completion = num("completion") ?: return null
            val cacheRead = num("cacheRead") ?: return null
            val cacheWrite = num("cacheWrite") ?: return null
            return ModelPrice(prompt, completion, cacheRead, cacheWrite)
        }
    }
}

/**
 * 一次调用的费用计算；**算不出价格时返回 null，绝不返回 0**。
 *
 * 口径：`promptTokens` 是上游报的总输入（通常已含缓存命中），所以
 * 未命中部分 = max(prompt - cacheRead - cacheWrite, 0)，再分别按三个单价计。
 * 若把总输入直接乘 prompt 单价、又把缓存另算一遍，费用会被高估一倍以上。
 *
 * [price] 为 null 表示未知价格 → 返回 null；价格为 0 是合法价格（免费模型）→ 返回 0.0。
 * 负 token 一律按 0 处理（上游偶有报错字段，不该反向扣钱）。
 */
fun costOf(
    price: ModelPrice?,
    promptTokens: Long,
    completionTokens: Long,
    cacheReadTokens: Long,
    cacheWriteTokens: Long,
): Double? {
    val p = price ?: return null
    val prompt = promptTokens.coerceAtLeast(0L)
    val completion = completionTokens.coerceAtLeast(0L)
    val cacheRead = cacheReadTokens.coerceAtLeast(0L).coerceAtMost(prompt)
    val cacheWrite = cacheWriteTokens.coerceAtLeast(0L).coerceAtMost((prompt - cacheRead).coerceAtLeast(0L))
    val fresh = (prompt - cacheRead - cacheWrite).coerceAtLeast(0L)
    val micros = fresh * p.promptPerMillion +
        cacheRead * p.cacheReadPerMillion +
        cacheWrite * p.cacheWritePerMillion +
        completion * p.completionPerMillion
    return micros / 1_000_000.0
}

/** 把美元金额转成微美元（整数，1e-6 USD），供限额与聚合使用。 */
fun toMicroUsd(usd: Double?): Long {
    if (usd == null || !usd.isFinite() || usd <= 0.0) return 0L
    return (usd * 1_000_000.0).toLong()
}

/** 微美元 → 展示用字符串（保留到 1e-6，去掉多余的 0）。 */
fun formatMicroUsd(micros: Long): String {
    if (micros <= 0L) return "0"
    val dollars = micros / 1_000_000L
    val fraction = micros % 1_000_000L
    if (fraction == 0L) return dollars.toString()
    val text = fraction.toString().padStart(6, '0').trimEnd('0')
    return "$dollars.$text"
}
