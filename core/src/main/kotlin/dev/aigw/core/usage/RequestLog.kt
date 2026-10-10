package dev.aigw.core.usage

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「请求日志」页用的一行日志。 */
data class LogLine(
    /** 单调自增序号：LazyColumn 的 key 用它保证唯一（时间戳+内容可能重复，会崩）。 */
    val seq: Long,
    val atMillis: Long,
    val level: String,
    val text: String,
) {
    fun render(): String = "${TIME_FORMAT.format(Date(atMillis))} [$level] $text"

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
}

/**
 * 环形日志缓冲。写盘做了节流（默认 2 秒最多一次），避免每个请求多写几次文件。
 */
class RequestLog(
    private val store: KeyValueStore,
    private val capacity: Int = 300,
    private val persistIntervalMillis: Long = 2_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lines = ArrayDeque<LogLine>()
    private var lastPersistAt = 0L

    /** 日志行序号：同一实例内单调递增，供 LazyColumn 稳定 key 使用。 */
    private var nextSeq = 0L

    init {
        load()
    }

    @Synchronized
    fun info(text: String) = append("INFO", text)

    @Synchronized
    fun warn(text: String) = append("WARN", text)

    @Synchronized
    fun error(text: String) = append("ERROR", text)

    /** 最新在前。 */
    @Synchronized
    fun lines(): List<LogLine> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        persist()
    }

    @Synchronized
    fun flush() = persist()

    private fun append(level: String, text: String) {
        val now = nowMillis()
        lines.addFirst(LogLine(nextSeq++, now, level, text))
        while (lines.size > capacity) lines.removeLast()
        if (now - lastPersistAt >= persistIntervalMillis) persist()
    }

    private fun persist() {
        lastPersistAt = nowMillis()
        val array = JsonArray()
        for (line in lines) {
            array.add(com.google.gson.JsonObject().apply {
                addProperty("at", line.atMillis)
                addProperty("level", line.level)
                addProperty("text", line.text)
            })
        }
        store.write(STORE_KEY, array.toString())
    }

    private fun load() {
        val raw = store.read(STORE_KEY) ?: return
        val array = runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull() ?: return
        for (element in array) {
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: continue
            lines.addLast(
                LogLine(
                    seq = nextSeq++,
                    atMillis = obj.get("at")?.asLong ?: 0L,
                    level = obj.get("level")?.asString.orEmpty(),
                    text = obj.get("text")?.asString.orEmpty(),
                ),
            )
        }
    }

    companion object {
        const val STORE_KEY = "logs/requests.json"
    }
}
