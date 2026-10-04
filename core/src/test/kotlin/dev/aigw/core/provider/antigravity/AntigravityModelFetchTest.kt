package dev.aigw.core.provider.antigravity

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.ProviderAccount
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AntigravityModelFetchTest {

    private fun withMockServer(
        modelsJson: String,
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:fetchAvailableModels") { exchange ->
            val bytes = modelsJson.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private val sampleModels = """
        {
          "models": {
            "gemini-3.8-flash-high": {
              "displayName": "Gemini 3.8 Flash",
              "maxTokens": 1048576,
              "maxOutputTokens": 65536
            },
            "gemini-3.1-flash-lite": {
              "displayName": "Gemini 3.1 Flash Lite",
              "maxTokens": 1048576
            },
            "chat_20706": {
              "displayName": "Internal Chat"
            }
          }
        }
    """.trimIndent()

    private val accountJson = """
        {
          "accessToken": "valid-token",
          "refreshToken": "refresh-token",
          "expiresAt": ${System.currentTimeMillis() / 1000 + 3600},
          "email": "test@gmail.com",
          "projectId": "test-project"
        }
    """.trimIndent()

    @Test
    fun `从云端拉取模型并过滤内部模型`() {
        withMockServer(sampleModels) { baseUrl ->
            val provider = AntigravityProvider(
                apiBase = baseUrl,
                quotaBase = baseUrl,
            )
            val account = ProviderAccount("antigravity", "test@gmail.com", "test", accountJson)
            val catalog = provider.listModels(account)

            assertFalse(catalog.fromFallback, "应从云端获取而非 fallback")
            val ids = catalog.models.map { it.id }
            assertTrue("gemini-3.8-flash-high" in ids)
            assertTrue("gemini-3.1-flash-lite" in ids)
            assertFalse("chat_20706" in ids, "内部模型必须被过滤")

            val m38 = catalog.models.first { it.id == "gemini-3.8-flash-high" }
            assertEquals("Gemini 3.8 Flash", m38.name)
            assertEquals(1_048_576L, m38.contextWindow)
        }
    }

    @Test
    fun `别名能正确映射到完整上游模型名`() {
        val provider = AntigravityProvider()
        assertEquals("gemini-3.8-flash-high", provider.resolveModel("gemini-3.8-flash"))
        assertEquals("gemini-3.7-flash-high", provider.resolveModel("gemini-3.7-flash"))
        assertEquals("gemini-pro-agent", provider.resolveModel("gemini-3.1-pro"))
        assertEquals("claude-opus-4-6-thinking", provider.resolveModel("claude-opus"))
    }

    @Test
    fun `无账号时不返回模型`() {
        val provider = AntigravityProvider()
        val catalog = provider.listModels(null)
        assertFalse(catalog.fromFallback)
        assertTrue(catalog.models.isEmpty(), "无账号时不应返回硬编码模型")
    }
}
