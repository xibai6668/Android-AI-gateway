package dev.aigw.core.usage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.startOfDay

/** 一次调用的结果。 */
enum class CallStatus { SUCCESS, FAILED, ABORTED }

/** 一次调用的记录（不含凭证）。 */
data class CallRecord(
    val id: String,
    val startedAtMillis: Long,
    /** 处理这次请求的供应商 id（如 `trae`、`custom:mysite`）。 */
    val providerId: String,
    val model: String,
    val accountUid: String,
    val accountNickname: String,
    val streaming: Boolean,
    val status: CallStatus,
    val httpStatus: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    /** 上游回报的本次积分消耗（如 Loomy 的 points_consumed），无则 0。 */
    val pointsConsumed: Long = 0,
    val durationMillis: Long,
    val error: String,
    /** 请求体原文（截断保存），供「调用记录」详情查看。 */
    val requestBody: String,
    /** 响应摘要（截断保存）：流式为拼接后的正文，非流式为 content。 */
    val responseBody: String,
    /** 返回原文（截断保存）：流式为上游逐行报文，非流式为完整 JSON；排查 503/空回复用。 */
    val rawResponse: String = "",
)

/** 用量统计聚合。 */
data class UsageStats(
    val requests: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val success: Long = 0,
    val failed: Long = 0,
)

/**
 * 调用记录存储。
 *
 * 每条记录单独占一个键（`logs/calls/{id}`）：请求/响应原文动辄几 KB，
 * 攒成一个大 JSON 会让加密存储单值过大、每次写都要整体重加密。
 */
