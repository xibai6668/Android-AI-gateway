package dev.aigw.core.usage

import java.util.concurrent.ConcurrentHashMap

/**
 * 按「供应商 × 模型」的近期健康窗口。
 *
 * 为什么要单独一份：`ProviderMetricsTracker` 只有累计计数与均值，
 * 问不出「最近五分钟这个模型是不是集体超时」——而线上排障真正要看的就是这个。
 * 这里保存每个键最近 [capacity] 次调用的结果、耗时与首字延迟，按需现算分位数。
 *
 * 只保留内存、不落盘：它是排障用的实时视图，重启后从零开始是合理语义（不是数据丢失）。
 */
class ModelHealthWindow(private val capacity: Int = DEFAULT_CAPACITY) {

    /** 一次调用的观测结果。 */
    private data class Sample(
        val ok: Boolean,
        val durationMillis: Long,
        /** 首字延迟；-1 表示没有首字。 */
        val firstByteMillis: Long,
        val error: String,
    )

    private val windows = ConcurrentHashMap<String, ArrayDeque<Sample>>()

    /** 记录一次调用；[model] 为空时只按供应商聚合。 */
    fun record(
        providerId: String,
        model: String,
        ok: Boolean,
        durationMillis: Long,
        firstByteMillis: Long = -1,
        error: String = "",
    ) {
        val key = keyOf(providerId, model)
        val window = windows.computeIfAbsent(key) { ArrayDeque() }
        synchronized(window) {
            window.addLast(
                Sample(ok, durationMillis.coerceAtLeast(0), firstByteMillis, error.take(120)),
            )
            while (window.size > capacity) window.removeFirst()
        }
    }

    /** 某供应商下所有模型键的快照（键形如 `providerId/model`）。 */
    fun snapshot(): Map<String, Map<String, Any>> =
        windows.entries.associate { (key, window) -> key to summarize(window) }

    /** 单个键的快照；没有样本时返回 null。 */
    fun snapshotOf(providerId: String, model: String): Map<String, Any>? =
        windows[keyOf(providerId, model)]?.let { summarize(it) }

    fun clear() = windows.clear()

    private fun summarize(window: ArrayDeque<Sample>): Map<String, Any> = synchronized(window) {
        val samples = window.toList()
        if (samples.isEmpty()) return@synchronized emptyMap<String, Any>()
        val okCount = samples.count { it.ok }
        val ttfb = samples.map { it.firstByteMillis }.filter { it >= 0 }.sorted()
        val durations = samples.map { it.durationMillis }.sorted()
        val errors = samples.filter { !it.ok && it.error.isNotEmpty() }
            .groupingBy { it.error }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(5)
            .associate { it.key to it.value }
        mapOf(
            "samples" to samples.size,
            "success" to okCount,
            "successRate" to "%.1f%%".format(okCount.toDouble() * 100.0 / samples.size),
            "p50Ms" to percentile(durations, 50),
            "p95Ms" to percentile(durations, 95),
            // 首字延迟单独统计：它区分「上游排队」与「模型思考」，和总耗时不是一回事
            "ttfbSamples" to ttfb.size,
            "ttfbP50Ms" to percentile(ttfb, 50),
            "ttfbP95Ms" to percentile(ttfb, 95),
            "errors" to errors,
        )
    }

    /**
     * nearest-rank 分位数：取第 ⌈p% × n⌉ 个样本。
     *
     * 不用线性插值：小样本下插值会给出一个**实际不存在**的耗时（如 5 个样本算 p95 得 48ms），
     * 排障时容易误导；nearest-rank 永远返回真实观测值。空列表返回 -1（「没有数据」而不是 0）。
     */
    private fun percentile(sorted: List<Long>, percent: Int): Long {
        if (sorted.isEmpty()) return -1L
        val rank = Math.ceil(percent.toDouble() / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    companion object {
        /** 每个键保留的样本数：够看出趋势，又不至于在手机上堆内存。 */
        const val DEFAULT_CAPACITY = 50

        fun keyOf(providerId: String, model: String): String =
            if (model.isEmpty()) providerId else "$providerId/$model"
    }
}
