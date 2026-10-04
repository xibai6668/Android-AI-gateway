package dev.aigw.core.provider.trae

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trae 上游 200 但没回 SSE（回 JSON 错误体）时的行为回归。
 *
 * 签名/凭证失效时 Trae 上游常直接回 200 + JSON 错误体。旧实现把非 SSE 字节流
 * 交给 SSE 解析器（SoloSseParser 只认 event:/data: 行），所有行被静默忽略，
 * 最终表现为「调用成功、客户端零输出」。
 */
class TraeNonSseResponseTest {

    private fun withUpstream(
        contentType: String,
        body: String,
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun chatClient(baseUrl: String) = TraeChatClient(
        version = TraeVersion(),
        host = baseUrl,
    )

    private val account = TraeAccount(
        uid = "u1",
        accessToken = "token",
        refreshToken = "refresh",
        expiresAt = Long.MAX_VALUE,
        nickname = "测试",
        enterpriseId = "",
        machineId = "m",
        deviceId = "d",
    )

    private val chatBody = """{"model":"m","messages":[{"role":"user","content":"hi"}]}"""

    @Test
    fun `200 回 JSON 错误体时不交给 SSE 解析器`() {
        withUpstream("application/json", """{"code":1001,"message":"user session expired"}""") { baseUrl ->
            val call = chatClient(baseUrl).openStream(account, chatBody)
            assertNull(call.stream, "非 SSE 字节流不能交给流式转发")
            assertTrue(
                call.errorBody.contains("user session expired"),
                "errorBody 应带上游错误内容，实际：${call.errorBody}",
            )
        }
    }

    @Test
    fun `200 空 body 时报失败而不是静默成功`() {
        withUpstream("application/json", "") { baseUrl ->
            val call = chatClient(baseUrl).openStream(account, chatBody)
            assertNull(call.stream, "空响应不能交给流式转发")
            assertTrue(
                call.errorBody.contains("空响应"),
                "errorBody 应说明空响应，实际：${call.errorBody}",
            )
        }
    }

    @Test
    fun `正常 SSE 上游仍然返回流`() {
        withUpstream("text/event-stream", "event: done\ndata: {}\n\n") { baseUrl ->
            val call = chatClient(baseUrl).openStream(account, chatBody)
            assertNotNull(call.stream, "SSE 上游必须返回流")
            assertEquals("", call.errorBody)
            call.close()
        }
    }
}
