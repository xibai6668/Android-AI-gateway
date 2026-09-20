package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import java.io.ByteArrayOutputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 直接对真实 HTTP 端口发请求，覆盖路由、鉴权与请求体解析。 */
class GatewayHttpServerTest {

    private fun withServer(
        configure: (GatewayEngine) -> Unit = {},
        block: (port: Int) -> Unit,
    ) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        configure(engine)
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort)
        } finally {
            server.stop()
        }
    }

    private fun send(port: Int, request: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 15_000
        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun post(port: Int, body: String, extraHeaders: String = ""): String = send(
        port,
        "POST /v1/chat/completions HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            extraHeaders +
            "Connection: close\r\n\r\n" + body,
    )

    @Test
    fun `没有可用账号时返回 OpenAI 结构的 503`() {
        withServer { port ->
            val response = post(port, """{"model":"glm-5.2","messages":[{"role":"user","content":"hi"}]}""")
            assertTrue(response.startsWith("HTTP/1.1 503"), response.lineSequence().first())
            assertTrue(response.contains("no_healthy_account"))
        }
    }

    @Test
    fun `分块请求体被正确解析而不是挂住`() {
        withServer { port ->
            val body = """{"model":"glm-5.2","messages":[{"role":"user","content":"hi"}]}"""
            // 故意拆成两个分块，模拟流式发送请求体的客户端
            val first = body.substring(0, 20)
            val second = body.substring(20)
            val chunked = "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n" +
                Integer.toHexString(first.toByteArray(Charsets.UTF_8).size) + "\r\n" + first + "\r\n" +
                Integer.toHexString(second.toByteArray(Charsets.UTF_8).size) + "\r\n" + second + "\r\n" +
                "0\r\n\r\n"
            val response = send(port, chunked)
            // 请求体若没被正确读出会退化成「请求体不是合法 JSON」或直接超时
            assertTrue(response.startsWith("HTTP/1.1 503"), response.lineSequence().first())
            assertTrue(response.contains("no_healthy_account"))
        }
    }

    @Test
    fun `模型列表走内置快照，不带账号也能查`() {
        withServer { port ->
            val response = send(
                port,
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
            )
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            assertTrue(response.contains("\"trae/glm-5.2\""), "模型 id 应带 provider 前缀")
            assertTrue(response.contains("\"object\":\"list\""))
        }
    }

    @Test
    fun `关闭无 Key 调用后缺 Key 会被拒`() {
        withServer(configure = { engine ->
            engine.updateSettings(engine.settings().copy(apiKey = "secret", allowNoKey = false))
        }) { port ->
            val withoutKey = send(
                port,
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
            )
            assertTrue(withoutKey.startsWith("HTTP/1.1 401"), withoutKey.lineSequence().first())

            val withKey = send(
                port,
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\nConnection: close\r\n\r\n",
            )
            assertTrue(withKey.startsWith("HTTP/1.1 200"), withKey.lineSequence().first())
        }
    }

    @Test
    fun `请求体不是 JSON 时返回 400`() {
        withServer { port ->
            val response = post(port, "not-json")
            assertTrue(response.startsWith("HTTP/1.1 400"), response.lineSequence().first())
            assertTrue(response.contains("invalid_request"))
        }
    }

    @Test
    fun `登录回调缺参数时返回可读的错误页`() {
        withServer { port ->
            val response = send(
                port,
                "GET /authorize?foo=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
            )
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            assertTrue(response.contains("text/html"))
            assertTrue(response.contains("登录失败"))
        }
    }

    @Test
    fun `普通响应带 CORS 头`() {
        withServer { port ->
            val response = send(
                port,
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
            )
            assertTrue(response.contains("Access-Control-Allow-Origin: *"), response)
        }
    }

    @Test
    fun `OPTIONS 预检直接放行`() {
        withServer { port ->
            val response = send(
                port,
                "OPTIONS /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Origin: http://localhost\r\n" +
                    "Access-Control-Request-Method: POST\r\n" +
                    "Connection: close\r\n\r\n",
            )
            assertTrue(response.startsWith("HTTP/1.1 204"), response.lineSequence().first())
            assertTrue(response.contains("Access-Control-Allow-Origin: *"))
            assertTrue(response.contains("POST"))
        }
    }
}

class ChunkWriterTest {

    @Test
    fun `按 HTTP 分块编码写出并收尾`() {
        val out = ByteArrayOutputStream()
        val writer = ChunkWriter(out)
        writer.write("你好")
        writer.write("")
        writer.finish()

        val expected = Integer.toHexString("你好".toByteArray(Charsets.UTF_8).size) + "\r\n你好\r\n0\r\n\r\n"
        assertEquals(expected, out.toString("UTF-8"))
    }

    @Test
    fun `收尾后的写入会被丢弃，不会把分块帧写坏`() {
        val out = ByteArrayOutputStream()
        val writer = ChunkWriter(out)
        writer.finish()
        writer.write("迟到的数据")
        writer.finish()

        assertEquals("0\r\n\r\n", out.toString("UTF-8"))
    }
}

class SseResponseTest {

    @Test
    fun `流式响应自带 CORS 头并以分块结束`() {
        val out = ByteArrayOutputStream()
        SseResponse { writer -> writer.write("data: [DONE]\n\n") }.send(out)

        val text = out.toString("UTF-8")
        assertTrue(text.contains("Content-Type: text/event-stream"), text)
        assertTrue(text.contains("Transfer-Encoding: chunked"), text)
        assertTrue(text.contains("Access-Control-Allow-Origin: *"), text)
        assertTrue(text.endsWith("0\r\n\r\n"), text)
        assertTrue(text.contains("data: [DONE]"), text)
    }
}
