package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AudioTranscriptionRequest
import dev.aigw.core.provider.AudioTranscriptionResult
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.MediaCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /v1/audio/transcriptions` 的端到端回归：multipart 二进制上传、字段映射与响应格式。
 *
 * 这里只用假 provider，不打真实上游；真实的 multipart 解析交给 [MultipartForm]（其单测在
 * [MultipartFormTest] 里覆盖二进制边界）。
 */
class AudioTranscriptionTest {

    private val boundary = "----aigwTestBoundary"

    /** 一段含非法 UTF-8 字节的音频：必须原样到 provider。 */
    private val audioBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0xFF.toByte(), 0x80.toByte(), 0x28)

    /** 记录收到的转写请求的假供应商。 */
    private class RecordingProvider(
        override val id: String,
        private val supports: Boolean = true,
        private val respond: (AudioTranscriptionRequest) -> MediaCall<AudioTranscriptionResult> = {
            MediaCall.Success(AudioTranscriptionResult(text = "你好世界", language = "zh", durationSeconds = 1.5))
        },
    ) : Provider {
        var lastRequest: AudioTranscriptionRequest? = null
        var calls = 0
        override val displayName = id
        override val authKind = AuthKind.NONE
        override val supportsAudioTranscription = supports
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = when {
            status == 401 -> UpstreamError(ErrorKind.SESSION_DEAD, body)
            status == 400 -> UpstreamError(ErrorKind.CLIENT, body)
            else -> UpstreamError(ErrorKind.SERVER, body)
        }
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            FailedChatCall(500, "不该走对话通道")
        override fun transcribeAudio(account: ProviderAccount, request: AudioTranscriptionRequest): MediaCall<AudioTranscriptionResult> {
            calls++
            lastRequest = request
            return respond(request)
        }
    }

    private fun withServer(
        vararg providers: Provider,
        accountsPerProvider: Int = 1,
        block: (port: Int) -> Unit,
    ) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        for (provider in providers) {
            engine.registry.register(provider)
            for (i in 0 until accountsPerProvider) {
                engine.pool.upsert(ProviderAccount(provider.id, "u${provider.id}$i", "${provider.id}#$i", "{}"))
            }
        }
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort)
        } finally {
            server.stop()
        }
    }

    private fun transcribe(
        port: Int,
        multipart: ByteArray,
        contentType: String = "multipart/form-data; boundary=$boundary",
    ): String {
        val conn = URL("http://127.0.0.1:$port/v1/audio/transcriptions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(multipart.size)
        conn.setRequestProperty("Content-Type", contentType)
        conn.outputStream.use { it.write(multipart) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private fun multipartBody(extra: List<MultipartForm.Field> = emptyList()): ByteArray = MultipartForm.build(
        boundary,
        listOf(MultipartForm.Field("file", audioBytes, fileName = "voice.wav", contentType = "audio/wav")) + extra,
    )

    @Test
    fun `二进制音频原样送达 provider 并返回 text 结构`() {
        val provider = RecordingProvider("fake-audio")
        withServer(provider) { port ->
            val response = transcribe(
                port,
                multipartBody(listOf(MultipartForm.Field("model", "whisper-1".toByteArray()))),
            )
            assertEquals("""{"text":"你好世界"}""", response)
            val request = provider.lastRequest!!
            assertContentEquals(audioBytes, request.audio, "音频字节必须在网关层无损")
            assertEquals("voice.wav", request.fileName)
            assertEquals("audio/wav", request.mimeType)
            assertEquals("whisper-1", request.model)
            assertTrue(request.rawBody.isNotEmpty(), "原始 multipart 要带给 provider，供直传上游")
        }
    }

    @Test
    fun `response_format 为 text 时返回纯文本`() {
        val provider = RecordingProvider("fake-audio")
        withServer(provider) { port ->
            val response = transcribe(
                port,
                multipartBody(listOf(MultipartForm.Field("response_format", "text".toByteArray()))),
            )
            assertEquals("你好世界", response)
        }
    }

    @Test
    fun `response_format 为 verbose_json 时带语言与时长`() {
        val provider = RecordingProvider("fake-audio")
        withServer(provider) { port ->
            val response = transcribe(
                port,
                multipartBody(listOf(MultipartForm.Field("response_format", "verbose_json".toByteArray()))),
            )
            assertTrue(response.contains("\"task\":\"transcribe\""), response)
            assertTrue(response.contains("\"language\":\"zh\""), response)
            assertTrue(response.contains("\"duration\":1.5"), response)
        }
    }

    @Test
    fun `缺少 file 字段时返回 400`() {
        val provider = RecordingProvider("fake-audio")
        withServer(provider) { port ->
            val body = MultipartForm.build(boundary, listOf(MultipartForm.Field("model", "whisper-1".toByteArray())))
            val response = transcribe(port, body)
            assertTrue(response.contains("invalid_request"), response)
            assertTrue(response.contains("file"), response)
        }
    }

    @Test
    fun `非 multipart 请求返回 400 而不是挂住`() {
        val provider = RecordingProvider("fake-audio")
        withServer(provider) { port ->
            val response = transcribe(port, """{"model":"whisper-1"}""".toByteArray(), "application/json")
            assertTrue(response.contains("multipart"), response)
        }
    }

    @Test
    fun `模型指向不支持转写的供应商时明确拒绝`() {
        val unsupported = RecordingProvider("chat-only", supports = false)
        withServer(unsupported) { port ->
            val response = transcribe(
                port,
                multipartBody(listOf(MultipartForm.Field("model", "chat-only/gpt-4o".toByteArray()))),
            )
            assertTrue(response.contains("invalid_request"), response)
            assertTrue(response.contains("不支持语音转写"), response)
        }
    }

    @Test
    fun `首个支持转写的供应商失败后切到下一个`() {
        val broken = RecordingProvider("audio-a") { MediaCall.Failure(500, "上游炸了") }
        val healthy = RecordingProvider("audio-b") {
            MediaCall.Success(AudioTranscriptionResult(text = "来自 b"))
        }
        withServer(broken, healthy) { port ->
            val response = transcribe(port, multipartBody())
            assertTrue(response.contains("来自 b"), response)
            assertEquals(1, broken.calls)
            assertEquals(1, healthy.calls)
        }
    }

    @Test
    fun `首个账号失败后换号重试成功`() {
        var calls = 0
        val provider = RecordingProvider("fake-audio") {
            calls++
            if (calls == 1) MediaCall.Failure(500, "上游炸了") else MediaCall.Success(AudioTranscriptionResult(text = "重试成功"))
        }
        // 两个账号：选号会排除已试过的 uid，第二次尝试才会落到第二个账号
        withServer(provider, accountsPerProvider = 2) { port ->
            val response = transcribe(port, multipartBody())
            assertTrue(response.contains("重试成功"), response)
        }
    }

    @Test
    fun `上游返回客户端错误时立即失败而不无限重试`() {
        var calls = 0
        val provider = RecordingProvider("fake-audio") {
            calls++
            MediaCall.Failure(400, "不支持该音频格式")
        }
        withServer(provider, accountsPerProvider = 3) { port ->
            val response = transcribe(port, multipartBody())
            // CLIENT 类错误换号必然再撞同一道墙，只该试一次
            assertEquals(1, calls, "客户端错误不应换号重试")
            assertTrue(response.contains("不支持该音频格式"), response)
        }
    }
}
