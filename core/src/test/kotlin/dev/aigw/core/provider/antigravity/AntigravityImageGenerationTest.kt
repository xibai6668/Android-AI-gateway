package dev.aigw.core.provider.antigravity

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ImageGenerationRequest
import dev.aigw.core.provider.ImageGenerationResult
import dev.aigw.core.provider.MediaCall
import dev.aigw.core.provider.ProviderAccount
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 图像生成的假上游测试：断言 generateImage 走的是 `image_gen` 通道，
 * 且能从 SSE 的 inlineData 解析出 base64（mime 不丢）。
 */
class AntigravityImageGenerationTest {

    private class Received(val paths: MutableList<String> = mutableListOf(), val bodies: MutableList<String> = mutableListOf())

    private fun withUpstream(
        respond: (path: String, body: String) -> Pair<Int, String>,
        block: (base: String, received: Received) -> Unit,
    ) {
        val received = Received()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            received.paths += path
            received.bodies += requestBody
            val (status, body) = respond(path, requestBody)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", received)
        } finally {
            server.stop(0)
        }
    }

    private fun account(): ProviderAccount = ProviderAccount(
        providerId = AntigravityProvider.ID,
        uid = "u1",
        nickname = "测试",
        secret = """{"accessToken":"tok","refreshToken":"r","expiresAt":0,"email":"a@b.c","projectId":"proj-1"}""",
    )

    /** 上游回一张图：`candidates[0].content.parts[].inlineData`。 */
    private fun imageSse(mime: String, data: String): String = buildString {
        append("data: ")
        append(
            """{"response":{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"$mime","data":"$data"}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":7,"totalTokenCount":12}},"traceId":"t"}""",
        )
        append("\n\n")
    }

    @Test
    fun `图像请求走 image_gen 通道并从 inlineData 解析出 base64`() = withUpstream(
        { _, _ -> 200 to imageSse("image/png", "QUJD") },
    ) { base, received ->
        val provider = AntigravityProvider(apiBase = base, quotaBase = base, chatBase = base)
        val result = provider.generateImage(
            account(),
            ImageGenerationRequest(model = "gemini-2.5-flash-image", prompt = "一只铅笔"),
        )

        assertTrue(received.paths.single().endsWith(":streamGenerateContent"), "${received.paths}")
        val sent = JsonParser.parseString(received.bodies.single()).asJsonObject
        assertEquals("image_gen", sent.get("requestType").asString, "图像模型必须用 image_gen 通道")
        assertEquals("gemini-2.5-flash-image", sent.get("model").asString)
        assertTrue(sent.get("requestId").asString.startsWith("image_gen/"), sent.get("requestId").asString)
        val request = sent.getAsJsonObject("request")
        assertEquals("proj-1", sent.get("project").asString)
        val parts = request.getAsJsonArray("contents").first().asJsonObject
            .getAsJsonArray("parts")
        assertEquals("一只铅笔", parts.first().asJsonObject.get("text").asString)
        assertTrue(!request.has("sessionId"), "image_gen 不带 sessionId")

        val success = result as? MediaCall.Success<ImageGenerationResult> ?: throw AssertionError("应成功：$result")
        val image = success.value.images.single()
        assertEquals("QUJD", image.b64Json)
        assertEquals("data:image/png;base64,QUJD", image.url, "mime 必须保留在 data URL 里")
    }

    @Test
    fun `mime 不是 png 时也原样保留`() = withUpstream(
        { _, _ -> 200 to imageSse("image/webp", "V0VCUA==") },
    ) { base, _ ->
        val provider = AntigravityProvider(apiBase = base, quotaBase = base, chatBase = base)
        val success = provider.generateImage(
            account(),
            ImageGenerationRequest(model = "gemini-2.5-flash-image", prompt = "x"),
        ) as MediaCall.Success<ImageGenerationResult>
        val image = success.value.images.single()
        assertEquals("V0VCUA==", image.b64Json)
        assertEquals("data:image/webp;base64,V0VCUA==", image.url)
    }

    @Test
    fun `上游只回文本没有内联图时报未返回图片`() = withUpstream(
        { _, _ -> 200 to "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"我无法生成\"}]},\"finishReason\":\"STOP\"}]}}\n\n" },
    ) { base, _ ->
        val provider = AntigravityProvider(apiBase = base, quotaBase = base, chatBase = base)
        val failure = provider.generateImage(
            account(),
            ImageGenerationRequest(model = "gemini-2.5-flash-image", prompt = "x"),
        ) as? MediaCall.Failure ?: throw AssertionError("没有图必须失败")
        assertEquals(502, failure.status)
        assertTrue(failure.errorBody.contains("未返回图片"), failure.errorBody)
    }

    @Test
    fun `上游非 2xx 时原样带出状态码与原文`() = withUpstream(
        { _, _ -> 429 to """{"error":{"message":"quota exceeded"}}""" },
    ) { base, _ ->
        val provider = AntigravityProvider(apiBase = base, quotaBase = base, chatBase = base)
        val failure = provider.generateImage(
            account(),
            ImageGenerationRequest(model = "gemini-2.5-flash-image", prompt = "x"),
        ) as? MediaCall.Failure ?: throw AssertionError("非 2xx 必须失败")
        assertEquals(429, failure.status)
        assertTrue(failure.errorBody.contains("quota exceeded"), failure.errorBody)
    }

    @Test
    fun `客户端给通用图像模型名时改用上游目录里真实存在的图像模型`() = withUpstream(
        { path, _ ->
            if (path.endsWith(":fetchAvailableModels")) {
                200 to """{"models":{"gemini-3.6-flash-image":{"displayName":"Gemini Image"},"gemini-3.8-flash-high":{"displayName":"Flash"}}}"""
            } else {
                200 to imageSse("image/png", "QUJD")
            }
        },
    ) { base, received ->
        val provider = AntigravityProvider(apiBase = base, quotaBase = base, chatBase = base)
        val result = provider.generateImage(
            account(),
            ImageGenerationRequest(model = "dall-e-3", prompt = "x"),
        )

        val sent = JsonParser.parseString(received.bodies.last()).asJsonObject
        assertEquals("gemini-3.6-flash-image", sent.get("model").asString, "不能把 dall-e-3 直接下传，也不能编造模型")
        assertEquals("image_gen", sent.get("requestType").asString)
        assertTrue(result is MediaCall.Success<*>, "应成功：$result")
    }

    @Test
    fun `目录里没有图像模型时原样下传交给上游判定`() = withUpstream(
        { path, _ ->
            if (path.endsWith(":fetchAvailableModels")) {
                200 to """{"models":{"gemini-3.8-flash-high":{"displayName":"Flash"}}}"""
            } else {
                404 to """{"error":{"message":"model not found"}}"""
            }
        },
    ) { base, received ->
        val provider = AntigravityProvider(apiBase = base, quotaBase = base, chatBase = base)
        val failure = provider.generateImage(
            account(),
            ImageGenerationRequest(model = "gpt-image-1", prompt = "x"),
        ) as? MediaCall.Failure ?: throw AssertionError("上游 404 必须失败")
        assertEquals(404, failure.status)
        val sent = JsonParser.parseString(received.bodies.last()).asJsonObject
        assertEquals("gpt-image-1", sent.get("model").asString, "不能凭空编造模型名")
    }
}
