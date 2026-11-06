package dev.aigw.core.provider.antigravity

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.AudioTranscriptionRequest
import dev.aigw.core.provider.MediaCall
import dev.aigw.core.provider.ProviderAccount
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「用音频对话模型代转写」的假上游回归。
 *
 * 两个要点：音频必须真的以 `inlineData` 出现在请求体里（否则模型拿到空内容会凭空编造），
 * 以及目录里没有声明音频能力的模型时**必须报错**而不是硬塞给一个文本模型。
 */
class AntigravityAudioTranscriptionTest {

    private fun account(): ProviderAccount = ProviderAccount(
        providerId = AntigravityProvider.ID,
        uid = "u1",
        nickname = "测试",
        secret = """{"accessToken":"tok","refreshToken":"r","expiresAt":0,"email":"a@b.c","projectId":"proj-1"}""",
    )

    /** 模型目录里只有一个声明了音频输入的模型。 */
    private fun modelsBody(): String =
        """{"models":{"gemini-flash":{"displayName":"Flash","maxTokens":1000},
                    "gemini-audio":{"displayName":"Audio","maxTokens":2000,
                      "inputModalities":["text","audio"]}}}"""

    private fun audioSse(text: String): String {
        val chunk = """{"response":{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}}"""
        val last = """{"response":{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":3}}}"""
        return "data: $chunk\n\ndata: $last\n\n"
    }

    private fun withUpstream(
        models: String,
        sse: String,
        block: (base: String, bodies: MutableList<String>) -> Unit,
    ) {
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            bodies += body
            val payload = if (path.contains("fetchAvailableModels")) models else sse
            val bytes = payload.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", bodies)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `音频以 inlineData 发给声明了音频能力的模型`() {
        withUpstream(modelsBody(), audioSse("你好世界")) { base, bodies ->
            val provider = AntigravityProvider(chatBase = base, apiBase = base, quotaBase = base, tokenEndpoint = "$base/token")
            val outcome = provider.transcribeAudio(
                account(),
                AudioTranscriptionRequest(
                    model = "",
                    audio = byteArrayOf(1, 2, 3),
                    fileName = "a.wav",
                    mimeType = "audio/wav",
                    prompt = "",
                    language = "zh",
                    rawBody = ByteArray(0),
                    rawContentType = "",
                ),
            )
            val result = (outcome as? MediaCall.Success)?.value
            assertTrue(result != null, "应成功返回转写：$outcome")
            assertEquals("你好世界", result!!.text)
            assertEquals("zh", result.language)

            val chatBody = bodies.last()
            // 音频必须真的在请求里，且是声明了音频能力的那个模型
            assertTrue(chatBody.contains("\"inlineData\""), "音频必须以 inlineData 发出：${chatBody.take(400)}")
            assertTrue(chatBody.contains("\"mimeType\":\"audio/wav\""), chatBody.take(400))
            assertTrue(chatBody.contains("\"model\":\"gemini-audio\""), "必须选音频模型：${chatBody.take(200)}")
            assertTrue(chatBody.contains("转写"), "要有转写指令，否则模型不知道干什么")
        }
    }

    @Test
    fun `目录里没有音频模型时明确报错而不是硬塞文本模型`() {
        val noAudio = """{"models":{"gemini-flash":{"displayName":"Flash","maxTokens":1000}}}"""
        withUpstream(noAudio, audioSse("不该走到这里")) { base, bodies ->
            val provider = AntigravityProvider(chatBase = base, apiBase = base, quotaBase = base, tokenEndpoint = "$base/token")
            val outcome = provider.transcribeAudio(
                account(),
                AudioTranscriptionRequest(
                    model = "", audio = byteArrayOf(1), fileName = "a.wav", mimeType = "audio/wav",
                    prompt = "", language = "", rawBody = ByteArray(0), rawContentType = "",
                ),
            )
            val failure = outcome as? MediaCall.Failure
            assertTrue(failure != null, "没有音频模型必须失败：$outcome")
            assertEquals(400, failure!!.status)
            assertTrue(failure.errorBody.contains("音频"), failure.errorBody)
            // 关键：不能回退到文本模型去「猜」一段转写
            assertTrue(bodies.none { it.contains("inlineData") }, "不该向文本模型发送音频")
        }
    }

    @Test
    fun `上游回了空文本时算失败而不是空串成功`() {
        val empty = "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"STOP\"}]}}\n\n"
        withUpstream(modelsBody(), empty) { base, _ ->
            val provider = AntigravityProvider(chatBase = base, apiBase = base, quotaBase = base, tokenEndpoint = "$base/token")
            val outcome = provider.transcribeAudio(
                account(),
                AudioTranscriptionRequest(
                    model = "", audio = byteArrayOf(1), fileName = "a.wav", mimeType = "audio/wav",
                    prompt = "", language = "", rawBody = ByteArray(0), rawContentType = "",
                ),
            )
            assertTrue(outcome is MediaCall.Failure, "空转写必须报错：$outcome")
        }
    }
}
