package dev.aigw.core.gateway

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 代理规则：按「供应商」开关，实际按上游域名判定。
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
}
