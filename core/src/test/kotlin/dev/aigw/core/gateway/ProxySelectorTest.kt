package dev.aigw.core.gateway

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 代理规则：按「供应商」开关 + 按地址规则排除，实际按上游域名判定。
 *
 * 这条规则错了会很难查——要么境外供应商连不上（该走没走），要么本机登录回调被绕进代理（不该走却走）。
 */
class ProxySelectorTest {

    private val hosts = mapOf(
        "antigravity" to listOf("googleapis.com", "accounts.google.com"),
        "trae" to listOf("api.trae.cn"),
    )

    private fun selector(settings: ProxySettings) =
        GatewayProxySelector(settings = { settings }, providerHosts = { hosts })

    private fun proxied(uri: String, settings: ProxySettings): Boolean =
        selector(settings).select(URI(uri)).first().type() != Proxy.Type.DIRECT

    @Test
    fun `未启用时全部直连`() {
        val settings = ProxySettings(enabled = false, host = "10.0.0.2", port = 7890)
        assertEquals(listOf(Proxy.NO_PROXY), selector(settings).select(URI("https://cloudcode-pa.googleapis.com/x")))
    }

    @Test
    fun `启用后境外域名走代理`() {
        val settings = ProxySettings(enabled = true, host = "10.0.0.2", port = 7890)
        val proxies = selector(settings).select(URI("https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent"))
        assertEquals(1, proxies.size)
        assertEquals(Proxy.Type.HTTP, proxies[0].type())
        assertEquals(InetSocketAddress("10.0.0.2", 7890), proxies[0].address())
    }

    @Test
    fun `被排除的供应商直连，其它仍走代理`() {
        val settings = ProxySettings(
            enabled = true,
            host = "10.0.0.2",
            port = 7890,
            excludedProviderIds = setOf("trae"),
        )
        assertTrue(!proxied("https://api.trae.cn/trae/api/v2/ug/checkin_credits/status", settings))
        assertTrue(proxied("https://cloudcode-pa.googleapis.com/x", settings))
    }

    @Test
    fun `子域名也能匹配到供应商`() {
        val settings = ProxySettings(
            enabled = true,
            host = "10.0.0.2",
            port = 7890,
            excludedProviderIds = setOf("trae"),
        )
        assertTrue(!proxied("https://sub.api.trae.cn/x", settings), "子域也应算作该供应商")
    }

    @Test
    fun `本机地址永远直连`() {
        val settings = ProxySettings(enabled = true, host = "10.0.0.2", port = 7890)
        // 登录回调监听跑在本机，绕进代理会直接拿不到回调
        assertTrue(!proxied("http://127.0.0.1:51120/authorize?code=1", settings))
        assertTrue(!proxied("http://localhost:51121/oauth-callback?code=1", settings))
    }

    @Test
    fun `地址为空或端口非法时直连`() {
        assertTrue(!proxied("https://cloudcode-pa.googleapis.com/x", ProxySettings(enabled = true, host = "", port = 7890)))
        assertTrue(!proxied("https://cloudcode-pa.googleapis.com/x", ProxySettings(enabled = true, host = "10.0.0.2", port = 0)))
    }

    @Test
    fun `SOCKS 类型返回 SOCKS 代理`() {
        val settings = ProxySettings(enabled = true, type = ProxyType.SOCKS, host = "10.0.0.2", port = 1080)
        val proxies = selector(settings).select(URI("https://cloudcode-pa.googleapis.com/x"))
        assertEquals(Proxy.Type.SOCKS, proxies[0].type())
        assertEquals(InetSocketAddress("10.0.0.2", 1080), proxies[0].address())
    }

    @Test
    fun `默认排除地址让内网直连`() {
        val settings = ProxySettings(enabled = true, host = "10.0.0.2", port = 7890)
        assertTrue(!proxied("http://10.8.9.10:8080/v1", settings))
        assertTrue(!proxied("http://172.16.3.4/v1", settings))
        assertTrue(!proxied("http://192.168.1.5/v1", settings))
        assertTrue(proxied("https://cloudcode-pa.googleapis.com/x", settings))
    }

    @Test
    fun `通配符规则只匹配子域`() {
        val settings = ProxySettings(
            enabled = true,
            host = "10.0.0.2",
            port = 7890,
            excludedHosts = listOf("*.mycompany.com"),
        )
        assertTrue(!proxied("https://a.mycompany.com/x", settings))
        assertTrue(proxied("https://mycompany.com/x", settings))
    }

    @Test
    fun `host_port 规则只匹配同端口`() {
        val settings = ProxySettings(
            enabled = true,
            host = "10.0.0.2",
            port = 7890,
            excludedHosts = listOf("example.com:8443"),
        )
        assertTrue(!proxied("https://example.com:8443/x", settings))
        // URI 未写端口时按 scheme 补默认端口，443 规则也能命中裸 https 域名
        assertTrue(!proxied("https://example.com/x", ProxySettings(
            enabled = true, host = "10.0.0.2", port = 7890, excludedHosts = listOf("example.com:443"),
        )))
        assertTrue(proxied("https://example.com/x", settings))
        assertTrue(proxied("https://example.com:9443/x", settings))
    }

    @Test
    fun `自定义 CIDR 网段直连`() {
        val settings = ProxySettings(
            enabled = true,
            host = "10.0.0.2",
            port = 7890,
            excludedHosts = listOf("192.168.10.0/24"),
        )
        assertTrue(!proxied("http://192.168.10.7/v1", settings))
        assertTrue(proxied("http://192.168.11.7/v1", settings))
    }

    @Test
    fun `纯域名规则匹配子域但不误伤长后缀`() {
        val settings = ProxySettings(
            enabled = true,
            host = "10.0.0.2",
            port = 7890,
            excludedHosts = listOf("internal.corp"),
        )
        assertTrue(!proxied("https://x.internal.corp/v1", settings))
        assertTrue(proxied("https://internal.corp.evil.com/x", settings))
    }

    @Test
    fun `旧配置缺字段时回退默认值`() {
        val settings = ProxySettings.fromJson("""{"enabled":true,"host":"10.0.0.2","port":7890}""")
        assertEquals(ProxyType.HTTP, settings.type)
        assertEquals(ProxySettings.DEFAULT_EXCLUDED_HOSTS, settings.excludedHosts)
    }

    @Test
    fun `显式清空的排除地址不会被默认值覆盖`() {
        val raw = ProxySettings(enabled = true, excludedHosts = emptyList()).toJson().toString()
        assertTrue(ProxySettings.fromJson(raw).excludedHosts.isEmpty())
    }
}
