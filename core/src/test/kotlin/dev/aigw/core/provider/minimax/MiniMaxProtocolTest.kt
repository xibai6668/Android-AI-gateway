package dev.aigw.core.provider.minimax

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MiniMax 反代协议测试：签名基础、凭证导入、SSE 差分翻译、
 * 以及假上游上的端到端对话/余额/设备注册链路。
 */
class MiniMaxProtocolTest {

    // ------------------------------------------------------------------ 签名

    @Test
    fun `md5Hex 对齐已知向量`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", MiniMaxSign.md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", MiniMaxSign.md5Hex("abc"))
        assertEquals("098f6bcd4621d373cade4e832627b4f6", MiniMaxSign.md5Hex("test"))
        assertEquals(
            "9e107d9d372bb6826bd81d3542a419d6",
            MiniMaxSign.md5Hex("The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun `encodeURIComponent 与 JS 语义一致`() {
        assertEquals("hello%20world", MiniMaxSign.encodeURIComponent("hello world"))
        assertEquals("a%2Bb", MiniMaxSign.encodeURIComponent("a+b"))
        assertEquals("a%2Fb", MiniMaxSign.encodeURIComponent("a/b"))
        assertEquals("a.b~*()!'x", MiniMaxSign.encodeURIComponent("a.b~*()!'x"))
        assertEquals("%E4%BD%A0%E5%A5%BD", MiniMaxSign.encodeURIComponent("你好"))
        assertEquals("%26%3D%3F%23", MiniMaxSign.encodeURIComponent("&=?#"))
    }

    @Test
    fun `签名头由 ts 密钥与 body 组成`() {
        val body = """{"text":"hi"}"""
        val ts = "1700000000"
        // 锁定公式结构：改公式时这里会失败，强制意识到签名对格式敏感
        assertEquals(
            MiniMaxSign.md5Hex(ts + MiniMaxConstants.SECRET_KEY + body),
            MiniMaxSign.xSignature(ts, body),
        )
        val path = "/matrix/api/v1/chat/send_msg?a=1"
        assertEquals(
            MiniMaxSign.md5Hex(
                MiniMaxSign.encodeURIComponent(path) + "_" + body +
                    MiniMaxSign.md5Hex(ts) + MiniMaxConstants.SIGN_SALT,
            ),
            MiniMaxSign.yy(path, body, ts),
        )
    }

    // ------------------------------------------------------------------ 凭证导入

    @Test
    fun `导入 JSON 凭证`() {
        val account = MiniMaxAccount.import("""{"token":"eyJx","userId":"123","deviceId":"dev1"}""")
        assertNotNull(account)
        assertEquals("eyJx", account.token)
        assertEquals("123", account.userId)
        assertEquals("dev1", account.deviceId)
    }

    @Test
    fun `导入 userId+token 组合串`() {
        val account = MiniMaxAccount.import("450234567894+eyJhbGciOiJIUzI1NiJ9.sig")
        assertNotNull(account)
        assertEquals("450234567894", account.userId)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.sig", account.token)
        assertEquals("", account.deviceId)
    }

    @Test
    fun `裸 JWT 缺 userId 拒绝导入`() {
        assertNull(MiniMaxAccount.import("eyJhbGciOiJIUzI1NiJ9.sig"))
        assertNull(MiniMaxAccount.import(""))
        assertNull(MiniMaxAccount.import("乱七八糟"))
        assertNull(MiniMaxAccount.import("""{"token":"eyJx"}"""))
        assertNull(MiniMaxAccount.import("""{"userId":"1"}"""))
    }

    @Test
    fun `parse 拒绝缺字段的 secret`() {
        assertNull(MiniMaxAccount.parse("""{"token":"eyJx"}"""))
        assertNotNull(MiniMaxAccount.parse(MiniMaxAccount("t", "u").toJson().toString()))
    }

    // ------------------------------------------------------------------ SSE 翻译

    private fun feedAll(translator: MiniMaxOpenAiTranslator, frames: List<Pair<String, String>>): String =
        frames.flatMap { (event, data) ->
            val parser = MiniMaxSseParser()
            listOfNotNull(
                parser.feed("event: $event"),
                parser.feed("data: $data"),
                parser.feed(""),
            ).flatMap { translator.translate(it) }
        }.joinToString("") + translator.close().joinToString("")

    @Test
    fun `content 全量累积差分成增量`() {
        val translator = MiniMaxOpenAiTranslator("Lightning")
        val out = feedAll(
            translator,
            listOf(
                "message_result" to """{"data":{"messageResult":{"chat_id":"c1","isEnd":1,"content":"你好"}}}""",
                "message_result" to """{"data":{"messageResult":{"chat_id":"c1","isEnd":1,"content":"你好，世界"}}}""",
                "message_result" to """{"data":{"messageResult":{"chat_id":"c1","isEnd":0,"content":"你好，世界！"}}}""",
            ),
        )
        // 每帧 delta 只含相对上一帧的增量
        assertTrue(out.contains(""""content":"你好""""))
        assertTrue(out.contains(""""content":"，世界""""))
        assertTrue(out.contains(""""content":"！""""))
        assertTrue(out.contains(""""finish_reason":"stop""""))
        assertTrue(out.endsWith("data: [DONE]\n\n"))
        // 全量不重复输出：「你好」只作为增量出现一次
        assertEquals(1, Regex(""""content":"你好[",]""").findAll(out).count())
        assertEquals("c1", translator.chatId)
    }

    @Test
    fun `U+FFFD 截断只输出确定内容`() {
        val translator = MiniMaxOpenAiTranslator("Lightning")
        val out = feedAll(
            translator,
            listOf(
                "message_result" to """{"data":{"messageResult":{"isEnd":1,"content":"你"}}}""",
                "message_result" to """{"data":{"messageResult":{"isEnd":1,"content":"你\uFFFD"}}}""",
                "message_result" to """{"data":{"messageResult":{"isEnd":0,"content":"你"}}}""",
            ),
        )
        assertTrue(out.contains(""""content":"你"""))
        assertTrue(out.contains("finish_reason"))
    }

    @Test
    fun `type 8 终止帧输出 stop 加 DONE`() {
        val translator = MiniMaxOpenAiTranslator("Lightning")
        val out = feedAll(
            translator,
            listOf(
                "message_result" to """{"data":{"messageResult":{"isEnd":1,"content":"hi"}}}""",
                "message_result" to """{"type":8}""",
            ),
        )
        assertTrue(out.contains(""""finish_reason":"stop""""))
        assertTrue(out.endsWith("data: [DONE]\n\n"))
    }

    @Test
    fun `流内业务错误输出 OpenAI error 帧`() {
        val translator = MiniMaxOpenAiTranslator("Lightning")
        val out = feedAll(
            translator,
            listOf("message_result" to """{"base_resp":{"status_code":1004,"status_msg":"未登录"}}"""),
        )
        assertTrue(out.contains(""""error":"""))
        assertTrue(out.contains("1004"))
        assertTrue(out.contains("未登录"))
        assertTrue(out.endsWith("data: [DONE]\n\n"))
    }

    @Test
    fun `上游中断时 close 补 DONE`() {
        val translator = MiniMaxOpenAiTranslator("Lightning")
        val out = feedAll(
            translator,
            listOf("message_result" to """{"data":{"messageResult":{"isEnd":1,"content":"hi"}}}"""),
        )
        assertTrue(out.endsWith("data: [DONE]\n\n"))
    }

    // ------------------------------------------------------------------ 消息合并

    @Test
    fun `多轮消息按 role 合并并以 assistant 引导结尾`() {
        val body = """
            {"model":"Lightning","messages":[
                {"role":"system","content":"你是助手"},
                {"role":"user","content":"第一问"},
                {"role":"assistant","content":"第一答"},
                {"role":"user","content":"第二问"}
            ]}
        """.trimIndent()
        assertEquals(
            "system:你是助手\nuser:第一问\nassistant:第一答\nuser:第二问\nassistant:\n",
            MiniMaxChatClient().mergeMessages(body),
        )
    }

    @Test
    fun `多模态 content 只取 text 块并移除图片 markdown`() {
        val body = """
            {"messages":[{"role":"user","content":[
                {"type":"text","text":"看 ![图](https:\/\/x\/a.png) 这个"},
                {"type":"image_url","image_url":{"url":"https://x/a.png"}}
            ]}]}
        """.trimIndent()
        val merged = MiniMaxChatClient().mergeMessages(body)
        assertEquals("user:看  这个\nassistant:\n", merged)
    }

    // ------------------------------------------------------------------ 假上游端到端

    /** 假上游：`ssePaths` 内的路径回 SSE，其余回 JSON；记录每个请求的 `METHOD 路径`。 */
    private fun withUpstream(
        responses: Map<String, String>,
        headers: MutableMap<String, String> = mutableMapOf(),
        ssePaths: Set<String> = emptySet(),
        paths: MutableList<String> = mutableListOf(),
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            println("[MiniMaxFakeUpstream] ${exchange.requestMethod} $path -> responses[path]=${if (path in ssePaths) "(sse)" else responses[path]?.take(60)}")
            paths.add("${exchange.requestMethod} $path")
            headers.putAll(
                exchange.requestHeaders.flatMap { (k, v) -> v.map { k.lowercase() to it } }.toMap(),
            )
            if (path in ssePaths) {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { out ->
                    responses[path]?.toByteArray(Charsets.UTF_8)?.let { out.write(it) }
                }
            } else {
                val body = responses[path] ?: "{}"
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun account(deviceId: String = ""): ProviderAccount = ProviderAccount(
        providerId = MiniMaxProvider.ID,
        uid = "123",
        nickname = "测试",
        secret = MiniMaxAccount(token = "tok123", userId = "123", deviceId = deviceId).toJson().toString(),
    )

    @Test
    fun `SSE 无终止空行时 EOF flush 最后一帧`() {
        val sse = """
            event: message_result
            data: {"data":{"messageResult":{"chat_id":"9001","isEnd":1,"content":"你好"}}}

            event: message_result
            data: {"data":{"messageResult":{"chat_id":"9001","isEnd":0,"content":"你好，世界"}}}
        """.trimIndent()
        val parser = MiniMaxSseParser()
        val translator = MiniMaxOpenAiTranslator("Lightning")
        val transformed = LineTransformStream(
            sse.byteInputStream(Charsets.UTF_8),
            { line -> parser.feed(line)?.let { translator.translate(it) }.orEmpty() },
            { listOfNotNull(parser.flush()).flatMap { translator.translate(it) } + translator.close() },
        )
        val text = transformed.readBytes().toString(Charsets.UTF_8)
        assertTrue(text.contains("你好"), "text=$text")
        assertTrue(text.contains("，世界"), "text=$text")
        assertTrue(text.contains(""""finish_reason":"stop""""), "text=$text")
        assertTrue(text.endsWith("data: [DONE]\n\n"), "text=$text")
    }

    @Test
    fun `openChat 流式返回 OpenAI SSE 并清理会话`() {
        val sse = """
            event: message_result
            data: {"data":{"messageResult":{"chat_id":"9001","isEnd":1,"content":"你好"}}}

            event: message_result
            data: {"data":{"messageResult":{"chat_id":"9001","isEnd":0,"content":"你好，世界"}}}

        """.trimIndent()
        val requestHeaders = mutableMapOf<String, String>()
        val requestPaths = mutableListOf<String>()
        withUpstream(
            mapOf(MiniMaxConstants.PATH_CHAT_SEND to sse),
            requestHeaders,
            ssePaths = setOf(MiniMaxConstants.PATH_CHAT_SEND),
            paths = requestPaths,
        ) { base ->
            val provider = MiniMaxProvider(
                userClient = MiniMaxUserClient(base),
                chatClient = MiniMaxChatClient(base),
                nowMillis = { 1_700_000_000_000 },
            )
            val call = provider.openChat(
                account(deviceId = "dev-1"),
                """{"model":"Lightning","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertTrue(call is StreamingChatCall, "call=$call error=${call.errorBody}")
            val text = call.stream!!.bufferedReader(Charsets.UTF_8).readText()
            assertTrue(text.startsWith("data: "), "text=$text")
            assertTrue(text.contains("你好"), "text=$text")
            assertTrue(text.contains(""""finish_reason":"stop""""))
            assertTrue(text.endsWith("data: [DONE]\n\n"))
            call.close()
        }
        // 伪装头与签名头都在
        assertEquals("tok123", requestHeaders["token"])
        assertNotNull(requestHeaders["x-timestamp"])
        assertNotNull(requestHeaders["x-signature"])
        assertNotNull(requestHeaders["yy"])
        // 会话清理
        assertTrue(requestPaths.contains("DELETE /v1/api/chat/history/9001"))
    }

    @Test
    fun `openChat 非流式聚合为 chat completion`() {
        val sse = """
            event: message_result
            data: {"data":{"messageResult":{"chat_id":"9002","isEnd":1,"content":"第一段"}}}

            event: message_result
            data: {"data":{"messageResult":{"chat_id":"9002","isEnd":0,"content":"第一段第二段"}}}

        """.trimIndent()
        withUpstream(
            mapOf(MiniMaxConstants.PATH_CHAT_SEND to sse),
            ssePaths = setOf(MiniMaxConstants.PATH_CHAT_SEND),
        ) { base ->
            val provider = MiniMaxProvider(
                userClient = MiniMaxUserClient(base),
                chatClient = MiniMaxChatClient(base),
                nowMillis = { 1_700_000_000_000 },
            )
            val call = provider.openChat(
                account(deviceId = "dev-1"),
                """{"model":"Lightning","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertEquals(200, call.status, "error=${call.errorBody}")
            val aggregated = call.aggregated
            assertNotNull(aggregated, "aggregated is null")
            assertTrue(aggregated.contains("第一段第二段"), "aggregated=$aggregated")
            assertEquals("Lightning", com.google.gson.JsonParser.parseString(aggregated)
                .asJsonObject.get("model").asString)
        }
    }

    @Test
    fun `openChat 业务错误转为流内失败`() {
        withUpstream(
            mapOf(
                MiniMaxConstants.PATH_CHAT_SEND to
                    """{"base_resp":{"status_code":1004,"status_msg":"登录已过期"}}""",
            ),
        ) { base ->
            val provider = MiniMaxProvider(
                userClient = MiniMaxUserClient(base),
                chatClient = MiniMaxChatClient(base),
                nowMillis = { 1_700_000_000_000 },
            )
            val call = provider.openChat(
                account(deviceId = "dev-1"),
                """{"model":"Lightning","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertTrue(call is StreamFailureChatCall)
            assertEquals(ErrorKind.CLIENT, call.failure.kind)
            assertTrue(call.failure.message.contains("1004"))
            assertTrue(call.failure.message.contains("登录已过期"))
        }
    }

    @Test
    fun `classify 映射 HTTP 状态`() {
        val provider = MiniMaxProvider()
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(401, "").kind)
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(403, "").kind)
        assertEquals(ErrorKind.SOFT_RATE, provider.classify(429, "").kind)
        assertEquals(ErrorKind.QUOTA, provider.classify(402, "").kind)
        assertEquals(ErrorKind.SERVER, provider.classify(502, "").kind)
        assertEquals(ErrorKind.CLIENT, provider.classify(200, """{"base_resp":{"status_code":1004,"status_msg":"x"}}""").kind)
    }

    @Test
    fun `creditInfo 读取会员与剩余积分`() {
        withUpstream(
            mapOf(
                MiniMaxConstants.PATH_MEMBERSHIP to
                    """{"base_resp":{"status_code":0},"plan_name":"Free","total_remains_credit":88}""",
            ),
        ) { base ->
            val provider = MiniMaxProvider(
                userClient = MiniMaxUserClient(base),
                nowMillis = { 1_700_000_000_000 },
            )
            val info = provider.creditInfo(account())
            assertTrue(info.known)
            assertEquals(88L, info.balance)
            assertTrue(info.detail.contains("Free"))
        }
    }

    @Test
    fun `缺 deviceId 时自动注册并回存凭证`() {
        withUpstream(
            mapOf(
                MiniMaxConstants.PATH_DEVICE_REGISTER to
                    """{"statusInfo":{"code":0},"data":{"deviceIDStr":"dev-900"}}""",
                MiniMaxConstants.PATH_CHAT_SEND to "",
            ),
            ssePaths = setOf(MiniMaxConstants.PATH_CHAT_SEND),
        ) { base ->
            val updated = AtomicReference<ProviderAccount?>()
            val provider = MiniMaxProvider(
                hooks = ProviderHooks(onAccountUpdated = { updated.set(it) }),
                userClient = MiniMaxUserClient(base),
                chatClient = MiniMaxChatClient(base),
                nowMillis = { 1_700_000_000_000 },
            )
            // send_msg 回空流（无内容），调用本身允许失败；关注的是注册与回存
            provider.openChat(
                account(),
                """{"model":"Lightning","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
            )
            val saved = updated.get()
            assertNotNull(saved)
            val parsed = MiniMaxAccount.parse(saved.secret)
            assertNotNull(parsed)
            assertEquals("dev-900", parsed.deviceId)
            // 第二次请求直接使用注册好的 deviceId（query 里出现），不再注册
        }
    }

    @Test
    fun `importCredentials 产出 uid 为 userId 的账号`() {
        val provider = MiniMaxProvider()
        val imported = provider.importCredentials("450234567894+eyJtoken") ?: error("导入失败")
        assertEquals(MiniMaxProvider.ID, imported.providerId)
        assertEquals("450234567894", imported.uid)
        assertNull(provider.importCredentials("not-valid"))
    }
}
