package dev.aigw.core.provider.raccoon

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.ProviderAccount
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 小浣熊（商汤）协议转换回归。
 *
 * 上游把真正的 OpenAI 结构包在 `data` 里（外层 `{"status":..,"data":..}`），请求体
 * 上游 v2 是 LiteLLM 兼容层，`max_tokens` 等标准字段直接认（改名反而被 500 拒绝）。
 * 这里把关键转换锁住。
 */
class RaccoonProviderTest {

    private fun account() = ProviderAccount(
        providerId = RaccoonProvider.ID,
        uid = "u1",
        nickname = "测试",
        secret = """{"accessToken":"a.b.c","refreshToken":"r","expiresAt":0,"userId":"u1","nickname":"测试"}""",
    )

    @Test
    fun `登录 URL 带 redirect 且不带 login_source=desktop`() {
        val provider = RaccoonProvider(host = "https://xiaohuanxiong.com")
        val ticket = provider.beginWebLogin("http://127.0.0.1:51122/callback")
        assertTrue(ticket.loginUrl.startsWith("https://xiaohuanxiong.com/code/authorize?"), ticket.loginUrl)
        assertTrue(ticket.loginUrl.contains("redirect=http%3A%2F%2F127.0.0.1%3A51122%2Fcallback"), ticket.loginUrl)
        assertTrue(!ticket.loginUrl.contains("login_source=desktop"), "带 desktop 会走 office-raccoon:// 分支跳不回来：${ticket.loginUrl}")
    }

    @Test
    fun `内置快照含上游五个真实 model id`() {
        val ids = RaccoonProvider.FALLBACK_MODELS.map { it.id }.toSet()
        assertEquals(
            setOf(
                "raccoon-8c4485", "raccoon-19b265", "raccoon-405a1c",
                "raccoon-chat-ml-5-5", "sn-sensenova-6-8-flash-lite",
            ),
            ids,
        )
    }

    @Test
    fun `空或 auto 解析为默认模型`() {
        val provider = RaccoonProvider()
        assertEquals(RaccoonProvider.DEFAULT_MODEL, provider.resolveModel(""))
        assertEquals(RaccoonProvider.DEFAULT_MODEL, provider.resolveModel("auto"))
        assertEquals("raccoon-8c4485", provider.resolveModel("raccoon-8c4485"))
    }

    @Test
    fun `模型目录能从未知嵌套结构里找到列表`() {
        // 模拟上游把模型数组藏在 data.profiles.models 这种非标准键下
        val body = """{"status":{"code":0},"data":{"profiles":{"models":[
            {"id":"raccoon-8c4485","name":"Raccoon-Work","max_input_tokens":1000000},
            {"id":"raccoon-chat-ml-5-5","name":"Raccoon Chat"}
        ]}}}"""
        withUpstream(path = "/api/web/llm/v2/model_catalog", body = body) { base ->
            val view = RaccoonProvider(host = base).listModels(account())
            assertEquals(2, view.models.size, view.models.toString())
            assertEquals("raccoon-8c4485", view.models[0].id)
            assertEquals(1_000_000L, view.models[0].contextWindow)
        }
    }

    // ------------------------------------------------------------------ classify