class CallLogStore(
    private val store: KeyValueStore,
    private val capacity: Int = 100,
    /** 构造时是否同步载入历史；引擎传 false 时改为后台线程调 [load]，避免冷启动阻塞主线程。 */
    preloadOnConstruction: Boolean = true,
) {
    private val records = ArrayDeque<CallRecord>()

    /** 累计聚合：独立于调用记录持久化。记录有容量淘汰与过期清理，直接从记录里现算「累计」只会等于最近几条。 */
    private var totalAgg = UsageStats()

    /** 每日聚合，键为该日 00:00 的时间戳；仅保留最近 [RETENTION_DAYS] 天。 */
    private val daily = mutableMapOf<Long, UsageStats>()

    init {
        if (preloadOnConstruction) load()
    }

    @Synchronized
    fun add(record: CallRecord) {
        records.addFirst(record)
        store.write(keyOf(record.id), record.toJson().toString())
        while (records.size > capacity) {
            val evicted = records.removeLast()
            store.delete(keyOf(evicted.id))
        }
        totalAgg = totalAgg + record
        store.write(TOTAL_KEY, totalAgg.toJson().toString())
        val day = startOfDay(record.startedAtMillis)
        val updated = (daily[day] ?: UsageStats()) + record
        daily[day] = updated
        store.write(dayKeyOf(day), updated.toJson().toString())
        pruneDaily()
    }

    /** 最新在前。 */
    @Synchronized
    fun list(): List<CallRecord> = records.toList()

    @Synchronized
    fun delete(id: String) {
        val removed = records.firstOrNull { it.id == id }
        records.removeAll { it.id == id }
        store.delete(keyOf(id))
        if (removed != null) {
            totalAgg = totalAgg - removed
            store.write(TOTAL_KEY, totalAgg.toJson().toString())
            val day = startOfDay(removed.startedAtMillis)
            val remaining = (daily[day] ?: UsageStats()) - removed
            if (remaining.isZero()) {
                daily.remove(day)
                store.delete(dayKeyOf(day))
            } else {
                daily[day] = remaining
                store.write(dayKeyOf(day), remaining.toJson().toString())
            }
        }
    }

    @Synchronized
    fun clear() {
        // 扫键删除：`load()` 只载入 capacity 条，内存之外的旧键也得清掉，
        // 否则「清空调用记录」后存储占用降不下去。
        for (key in store.keys(PREFIX)) store.delete(key)
        records.clear()
        for (key in store.keys(USAGE_PREFIX)) store.delete(key)
        totalAgg = UsageStats()
        daily.clear()
    }

    /** 删除早于保留窗口的日键；只清内存与存储的旧日聚合，不影响累计。 */
    private fun pruneDaily() {
        val cutoff = startOfDay(System.currentTimeMillis()) - (RETENTION_DAYS - 1) * 86_400_000L
        for (day in daily.keys.filter { it < cutoff }) {
            daily.remove(day)
            store.delete(dayKeyOf(day))
        }
    }

    /** 累计聚合：不受容量淘汰与过期清理影响，跨启动累加。 */
    @Synchronized
    fun totalStats(): UsageStats = totalAgg

    /** [nowMillis] 所在天的聚合；当天还没有记录时为全零。 */
    @Synchronized
    fun todayStats(nowMillis: Long): UsageStats = daily[startOfDay(nowMillis)] ?: UsageStats()

    /**
     * 最近 [days] 天的每日聚合：下标 0 为最早一天、末尾为 [nowMillis] 所在天；
     * 无记录的日期补零。
     */
    @Synchronized
    fun dailyStats(nowMillis: Long, days: Int = 30): List<UsageStats> {
        if (days <= 0) return emptyList()
        val today = startOfDay(nowMillis)
        return (days - 1 downTo 0).map { offset ->
            daily[startOfDay(today - offset * 86_400_000L)] ?: UsageStats()
        }
    }

    /**
     * 删除 [cutoffMillis] 之前产生的记录，返回删除条数。
     * 直接扫键而不是只看内存列表：`load()` 只载入 capacity 条，超过容量的旧记录不在
     * 内存里，按内存清会漏掉它们，存储占用也就降不下来。
     */
    @Synchronized
    fun purgeOlderThan(cutoffMillis: Long): Int {
        var removed = 0
        for (key in store.keys(PREFIX)) {
            val raw = store.read(key) ?: continue
            val startedAt = runCatching {
                JsonParser.parseString(raw).asJsonObject.get("startedAt")?.asLong
            }.getOrNull() ?: continue
            if (startedAt >= cutoffMillis) continue
            store.delete(key)
            records.removeAll { keyOf(it.id) == key }
            removed++
        }
        return removed
    }

    /**
     * 把每条记录的请求/响应原文截到 [maxChars] 字符以内，返回「截断条数 to 释放字符数」。
     * 用于「截断超长记录内容」：原文动辄几十 KB，是存储占用的主要来源。
     * 同样扫键以覆盖未载入内存的记录；截断不动 startedAt，不影响排序与保留策略。
     */
    @Synchronized
    fun truncateFields(maxChars: Int): Pair<Int, Long> {
        if (maxChars < MIN_FIELD_CHARS) return 0 to 0L
        var changed = 0
        var saved = 0L
        for (key in store.keys(PREFIX)) {
            val raw = store.read(key) ?: continue
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: continue
            val request = obj.get("requestBody")?.asString.orEmpty()
            val response = obj.get("responseBody")?.asString.orEmpty()
            val rawResponse = obj.get("rawResponse")?.asString.orEmpty()
            val trimmedRequest = truncate(request, maxChars)
            val trimmedResponse = truncate(response, maxChars)
            val trimmedRaw = truncate(rawResponse, maxChars)
            if (trimmedRequest == request && trimmedResponse == response && trimmedRaw == rawResponse) continue
            saved += (request.length - trimmedRequest.length).toLong() +
                (response.length - trimmedResponse.length).toLong() +
                (rawResponse.length - trimmedRaw.length).toLong()
            changed++
            obj.addProperty("requestBody", trimmedRequest)
            obj.addProperty("responseBody", trimmedResponse)
            obj.addProperty("rawResponse", trimmedRaw)
            store.write(key, obj.toString())
            val id = obj.get("id")?.asString.orEmpty()
            val index = records.indexOfFirst { it.id == id }
            if (index >= 0) {
                records[index] = records[index].copy(
                    requestBody = trimmedRequest,
                    responseBody = trimmedResponse,
                    rawResponse = trimmedRaw,
                )
            }
        }
        return changed to saved
    }

    /**
     * 存储中的字符量（内容以 ASCII 为主，与磁盘占用同量级）。
     * 扫键统计才能覆盖未载入内存的记录。
     */
    @Synchronized
    fun storageChars(): Long = store.keys(PREFIX).sumOf { store.read(it)?.length?.toLong() ?: 0L }

    /** 存储中的记录条数（含未载入内存的）。 */
    @Synchronized
    fun storedCount(): Int = store.keys(PREFIX).size

    private fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + TRUNCATE_MARK
    }

    /**
     * 从存储载入历史记录与每日/累计聚合。
     *
     * 引擎的异步路径会在后台线程调用它；若载入期间已有新记录写入内存（需要网关在进程
     * 启动后极短时间内就开始服务才会发生），以内存为准、跳过本次载入避免重复条目。
     */
    @Synchronized
    fun load() {
        val loaded = ArrayList<CallRecord>()
        for (key in store.keys(PREFIX)) {
            val raw = store.read(key) ?: continue
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: continue
            loaded.add(
                CallRecord(
                    id = obj.get("id")?.asString.orEmpty(),
                    startedAtMillis = obj.get("startedAt")?.asLong ?: 0L,
                    providerId = obj.get("providerId")?.asString.orEmpty(),
                    model = obj.get("model")?.asString.orEmpty(),
                    accountUid = obj.get("accountUid")?.asString.orEmpty(),
                    accountNickname = obj.get("accountNickname")?.asString.orEmpty(),
                    streaming = obj.get("streaming")?.asBoolean ?: false,
                    status = runCatching { CallStatus.valueOf(obj.get("status")?.asString.orEmpty()) }
                        .getOrDefault(CallStatus.FAILED),
                    httpStatus = obj.get("httpStatus")?.asInt ?: 0,
                    promptTokens = obj.get("promptTokens")?.asLong ?: 0L,
                    completionTokens = obj.get("completionTokens")?.asLong ?: 0L,
                    totalTokens = obj.get("totalTokens")?.asLong ?: 0L,
                    pointsConsumed = obj.get("pointsConsumed")?.asLong ?: 0L,
                    durationMillis = obj.get("durationMillis")?.asLong ?: 0L,
                    error = obj.get("error")?.asString.orEmpty(),
                    requestBody = obj.get("requestBody")?.asString.orEmpty(),
                    responseBody = obj.get("responseBody")?.asString.orEmpty(),
                    rawResponse = obj.get("rawResponse")?.asString.orEmpty(),
                ),
            )
        }
        loaded.sortByDescending { it.startedAtMillis }
        records.addAll(loaded.take(capacity))
        totalAgg = store.read(TOTAL_KEY)?.let(::usageStatsFromJson) ?: aggregate(records)
        for (key in store.keys(DAY_PREFIX)) {
            val day = key.removePrefix(DAY_PREFIX).toLongOrNull() ?: continue
            daily[day] = store.read(key)?.let(::usageStatsFromJson) ?: UsageStats()
        }
        if (daily.isEmpty() && records.isNotEmpty()) {
            // 老版本升级：存储里还没有日聚合键，按已载入的记录兜底（更早的已随记录淘汰，不可考）
            for (record in records) {
                val day = startOfDay(record.startedAtMillis)
                daily[day] = (daily[day] ?: UsageStats()) + record
            }
        }
    }

    private fun aggregate(records: List<CallRecord>): UsageStats {
        var stats = UsageStats()
        for (record in records) stats = stats + record
        return stats
    }

    private fun keyOf(id: String) = "$PREFIX$id"

    private fun dayKeyOf(day: Long) = "$DAY_PREFIX$day"

    companion object {
        const val PREFIX = "logs/calls/"
        private const val USAGE_PREFIX = "usage/"
        private const val TOTAL_KEY = "usage/total"
        private const val DAY_PREFIX = "usage/day/"

        /** 每日聚合保留天数（含今天）。 */
        const val RETENTION_DAYS = 30

        /** 截断标记，让用户在记录详情里看得出这条已被截过。 */
        const val TRUNCATE_MARK = "\n…（内容已截断）"

        /** 低于该值就不再截了，免得把记录截成完全无用。 */
        const val MIN_FIELD_CHARS = 200
    }
}

