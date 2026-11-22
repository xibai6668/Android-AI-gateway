package dev.aigw.core.usage

import java.io.File

/** 一条存储明细：某项数据占了多少。 */
data class StorageEntry(
    val name: String,
    /** 占用量（字节）。键值类按字符数近似（内容以 ASCII 为主，与磁盘占用同量级）。 */
    val bytes: Long,
    val items: Int,
    val detail: String = "",
    /** 是否为可以安全删除的缓存（界面据此决定要不要给「清理」按钮）。 */
    val clearable: Boolean = false,
)

/** 存储占用总览。 */
data class StorageReport(
    val entries: List<StorageEntry>,
    val totalBytes: Long,
) {
    /** 调用记录（含溢出原文）的合计。 */
    val recordsBytes: Long
        get() = entries.filter { it.name == NAME_CALLS || it.name == NAME_CALL_FULLTEXT }.sumOf { it.bytes }

    /** 可清理项的合计：缓存、更新包、崩溃日志这类删掉不影响功能的数据。 */
    val clearableBytes: Long get() = entries.filter { it.clearable }.sumOf { it.bytes }

    companion object {
        const val NAME_CALLS = "调用记录（含请求/响应原文）"
        const val NAME_CALL_FULLTEXT = "调用记录完整原文（缓存）"
        const val NAME_REQUESTS = "请求日志"
        const val NAME_ACCOUNTS = "账号与凭证"
        const val NAME_SETTINGS = "设置"
        const val NAME_PENDING = "登录中间态"
        const val NAME_UPDATE_APK = "下载的更新包（缓存）"
        const val NAME_CRASH = "崩溃日志"
    }
}

/**
 * 存储占用统计。纯本地计算，不触网。
 *
 * 之所以放在 core 而不是 UI：这些前缀（`logs/calls/`、`account/`…）是存储层的实现细节，
 * 让界面去拼字符串容易随存储结构调整而失配，集中在这里也便于单测。
 *
 * **必须同时统计目录**：键值存储只占几 MB，真正把应用撑到几十 MB 的是缓存目录里的
 * 溢出原文与更新包。只算键值会让界面显示的数字与系统「应用信息」里的占用差一个数量级，
 * 用户就无从判断该清什么。
 */
object StorageAudit {

    /** 与 [StorageReport] 的名字一一对应。 */
    private val GROUPS = listOf(
        Triple("logs/calls/", StorageReport.NAME_CALLS, "请求/响应原文摘要，体积主要来源"),
        Triple("logs/requests.json", StorageReport.NAME_REQUESTS, "服务运行日志"),
        Triple("account/", StorageReport.NAME_ACCOUNTS, "含 token，请勿外传"),
        Triple("login/pending/", StorageReport.NAME_PENDING, "登录用的设备指纹缓存"),
        Triple("settings/", StorageReport.NAME_SETTINGS, "网关与界面偏好"),
    )

    /**
     * @param store 键值存储
     * @param dirs 额外统计的目录（名称 → 目录 → 说明 → 是否可清理）。App 传缓存与崩溃日志目录，
     *   CLI 不需要传。
     */
    fun audit(store: dev.aigw.core.store.KeyValueStore, dirs: List<DirectoryGroup> = emptyList()): StorageReport {
        val entries = ArrayList<StorageEntry>(GROUPS.size + dirs.size)
        var total = 0L
        for ((prefix, name, detail) in GROUPS) {
            // 前缀可能是目录（account/）也可能是单个键（settings/...），两种都要能数到
            val keys = if (prefix.endsWith("/")) store.keys(prefix) else keysMatching(store, prefix)
            val chars = keys.sumOf { store.read(it)?.length?.toLong() ?: 0L }
            total += chars
            entries.add(StorageEntry(name = name, bytes = chars, items = keys.size, detail = detail))
        }
        for (group in dirs) {
            val (bytes, count) = sizeOf(group.dir)
            total += bytes
            entries.add(
                StorageEntry(
                    name = group.name,
                    bytes = bytes,
                    items = count,
                    detail = group.detail,
                    clearable = group.clearable,
                ),
            )
        }
        return StorageReport(entries = entries.sortedByDescending { it.bytes }, totalBytes = total)
    }

    /** 一个需要单独统计、可能还需要清理的目录。 */
    data class DirectoryGroup(
        val name: String,
        val dir: File,
        val detail: String,
        /** 可清理：删掉只影响「历史详情能否回读」这类次要能力，不影响网关工作。 */
        val clearable: Boolean = false,
    )

    /** 目录占用（字节, 文件数）；目录不存在时返回 0。只统计普通文件，不跟随符号链接。 */
    fun sizeOf(dir: File): Pair<Long, Int> {
        if (!dir.isDirectory) return 0L to 0
        var bytes = 0L
        var count = 0
        dir.walkTopDown()
            .filter { it.isFile }
            .forEach {
                bytes += it.length()
                count++
            }
        return bytes to count
    }

    private fun keysMatching(store: dev.aigw.core.store.KeyValueStore, exact: String): List<String> =
        store.keys(exact).filter { it == exact }

    /** 把字节量折成便于阅读的字符串。 */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
        else -> "%.2f MB".format(java.util.Locale.US, bytes / 1024.0 / 1024.0)
    }
}
