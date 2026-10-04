package dev.aigw.core.provider.custom

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.provider.ProviderAccount
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 上游 200 但内容为空/不合规时的行为回归。
 *
 * 背景：上游（或中间代理）返回 200 + 空 body 时，旧实现当成「成功的空回复」透传，
 * 客户端只看到「输出完成却什么都没有」，调用记录还是成功——完全无法定位。
 * CLIProxyAPI 对此有明确防护（空响应必须保持可检测为失败），这里锁住同样的行为。
 */
class CustomProviderEmptyResponseTest {

    private fun withUpstream(
        status: Int = 200,
        contentType: String = "application/json",
        body: String = "",
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/v1")
        } finally {
            server.stop(0)
        }
    }

    private fun provider(baseUrl: String) = CustomProvider(
        CustomProviderConfig(
            key = "t",
            name = "测试",
            baseUrl = baseUrl,
            apiKeys = listOf("sk-1"),
        ),
    )

    private fun account() = ProviderAccount("custom:t", "u1", "测试", CustomProvider.secretOf("sk-1"))

    @Test
    fun `200 空 body 视为失败而不是空回复`() {
        withUpstream(body = "") { baseUrl ->
            val call = provider(baseUrl).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.aggregated, "空 body 不能当成功回复")
            val failure = assertNotNull(call.failure, "必须报失败")
            assertTrue(failure.message.contains("空响应"), "错误信息应说明空响应，实际：${failure.message}")
            assertTrue(failure.message.contains("HTTP 200"), "错误信息应带上游状态码，实际：${failure.message}")
        }
    }

    @Test
    fun `200 空 body 且客户端要流式时同样报失败`() {
        withUpstream(body = "") { baseUrl ->
            val call = provider(baseUrl).openChat(
                account(),
                """{"model":"m","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.stream, "空 body 不能包成空内容的 SSE 流")
            assertNotNull(call.failure, "必须报失败")
        }
    }

    @Test
    fun `200 回 HTML 时不当回复透传`() {
        withUpstream(contentType = "text/html", body = "<html>Proxy auth required</html>") { baseUrl ->
            val call = provider(baseUrl).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.aggregated)
            val failure = assertNotNull(call.failure)
            assertTrue(failure.message.contains("不符合 OpenAI 协议"), "实际：${failure.message}")
        }
    }

    @Test
    fun `SSE 流里一条内容都没有时视为失败`() {
        withUpstream(contentType = "text/event-stream", body = "data: [DONE]\n\n") { baseUrl ->
            val call = provider(baseUrl).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.aggregated, "零内容的流聚合不能当成功")
            assertNotNull(call.failure)
        }
    }

    @Test
    fun `正常 completion 仍然透传`() {
        val body = """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}]}"""
        withUpstream(body = body) { baseUrl ->
            val call = provider(baseUrl).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.failure)
            assertEquals(body, call.aggregated)
        }
    }

    @Test
    fun `工具调用回复不算空回复`() {
        val body = """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        withUpstream(body = body) { baseUrl ->
            val call = provider(baseUrl).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.failure, "只有工具调用也是有效回复")
            assertEquals(body, call.aggregated)
        }
    }
}
