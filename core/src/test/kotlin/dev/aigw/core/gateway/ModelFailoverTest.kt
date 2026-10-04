package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 同一款模型多供应商故障转移（Failover）自动化回归测试。
 */
class ModelFailoverTest {

    private inner class TestProvider(
        override val id: String,
        private val availableModels: List<String>,
        private val shouldFail: Boolean = false,
        private val responseContent: String = "ok",
    ) : Provider {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(
            models = availableModels.map { ProviderModel(it, it) },
            fromFallback = false,
            error = "",
        )
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            if (shouldFail) {
                return FailedChatCall(500, "上游服务器暂时故障")
            }
            return AggregatedChatCall(
                200,
                """{"id":"c","object":"chat.completion","created":1,"model":"$id","choices":[{"index":0,"message":{"role":"assistant","content":"$responseContent"},"finish_reason":"stop"}]}""",
            )
        }
    }

    @Test
    fun `首选供应商失败时自动故障转移到支持同款模型的备用供应商`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // Provider A: 支持 glm-5.2，但上游崩了 (500)
        val providerA = TestProvider("providerA", listOf("glm-5.2"), shouldFail = true)
        // Provider B: 也支持 glm-5.2，可用
        val providerB = TestProvider("providerB", listOf("glm-5.2"), shouldFail = false, responseContent = "来自B的回复")

        engine.registry.register(providerA)
        engine.registry.register(providerB)

        // 两个供应商都配置账号
        engine.pool.upsert(ProviderAccount("providerA", "u1", "账号A", "{}"))
        engine.pool.upsert(ProviderAccount("providerB", "u2", "账号B", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)

        try {
            val conn = URL("http://127.0.0.1:${server.listeningPort}/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            // 客户端请求 providerA 的 glm-5.2
            val body = """{"model":"providerA/glm-5.2","messages":[{"role":"user","content":"hi"}]}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))

            assertEquals(200, conn.responseCode, "自动故障转移应成功返回 200")
            val resp = conn.inputStream.bufferedReader().readText()
            assertTrue(resp.contains("来自B的回复"), "响应内容应来自备用供应商 B：$resp")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `首选供应商无可用账号时直接切换到备用供应商`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // Provider A: 支持 gpt-4o，无可用账号
        val providerA = TestProvider("providerA", listOf("gpt-4o"), shouldFail = false)
        // Provider B: 也支持 gpt-4o，有可用账号
        val providerB = TestProvider("providerB", listOf("gpt-4o"), shouldFail = false, responseContent = "来自B的成功响应")

        engine.registry.register(providerA)
        engine.registry.register(providerB)

        // 只有 providerB 配了账号，providerA 没有
        engine.pool.upsert(ProviderAccount("providerB", "u2", "账号B", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)

        try {
            val conn = URL("http://127.0.0.1:${server.listeningPort}/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = """{"model":"providerA/gpt-4o","messages":[{"role":"user","content":"hi"}]}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))

            assertEquals(200, conn.responseCode, "A 无账号时应自动平滑切到 B")
            val resp = conn.inputStream.bufferedReader().readText()
            assertTrue(resp.contains("来自B的成功响应"), "响应内容：$resp")
        } finally {
            server.stop()
        }
    }
}
