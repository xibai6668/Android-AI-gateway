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
 * 一套代理服务器配置。
 *
 * 多套配置是为了「多线路混合测试」：家里 / 公司 / 机场各存一套，切换只改
 * [ProxySettings.activeProfileId]，不必反复重填地址与密码。
 */
data class ProxyProfile(
    val id: String,
    val name: String,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = "",
) {
    val usable: Boolean get() = host.isNotBlank() && port in 1..65535

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("type", type.name)
        addProperty("host", host)
        addProperty("port", port)
        addProperty("username", username)
        addProperty("password", password)
    }

    companion object {
        fun fromJson(obj: JsonObject): ProxyProfile? {
            val id = obj.get("id")?.asString?.trim().orEmpty()
            if (id.isEmpty()) return null
            return ProxyProfile(
                id = id,
                name = obj.get("name")?.asString?.trim().orEmpty().ifEmpty { id },
                type = obj.get("type")?.asString
                    ?.let { name -> runCatching { ProxyType.valueOf(name) }.getOrNull() }
                    ?: ProxyType.HTTP,
                host = obj.get("host")?.asString.orEmpty().trim(),
                port = obj.get("port")?.asInt ?: 8080,
                username = obj.get("username")?.asString.orEmpty(),
                password = obj.get("password")?.asString.orEmpty(),
            )
        }
    }
}

/**
 * 代理设置：多套服务器配置 + 按「供应商 / 区域」粒度决定谁走代理。
 *
 * 是否走代理完全由 [proxiedRoutes] 决定，不再有「某类上游强制直连」的隐藏规则——
 * 用户勾了就走，没勾就直连。路由键的口径见 [routeKey]：
 * - 普通供应商为 `providerId`；
 * - 区域型供应商为 `providerId/region`（如 `trae/cn`、`trae/global`），
 *   这样同一条供应商的国内站与国际站可以分别决定是否走代理。
 */
