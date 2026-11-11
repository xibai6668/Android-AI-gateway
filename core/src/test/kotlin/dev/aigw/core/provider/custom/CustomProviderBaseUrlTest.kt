package dev.aigw.core.provider.custom

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自定义供应商的地址规范化与拉模型失败说明。
 *
 * 回归背景：新建页让用户填「到 /v1，或直接写 /v1/chat/completions」，但只填域名会被拼成
 * `https://api.x.com/chat/completions`（缺 /v1，必 404）；而 `fetchModels` 把所有失败压成空列表，
 * 界面只能笼统地说「检查接口地址与 API Key」，401 与 404 无迹可循。
 */
class CustomProviderBaseUrlTest {

    @Test
    fun `只填域名时自动补 v1`() {
        assertEquals("https://api.example.com/v1", CustomProvider.normalizeBaseUrl("https://api.example.com"))
    }

    @Test
    fun `已带 v1 时不重复补`() {
        assertEquals("https://api.example.com/v1", CustomProvider.normalizeBaseUrl("https://api.example.com/v1"))
        assertEquals("https://api.example.com/v1", CustomProvider.normalizeBaseUrl("https://api.example.com/v1/"))
    }

    @Test
    fun `贴完整对话地址时退回到根地址`() {
        assertEquals(
            "https://api.example.com/v1",
            CustomProvider.normalizeBaseUrl("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `带自定义版本段或路径前缀时保留原样`() {
        assertEquals("https://open.bigmodel.cn/api/paas/v4", CustomProvider.normalizeBaseUrl("https://open.bigmodel.cn/api/paas/v4"))
        assertEquals("https://api.example.com/proxy/v1", CustomProvider.normalizeBaseUrl("https://api.example.com/proxy"))
    }

    @Test
    fun `空地址保持为空`() {
        assertEquals("", CustomProvider.normalizeBaseUrl("   "))
    }

    @Test
    fun `对话与模型端点按规范化后的地址拼接`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            CustomProvider.chatEndpointOf("https://api.example.com"),
        )
        assertEquals(
            "https://api.example.com/v1/models",
            CustomProvider.modelsEndpointOf("https://api.example.com"),
        )
    }
}

/** 拉模型：成功带模型、失败带可照着修的原因。 */
class CustomProviderFetchModelsTest {

    private fun withModelsEndpoint(
        status: Int = 200,
        body: String = """{"data":[{"id":"gpt-4o"},{"id":"claude-sonnet-5"}]}""",
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
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

    @Test
    fun `只填域名也能拉到模型`() {
        withModelsEndpoint { baseUrl ->
            val result = CustomProvider.fetchModels(baseUrl, "sk-1")
            assertEquals(listOf("gpt-4o", "claude-sonnet-5"), result.models)
            assertEquals("", result.error)
        }
    }

    @Test
    fun `401 时说明是 Key 问题而不是笼统报错`() {
        withModelsEndpoint(status = 401, body = """{"error":{"message":"invalid api key"}}""") { baseUrl ->
            val result = CustomProvider.fetchModels(baseUrl, "sk-bad")
            assertTrue(result.models.isEmpty())
            assertTrue(result.error.contains("401"), "应带上游状态码，实际：${result.error}")
            assertTrue(result.error.contains("Key"), "应指向 Key，实际：${result.error}")
        }
    }

    @Test
    fun `404 时指出实际请求的地址`() {
        withModelsEndpoint(status = 404, body = "not found") { baseUrl ->
            val result = CustomProvider.fetchModels("$baseUrl/nowhere", "sk-1")
            assertTrue(result.error.contains("404"))
            assertTrue(result.error.contains("/models"), "应提示实际拼出的地址，实际：${result.error}")
        }
    }

    @Test
    fun `空地址直接提示填写而不是发请求`() {
        val result = CustomProvider.fetchModels("", "sk-1")
        assertTrue(result.models.isEmpty())
        assertTrue(result.error.contains("接口地址"))
    }

    @Test
    fun `返回不是模型列表时给出协议提示`() {
        withModelsEndpoint(body = "<html>login</html>") { baseUrl ->
            val result = CustomProvider.fetchModels(baseUrl, "sk-1")
            assertTrue(result.error.contains("OpenAI 兼容"))
        }
    }
}
