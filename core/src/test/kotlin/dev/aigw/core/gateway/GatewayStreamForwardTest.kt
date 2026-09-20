package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import java.io.ByteArrayInputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 流式转发的回归测试。
 *
 * 上游 chunk 里 `"usage": null` 这种字段用 Gson 的裸转换（`getAsJsonObject`）会抛
 * ClassCastException；异常一旦抛在转发循环里，整条流会被掐断，客户端只看到「连接断开」。
 * 标准 OpenAI 兼容服务的每个 chunk 都带 `"usage": null`，这里用同样的报文把行为锁死。
 */
class GatewayStreamForwardTest {

    /** 与标准 OpenAI 兼容服务一致：每个 chunk 都带 `usage: null`。 */
    private val upstreamSse = buildString {
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}],\"usage\":null}\n\n")
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":null}\n\n")
        append("data: [DONE]\n\n")
    }

    private inner class FakeProvider : Provider {
        override val id = "fake"
        override val displayName = "假上游"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            StreamingChatCall(200, ByteArrayInputStream(upstreamSse.toByteArray(Charsets.UTF_8)))
    }

    private fun withServer(block: (port: Int) -> Unit) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(FakeProvider())
        engine.pool.upsert(ProviderAccount("fake", "u1", "假账号", "{}"))
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `上游 chunk 带 usage null 时流仍被完整转发`() {
        withServer { port ->
            val body = """{"model":"fake/m","stream":true,"messages":[{"role":"user","content":"hi"}]}"""
            val response = Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 15_000
                socket.getOutputStream().write(
                    (
                        "POST /v1/chat/completions HTTP/1.1\r\n" +
                            "Host: 127.0.0.1\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                            "Connection: close\r\n\r\n" + body
                        ).toByteArray(Charsets.UTF_8),
                )
                socket.getOutputStream().flush()
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            assertTrue(response.contains("你好"), "内容必须完整透传：$response")
            assertTrue(response.contains("[DONE]"), "必须带结束标记，否则客户端会判定连接异常：$response")
        }
    }

    @Test
    fun `非流式聚合遇到 usage null 也不该炸`() {
        val aggregated = OpenAiSseAggregator.aggregate(
            ByteArrayInputStream(upstreamSse.toByteArray(Charsets.UTF_8)),
            "m",
        )
        assertTrue(aggregated.contains("你好"), aggregated)
        assertTrue(aggregated.contains("\"finish_reason\":\"stop\""), aggregated)
        assertTrue(!aggregated.contains("\"usage\""), "usage 为 null 时不该写进结果：$aggregated")
    }

    @Test
    fun `usage 为对象时仍能取到 token 统计`() {
        val sse = buildString {
            append("data: {\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}],")
            append("\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}\n\n")
            append("data: [DONE]\n\n")
        }
        val aggregated = OpenAiSseAggregator.aggregate(ByteArrayInputStream(sse.toByteArray(Charsets.UTF_8)), "m")
        assertTrue(aggregated.contains("\"total_tokens\":7"), aggregated)
    }

    @Test
    fun `字段值是 null 时取字符串不会抛异常`() {
        val sse = buildString {
            append("data: {\"id\":null,\"model\":null,\"created\":null,\"choices\":null,\"usage\":null}\n\n")
            append("data: [DONE]\n\n")
        }
        val aggregated = OpenAiSseAggregator.aggregate(ByteArrayInputStream(sse.toByteArray(Charsets.UTF_8)), "fallback")
        assertEquals(true, aggregated.contains("\"model\":\"fallback\""), aggregated)
    }
}
