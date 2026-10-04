package dev.aigw.core.usage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore

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
) {
    private val records = ArrayDeque<CallRecord>()

    init {
        load()
    }

    @Synchronized
    fun add(record: CallRecord) {
        records.addFirst(record)
        store.write(keyOf(record.id), record.toJson().toString())
        while (records.size > capacity) {
            val evicted = records.removeLast()
            store.delete(keyOf(evicted.id))
        }
    }

    /** 最新在前。 */
    @Synchronized
    fun list(): List<CallRecord> = records.toList()

    @Synchronized
    fun delete(id: String) {
        records.removeAll { it.id == id }
        store.delete(keyOf(id))
    }

    @Synchronized
    fun clear() {
        // 扫键删除：`load()` 只载入 capacity 条，内存之外的旧键也得清掉，
        // 否则「清空调用记录」后存储占用降不下去。
        for (key in store.keys(PREFIX)) store.delete(key)
        records.clear()
    }

    /** 统计 [sinceMillis] 之后的调用。 */
    @Synchronized
    fun stats(sinceMillis: Long): UsageStats {
        var stats = UsageStats()
        for (record in records) {
            if (record.startedAtMillis < sinceMillis) continue
            stats = stats.copy(
                requests = stats.requests + 1,
                promptTokens = stats.promptTokens + record.promptTokens,
                completionTokens = stats.completionTokens + record.completionTokens,
                totalTokens = stats.totalTokens + record.totalTokens,
                success = stats.success + if (record.status == CallStatus.SUCCESS) 1 else 0,
                failed = stats.failed + if (record.status == CallStatus.FAILED) 1 else 0,
            )
        }
        return stats
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

    private fun load() {
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
    }

    private fun keyOf(id: String) = "$PREFIX$id"

    companion object {
        const val PREFIX = "logs/calls/"

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
