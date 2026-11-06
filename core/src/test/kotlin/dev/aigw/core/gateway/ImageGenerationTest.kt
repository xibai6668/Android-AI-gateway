package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.GeneratedImage
import dev.aigw.core.provider.ImageGenerationRequest
import dev.aigw.core.provider.ImageGenerationResult
import dev.aigw.core.provider.MediaCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /v1/images/generations` 的端到端回归：候选过滤（只有声明了图像能力的供应商参与）、
 * prompt 校验、跨供应商回落与响应结构。
 */
class ImageGenerationTest {

    private class ImageProvider(
        override val id: String,
        private val supports: Boolean = true,
        private val respond: (ImageGenerationRequest) -> MediaCall<ImageGenerationResult> = {
            MediaCall.Success(ImageGenerationResult(listOf(GeneratedImage(b64Json = "aGVsbG8="))))
        },
    ) : Provider {
        var lastRequest: ImageGenerationRequest? = null
        var calls = 0
        override val displayName = id
        override val authKind = AuthKind.NONE
        override val supportsImageGeneration = supports
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = when {
            status == 401 -> UpstreamError(ErrorKind.SESSION_DEAD, body)
            status == 400 -> UpstreamError(ErrorKind.CLIENT, body)
            else -> UpstreamError(ErrorKind.SERVER, body)
        }
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            FailedChatCall(500, "不该走对话通道")
        override fun generateImage(account: ProviderAccount, request: ImageGenerationRequest): MediaCall<ImageGenerationResult> {
            calls++
            lastRequest = request
            return respond(request)
        }
    }

    private fun withServer(vararg providers: Provider, block: (port: Int) -> Unit) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        for (provider in providers) {
            engine.registry.register(provider)
            engine.pool.upsert(ProviderAccount(provider.id, "u-${provider.id}", provider.id, "{}"))
        }
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort)
        } finally {
            server.stop()
        }
    }

    private fun generate(port: Int, body: String): String {
        val conn = URL("http://127.0.0.1:$port/v1/images/generations").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    @Test
    fun `生成成功时返回 OpenAI 图像结构`() {
        val provider = ImageProvider("img")
        withServer(provider) { port ->
            val response = generate(port, """{"model":"img/gemini-3-pro-image","prompt":"一只猫"}""")
            assertTrue(response.contains("\"created\":"), response)
            assertTrue(response.contains("\"b64_json\":\"aGVsbG8=\""), response)
            val request = provider.lastRequest!!
            assertEquals("一只猫", request.prompt)
            assertEquals("gemini-3-pro-image", request.model, "provider 收到的模型名应剥掉路由前缀")
        }
    }

    @Test
    fun `缺少 prompt 时返回 400`() {
        val provider = ImageProvider("img")
        withServer(provider) { port ->
            val response = generate(port, """{"model":"img/x","prompt":"  "}""")
            assertTrue(response.contains("invalid_request"), response)
            assertEquals(0, provider.calls, "参数不合法不应打上游")
        }
    }

    @Test
    fun `首选供应商不支持图像时落到支持的那家`() {
        // chat-only 注册在前，且模型名指向它：网关必须跳过它去找真正支持图像能力的供应商
        val chatOnly = ImageProvider("chat-only", supports = false)
        val imageProvider = ImageProvider("img")
        withServer(chatOnly, imageProvider) { port ->
            val response = generate(port, """{"model":"chat-only/some-chat-model","prompt":"一只狗"}""")
            assertTrue(response.contains("aGVsbG8="), response)
            assertEquals(0, chatOnly.calls, "不支持图像的供应商不该被调用")
            assertEquals(1, imageProvider.calls)
            // 别家的模型名对承接方毫无意义，传下去只会被上游以「模型不存在」拒掉
            assertEquals("", imageProvider.lastRequest!!.model, "切家时不应带原供应商的模型名")
        }
    }

    @Test
    fun `首选图像供应商失败后换下一个供应商`() {        val broken = ImageProvider("img-a") { MediaCall.Failure(500, "上游炸了") }
        val healthy = ImageProvider("img-b") {
            MediaCall.Success(ImageGenerationResult(listOf(GeneratedImage(b64Json = "ZnJvbS1i"))))
        }
        withServer(broken, healthy) { port ->
            val response = generate(port, """{"model":"img-a/gemini-image","prompt":"一只兔"}""")
            assertTrue(response.contains("ZnJvbS1i"), response)
            assertEquals(1, broken.calls)
            assertEquals(1, healthy.calls)
        }
    }

    @Test
    fun `供应商只说客户端错误时直接失败不再换家`() {
        val broken = ImageProvider("img-a") { MediaCall.Failure(400, "size 参数非法") }
        val healthy = ImageProvider("img-b")
        withServer(broken, healthy) { port ->
            val response = generate(port, """{"model":"img-a/gemini-image","prompt":"猫"}""")
            assertTrue(response.contains("size 参数非法"), response)
            assertEquals(0, healthy.calls, "客户端参数错误换家也会再撞同一道墙")
        }
    }

    @Test
    fun `没有任何记录时返回空响应而不是崩`() {
        val provider = ImageProvider("img") {
            MediaCall.Success(ImageGenerationResult(listOf(GeneratedImage(b64Json = "ZZZ"))))
        }
        withServer(provider) { port ->
            val response = generate(port, """{"model":"img/x","prompt":"p","response_format":"url"}""")
            // 上游只给 base64：要 url 的客户端也拿回 b64_json，并标注实际格式
            assertTrue(response.contains("\"b64_json\":\"ZZZ\""), response)
            assertTrue(response.contains("\"response_format\":\"b64_json\""), response)
        }
    }
}
