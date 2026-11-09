package dev.aigw.core.gateway

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 代理规则：多套配置 + 按「供应商 / 区域」白名单 + 按地址规则排除，实际按上游域名判定。
 *
 * 这条规则错了会很难查——要么该走代理的没走，要么本机登录回调被绕进代理（不该走却走）。
 */
class ProxySelectorTest {

    private val hosts = mapOf(
        "antigravity" to listOf("googleapis.com", "accounts.google.com"),
        "trae/cn" to listOf("api.trae.cn"),
        "trae/global" to listOf("api.trae.ai"),
        "codebuddy/cn" to listOf("copilot.tencent.com", "codebuddy.cn"),
        "codebuddy/global" to listOf("workbuddy.ai"),
    )

    private fun selector(settings: ProxySettings) =
        GatewayProxySelector(settings = { settings }, routeHosts = { hosts })

    private fun proxied(uri: String, settings: ProxySettings): Boolean =
        selector(settings).select(URI(uri)).first().type() != Proxy.Type.DIRECT

    private fun profile(id: String = "p1", type: ProxyType = ProxyType.HTTP, port: Int = 7890) =
        ProxyProfile(id = id, name = id, type = type, host = "10.0.0.2", port = port)

    private fun settings(
        proxiedRoutes: Set<String> = setOf(ProxySettings.ALL_ROUTES),
        excludedHosts: List<String> = ProxySettings.DEFAULT_EXCLUDED_HOSTS,
        activeProfileId: String = "p1",
        profiles: List<ProxyProfile> = listOf(profile()),
    ) = ProxySettings(
        profiles = profiles,
        activeProfileId = activeProfileId,
        proxiedRoutes = proxiedRoutes,
        excludedHosts = excludedHosts,
    )

    @Test
    fun `没有选配置时全部直连`() {
        val config = settings(activeProfileId = "")
        assertEquals(listOf(Proxy.NO_PROXY), selector(config).select(URI("https://cloudcode-pa.googleapis.com/x")))
    }

    @Test
    fun `未勾选任何路由时全部直连`() {
        val config = settings(proxiedRoutes = emptySet())
        assertFalse(proxied("https://cloudcode-pa.googleapis.com/x", config))
    }

    @Test
    fun `勾选供应商后该域名走代理`() {
        val config = settings(proxiedRoutes = setOf("antigravity"))
        val proxies = selector(config).select(URI("https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent"))
        assertEquals(1, proxies.size)
        assertEquals(Proxy.Type.HTTP, proxies[0].type())
        assertEquals(InetSocketAddress("10.0.0.2", 7890), proxies[0].address())
    }

    @Test
    fun `未勾选的供应商直连`() {
        val config = settings(proxiedRoutes = setOf("antigravity"))
        assertFalse(proxied("https://api.trae.ai/x", config))
    }

    @Test
    fun `区域型供应商可按国内国外分别勾选`() {
        // 只让国际版走代理：国内版必须直连（国内站走代理反而慢或被拒，但这是用户选择的结果）
        val config = settings(proxiedRoutes = setOf("trae/global"))
        assertTrue(proxied("https://api.trae.ai/x", config))
        assertFalse(proxied("https://api.trae.cn/x", config))
    }

    @Test
    fun `WorkBuddy 国内国际站点分开控制`() {
        val config = settings(proxiedRoutes = setOf("codebuddy/global"))
        assertTrue(proxied("https://www.workbuddy.ai/v2/chat", config))
        assertFalse(proxied("https://copilot.tencent.com/v2/chat", config))
        assertFalse(proxied("https://www.codebuddy.cn/v2/chat", config))
    }

    @Test
    fun `子域名也能匹配到路由`() {
        val config = settings(proxiedRoutes = setOf("trae/cn"))
        assertTrue(proxied("https://sub.api.trae.cn/x", config), "子域也应算作该路由")
    }

    @Test
    fun `本机地址永远直连`() {
        val config = settings()
        // 登录回调监听跑在本机，绕进代理会直接拿不到回调
        assertFalse(proxied("http://127.0.0.1:51120/authorize?code=1", config))
        assertFalse(proxied("http://localhost:51121/oauth-callback?code=1", config))
    }

    @Test
    fun `地址为空或端口非法时直连`() {
        val empty = ProxyProfile(id = "p1", name = "空", host = "", port = 7890)
        assertFalse(proxied("https://cloudcode-pa.googleapis.com/x", settings(profiles = listOf(empty))))
        val badPort = ProxyProfile(id = "p1", name = "坏端口", host = "10.0.0.2", port = 0)
        assertFalse(proxied("https://cloudcode-pa.googleapis.com/x", settings(profiles = listOf(badPort))))
    }

    @Test
    fun `SOCKS 类型返回 SOCKS 代理`() {
        val config = settings(profiles = listOf(profile(type = ProxyType.SOCKS, port = 1080)))
        val proxies = selector(config).select(URI("https://cloudcode-pa.googleapis.com/x"))
        assertEquals(Proxy.Type.SOCKS, proxies[0].type())
        assertEquals(InetSocketAddress("10.0.0.2", 1080), proxies[0].address())
    }

