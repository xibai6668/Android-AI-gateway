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

/** 代理协议类型。 */
enum class ProxyType(val label: String) {
    HTTP("HTTP"),
    SOCKS("SOCKS5");

    fun toJavaType(): Proxy.Type = when (this) {
        HTTP -> Proxy.Type.HTTP
        SOCKS -> Proxy.Type.SOCKS
    }
}

/**
 * 代理设置。
 *
 * 境外供应商（Antigravity 等）通常需要代理才能访问，所以支持「总开关 + 按供应商排除 + 按地址排除」：
 * 打开总开关后默认所有上游都走代理，个别供应商或地址可以单独直连。
 */
data class ProxySettings(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = "",
    /** 不走代理的供应商 id（默认空 = 全部走代理）。 */
    val excludedProviderIds: Set<String> = emptySet(),
    /** 不走代理的地址规则：纯域名（含子域）、* 通配、host:port、IPv4 CIDR。 */
    val excludedHosts: List<String> = DEFAULT_EXCLUDED_HOSTS,
) {
    val usable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("enabled", enabled)
        addProperty("type", type.name)
        addProperty("host", host)
        addProperty("port", port)
        addProperty("username", username)
        addProperty("password", password)
        add("excludedProviderIds", com.google.gson.JsonArray().apply {
            excludedProviderIds.forEach { add(it) }
        })
        add("excludedHosts", com.google.gson.JsonArray().apply {
            excludedHosts.forEach { add(it) }
        })
    }

    companion object {
        const val STORE_KEY = "settings/proxy.json"

        /** 与系统级代理工具一致的兜底：本机与内网段直连，防止内网上游被误送进代理。 */
        val DEFAULT_EXCLUDED_HOSTS = listOf(
            "localhost", "127.0.0.1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        )

        fun fromJson(raw: String?): ProxySettings {
            if (raw.isNullOrEmpty()) return ProxySettings()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ProxySettings()
            val excluded = obj.arrayOrNull("excludedProviderIds")
                ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                ?.toSet()
                .orEmpty()
            // 字段缺失（旧配置）才回退默认值；显式存过空数组则尊重用户清空的选择
            val excludedHosts = obj.get("excludedHosts")?.takeIf { it.isJsonArray }
                ?.let { array ->
                    array.asJsonArray.mapNotNull { runCatching { it.asString }.getOrNull() }
                } ?: DEFAULT_EXCLUDED_HOSTS
            return ProxySettings(
                enabled = obj.get("enabled")?.asBoolean ?: false,
                type = obj.get("type")?.asString
                    ?.let { name -> runCatching { ProxyType.valueOf(name) }.getOrNull() }
                    ?: ProxyType.HTTP,
                host = obj.get("host")?.asString.orEmpty().trim(),
                port = obj.get("port")?.asInt ?: 8080,
                username = obj.get("username")?.asString.orEmpty(),
                password = obj.get("password")?.asString.orEmpty(),
                excludedProviderIds = excluded,
                excludedHosts = excludedHosts,
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

    /** 通配规则编译缓存：excludedHosts 内容变了才重编（每次连接现编 Regex 开销大）。 */
    @Volatile
    private var ruleCacheSource: List<String>? = null
    @Volatile
    private var ruleCache: List<CompiledRule> = emptyList()

    override fun select(uri: URI): List<Proxy> {
        val config = settings()
        if (!config.usable) return NO_PROXY
        val host = uri.host?.lowercase() ?: return NO_PROXY
        // 本机地址永远直连（登录回调监听、本地网关）
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") return NO_PROXY
        // 国内上游永远直连：走了代理反而慢或被拒（供应商区域、CDN 调度都是按来源 IP 的）
        if (isDomestic(host)) return NO_PROXY
        if (isExcluded(host, config)) return NO_PROXY
        if (isExcludedByRule(host, uri, config.excludedHosts)) return NO_PROXY
        return listOf(Proxy(config.type.toJavaType(), InetSocketAddress(config.host, config.port)))
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

    private fun isExcludedByRule(host: String, uri: URI, rules: List<String>): Boolean {
        for (rule in compiledRules(rules)) {
            if (rule.matches(host, uri)) return true
        }
        return false
    }

    /** 按 excludedHosts 内容缓存预编译规则：内容不变就复用，内容变了才重建。 */
    private fun compiledRules(rules: List<String>): List<CompiledRule> {
        val cachedSource = ruleCacheSource
        if (cachedSource == rules) return ruleCache
        val compiled = rules.mapNotNull { compileRule(it) }
        ruleCacheSource = rules
        ruleCache = compiled
        return compiled
    }

    /** 一条已编译的排除规则（普通域名或通配正则）。 */
    private open class CompiledRule(
        val hostRule: String,
        val port: Int?,
        val regex: Regex?,
    ) {
        open fun matches(host: String, uri: URI): Boolean {
            if (port != null && port != effectivePort(uri)) return false
            val re = regex
            return if (re != null) re.matches(host) else host == hostRule || host.endsWith(".$hostRule")
        }

        private companion object {
            fun effectivePort(uri: URI): Int =
                uri.port.takeIf { it != -1 } ?: if (uri.scheme.equals("http", ignoreCase = true)) 80 else 443
        }
    }

    /** IPv4 CIDR 规则（如 192.168.10.0/24）；非 IPv4 字面量或规则非法时一律不匹配。 */
    private inner class CidrRule(private val rule: String) : CompiledRule(hostRule = "", port = null, regex = null) {
        override fun matches(host: String, uri: URI): Boolean = matchesCidr(host, rule)
    }

    private fun compileRule(raw: String): CompiledRule? {
        val rule = raw.trim().lowercase()
        if (rule.isEmpty()) return null
        // CIDR 规则不适合缓存成正则，单独一类（数量少、无正则编译开销）
        if (rule.contains('/')) return CidrRule(rule)
        val portSuffix = rule.substringAfterLast(':', "")
        val rulePort = portSuffix.toIntOrNull()
        val hostRule = if (rulePort != null) rule.substringBeforeLast(':') else rule
        val regex = if (hostRule.contains('*')) {
            Regex(hostRule.split('*').joinToString(".*") { Regex.escape(it) })
        } else {
            null
        }
        return CompiledRule(hostRule, rulePort, regex)
    }

    /** 仅支持 IPv4 CIDR；host 非 IPv4 字面量或规则非法时一律不匹配。 */
    private fun matchesCidr(host: String, rule: String): Boolean {
        val slash = rule.indexOf('/')
        if (slash <= 0) return false
        val network = ipv4ToInt(rule.substring(0, slash)) ?: return false
        val prefix = rule.substring(slash + 1).toIntOrNull() ?: return false
        if (prefix !in 0..32) return false
        val ip = ipv4ToInt(host) ?: return false
        if (prefix == 0) return true
        val mask = -(1 shl (32 - prefix))
        return (ip and mask) == (network and mask)
    }

    private fun ipv4ToInt(value: String): Int? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255 || (part.length > 1 && part.startsWith('0'))) return null
            result = (result shl 8) or octet
        }
        return result
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
            // 小浣熊（商汤）
            "xiaohuanxiong.com",
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
