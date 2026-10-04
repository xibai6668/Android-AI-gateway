package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.GeminiNativeSupport
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.antigravity.AntigravityProvider
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 原生 Google Gemini 协议端点 (v1beta models) 自动化测试。
 */
class GeminiNativeEndpointTest {

    private inner class MockAntigravityProvider : Provider, GeminiNativeSupport {
        override val id = "antigravity"
        override val displayName = "Antigravity"
        override val authKind = AuthKind.OAUTH_LOOPBACK
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(
            models = listOf(ProviderModel("gemini-3.8-flash-high", "Gemini 3.8 Flash", 1048576)),
            fromFallback = false,
            error = "",
        )
        override fun resolveModel(requested: String) = "gemini-3.8-flash-high"
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            AggregatedChatCall(200, "{}")

        override fun openGeminiNative(account: ProviderAccount, model: String, geminiBody: String, streaming: Boolean): ChatCall {
            if (streaming) {
                val sseData = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"原生Gemini回复\"}]}}]}\n\n"
                return StreamingChatCall(200, ByteArrayInputStream(sseData.toByteArray(Charsets.UTF_8)))
            }
            return AggregatedChatCall(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"原生Gemini非流式回复"}]}}]}""",
            )
        }
    }

    @Test
    fun `GET v1beta models 返回 Google 格式模型列表`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(MockAntigravityProvider())
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)

        try {
            val conn = URL("http://127.0.0.1:${server.listeningPort}/v1beta/models")
                .openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().readText()
            assertTrue(body.contains("models/gemini-3.8-flash-high"), "应包含 models/ 规范前缀：$body")
            assertTrue(body.contains("generateContent"), "应包含 supportedGenerationMethods")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `POST v1beta streamGenerateContent 正常流式透传`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(MockAntigravityProvider())
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "谷歌账号", "{}"))
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)

        try {
            val conn = URL("http://127.0.0.1:${server.listeningPort}/v1beta/models/gemini-2.5-flash:streamGenerateContent")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", "test-key")
            val reqBody = """{"contents":[{"role":"user","parts":[{"text":"hello"}]}]}"""
            conn.outputStream.write(reqBody.toByteArray(Charsets.UTF_8))

            assertEquals(200, conn.responseCode)
            val resp = conn.inputStream.bufferedReader().readText()
            assertTrue(resp.contains("原生Gemini回复"), "应收到原生 Gemini 流式返回：$resp")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `POST v1beta generateContent 非流式正常返回`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(MockAntigravityProvider())
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "谷歌账号", "{}"))
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)

        try {
            val conn = URL("http://127.0.0.1:${server.listeningPort}/v1beta/models/gemini-2.5-flash:generateContent?key=test-key")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val reqBody = """{"contents":[{"role":"user","parts":[{"text":"hello"}]}]}"""
            conn.outputStream.write(reqBody.toByteArray(Charsets.UTF_8))

            assertEquals(200, conn.responseCode)
            val resp = conn.inputStream.bufferedReader().readText()
            assertTrue(resp.contains("原生Gemini非流式回复"), "应收到原生 Gemini 非流式返回：$resp")
        } finally {
            server.stop()
        }
    }
}