    @Test
    fun `classify 状态码映射`() {
        val provider = RaccoonProvider()
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(401, "").kind)
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(403, "").kind)
        assertEquals(ErrorKind.QUOTA, provider.classify(402, "").kind)
        assertEquals(ErrorKind.NOT_FOUND, provider.classify(404, "").kind)
        assertEquals(ErrorKind.SOFT_RATE, provider.classify(429, "").kind)
        assertEquals(ErrorKind.SERVER, provider.classify(500, "").kind)
        assertEquals(ErrorKind.SERVER, provider.classify(503, "").kind)
        assertEquals(ErrorKind.CLIENT, provider.classify(400, "").kind)
        assertEquals(ErrorKind.CLIENT, provider.classify(418, "").kind)
    }

    @Test
    fun `classify 从响应体里取错误文案`() {
        val provider = RaccoonProvider()
        val err = provider.classify(401, """{"code":200003,"message":"authorization_verify_error","details":"authorization verify failed"}""")
        assertEquals(ErrorKind.SESSION_DEAD, err.kind)
        assertEquals("authorization_verify_error", err.message)
    }

    // ------------------------------------------------------------------ 请求体转换

    private fun prepare(body: String) = JsonParser.parseString(prepareRaccoonBody(body)).asJsonObject

    @Test
    fun `max_tokens 保持标准名（改名会被上游拒绝）`() {
        val obj = prepare("""{"model":"raccoon-chat","max_tokens":128,"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(128L, obj.get("max_tokens").asLong)
        assertTrue(!obj.has("max_new_tokens"), "上游 v2 不认 max_new_tokens")
    }

    @Test
    fun `采样参数同名保留且 stream 保留`() {
        val obj = prepare(
            """{"model":"m","temperature":0.7,"top_p":0.9,"frequency_penalty":0.1,"presence_penalty":0.2,"stream":true,"stop":["x"]}""",
        )
        assertEquals(0.7, obj.get("temperature").asDouble, 1e-9)
        assertEquals(0.9, obj.get("top_p").asDouble, 1e-9)
        assertEquals(0.1, obj.get("frequency_penalty").asDouble, 1e-9)
        assertEquals(0.2, obj.get("presence_penalty").asDouble, 1e-9)
        assertTrue(obj.get("stream").asBoolean)
        assertEquals("x", obj.getAsJsonArray("stop")[0].asString)
    }

    @Test
    fun `有 tools 时原样映射并带 tool_choice auto`() {
        val tools = """[{"type":"function","function":{"name":"f","description":"d","parameters":{"type":"object"}}}]"""
        val obj = prepare("""{"model":"m","messages":[],"tools":$tools}""")
        assertEquals(1, obj.getAsJsonArray("tools").size())
        assertEquals("f", obj.getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function").get("name").asString)
        assertEquals("auto", obj.get("tool_choice").asString)
    }

    @Test
    fun `无 tools 时不加 tool_choice 且非法 JSON 原样返回`() {
        val obj = prepare("""{"model":"m","messages":[]}""")
        assertTrue(!obj.has("tool_choice"))
        assertEquals("not-json", prepareRaccoonBody("not-json"))
    }

    @Test
    fun `客户端非流式请求也被强制上游流式`() {
        val obj = prepare("""{"model":"m","stream":false,"messages":[]}""")
        assertTrue(obj.get("stream").asBoolean, "上游拒绝非流式，必须强制 stream:true")
    }

    @Test
    fun `未传 stop 时补官方默认停止符且带 n=1`() {
        val obj = prepare("""{"model":"m","messages":[]}""")
        assertEquals("<|endofmessage|>", obj.get("stop").asString)
        assertEquals(1, obj.get("n").asInt)
    }

    @Test
    fun `web 通道 delta 为字符串时翻译成 content`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        val out = translator.translate(
            """data: {"status":{"code":0},"data":{"id":"abc","choices":[{"index":0,"delta":"你好"}]}}""",
        ).joinToString("")
        assertTrue(out.contains("\"content\":\"你好\""), out)
        assertTrue(out.contains("\"id\":\"abc\""), "应沿用上游帧 id：$out")
        assertTrue(out.contains("\"object\":\"chat.completion.chunk\""), out)
    }

    @Test
    fun `web 通道 status 非 0 时转错误帧`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        val out = translator.translate(
            """data: {"status":{"code":200001,"message":"authorization_empty_error"}}""",
        ).joinToString("")
        assertTrue(out.contains("\"error\""), out)
        assertTrue(out.contains("authorization_empty_error"), out)
    }

    @Test
    fun `web 通道非流式 delta 字符串包成 message`() {
        val body = """{"status":{"code":0},"data":{"id":"xyz","choices":[{"index":0,"delta":"答复","finish_reason":"stop"}]}}"""
        withUpstream(body = body) { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            val aggregated = assertNotNull(call.aggregated)
            val obj = JsonParser.parseString(aggregated).asJsonObject
            assertEquals("xyz", obj.get("id").asString)
            val message = obj.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
            assertEquals("答复", message.get("content").asString)
        }
    }

    @Test
    fun `上游未包裹的 SSE 帧也能翻译`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        val out = translator.translate(
            """data: {"choices":[{"index":0,"delta":{"content":"裸"},"finish_reason":"stop"}]}""",
        ).joinToString("")
        assertTrue(out.contains("\"content\":\"裸\""), out)
        assertTrue(out.contains("\"object\":\"chat.completion.chunk\""), out)
    }

    // ------------------------------------------------------------------ SSE 翻译

    @Test
    fun `上游 SSE 翻译成 OpenAI SSE 并以 DONE 结尾`() {
        val translator = RaccoonSseTranslator("chatcmpl-test", "raccoon-chat", 1L)
        val out = ArrayList<String>()
        out += translator.translate(
            """data: {"status":{"code":0,"message":"ok"},"data":{"choices":[{"index":0,"delta":{"role":"assistant","content":"你"},"finish_reason":null}]}}""",
        )
        // 心跳行忽略
        out += translator.translate(": ping")
        out += translator.translate("")
        out += translator.translate(
            """data: {"status":{"code":0},"data":{"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":"stop"}]}}""",
        )
        out += translator.translate("data: [DONE]")

        val joined = out.joinToString("")
        assertTrue(joined.contains("\"object\":\"chat.completion.chunk\""), "必须是 OpenAI chunk：$joined")
        assertTrue(joined.contains("\"content\":\"你\""), "第一段内容缺失：$joined")
        assertTrue(joined.contains("\"content\":\"好\""), "第二段内容缺失：$joined")
        assertTrue(joined.contains("\"finish_reason\":\"stop\""), "收尾 chunk 应带 finish_reason：$joined")
        assertTrue(joined.endsWith("data: [DONE]\n\n"), "必须以 DONE 结尾：$joined")
        // 每帧都必须是 `data: ...\n\n` 形式
        out.filter { it.isNotEmpty() }.forEach {
            assertTrue(it.startsWith("data: ") && it.endsWith("\n\n"), "非法帧：$it")
        }
    }

    @Test
    fun `思维链 reasoning_content 透传`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        val out = translator.translate(
            """data: {"status":{"code":0},"data":{"choices":[{"index":0,"delta":{"reasoning_content":"想"},"finish_reason":null}]}}""",
        ).joinToString("")
        assertTrue(out.contains("\"reasoning_content\":\"想\""), out)
    }

    @Test
    fun `上游流内业务错误转成 OpenAI 错误帧并收尾`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        val out = translator.translate("""data: {"status":{"code":500001,"message":"quota exceeded"}}""")
        val joined = out.joinToString("")
        assertTrue(joined.contains("\"error\""), joined)
        assertTrue(joined.contains("quota exceeded"), joined)
        assertTrue(joined.endsWith("data: [DONE]\n\n"), joined)
        // 已收尾，close 不再重复补 DONE
        assertTrue(translator.close().isEmpty())
    }

    @Test
    fun `上游没发 DONE 时 close 补一个`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        translator.translate("""data: {"status":{"code":0},"data":{"choices":[{"index":0,"delta":{"content":"x"},"finish_reason":"stop"}]}}""")
        assertEquals(listOf("data: [DONE]\n\n"), translator.close())
    }

    // ------------------------------------------------------------------ 上游响应

    private fun withUpstream(
        status: Int = 200,
        contentType: String = "application/json",
        body: String = "",
        path: String = "/api/web/llm/v2/chat/completions",
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
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
    fun `非流式响应解包 data 里的 choices`() {
        val body = """{"status":{"code":0},"data":{"choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}]}}"""
        withUpstream(body = body) { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"raccoon-chat","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.failure)
            val aggregated = assertNotNull(call.aggregated)
            val obj = JsonParser.parseString(aggregated).asJsonObject
            assertEquals("chat.completion", obj.get("object").asString)
            assertEquals(
                "你好",
                obj.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString,
            )
        }
    }

    @Test
    fun `200 空 body 视为失败而不是空回复`() {
        withUpstream(body = "") { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.aggregated)
            val failure = assertNotNull(call.failure, "必须报失败")
            assertTrue(failure.message.contains("空响应"), failure.message)
            assertTrue(failure.message.contains("HTTP 200"), failure.message)
        }
    }

    @Test
    fun `200 但 data choices 为空视为失败`() {
        withUpstream(body = """{"status":{"code":0},"data":{"choices":[]}}""") { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.aggregated, "零内容不能当成功")
            assertNotNull(call.failure)
        }
    }

    @Test
    fun `客户端要流式而上游回 JSON 时包成 SSE`() {
        val body = """{"status":{"code":0},"data":{"choices":[{"index":0,"message":{"role":"assistant","content":"嗨"},"finish_reason":"stop"}]}}"""
        withUpstream(body = body) { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"m","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.failure)
            val sse = assertNotNull(call.stream).readBytes().toString(Charsets.UTF_8)
            assertTrue(sse.contains("\"object\":\"chat.completion.chunk\""), sse)
            assertTrue(sse.contains("嗨"), sse)
            assertTrue(sse.trimEnd().endsWith("data: [DONE]"), sse)
        }
    }

    @Test
    fun `流式 SSE 上游实时翻译`() {
        val body = buildString {
            append("""data: {"status":{"code":0},"data":{"choices":[{"index":0,"delta":{"role":"assistant","content":"嗨"},"finish_reason":null}]}}""")
            append("\n\n")
            append(": ping\n\n")
            append("""data: {"status":{"code":0},"data":{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}}""")
            append("\n\n")
            append("data: [DONE]\n\n")
        }
        withUpstream(contentType = "text/event-stream", body = body) { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"m","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.failure)
            val sse = assertNotNull(call.stream).readBytes().toString(Charsets.UTF_8)
            assertTrue(sse.contains("\"content\":\"嗨\""), sse)
            assertTrue(sse.contains("\"finish_reason\":\"stop\""), sse)
            assertTrue(sse.trimEnd().endsWith("data: [DONE]"), sse)
        }
    }
}
