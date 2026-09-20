package dev.aigw.core.usage

/** 一条存储明细：某项数据占了多少。 */
data class StorageEntry(val name: String, val chars: Long, val items: Int, val detail: String = "")

/** 存储占用总览。 */
data class StorageReport(
    val entries: List<StorageEntry>,
    val totalChars: Long,
) {
    /** 除调用记录外的其它数据（日志、设置、凭证）合计。 */
    val recordsChars: Long get() = entries.firstOrNull { it.name == NAME_CALLS }?.chars ?: 0L

    companion object {
        const val NAME_CALLS = "调用记录（含请求/响应原文）"
        const val NAME_REQUESTS = "请求日志"
        const val NAME_ACCOUNTS = "账号与凭证"
        const val NAME_SETTINGS = "设置"
        const val NAME_PENDING = "登录中间态"
    }
}

/**
 * 按存储键前缀统计占用。纯本地计算，不触网。
 *
 * 之所以放在 core 而不是 UI：这些前缀（`logs/calls/`、`account/`…）是存储层的实现细节，
 * 让界面去拼字符串容易随存储结构调整而失配，集中在这里也便于单测。
 */
object StorageAudit {

    /** 与 [StorageReport] 的名字一一对应。 */
    private val GROUPS = listOf(
        Triple("logs/calls/", StorageReport.NAME_CALLS, "请求/响应原文，体积主要来源"),
        Triple("logs/requests.json", StorageReport.NAME_REQUESTS, "服务运行日志"),
        Triple("account/", StorageReport.NAME_ACCOUNTS, "含 token，请勿外传"),
        Triple("login/pending/", StorageReport.NAME_PENDING, "登录用的设备指纹缓存"),
        Triple("settings/", StorageReport.NAME_SETTINGS, "网关与界面偏好"),
    )

    fun audit(store: dev.aigw.core.store.KeyValueStore): StorageReport {
        val entries = ArrayList<StorageEntry>(GROUPS.size)
        var total = 0L
        for ((prefix, name, detail) in GROUPS) {
            // 前缀可能是目录（account/）也可能是单个键（settings/...），两种都要能数到
            val keys = if (prefix.endsWith("/")) store.keys(prefix) else keysMatching(store, prefix)
            val chars = keys.sumOf { store.read(it)?.length?.toLong() ?: 0L }
            total += chars
            entries.add(StorageEntry(name = name, chars = chars, items = keys.size, detail = detail))
        }
        return StorageReport(entries = entries.sortedByDescending { it.chars }, totalChars = total)
    }

    private fun keysMatching(store: dev.aigw.core.store.KeyValueStore, exact: String): List<String> =
        store.keys(exact).filter { it == exact }

    /** 把字符量折成便于阅读的字符串（1 KB = 1024 字符）。 */
    fun formatSize(chars: Long): String = when {
        chars < 1024 -> "$chars B"
        chars < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.US, chars / 1024.0)
        else -> "%.2f MB".format(java.util.Locale.US, chars / 1024.0 / 1024.0)
    }
}
