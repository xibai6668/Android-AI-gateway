package dev.aigw.core.provider.chatgpt

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Codex CLI 的客户端版本号。
 *
 * 上游按请求里的 `client_version` 与 `User-Agent` 决定给哪些模型、认不认这个客户端：
 * 版本写死会随着上游推进逐渐失效（表现为「模型列表变少」或「请求被判非法」）。
 * 这里从 npm registry 取 `@openai/codex` 的最新版本并缓存，取不到则退回 [FALLBACK_VERSION]。
 *
 * 只做一次轻量 GET 且结果缓存 [TTL_MILLIS]，不会给每个请求增加往返；
 * 任何失败都只退回兜底值，不因为版本探测而让对话失败。
 */
internal object ChatGPTVersion {

    /** npm registry 的包元数据端点，`dist-tags.latest` 就是官方最新版本。 */
    private const val REGISTRY_URL = "https://registry.npmjs.org/@openai/codex/latest"

    /** 兜底版本：必须与本地实测可用的版本一致，避免探测失败时直接不可用。 */
    const val FALLBACK_VERSION = "0.163.0"

    private const val TTL_MILLIS = 6L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 8_000

    private val cachedVersion = AtomicReference(FALLBACK_VERSION to 0L)

    /** 当前生效的版本号（缓存过期才回源）。 */
    fun current(nowMillis: Long = System.currentTimeMillis()): String {
        val (version, fetchedAt) = cachedVersion.get()
        if (nowMillis - fetchedAt < TTL_MILLIS) return version
        val fetched = fetchLatest() ?: FALLBACK_VERSION
        cachedVersion.set(fetched to nowMillis)
        return fetched
    }

    /** 丢弃缓存，下次 [current] 重新回源。 */
    fun invalidate() {
        cachedVersion.set(FALLBACK_VERSION to 0L)
    }

    /** 从 npm registry 的 JSON 里取 `version`；解析不出来返回 null。 */
    internal fun parseRegistry(text: String): String? {
        val obj = runCatching {
            com.google.gson.JsonParser.parseString(text).asJsonObject
        }.getOrNull() ?: return null
        val version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asString
        // 只接受看起来是版本号的串（`0.171.0` 这种），避免把错误页当成版本
        return version?.takeIf { VERSION_PATTERN.matches(it) }
    }

    private val VERSION_PATTERN = Regex("""^\d+\.\d+\.\d+$""")

    private fun fetchLatest(): String? = try {
        val conn = (URL(REGISTRY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) {
                null
            } else {
                parseRegistry(conn.inputStream.bufferedReader().readText())
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
