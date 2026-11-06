package dev.aigw.core.usage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.startOfDay
import java.io.File

/** 调用记录详情里的文本字段。 */
enum class CallTextField(val fileName: String) {
    REQUEST("request"),
    RESPONSE("response"),
    RAW("raw"),
}

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
    /**
     * 本次请求命中上游前缀缓存的输入 token（按 OpenAI 口径，视为 [promptTokens] 的子集）。
     * 上游没报就是 0——「没报」与「命中 0」在界面上都会显示 0，属已知取舍。
     */
    val cachedTokens: Long = 0,
    /** 上游回报的本次积分消耗（如 Loomy 的 points_consumed），无则 0。 */
    val pointsConsumed: Long = 0,
    /**
     * 首字延迟（毫秒）：从网关收到请求到第一字节内容写向客户端。
     * -1 表示这次请求没有产生首字（失败/空回复/非流式未拿到内容）。
     * 排障时它是「上游排队 vs 模型思考」的直接区分依据，所以必须落库而不是只打日志。
     */
    val firstByteMillis: Long = -1,
    val durationMillis: Long,
    val error: String,
    /** 请求体原文（截断保存），供「调用记录」详情查看。 */
    val requestBody: String,
    /** 响应摘要（截断保存）：流式为拼接后的正文，非流式为 content。 */
    val responseBody: String,
    /** 返回原文（截断保存）：流式为上游逐行报文，非流式为完整 JSON；排查 503/空回复用。 */
    val rawResponse: String = "",
    /**
     * 本次请求一共向上游尝试了几次（含最终成功的那次）。
     *
     * 与「请求数」分开记：一次请求换 3 个号才成功，请求数是 1、尝试数是 3。
     * 把两者混为一谈会让成功率既不能反映客户端体验、也不能反映上游健康度。
     */
    val tries: Int = 1,
    /** 是否靠换号/换供应商救回来的（尝试数 > 1 且最终成功）。 */
    val retried: Boolean = false,
    /** 调用方的客户端密钥 id / 名称；本机免鉴权调用为空。 */
    val clientKeyId: String = "",
    val clientKeyName: String = "",
    /** 本次调用的费用（微美元，1e-6 USD）。 */
    val costMicros: Long = 0L,
    /** 费用是否算得出来；false 表示该模型没配价格，界面必须显示「未定价」而不是 0 元。 */
    val costKnown: Boolean = false,
    /** 是否复用了会话亲和（同会话仍落在上一轮的账号上）。 */
    val affinityReused: Boolean = false,
)

