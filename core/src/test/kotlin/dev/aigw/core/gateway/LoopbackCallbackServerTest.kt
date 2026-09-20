package dev.aigw.core.gateway

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 登录回调服务：浏览器完成登录后会被重定向到本机端口，由它接住回调。
 *
 * 这是「不用 WebView」方案的关键一环——登录结果不再靠 WebView 拦截 URL 传递，
 * 而是靠真实的本地 HTTP 回调，所以必须能收、能解析 query、能把结果页面返回给浏览器。
 */
class LoopbackCallbackServerTest {

    private fun get(port: Int, path: String): Pair<Int, String> {
        val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        return try {
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            status to body
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `收到回调时把完整链接交给处理器并返回结果页`() {
        val received = AtomicReference("")
        val server = LoopbackCallbackServer(0, "/authorize") { url ->
            received.set(url)
            "<html><body>登录成功</body></html>"
        }
        server.startServer()
        try {
            val (status, body) = get(server.listeningPort, "/authorize?code=abc&state=xyz")
            assertEquals(200, status)
            assertTrue(body.contains("登录成功"), body)
            val url = received.get()
            assertTrue(url.startsWith("http://127.0.0.1:${server.listeningPort}/authorize?"), url)
            assertTrue(url.contains("code=abc"), "回调链接必须带 query：$url")
            assertTrue(url.contains("state=xyz"), url)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `其它路径不会被当成回调`() {
        var called = false
        val server = LoopbackCallbackServer(0, "/authorize") {
            called = true
            "ok"
        }
        server.startServer()
        try {
            val (status, _) = get(server.listeningPort, "/somewhere-else")
            assertEquals(404, status)
            assertTrue(!called, "非回调路径不该触发处理器")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `没有 query 时也能收到回调`() {
        val received = AtomicReference("")
        val server = LoopbackCallbackServer(0, "/oauth-callback") { url ->
            received.set(url)
            "ok"
        }
        server.startServer()
        try {
            val (status, _) = get(server.listeningPort, "/oauth-callback")
            assertEquals(200, status)
            assertEquals("http://127.0.0.1:${server.listeningPort}/oauth-callback", received.get())
        } finally {
            server.stop()
        }
    }
}