private fun CallRecord.toJson(): JsonObject = JsonObject().apply {
    addProperty("id", id)
    addProperty("startedAt", startedAtMillis)
    addProperty("providerId", providerId)
    addProperty("model", model)
    addProperty("accountUid", accountUid)
    addProperty("accountNickname", accountNickname)
    addProperty("streaming", streaming)
    addProperty("status", status.name)
    addProperty("httpStatus", httpStatus)
    addProperty("promptTokens", promptTokens)
    addProperty("completionTokens", completionTokens)
    addProperty("totalTokens", totalTokens)
    addProperty("pointsConsumed", pointsConsumed)
    addProperty("durationMillis", durationMillis)
    addProperty("error", error)
    addProperty("requestBody", requestBody)
    addProperty("responseBody", responseBody)
    addProperty("rawResponse", rawResponse)
}

private operator fun UsageStats.plus(record: CallRecord) = copy(
    requests = requests + 1,
    promptTokens = promptTokens + record.promptTokens,
    completionTokens = completionTokens + record.completionTokens,
    totalTokens = totalTokens + record.totalTokens,
    success = success + if (record.status == CallStatus.SUCCESS) 1 else 0,
    failed = failed + if (record.status == CallStatus.FAILED) 1 else 0,
)

private operator fun UsageStats.minus(record: CallRecord) = copy(
    requests = (requests - 1).coerceAtLeast(0),
    promptTokens = (promptTokens - record.promptTokens).coerceAtLeast(0),
    completionTokens = (completionTokens - record.completionTokens).coerceAtLeast(0),
    totalTokens = (totalTokens - record.totalTokens).coerceAtLeast(0),
    success = (success - if (record.status == CallStatus.SUCCESS) 1 else 0).coerceAtLeast(0),
    failed = (failed - if (record.status == CallStatus.FAILED) 1 else 0).coerceAtLeast(0),
)

private fun UsageStats.toJson(): JsonObject = JsonObject().apply {
    addProperty("requests", requests)
    addProperty("promptTokens", promptTokens)
    addProperty("completionTokens", completionTokens)
    addProperty("totalTokens", totalTokens)
    addProperty("success", success)
    addProperty("failed", failed)
}

private fun UsageStats.isZero(): Boolean =
    requests == 0L && promptTokens == 0L && completionTokens == 0L &&
        totalTokens == 0L && success == 0L && failed == 0L

private fun usageStatsFromJson(raw: String): UsageStats {
    val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return UsageStats()
    return UsageStats(
        requests = obj.get("requests")?.asLong ?: 0L,
        promptTokens = obj.get("promptTokens")?.asLong ?: 0L,
        completionTokens = obj.get("completionTokens")?.asLong ?: 0L,
        totalTokens = obj.get("totalTokens")?.asLong ?: 0L,
        success = obj.get("success")?.asLong ?: 0L,
        failed = obj.get("failed")?.asLong ?: 0L,
    )
}
