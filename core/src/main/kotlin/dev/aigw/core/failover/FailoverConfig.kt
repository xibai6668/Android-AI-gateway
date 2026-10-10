package dev.aigw.core.failover

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.random.Random

/** 单个供应商候选目标 */
data class FailoverCandidate(
    val providerId: String,          // 供应商 id（如 "trae"、"codebuddy"、"custom:zhipu"）
    val upstreamModel: String,        // 该供应商处实际对应的模型名（如 "glm-5.3-flash-20250101"）
    val priority: Int = 100,          // 优先级（数值越大越优先）
    /** 同优先级下的权重。⚠️ 未生效：当前调度只按 priority 排序，权重无消费方；保留字段仅为配置兼容。 */
    val weight: Int = 1,
    /** 该候选专用超时。⚠️ 未生效：上游超时由各 Provider 自己的 READ_TIMEOUT 决定，此字段无消费方；保留仅为配置兼容。 */
    val timeoutMs: Long = 30_000L,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("providerId", providerId)
        addProperty("upstreamModel", upstreamModel)
        addProperty("priority", priority)
        addProperty("weight", weight)
        addProperty("timeoutMs", timeoutMs)
    }

    companion object {
        fun fromJson(obj: JsonObject): FailoverCandidate? {
            val providerId = obj.get("providerId")?.asString?.trim() ?: return null
            val upstreamModel = obj.get("upstreamModel")?.asString?.trim() ?: return null
            if (providerId.isEmpty() || upstreamModel.isEmpty()) return null
            return FailoverCandidate(
                providerId = providerId,
                upstreamModel = upstreamModel,
                priority = obj.get("priority")?.asInt ?: 100,
                weight = obj.get("weight")?.asInt ?: 1,
                timeoutMs = obj.get("timeoutMs")?.asLong ?: 30_000L,
            )
        }
    }
}

/** 单供应商内部的退避重试参数 */
data class RetryConfig(
    val maxAttempts: Int = 3,         // 最大尝试次数（含首次请求）
    val initialBackoffMs: Long = 300L,// 初始退避毫秒数
    val maxBackoffMs: Long = 2000L,   // 退避上限
    val backoffMultiplier: Double = 2.0,
    val jitterRatio: Double = 0.25,   // 随机抖动比例 ±25%
) {
    /** 计算第 attempt 次重试前需要休眠的毫秒数（1-based，首次请求 attempt=1 不休眠） */
    fun computeBackoffMillis(attempt: Int): Long {
        if (attempt <= 1) return 0L
        val base = (initialBackoffMs * Math.pow(backoffMultiplier, (attempt - 2).toDouble())).toLong()
        val capped = minOf(base, maxBackoffMs)
        val jitter = (capped * jitterRatio).toLong()
        val delta = if (jitter > 0) Random.nextLong(-jitter, jitter + 1) else 0L
        return maxOf(0L, capped + delta)
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("maxAttempts", maxAttempts)
        addProperty("initialBackoffMs", initialBackoffMs)
        addProperty("maxBackoffMs", maxBackoffMs)
        addProperty("backoffMultiplier", backoffMultiplier)
        addProperty("jitterRatio", jitterRatio)
    }

    companion object {
        fun fromJson(obj: JsonObject?): RetryConfig {
            if (obj == null) return RetryConfig()
            return RetryConfig(
                maxAttempts = obj.get("maxAttempts")?.asInt?.coerceIn(1, 10) ?: 3,
                initialBackoffMs = obj.get("initialBackoffMs")?.asLong?.coerceIn(0L, 10_000L) ?: 300L,
                maxBackoffMs = obj.get("maxBackoffMs")?.asLong?.coerceIn(0L, 60_000L) ?: 2000L,
                backoffMultiplier = obj.get("backoffMultiplier")?.asDouble?.coerceIn(1.0, 5.0) ?: 2.0,
                jitterRatio = obj.get("jitterRatio")?.asDouble?.coerceIn(0.0, 1.0) ?: 0.25,
            )
        }
    }
}

/** 熔断参数 */
data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,    // 触发熔断的连续失败次数
    val openDurationMs: Long = 60_000L,// 熔断打开静默时长（60秒）
    val halfOpenSuccessCount: Int = 2, // 半开状态下连续成功几次才闭合复原
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("failureThreshold", failureThreshold)
        addProperty("openDurationMs", openDurationMs)
        addProperty("halfOpenSuccessCount", halfOpenSuccessCount)
    }

    companion object {
        fun fromJson(obj: JsonObject?): CircuitBreakerConfig {
            if (obj == null) return CircuitBreakerConfig()
            return CircuitBreakerConfig(
                failureThreshold = obj.get("failureThreshold")?.asInt?.coerceIn(1, 100) ?: 5,
                openDurationMs = obj.get("openDurationMs")?.asLong?.coerceIn(1000L, 3600_000L) ?: 60_000L,
                halfOpenSuccessCount = obj.get("halfOpenSuccessCount")?.asInt?.coerceIn(1, 10) ?: 2,
            )
        }
    }
}

/** 模型故障转移总配置 */
data class ModelFailoverSettings(
    val enabled: Boolean = true,
    val retry: RetryConfig = RetryConfig(),
    val circuitBreaker: CircuitBreakerConfig = CircuitBreakerConfig(),
    // 逻辑模型名 -> 候选供应商列表
    val routes: Map<String, List<FailoverCandidate>> = emptyMap(),
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("enabled", enabled)
        add("retry", retry.toJson())
        add("circuitBreaker", circuitBreaker.toJson())
        val routesObj = JsonObject()
        for ((model, candidates) in routes) {
            val arr = JsonArray()
            for (c in candidates) arr.add(c.toJson())
            routesObj.add(model, arr)
        }
        add("routes", routesObj)
    }

    companion object {
        const val STORE_KEY = "settings/failover.json"

        fun fromJson(raw: String?): ModelFailoverSettings {
            if (raw.isNullOrBlank()) return ModelFailoverSettings()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ModelFailoverSettings()
            val routes = LinkedHashMap<String, List<FailoverCandidate>>()
            obj.getAsJsonObject("routes")?.entrySet()?.forEach { (model, arrElem) ->
                if (arrElem.isJsonArray) {
                    val list = arrElem.asJsonArray.mapNotNull { item ->
                        if (item.isJsonObject) FailoverCandidate.fromJson(item.asJsonObject) else null
                    }
                    if (list.isNotEmpty()) routes[model] = list
                }
            }
            return ModelFailoverSettings(
                enabled = obj.get("enabled")?.asBoolean ?: true,
                retry = RetryConfig.fromJson(obj.getAsJsonObject("retry")),
                circuitBreaker = CircuitBreakerConfig.fromJson(obj.getAsJsonObject("circuitBreaker")),
                routes = routes,
            )
        }
    }
}
