package dev.aigw.core.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 代理设置。
 *
 * 境外供应商（Antigravity 等）通常需要代理才能访问，所以支持「总开关 + 按供应商排除」：
 * 打开总开关后默认所有上游都走代理，个别不需要的可以单独关掉。
 */
data class ProxySettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = "",
    /** 不走代理的供应商 id（默认空 = 全部走代理）。 */
    val excludedProviderIds: Set<String> = emptySet(),
) {
    val usable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("enabled", enabled)
        addProperty("host", host)
        addProperty("port", port)
        addProperty("username", username)
        addProperty("password", password)
        add("excludedProviderIds", com.google.gson.JsonArray().apply {
            excludedProviderIds.forEach { add(it) }
        })
    }

    companion object {
        const val STORE_KEY = "settings/proxy.json"

        fun fromJson(raw: String?): ProxySettings {
            if (raw.isNullOrEmpty()) return ProxySettings()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ProxySettings()
            val excluded = obj.arrayOrNull("excludedProviderIds")
                ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                ?.toSet()
                .orEmpty()
            return ProxySettings(
                enabled = obj.get("enabled")?.asBoolean ?: false,
                host = obj.get("host")?.asString.orEmpty().trim(),
                port = obj.get("port")?.asInt ?: 8080,
                username = obj.get("username")?.asString.orEmpty(),
                password = obj.get("password")?.asString.orEmpty(),
                excludedProviderIds = excluded,
            )
        }
    }
}

/**
 * 按目标域名决定是否走代理。
 *
 * 之所以用全局 [ProxySelector] 而不是给每个请求单独传 `Proxy`：各 provider 的 HTTP 客户端
 * 都是自己 `new HttpURLConnection` 的，逐个改造成本高且容易漏；而「按供应商」本质上就是
 * 「按上游域名」判断，[ProxySelector] 正好能表达这条规则。
 */
internal class GatewayProxySelector(
    private val settings: () -> ProxySettings,
    private val providerHosts: () -> Map<String, List<String>>,
) : ProxySelector() {

    override fun select(uri: URI): List<Proxy> {
        val config = settings()
        if (!config.usable) return NO_PROXY
        val host = uri.host?.lowercase() ?: return NO_PROXY
        // 本机地址永远直连（登录回调监听、本地网关）
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") return NO_PROXY
        // 国内上游永远直连：走了代理反而慢或被拒（供应商区域、CDN 调度都是按来源 IP 的）
        if (isDomestic(host)) return NO_PROXY
        if (isExcluded(host, config)) return NO_PROXY
        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(config.host, config.port)))
    }

    private fun isDomestic(host: String): Boolean =
        DOMESTIC_SUFFIXES.any { host == it || host.endsWith(".$it") }

    private fun isExcluded(host: String, config: ProxySettings): Boolean {
        if (config.excludedProviderIds.isEmpty()) return false
        for ((providerId, suffixes) in providerHosts()) {
            if (providerId !in config.excludedProviderIds) continue
            if (suffixes.any { host == it || host.endsWith(".$it") }) return true
        }
        return false
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    private companion object {
        val NO_PROXY = listOf(Proxy.NO_PROXY)

        /** 国内上游域名后缀：命中即直连，不受代理开关与供应商排除项影响。 */
        val DOMESTIC_SUFFIXES = setOf(
            // WorkBuddy 国内站（腾讯）
            "copilot.tencent.com",
            "codebuddy.cn",
            "tencent.com",
            "qq.com",
            // Trae 国内
            "api.trae.com.cn",
            "api.trae.cn",
            "trae-api-cn.mchost.guru",
            // Loomy（讯飞）
            "xfinfr.com",
            "xunfei.cn",
        )
    }
}

/** 代理需要用户名密码时给 HttpURLConnection 提供凭据（仅对代理主机生效）。 */
internal class GatewayProxyAuthenticator(
    private val settings: () -> ProxySettings,
) : Authenticator() {

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val config = settings()
        if (!config.usable || config.username.isEmpty()) return null
        if (!requestingHost.equals(config.host, ignoreCase = true)) return null
        return PasswordAuthentication(config.username, config.password.toCharArray())
    }
}