/** 用量统计聚合。 */
data class UsageStats(
    val requests: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    /** 命中前缀缓存的输入 token 合计；命中率 = cachedTokens / promptTokens。 */
    val cachedTokens: Long = 0,
    val success: Long = 0,
    val failed: Long = 0,
    /** 客户端中途断开（流式中断）的请求数；三者相加应等于 [requests]。 */
    val aborted: Long = 0,
    /** 靠换号/换供应商救回来的请求数（是 [requests] 的子集，不是另一个分类）。 */
    val retried: Long = 0,
    /** 已算出费用的调用合计（微美元）。 */
    val costMicros: Long = 0,
    /** 有记录但价格未知的调用数：它的费用**没有**计入 [costMicros]，界面要一并显示。 */
    val unpriced: Long = 0,
) {
    /** 命中率（0~100 的百分比）；没有输入 token 时返回 null（无意义，不编造 0%）。 */
    val cacheHitPercent: Double?
        get() = if (promptTokens > 0) cachedTokens.toDouble() * 100.0 / promptTokens else null
}

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
    /**
     * 完整原文溢出目录（可空）：加密存储只保留每字段前 [MAX_SUMMARY_CHARS] 字符的摘要，
     * 超出部分写到该目录下 `记录id.字段.txt`，详情页按 id 回读完整原文。
     * 目录由调用方提供（App 传 cacheDir，可被系统回收；CLI 传数据目录）。
     */
    private val spillDir: File? = null,
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
        // 入库前把每字段截到摘要长度：内存列表与加密存储都只持有短文本，
        // 超出的完整原文走 [spillOverflows] 落到缓存目录。
        val excerpt = record.copy(
            requestBody = record.requestBody.take(MAX_SUMMARY_CHARS),
            responseBody = record.responseBody.take(MAX_SUMMARY_CHARS),
            rawResponse = record.rawResponse.take(MAX_SUMMARY_CHARS),
        )
        spillOverflows(record)
        records.addFirst(excerpt)
        store.write(keyOf(record.id), excerpt.toJson().toString())
        while (records.size > capacity) {
            val evicted = records.removeLast()
            store.delete(keyOf(evicted.id))
            deleteSpill(evicted.id)
        }
        totalAgg = totalAgg + record
        store.write(TOTAL_KEY, totalAgg.toJson().toString())
        val day = startOfDay(record.startedAtMillis)
        val updated = (daily[day] ?: UsageStats()) + record
        daily[day] = updated
        store.write(dayKeyOf(day), updated.toJson().toString())
        pruneDaily()
    }

    /**
     * 回读某条记录某字段的完整原文；没有溢出文件（未超摘要长度或已清理）时返回 null，
     * 调用方回落展示加密存储里的摘要。
     */
    fun fullText(id: String, field: CallTextField): String? {
        val dir = spillDir ?: return null
        val file = File(dir, spillFileName(id, field))
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** 最新在前。 */
    @Synchronized
    fun list(): List<CallRecord> = records.toList()

    @Synchronized
    fun delete(id: String) {
        val removed = records.firstOrNull { it.id == id }
        records.removeAll { it.id == id }
        store.delete(keyOf(id))
        deleteSpill(id)
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
        if (spillDir != null && spillDir.isDirectory) {
            spillDir.listFiles()?.forEach { it.delete() }
        }
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
            deleteSpill(key.removePrefix(PREFIX))
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
            // 溢出的完整原文同样按截断后的长度回收：用户执行「截断超长记录内容」就是为了省存储，
            // 缓存目录里再留一份全文会让该动作失效。仅删除发生过截断的字段对应文件。
            if (id.isNotEmpty()) {
                if (trimmedRequest != request) deleteSpill(id, CallTextField.REQUEST)
                if (trimmedResponse != response) deleteSpill(id, CallTextField.RESPONSE)
                if (trimmedRaw != rawResponse) deleteSpill(id, CallTextField.RAW)
            }
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
                    cachedTokens = obj.get("cachedTokens")?.asLong ?: 0L,
                    pointsConsumed = obj.get("pointsConsumed")?.asLong ?: 0L,
                    firstByteMillis = obj.get("firstByteMillis")?.asLong ?: -1L,
                    durationMillis = obj.get("durationMillis")?.asLong ?: 0L,
                    error = obj.get("error")?.asString.orEmpty(),
                    requestBody = obj.get("requestBody")?.asString.orEmpty(),
                    responseBody = obj.get("responseBody")?.asString.orEmpty(),
                    rawResponse = obj.get("rawResponse")?.asString.orEmpty(),
                    tries = obj.get("tries")?.asInt ?: 1,
                    retried = obj.get("retried")?.asBoolean ?: false,
                    clientKeyId = obj.get("clientKeyId")?.asString.orEmpty(),
                    clientKeyName = obj.get("clientKeyName")?.asString.orEmpty(),
                    costMicros = obj.get("costMicros")?.asLong ?: 0L,
                    costKnown = obj.get("costKnown")?.asBoolean ?: false,
                    affinityReused = obj.get("affinityReused")?.asBoolean ?: false,
                ),
            )
        }
        loaded.sortByDescending { it.startedAtMillis }
        // 引擎的异步路径会在后台线程调用它；若载入期间已有新记录写入内存（需要网关在进程
        // 启动后极短时间内就开始服务才会发生），以内存为准、按 id 去重，避免同一 id 出现两条
        // 导致列表 LazyColumn 的 key 重复崩溃。
        val known = records.mapTo(HashSet()) { it.id }
        for (item in loaded) {
            if (known.add(item.id)) records.addLast(item)
        }
        while (records.size > capacity) records.removeLast()
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

    /**
     * 把超出摘要长度的完整原文溢写到缓存目录（每字段一个文件，单文件上限 [MAX_SPILL_CHARS]）。
     * 只在确实有超出时才落盘；写入失败静默降级为「详情页只看摘要」。
     */
    private fun spillOverflows(record: CallRecord) {
        val dir = spillDir ?: return
        spillField(dir, record.id, CallTextField.REQUEST, record.requestBody)
        spillField(dir, record.id, CallTextField.RESPONSE, record.responseBody)
        spillField(dir, record.id, CallTextField.RAW, record.rawResponse)
    }

    private fun spillField(dir: File, id: String, field: CallTextField, content: String) {
        if (content.length <= MAX_SUMMARY_CHARS) {
            // 没有溢出：清掉可能残留的旧文件（同 id 不会重发，防御性清理）
            File(dir, spillFileName(id, field)).delete()
            return
        }
        runCatching {
            dir.mkdirs()
            val file = File(dir, spillFileName(id, field))
            file.writeText(if (content.length > MAX_SPILL_CHARS) content.substring(0, MAX_SPILL_CHARS) else content)
            enforceSpillBudget(dir)
        }
    }

    /**
     * 溢出目录总量预算：超出 [MAX_SPILL_TOTAL_BYTES] 时按最后修改时间从最旧开始删，
     * 直到回到预算内。缓存目录本身可被系统回收，但不应无限制增长（最坏 3 字段×512K×容量）。
     */
    private fun enforceSpillBudget(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_SPILL_TOTAL_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_SPILL_TOTAL_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun deleteSpill(id: String, field: CallTextField? = null) {
        val dir = spillDir ?: return
        if (field == null) {
            for (f in CallTextField.entries) File(dir, spillFileName(id, f)).delete()
        } else {
            File(dir, spillFileName(id, field)).delete()
        }
    }

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

        /** 加密存储里每字段保留的摘要长度（字符），与 MAX_LOGGED_BODY 同值。 */
        const val MAX_SUMMARY_CHARS = 8 * 1024

        /** 溢出文件单字段上限（字符）：512K，超出部分不保留（摘要仍提示总长）。 */
        const val MAX_SPILL_CHARS = 512 * 1024

        /** 溢出目录总量预算（字节）：超出时删除最旧文件。 */
        const val MAX_SPILL_TOTAL_BYTES = 64L * 1024 * 1024

        fun spillFileName(id: String, field: CallTextField): String = "$id.${field.fileName}.txt"
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
    addProperty("cachedTokens", cachedTokens)
    addProperty("pointsConsumed", pointsConsumed)
    addProperty("firstByteMillis", firstByteMillis)
    addProperty("durationMillis", durationMillis)
    addProperty("error", error)
    addProperty("requestBody", requestBody)
    addProperty("responseBody", responseBody)
    addProperty("rawResponse", rawResponse)
    addProperty("tries", tries)
    addProperty("retried", retried)
    addProperty("clientKeyId", clientKeyId)
    addProperty("clientKeyName", clientKeyName)
    addProperty("costMicros", costMicros)
    addProperty("costKnown", costKnown)
    addProperty("affinityReused", affinityReused)
}

