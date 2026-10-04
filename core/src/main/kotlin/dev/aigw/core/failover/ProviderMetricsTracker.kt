package dev.aigw.core.failover

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 单个供应商的健康指标 */
class ProviderMetricsTracker {
    private val totalRequests = AtomicLong(0)
    private val successfulRequests = AtomicLong(0)
    private val totalDurationMs = AtomicLong(0)
    private val errorsByReason = ConcurrentHashMap<String, AtomicLong>()

    fun record(success: Boolean, durationMs: Long, errorReason: String? = null) {
        totalRequests.incrementAndGet()
        totalDurationMs.addAndGet(durationMs.coerceAtLeast(0L))
        if (success) {
            successfulRequests.incrementAndGet()
        } else if (!errorReason.isNullOrBlank()) {
            errorsByReason.computeIfAbsent(errorReason) { AtomicLong(0) }.incrementAndGet()
        }
    }

    fun snapshot(): Map<String, Any> {
        val total = totalRequests.get()
        val success = successfulRequests.get()
        val avgLatency = if (total > 0) totalDurationMs.get() / total else 0L
        val rate = if (total > 0) (success.toDouble() / total) * 100.0 else 100.0
        return mapOf(
            "total" to total,
            "success" to success,
            "failed" to (total - success),
            "successRate" to "%.1f%%".format(rate),
            "avgLatencyMs" to avgLatency,
            "errors" to errorsByReason.mapValues { it.value.get() },
        )
    }

    fun clear() {
        totalRequests.set(0)
        successfulRequests.set(0)
        totalDurationMs.set(0)
        errorsByReason.clear()
    }
}
