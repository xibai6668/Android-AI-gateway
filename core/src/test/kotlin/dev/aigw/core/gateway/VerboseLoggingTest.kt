package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.ErrorKind
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 详细日志与「无冷却」行为的网关级回归：
 * - verboseLogging 关闭时网关安静，开启后请求/转发/返回原文逐环节可见；
 * - 上游 SERVER 错误不再禁用账号（无冷却机制），后续请求仍选到同一账号。
 */
class VerboseLoggingTest {

    private inner class FlakyProvider : Provider {
        var calls = 0

        override val id = "flaky"
        override val displayName = "易错上游"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(
            models = listOf(ProviderModel("m", "m")),
            fromFallback = false,
            error = "",
        )

        override fun resolveModel(requested: String) = requested

        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)

        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            calls++
            return if (calls == 1) {
                FailedChatCall(500, "{\"error\":\"upstream boom\"}")
            } else {
                dev.aigw.core.provider.AggregatedChatCall(
                    200,
                    """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"recovered"},"finish_reason":"stop"}]}""",
                )
            }
        }
    }

    private fun withServer(verbose: Boolean, block: (port: Int, engine: GatewayEngine, provider: FlakyProvider) -> Unit) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.updateSettings(engine.settings().copy(verboseLogging = verbose))
        val provider = FlakyProvider()
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount("flaky", "u1", "唯一账号", "{}"))
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort, engine, provider)
        } finally {
            server.stop()
        }
    }

    private fun postChat(port: Int): Pair<Int, String> = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 15_000
        val body = """{"model":"flaky/m","messages":[{"role":"user","content":"hi"}]}"""
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
        val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        val statusLine = response.lineSequence().first()
        Pair(statusLine.substringAfter(" ").substringBefore(" ").toInt(), response)
    }

    @Test
    fun `上游 500 触发同供应商退避重试且自愈成功`() {
        withServer(verbose = false) { port, engine, provider ->
            // 第 1 次上游返回 500，调度器执行指数退避重试，第 2 次尝试直接成功返回 200
            val (status, response) = postChat(port)
            assertEquals(200, status, "单供应商内重试后应自愈成功返回 200")
            assertTrue(response.contains("recovered"))
            assertEquals(2, provider.calls, "经历 1 次失败 + 1 次重试成功")

            // 无冷却：账号依然可用
            val accStatus = engine.pool.status("flaky", "u1")!!
            assertTrue(accStatus.usable, "瞬态 500 不应禁用账号")
        }
    }

    @Test
    fun `详细日志关闭时不记录请求原文`() {
        withServer(verbose = false) { port, engine, _ ->
            postChat(port)
            val lines = engine.requestLog.lines().map { it.text }
            assertTrue(lines.none { it.contains("请求原文") || it.contains("收到请求") },
                "关闭详细日志时不应有链路日志：$lines")
        }
    }

    @Test
    fun `详细日志开启时逐环节可见并可从请求日志读回`() {
        withServer(verbose = true) { port, engine, _ ->
            // 第 1 次尝试 500，第 2 次重试成功
            val (status, response) = postChat(port)
            assertEquals(200, status)
            assertTrue(response.contains("recovered"), "重试后应成功")

            val logs = engine.requestLog.lines().map { it.text }
            assertTrue(logs.any { it.contains("收到请求") && it.contains("flaky/m") }, "应记录请求入口：$logs")
            assertTrue(logs.any { it.contains("[请求原文]") && it.contains("hi") }, "应记录请求原文：$logs")
            assertTrue(logs.any { it.contains("第 1 次选号") }, "应记录第1次选号：$logs")
            assertTrue(logs.any { it.contains("上游返回 HTTP 500") && it.contains("upstream boom") }, "应记录上游错误：$logs")
            assertTrue(logs.any { it.contains("[返回原文]") && it.contains("upstream boom") }, "应记录错误返回原文：$logs")
            assertTrue(logs.any { it.contains("指数退避休眠") }, "应记录指数退避休眠：$logs")
            assertTrue(logs.any { it.contains("第 2 次选号") }, "应记录第2次选号：$logs")
            assertTrue(logs.any { it.contains("上游返回 2xx") }, "应记录最终成功响应：$logs")
        }
    }
}