    @Test
    fun `多套配置时只有选中的那套生效`() {
        val config = settings(
            profiles = listOf(profile(id = "home", port = 7891), profile(id = "office", port = 1080)),
            activeProfileId = "office",
        )
        val proxies = selector(config).select(URI("https://cloudcode-pa.googleapis.com/x"))
        assertEquals(InetSocketAddress("10.0.0.2", 1080), proxies[0].address())
    }

    @Test
    fun `默认排除地址让内网直连`() {
        val config = settings()
        assertFalse(proxied("http://10.8.9.10:8080/v1", config))
        assertFalse(proxied("http://172.16.3.4/v1", config))
        assertFalse(proxied("http://192.168.1.5/v1", config))
        assertTrue(proxied("https://cloudcode-pa.googleapis.com/x", config))
    }

    @Test
    fun `通配符规则只匹配子域`() {
        val config = settings(excludedHosts = listOf("*.mycompany.com"))
        assertFalse(proxied("https://a.mycompany.com/x", config))
        assertTrue(proxied("https://mycompany.com/x", config))
    }

    @Test
    fun `host_port 规则只匹配同端口`() {
        val config = settings(excludedHosts = listOf("example.com:8443"))
        assertFalse(proxied("https://example.com:8443/x", config))
        // URI 未写端口时按 scheme 补默认端口，443 规则也能命中裸 https 域名
        assertFalse(proxied("https://example.com/x", settings(excludedHosts = listOf("example.com:443"))))
        assertTrue(proxied("https://example.com/x", config))
        assertTrue(proxied("https://example.com:9443/x", config))
    }

    @Test
    fun `自定义 CIDR 网段直连`() {
        val config = settings(excludedHosts = listOf("192.168.10.0/24"))
        assertFalse(proxied("http://192.168.10.7/v1", config))
        assertTrue(proxied("http://192.168.11.7/v1", config))
    }

    @Test
    fun `纯域名规则匹配子域但不误伤长后缀`() {
        val config = settings(excludedHosts = listOf("internal.corp"))
        assertFalse(proxied("https://x.internal.corp/v1", config))
        assertTrue(proxied("https://internal.corp.evil.com/x", config))
    }

    @Test
    fun `旧配置缺字段时回退默认值`() {
        val config = ProxySettings.fromJson("""{"enabled":true,"host":"10.0.0.2","port":7890}""")
        assertEquals(ProxySettings.DEFAULT_EXCLUDED_HOSTS, config.excludedHosts)
        assertEquals(ProxyType.HTTP, config.activeProfile?.type)
    }

    @Test
    fun `旧单套配置迁移成一套并启用`() {
        val config = ProxySettings.fromJson(
            """{"enabled":true,"type":"SOCKS","host":"10.0.0.2","port":1080,"username":"u","password":"p"}""",
        )
        assertEquals(1, config.profiles.size)
        val migrated = config.activeProfile
        assertEquals("SOCKS", migrated?.type?.name)
        assertEquals("10.0.0.2", migrated?.host)
        assertEquals(1080, migrated?.port)
        // 旧语义是「全部走代理」
        assertTrue(config.proxied(ProxySettings.ALL_ROUTES))
    }

    @Test
    fun `旧配置未启用时迁移后不生效`() {
        val config = ProxySettings.fromJson("""{"enabled":false,"host":"10.0.0.2","port":7890}""")
        assertEquals(1, config.profiles.size)
        assertFalse(config.usable)
    }

    @Test
    fun `显式清空的排除地址不会被默认值覆盖`() {
        val raw = ProxySettings(profiles = listOf(profile()), excludedHosts = emptyList()).toJson().toString()
        assertTrue(ProxySettings.fromJson(raw).excludedHosts.isEmpty())
    }

    @Test
    fun `指向已删除配置的 id 视为未启用`() {
        val raw = ProxySettings(profiles = listOf(profile()), activeProfileId = "gone").toJson().toString()
        assertEquals("", ProxySettings.fromJson(raw).activeProfileId)
    }

    @Test
    fun `多套配置与路由勾选可完整往返 JSON`() {
        val original = ProxySettings(
            profiles = listOf(profile(id = "a"), profile(id = "b", type = ProxyType.SOCKS, port = 1080)),
            activeProfileId = "b",
            proxiedRoutes = setOf("antigravity", "trae/global"),
            excludedHosts = listOf("localhost"),
        )
        val restored = ProxySettings.fromJson(original.toJson().toString())
        assertEquals(original.profiles, restored.profiles)
        assertEquals(original.activeProfileId, restored.activeProfileId)
        assertEquals(original.proxiedRoutes, restored.proxiedRoutes)
        assertEquals(original.excludedHosts, restored.excludedHosts)
    }
}