private operator fun UsageStats.plus(record: CallRecord) = copy(
    requests = requests + 1,
    promptTokens = promptTokens + record.promptTokens,
    completionTokens = completionTokens + record.completionTokens,
    totalTokens = totalTokens + record.totalTokens,
    cachedTokens = cachedTokens + record.cachedTokens,
    success = success + if (record.status == CallStatus.SUCCESS) 1 else 0,
    failed = failed + if (record.status == CallStatus.FAILED) 1 else 0,
    aborted = aborted + if (record.status == CallStatus.ABORTED) 1 else 0,
    retried = retried + if (record.retried) 1 else 0,
    // 未定价的调用不计入金额，单独计数：合计旁并排告知用户「另有 N 次未定价」，
    // 把它算成 0 元会让总金额看起来偏低。
    costMicros = costMicros + if (record.costKnown) record.costMicros else 0L,
    unpriced = unpriced + if (record.costKnown) 0 else 1,
)

private operator fun UsageStats.minus(record: CallRecord) = copy(
    requests = (requests - 1).coerceAtLeast(0),
    promptTokens = (promptTokens - record.promptTokens).coerceAtLeast(0),
    completionTokens = (completionTokens - record.completionTokens).coerceAtLeast(0),
    totalTokens = (totalTokens - record.totalTokens).coerceAtLeast(0),
    cachedTokens = (cachedTokens - record.cachedTokens).coerceAtLeast(0),
    success = (success - if (record.status == CallStatus.SUCCESS) 1 else 0).coerceAtLeast(0),
    failed = (failed - if (record.status == CallStatus.FAILED) 1 else 0).coerceAtLeast(0),
    aborted = (aborted - if (record.status == CallStatus.ABORTED) 1 else 0).coerceAtLeast(0),
    retried = (retried - if (record.retried) 1 else 0).coerceAtLeast(0),
    costMicros = (costMicros - if (record.costKnown) record.costMicros else 0L).coerceAtLeast(0),
    unpriced = (unpriced - if (record.costKnown) 0 else 1).coerceAtLeast(0),
)

private fun UsageStats.toJson(): JsonObject = JsonObject().apply {
    addProperty("requests", requests)
    addProperty("promptTokens", promptTokens)
    addProperty("completionTokens", completionTokens)
    addProperty("totalTokens", totalTokens)
    addProperty("cachedTokens", cachedTokens)
    addProperty("success", success)
    addProperty("failed", failed)
    addProperty("aborted", aborted)
    addProperty("retried", retried)
    addProperty("costMicros", costMicros)
    addProperty("unpriced", unpriced)
}

private fun UsageStats.isZero(): Boolean =
    requests == 0L && promptTokens == 0L && completionTokens == 0L &&
        totalTokens == 0L && cachedTokens == 0L && success == 0L && failed == 0L && aborted == 0L &&
        retried == 0L && costMicros == 0L && unpriced == 0L

private fun usageStatsFromJson(raw: String): UsageStats {
    val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return UsageStats()
    return UsageStats(
        requests = obj.get("requests")?.asLong ?: 0L,
        promptTokens = obj.get("promptTokens")?.asLong ?: 0L,
        completionTokens = obj.get("completionTokens")?.asLong ?: 0L,
        totalTokens = obj.get("totalTokens")?.asLong ?: 0L,
        cachedTokens = obj.get("cachedTokens")?.asLong ?: 0L,
        success = obj.get("success")?.asLong ?: 0L,
        failed = obj.get("failed")?.asLong ?: 0L,
        aborted = obj.get("aborted")?.asLong ?: 0L,
        retried = obj.get("retried")?.asLong ?: 0L,
        costMicros = obj.get("costMicros")?.asLong ?: 0L,
        unpriced = obj.get("unpriced")?.asLong ?: 0L,
    )
}
