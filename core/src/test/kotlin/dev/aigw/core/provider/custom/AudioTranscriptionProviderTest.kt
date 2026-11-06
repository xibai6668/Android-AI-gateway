package dev.aigw.core.provider.custom

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.provider.AudioTranscriptionRequest
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.failureOrNull
import dev.aigw.core.provider.valueOrNull
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 自定义供应商的语音转写：客户端原始 multipart 必须逐字节转发到 `/audio/transcriptions`。
 *
 * 转发不能走 String：音频是二进制，任何 UTF-8 编解码都会改写 0x80 以上的字节。
 */
class AudioTranscriptionProviderTest {

    private class Captured {
        var path: String = ""
        var authorization: String = ""
        var contentType: String = ""
        var body: ByteArray = ByteArray(0)
    }

    private fun withUpstream(
        status: Int = 200,
        responseBody: String = """{"text":"你好世界","language":"zh","duration":3.5}""",
        contentType: String = "application/json",
        block: (baseUrl: String, captured: Captured) -> Unit,
    ) {
        val captured = Captured()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured.path = exchange.requestURI.path
            captured.authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            captured.contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
            captured.body = exchange.requestBody.use { it.readBytes() }
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/v1", captured)
        } finally {
            server.stop(0)
        }
    }

    private fun provider(baseUrl: String) = CustomProvider(
        CustomProviderConfig(key = "t", name = "测试", baseUrl = baseUrl, apiKeys = listOf("sk-1")),
    )

    private fun account() = ProviderAccount("custom:t", "u1", "测试", CustomProvider.secretOf("sk-1"))

    /** 带 0x00/0xFF 的假 wav 头 + 中文文件名/字段的 multipart。 */
    private fun multipart(boundary: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
        val wav = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45,
            0x66, 0x6D, 0x74, 0x20, 0x10, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x3E, 0x00, 0x00,
            0x00, 0x7D, 0x00, 0x00, 0x02, 0x00, 0x10, 0x00,
            0x64, 0x61, 0x74, 0x61, 0x00, 0x00, 0x00, 0x00,
            0x00, 0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x0D, 0x0A, 0x2D, 0x2D,
        )
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        write("whisper-1\r\n")
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
        write("zh\r\n")
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"file\"; filename=\"录音.wav\"\r\n")
        write("Content-Type: audio/wav\r\n\r\n")
        out.write(wav)
        write("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    @Test
    fun `原始 multipart 逐字节转发到 audio transcriptions`() {
        withUpstream { baseUrl, captured ->
            val raw = multipart("----test-boundary-123")
            val contentType = "multipart/form-data; boundary=----test-boundary-123"
            val result = provider(baseUrl).transcribeAudio(
                account(),
                AudioTranscriptionRequest(
                    model = "whisper-1",
                    audio = raw,
                    fileName = "录音.wav",
                    mimeType = "audio/wav",
                    prompt = "",
                    language = "zh",
                    rawBody = raw,
                    rawContentType = contentType,
                ),
            )

            assertEquals("/v1/audio/transcriptions", captured.path)
            assertEquals("Bearer sk-1", captured.authorization)
            assertEquals(contentType, captured.contentType)
            assertContentEquals(raw, captured.body, "上游收到的字节必须与客户端原始 multipart 完全一致")

            val value = assertNotNull(result.valueOrNull(), "2xx 且 JSON 合法时应返回成功")
            assertEquals("你好世界", value.text)
            assertEquals("zh", value.language)
            assertEquals(3.5, value.durationSeconds)
        }
    }

    @Test
    fun `baseUrl 带 chat completions 或 models 后缀时仍拼到同一根地址`() {
        withUpstream { baseUrl, captured ->
            val raw = multipart("b1")
            provider("$baseUrl/chat/completions").transcribeAudio(account(), request(raw, "b1"))
            assertEquals("/v1/audio/transcriptions", captured.path)
        }
        withUpstream { baseUrl, captured ->
            val raw = multipart("b2")
            provider("$baseUrl/models/").transcribeAudio(account(), request(raw, "b2"))
            assertEquals("/v1/audio/transcriptions", captured.path)
        }
    }

    @Test
    fun `非 2xx 转成 Failure 并保留状态码与原文`() {
        withUpstream(status = 413, responseBody = """{"error":{"message":"file too large"}}""") { baseUrl, _ ->
            val raw = multipart("b3")
            val failure = assertNotNull(
                provider(baseUrl).transcribeAudio(account(), request(raw, "b3")).failureOrNull(),
                "非 2xx 必须返回失败",
            )
            assertEquals(413, failure.status)
            assertTrue(failure.errorBody.contains("file too large"), "失败原文应透传，实际：${failure.errorBody}")
        }
    }

    @Test
    fun `上游返回纯文本时按 text 收下`() {
        withUpstream(responseBody = "转写结果", contentType = "text/plain; charset=utf-8") { baseUrl, _ ->
            val raw = multipart("b4")
            val value = assertNotNull(provider(baseUrl).transcribeAudio(account(), request(raw, "b4")).valueOrNull())
            assertEquals("转写结果", value.text)
        }
    }

    @Test
    fun `凭证无法解析时不打上游`() {
        val result = provider("http://127.0.0.1:1/v1").transcribeAudio(
            ProviderAccount("custom:t", "u1", "测试", "not-json"),
            request(multipart("b5"), "b5"),
        )
        val failure = assertNotNull(result.failureOrNull())
        assertEquals(401, failure.status)
    }

    @Test
    fun `verbose json 里的 duration 是数字字段`() {
        withUpstream(responseBody = """{"task":"transcribe","language":"en","duration":12,"text":"hi"}""") { baseUrl, _ ->
            val value = assertNotNull(
                provider(baseUrl).transcribeAudio(account(), request(multipart("b6"), "b6")).valueOrNull(),
            )
            assertEquals(12.0, value.durationSeconds)
            assertEquals("hi", value.text)
        }
    }

    private fun request(raw: ByteArray, boundary: String) = AudioTranscriptionRequest(
        model = "whisper-1",
        audio = raw,
        fileName = "a.wav",
        mimeType = "audio/wav",
        prompt = "",
        language = "",
        rawBody = raw,
        rawContentType = "multipart/form-data; boundary=$boundary",
    )
}
