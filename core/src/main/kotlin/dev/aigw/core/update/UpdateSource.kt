package dev.aigw.core.update

import com.google.gson.JsonParser
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.str
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** 一个下载线路：名称 + 可访问 GitHub 的前缀（空串表示 GitHub 直连）。 */
data class MirrorSource(
    val id: String,
    val label: String,
    /** 加在完整 GitHub 地址前的镜像前缀；直连为空串。 */
    val prefix: String,
) {
    /** 把 GitHub 资源地址改写成该线路的地址。 */
    fun map(githubUrl: String): String = if (prefix.isEmpty()) githubUrl else prefix + githubUrl

    val isDirect: Boolean get() = prefix.isEmpty()
}

/**
 * 内置线路表。
 *
 * 顺序只影响「同速时的优先」，实测延迟由 [MirrorSpeedTest] 现场测量；镜像随时可能下线，
 * 因此任何一条失败都只是少一个候选，不影响整体。
 *
 * 只登记实测支持 Range 的镜像（多线程分段下载依赖 206）：ghproxy.net / ghfast.top /
 * gh-proxy.com / gh.llkk.cc。部分镜像会拒绝 `api.github.com`（403），所以 Release 元信息
 * 单独走 [UpdateRepository.METADATA_SOURCES]，与下载线路分开测速。
 */
object UpdateSources {

    val DOWNLOAD = listOf(
        MirrorSource("direct", "GitHub 直连", ""),
        MirrorSource("ghfast", "ghfast.top", "https://ghfast.top/"),
        MirrorSource("ghproxy", "ghproxy.net", "https://ghproxy.net/"),
        MirrorSource("gh-proxy", "gh-proxy.com", "https://gh-proxy.com/"),
        MirrorSource("llkk", "gh.llkk.cc", "https://gh.llkk.cc/"),
    )

    /** 能代理 `api.github.com` 的线路（实测 gh-proxy.com 可用，其余镜像多回 403）。 */
    val METADATA = DOWNLOAD.filter { it.id == "direct" || it.id == "gh-proxy" }
}

/** 最新发布信息。 */
data class ReleaseInfo(
    val version: String,
    /** 发布页面（浏览器打开用）。 */
    val pageUrl: String,
    val notes: String,
    /** APK 的 GitHub 原始下载地址；没有匹配资产时为 null。 */
    val apkUrl: String?,
    val apkSize: Long,
) {
    /** 与 [local] 逐段比较；远端更大返回 true。 */
    fun isNewerThan(local: String): Boolean = isNewerVersion(version, local)
}

/**
 * 逐段比较版本号：[remote] 比 [local] 新则返回 true。
 *
 * 按 `.`/`-` 切段后取数字段逐位比较，缺位补 0（`2.13` 与 `2.13.0` 视为相同），
 * 非数字段直接丢弃（如 `2.14.0-beta` 里的 beta）。app 与 core 共用这一份，
 * 不要在两端各写一遍。[ReleaseInfo.isNewerThan] 也走它。
 */
fun isNewerVersion(remote: String, local: String): Boolean {
    val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
    val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(r.size, l.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = l.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

/**
 * 读取 GitHub 最新 Release。
 *
 * 元信息请求也做多线路竞争：国内网络下 `api.github.com` 常常直接超时，而镜像代理的
 * JSON 接口足够小，抢最快的那个即可。返回 [ReleaseInfo] 不等于「有新版本」，由调用方比较。
 */
object UpdateRepository {

    private const val API = "https://api.github.com/repos/xibai6668/Android-AI-gateway/releases/latest"
    private const val UA = "ai-gateway-android"

    fun fetchLatest(
        sources: List<MirrorSource> = UpdateSources.METADATA,
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 15_000,
    ): ReleaseInfo {
        if (sources.isEmpty()) throw IllegalStateException("没有可用的更新线路")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(sources.size) { r ->
            Thread(r, "update-meta").apply { isDaemon = true }
        }
        try {
            // 竞争取先到的那份 JSON：国内直连可能一直等到超时，而镜像早就返回了
            val completion = java.util.concurrent.ExecutorCompletionService<ReleaseInfo>(pool)
            sources.forEach { source ->
                completion.submit(java.util.concurrent.Callable {
                    parse(loadText(source, connectTimeoutMs, readTimeoutMs))
                })
            }
            var lastError: Exception? = null
            repeat(sources.size) {
                val future = completion.take()
                try {
                    return future.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    lastError = (e.cause as? Exception) ?: e
                }
            }
            throw lastError ?: IllegalStateException("没有可用的更新线路")
        } finally {
            pool.shutdownNow()
        }
    }

    /** 解析 Release JSON（单独提出以便单测）。 */
    fun parse(body: String): ReleaseInfo {
        val obj = JsonParser.parseString(body).asJsonObject
        val version = obj.str("tag_name").trim().removePrefix("v")
        if (version.isEmpty()) throw IllegalStateException("未找到版本信息")
        val pageUrl = obj.str("html_url").ifEmpty { "https://github.com/xibai6668/Android-AI-gateway/releases" }
        val notes = obj.str("body").trim()
        // 发版产物固定命名为 ai-gateway-<version>-release.apk；没有就退回最大的 .apk 资产
        val assets = obj.arrayOrNull("assets")?.mapNotNull { it.takeIf { a -> a.isJsonObject }?.asJsonObject }.orEmpty()
        val apks = assets.filter { it.str("name").endsWith(".apk", ignoreCase = true) }
        val pick = apks.firstOrNull { it.str("name") == "ai-gateway-$version-release.apk" }
            ?: apks.maxByOrNull { it.str("size").toLongOrNull() ?: 0L }
        return ReleaseInfo(
            version = version,
            pageUrl = pageUrl,
            notes = notes,
            apkUrl = pick?.str("browser_download_url")?.ifEmpty { null },
            apkSize = pick?.str("size")?.toLongOrNull() ?: 0L,
        )
    }

    private fun loadText(source: MirrorSource, connectTimeoutMs: Int, readTimeoutMs: Int): String {
        val connection = if (source.isDirect) {
            (URL(source.map(API)).openConnection() as HttpURLConnection)
        } else {
            // 镜像是国内入口，绕开用户为 GitHub 开的代理
            (URL(source.map(API)).openConnection(Proxy.NO_PROXY) as HttpURLConnection)
        }
        val conn = connection.apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UA)
        }
        try {
            val status = conn.responseCode
            if (status == 404) throw IllegalStateException("暂无发布版本")
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            return HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }
}
