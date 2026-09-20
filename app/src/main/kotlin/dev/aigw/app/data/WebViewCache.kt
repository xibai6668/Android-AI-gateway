package dev.aigw.app.data

import android.content.Context
import java.io.File

/**
 * 登录用 WebView 的缓存统计与清理。
 *
 * 登录页只用来完成一次 OAuth 跳转，留下的 HTTP 缓存与渲染数据纯属残留，
 * 但它算在应用存储占用里，所以「数据管理」里要能看到并清掉。
 */
object WebViewCache {

    /** 缓存占用字节数（HTTP 缓存目录 + 各 WebView 数据目录）。 */
    fun sizeBytes(context: Context): Long {
        val cacheRoot = File(context.applicationContext.filesDir.parentFile, "app_webview")
        val httpCache = context.applicationContext.cacheDir
        return directorySize(cacheRoot) + directorySize(httpCache)
    }

    /**
     * 清理 WebView 缓存，返回清理前的占用字节数。
     *
     * **刻意只做文件系统删除，不调 `WebStorage.getInstance()` / `CookieManager.getInstance()`**：
     * 那两个 API 会触发当前进程加载整个 Chromium 渲染引擎（实测 native 吐 40MB 量级），
     * 而本方法是在**主进程**调的——调一次就等于把 WebView 的 native 开销永久留在主进程，
     * 使「登录页隔离到 :login 进程」的优化失效。
     *
     * 代价：Cookie 文件不会被删（只清 Cache/GPUCache 子目录）。
     * 登录页的 Cookie 属一次性授权中间态，保留无害；真需彻底清，卸载重装即可。
     */
    fun clear(context: Context): Long {
        val before = sizeBytes(context)
        val app = context.applicationContext
        runCatching {
            app.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        }
        runCatching {
            val webViewData = File(app.filesDir.parentFile, "app_webview")
            if (webViewData.isDirectory) {
                webViewData.listFiles()?.forEach { entry ->
                    if (entry.name.contains("Cache", ignoreCase = true)) {
                        entry.deleteRecursively()
                    }
                }
            }
        }
        return before
    }

    private fun directorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
