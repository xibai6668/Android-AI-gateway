package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 直通路径的 SSE 帧完整性回归测试。
 *
 * 网关的 streamResponse 读上游一行、写下游一行；若只补单个 \n，事件之间没有空行，
 * 严格按 SSE 规范实现的客户端（如 OpenAI SDK）会把整条流当成一个未结束的事件，
 * 表现为「调用成功、日志正常、客户端却一个字都收不到」。
 */
class SsePassthroughFramingTest {

    private val upstreamSse = buildString {
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}],\"usage\":null}\n\n")
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":null}\n\n")
        append("data: [DONE]\n\n")
    }

    private inner class PassthroughProvider : Provider {
        override val id = "pass"
        override val displayName = "直通上游"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            StreamingChatCall(200, ByteArrayInputStream(upstreamSse.toByteArray(Charsets.UTF_8)))
    }

    private fun withServer(block: (port: Int) -> Unit) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(PassthroughProvider())
        engine.pool.upsert(ProviderAccount("pass", "u1", "直通账号", "{}"))
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `客户端能从直通流里解析出完整事件`() {
        withServer { port ->
            val body = """{"model":"pass/m","stream":true,"messages":[{"role":"user","content":"hi"}]}"""
            val conn = java.net.URL("http://127.0.0.1:$port/v1/chat/completions")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer test")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            assertEquals(200, conn.responseCode)
            // 严格按 SSE 规范：事件以空行分隔，读到空行才是一次完整事件
            val raw = conn.inputStream.bufferedReader().readText()
            val events = raw.split("\n\n").filter { it.isNotBlank() }
            assertTrue(events.size >= 3, "应有至少 3 个事件（2 个 chunk + DONE），实际：$raw")
            assertTrue(events.last().contains("[DONE]"), "流必须以 [DONE] 事件结束，实际：$raw")
            val collected = StringBuilder()
            for (event in events) {
                val data = event.lines().firstOrNull { it.startsWith("data: ") } ?: continue
                if (data.endsWith("[DONE]")) break
                val chunk = com.google.gson.JsonParser.parseString(data.removePrefix("data: ").trim()).asJsonObject
                val content = chunk.getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("delta").get("content")?.asString
                content?.let { collected.append(it) }
            }
            assertEquals("你好", collected.toString(), "客户端按规范解析应还原全部内容，原始流：$raw")
        }
    }
}