data class ProxySettings(
    val profiles: List<ProxyProfile> = emptyList(),
    /** 当前使用的配置 id；空串表示不使用代理（全部直连）。 */
    val activeProfileId: String = "",
    /** 走代理的路由键集合；[ALL_ROUTES] 表示全部上游走代理。 */
    val proxiedRoutes: Set<String> = emptySet(),
    /** 不走代理的地址规则：纯域名（含子域）、* 通配、host:port、IPv4 CIDR。 */
    val excludedHosts: List<String> = DEFAULT_EXCLUDED_HOSTS,
) {
    val activeProfile: ProxyProfile? get() = profiles.firstOrNull { it.id == activeProfileId }

    /** 代理是否可用：选了配置且该配置填了合法地址。 */
    val usable: Boolean get() = activeProfile?.usable == true

    /** 某个路由键是否走代理（`*` 视为全部命中）。 */
    fun proxied(routeKey: String): Boolean = ALL_ROUTES in proxiedRoutes || routeKey in proxiedRoutes

    /** 新增或替换一套配置（按 id 匹配）。 */
    fun upsertProfile(profile: ProxyProfile): ProxySettings {
        val existing = profiles.any { it.id == profile.id }
        val next = if (existing) {
            profiles.map { if (it.id == profile.id) profile else it }
        } else {
            profiles + profile
        }
        // 第一套配置自动成为当前使用的配置，避免「加了配置却没生效」
        return copy(profiles = next, activeProfileId = activeProfileId.ifEmpty { profile.id })
    }

    /** 删除一套配置；删的是当前使用的配置时回落到第一套，没有剩余则停止使用代理。 */
    fun removeProfile(id: String): ProxySettings {
        val next = profiles.filterNot { it.id == id }
        return copy(
            profiles = next,
            activeProfileId = if (activeProfileId == id) next.firstOrNull()?.id.orEmpty() else activeProfileId,
        )
    }

    fun toJson(): JsonObject = JsonObject().apply {
        add("profiles", com.google.gson.JsonArray().apply {
            profiles.forEach { add(it.toJson()) }
        })
        addProperty("activeProfileId", activeProfileId)
        add("proxiedRoutes", com.google.gson.JsonArray().apply {
            proxiedRoutes.forEach { add(it) }
        })
        add("excludedHosts", com.google.gson.JsonArray().apply {
            excludedHosts.forEach { add(it) }
        })
    }

    companion object {
        const val STORE_KEY = "settings/proxy.json"

        /** [proxiedRoutes] 里的通配键：表示全部上游走代理。 */
        const val ALL_ROUTES = "*"

        /** 历史配置迁移出来的默认配置 id。 */
        const val LEGACY_PROFILE_ID = "default"

        /** 与系统级代理工具一致的兜底：本机与内网段直连，防止内网上游被误送进代理。 */
        val DEFAULT_EXCLUDED_HOSTS = listOf(
            "localhost", "127.0.0.1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        )

        fun routeKey(providerId: String, region: String?): String =
            if (region.isNullOrBlank()) providerId else "$providerId/$region"


        fun fromJson(raw: String?): ProxySettings {
            if (raw.isNullOrEmpty()) return ProxySettings()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ProxySettings()
            // 字段缺失（旧配置）才回退默认值；显式存过空数组则尊重用户清空的选择
            val excludedHosts = obj.get("excludedHosts")?.takeIf { it.isJsonArray }
                ?.let { array ->
                    array.asJsonArray.mapNotNull { runCatching { it.asString }.getOrNull() }
                } ?: DEFAULT_EXCLUDED_HOSTS
            val profiles = obj.get("profiles")?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { runCatching { ProxyProfile.fromJson(it.asJsonObject) }.getOrNull() }
                .orEmpty()
            if (profiles.isNotEmpty()) {
                val active = obj.get("activeProfileId")?.asString.orEmpty()
                val routes = obj.arrayOrNull("proxiedRoutes")
                    ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                    ?.toSet()
                    .orEmpty()
                return ProxySettings(
                    profiles = profiles,
                    // 指向已删除配置的悬空 id 视为未启用，避免「看着选了配置却不生效」
                    activeProfileId = active.takeIf { id -> profiles.any { it.id == id } }.orEmpty(),
                    proxiedRoutes = routes,
                    excludedHosts = excludedHosts,
                )
            }
            // 旧格式（单套 enabled/host/port/type）：迁移成一套配置；旧语义是「全部走代理 + 按供应商排除」，
            // 排除项转成具体路由白名单需要域名表，由 GatewayEngine 在加载时补齐（见 loadProxySettings）。
            val host = obj.get("host")?.asString.orEmpty().trim()
            val legacyEnabled = obj.get("enabled")?.asBoolean == true
            if (host.isEmpty()) return ProxySettings(excludedHosts = excludedHosts)
            val profile = ProxyProfile(
                id = LEGACY_PROFILE_ID,
                name = "默认配置",
                type = obj.get("type")?.asString
                    ?.let { name -> runCatching { ProxyType.valueOf(name) }.getOrNull() }
                    ?: ProxyType.HTTP,
                host = host,
                port = obj.get("port")?.asInt ?: 8080,
                username = obj.get("username")?.asString.orEmpty(),
                password = obj.get("password")?.asString.orEmpty(),
            )
            return ProxySettings(
                profiles = listOf(profile),
                activeProfileId = if (legacyEnabled) profile.id else "",
                proxiedRoutes = if (legacyEnabled) setOf(ALL_ROUTES) else emptySet(),
                excludedHosts = excludedHosts,
            )
        }

        /**
         * 旧格式储存的「不走代理的供应商 id」；新格式返回空集。
         *
         * 迁移时不能丢掉它：旧语义是「全部走代理，列出的供应商直连」，直接当成「全部走代理」
         * 会把用户特意直连的上游送进代理。
         */
        fun legacyExcludedProviderIds(raw: String?): Set<String> {
            if (raw.isNullOrEmpty() || raw.contains("\"profiles\"")) return emptySet()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return emptySet()
            return obj.arrayOrNull("excludedProviderIds")
                ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                ?.toSet()
                .orEmpty()
        }
    }
}

/**
 * 按目标域名决定是否走代理。
 *
 * 之所以用全局 [ProxySelector] 而不是给每个请求单独传 `Proxy`：各 provider 的 HTTP 客户端
 * 都是自己 `new HttpURLConnection` 的，逐个改造成本高且容易漏；而「按供应商 / 区域」本质上就是
 * 「按上游域名」判断，[ProxySelector] 正好能表达这条规则。
 */
internal class GatewayProxySelector(
    private val settings: () -> ProxySettings,
    private val routeHosts: () -> Map<String, List<String>>,
) : ProxySelector() {

    /** 通配规则编译缓存：excludedHosts 内容变了才重编（每次连接现编 Regex 开销大）。 */
    @Volatile
    private var ruleCacheSource: List<String>? = null
    @Volatile
    private var ruleCache: List<CompiledRule> = emptyList()

    override fun select(uri: URI): List<Proxy> {
        val config = settings()
        val profile = config.activeProfile ?: return NO_PROXY
        if (!profile.usable) return NO_PROXY
        val host = uri.host?.lowercase() ?: return NO_PROXY
        // 本机地址永远直连（登录回调监听、本地网关）
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") return NO_PROXY
        if (isExcludedByRule(host, uri, config.excludedHosts)) return NO_PROXY
        if (!shouldProxy(host, config)) return NO_PROXY
        return listOf(Proxy(profile.type.toJavaType(), InetSocketAddress(profile.host, profile.port)))
    }

    /** 目标域名归属的路由键里只要有一个被勾选就走代理；`*` 命中全部。 */
    private fun shouldProxy(host: String, config: ProxySettings): Boolean {
        if (config.proxiedRoutes.isEmpty()) return false
        if (ProxySettings.ALL_ROUTES in config.proxiedRoutes) return true
        for ((routeKey, suffixes) in routeHosts()) {
            if (routeKey !in config.proxiedRoutes) continue
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
    }
}

/** 代理需要用户名密码时给 HttpURLConnection 提供凭据（仅对代理主机生效）。 */
internal class GatewayProxyAuthenticator(
    private val settings: () -> ProxySettings,
) : Authenticator() {

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val profile = settings().activeProfile ?: return null
        if (!profile.usable || profile.username.isEmpty()) return null
        if (!requestingHost.equals(profile.host, ignoreCase = true)) return null
        return PasswordAuthentication(profile.username, profile.password.toCharArray())
    }
}
