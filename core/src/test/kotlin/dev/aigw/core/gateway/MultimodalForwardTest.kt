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
 * 多模态（识图）请求的透传回归测试。
 *
 * 调用方按 OpenAI 规范发 `content: [{type:"text",...},{type:"image_url",...}]`，
 * 网关必须原样把数组交给 provider，不能在公共层把它压扁或丢弃。
 */
class MultimodalForwardTest {

    /** 记录收到的请求体；回一个合法 completion（网关会拒绝零内容回复）。 */
    private inner class EchoProvider : Provider {
        var lastBody: String = ""
        override val id = "echo"
        override val displayName = "回显上游"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            lastBody = openAiBody
            return AggregatedChatCall(
                200,
                "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\"," +
                    "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
            )
        }
    }

    private fun withServer(block: (port: Int, provider: EchoProvider) -> Unit) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val provider = EchoProvider()
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount("echo", "u1", "回显账号", "{}"))
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort, provider)
        } finally {
            server.stop()
        }
    }

    private val visionBody = """
        {
          "model": "echo/vision",
          "messages": [
            {
              "role": "user",
              "content": [
                {"type": "text", "text": "这是什么"},
                {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgo="}}
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `多模态 content 数组原样透传给 provider`() {
        withServer { port, provider ->
            val connection = java.net.URL("http://127.0.0.1:$port/v1/chat/completions")
                .openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer test")
            connection.outputStream.use { it.write(visionBody.toByteArray(Charsets.UTF_8)) }

            assertEquals(200, connection.responseCode)
            val obj = com.google.gson.JsonParser.parseString(provider.lastBody).asJsonObject
            val content = obj.getAsJsonArray("messages")[0].asJsonObject.get("content")
            assertTrue(content.isJsonArray, "content 必须保持数组形式，实际：$content")
            assertEquals(2, content.asJsonArray.size(), "text + image_url 两部分都要在，实际：$content")
            assertEquals(
                "image_url",
                content.asJsonArray[1].asJsonObject.get("type").asString,
            )
        }
    }
}
