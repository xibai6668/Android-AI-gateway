package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 客户端要流式、上游却回了普通 JSON 时的兼容性回归。
 *
 * OpenAI 协议要求 stream=true 必须以 SSE 响应。有些兼容上游不支持流式、
 * 直接回一个 chat.completion JSON；把 JSON 原样发回，严格客户端会当成无效
 * SSE 事件丢弃——表现为「调用成功、日志正常、客户端零输出」。
 */
class StreamRequestedJsonReturnedTest {

    private val completion = """
        {"id":"c1","object":"chat.completion","created":1,"model":"m",
         "choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}
    """.trimIndent()

    private inner class JsonOnlyProvider : Provider {
        override val id = "jsononly"
        override val displayName = "只回 JSON 的上游"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            AggregatedChatCall(200, completion)
    }

    private fun withServer(block: (port: Int) -> Unit) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(JsonOnlyProvider())
        engine.pool.upsert(ProviderAccount("jsononly", "u1", "账号", "{}"))
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `流式请求收到 JSON 响应时客户端仍能拿到内容`() {
        withServer { port ->
            val body = """{"model":"jsononly/m","stream":true,"messages":[{"role":"user","content":"hi"}]}"""
            val conn = java.net.URL("http://127.0.0.1:$port/v1/chat/completions")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer test")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            assertEquals(200, conn.responseCode)
            assertEquals("text/event-stream", conn.contentType.substringBefore(";").trim())

            val raw = conn.inputStream.bufferedReader().readText()
            val events = raw.split("\n\n").filter { it.isNotBlank() }
            assertTrue(events.isNotEmpty(), "必须有事件，实际：$raw")
            val collected = StringBuilder()
            for (event in events) {
                val data = event.lines().firstOrNull { it.startsWith("data: ") } ?: continue
                if (data.endsWith("[DONE]")) break
                val chunk = com.google.gson.JsonParser.parseString(data.removePrefix("data: ").trim()).asJsonObject
                chunk.getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("delta").get("content")?.asString?.let { collected.append(it) }
            }
            assertEquals("你好", collected.toString(), "客户端按 SSE 解析应拿到 JSON 里的内容，原始流：$raw")
        }
    }
}
