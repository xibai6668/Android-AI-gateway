package dev.aigw.core.provider.trae

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraePayloadTest {

    private fun prepare(json: String) = JsonParser.parseString(TraePayload.prepare(json)).asJsonObject

    @Test
    fun `强制流式并写入 function 与 config_name`() {
        val out = prepare("""{"model":"glm-5.2","stream":false,"messages":[]}""")
        assertEquals(true, out.get("stream").asBoolean)
        assertEquals("solo_work_lite", out.get("function").asString)
        assertEquals("glm-5.2", out.get("config_name").asString)
        assertEquals("glm-5.2", out.get("model").asString)
    }

    @Test
    fun `model 为空时用默认模型`() {
        val out = prepare("""{"messages":[]}""")
        assertEquals("glm-5.2", out.get("config_name").asString)
    }

    @Test
    fun `字符串 content 被包装成 text 数组`() {
        val out = prepare("""{"messages":[{"role":"user","content":"你好"},{"role":"system","content":"s"}]}""")
        val first = out.getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")
        assertEquals(1, first.size())
        assertEquals("text", first[0].asJsonObject.get("type").asString)
        assertEquals("你好", first[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `已是数组的 content 原样透传`() {
        val raw = """{"messages":[{"role":"user","content":[{"type":"text","text":"x"}]}]}"""
        val out = prepare(raw)
        val content = out.getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")
        assertEquals(1, content.size())
        assertEquals("x", content[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `tools 的 parameters 对象被序列化为字符串`() {
        val raw = """
            {"messages":[],"tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object","properties":{}}}}]}
        """.trimIndent()
        val out = prepare(raw)
        val tool = out.getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function")
        assertTrue(tool.get("parameters").isJsonPrimitive, "parameters 应为字符串")
        assertTrue(tool.get("parameters").asString.contains("\"type\":\"object\""))
    }

    @Test
    fun `tool_choice 为 none 时连同 tools 一起删掉`() {
        val raw = """{"messages":[],"tool_choice":"none","tools":[{"type":"function","function":{"name":"f"}}]}"""
        val out = prepare(raw)
        assertNull(out.get("tool_choice"))
        assertNull(out.get("tools"))
    }

    @Test
    fun `tool_choice 对象被归一化为字符串`() {
        val auto = prepare("""{"messages":[],"tool_choice":{"type":"auto"}}""")
        assertEquals("auto", auto.get("tool_choice").asString)

        val named = prepare(
            """{"messages":[],"tool_choice":{"type":"function","function":{"name":"do_it"}}}""",
        )
        assertEquals("do_it", named.get("tool_choice").asString)
    }

    @Test
    fun `assistant 的 tool_calls 被改写成 function_call`() {
        val raw = """
            {"messages":[{"role":"assistant","content":null,
              "tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}}]}]}
        """.trimIndent()
        val out = prepare(raw)
        val call = out.getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("tool_calls")[0].asJsonObject
        assertNull(call.get("function"), "OpenAI 的 function 字段应被移除")
        assertNotNull(call.getAsJsonObject("function_call"))
        assertEquals("f", call.getAsJsonObject("function_call").get("name").asString)
    }

    @Test
    fun `没有名字的 tool_call 被剔除`() {
        val raw = """
            {"messages":[{"role":"assistant",
              "tool_calls":[{"id":"c1","type":"function","function":{"arguments":"{}"}}]}]}
        """.trimIndent()
        val out = prepare(raw)
        assertNull(out.getAsJsonArray("messages")[0].asJsonObject.get("tool_calls"))
    }

    @Test
    fun `非法 JSON 原样返回`() {
        assertEquals("not json", TraePayload.prepare("not json"))
    }
}

class TraeSseParserTest {

    private fun parseAll(text: String): List<SoloEvent> {
        val parser = SoloSseParser()
        val events = ArrayList<SoloEvent>()
        for (line in text.split('\n')) {
            parser.feed(line)?.let { events.add(it) }
        }
        return events
    }

    @Test
    fun `解析 output 事件`() {
        val events = parseAll(
            """
            id:1
            event:metadata
            data:{"session_id":"s1"}

            id:2
            event:output
            data:{"response":"你","reasoning_content":"想","tool_calls":null}

            event:done
            data:{"finish_reason":"stop"}

            """.trimIndent(),
        )
        assertEquals(3, events.size)
        assertTrue(events[0] is SoloEvent.Named)
        val output = events[1] as SoloEvent.Output
        assertEquals("你", output.response)
        assertEquals("想", output.reasoning)
        assertEquals("stop", (events[2] as SoloEvent.Done).finishReason)
    }

    @Test
    fun `多行 data 被拼接`() {
        val events = parseAll("event:output\ndata:{\"resp\ndata:onse\":\"x\"}\n\n")
        assertEquals(1, events.size)
        assertEquals("x", (events[0] as SoloEvent.Output).response)
    }

    @Test
    fun `error 事件带 code 与 message`() {
        val events = parseAll("event:error\ndata:{\"code\":1005,\"message\":\"plan\"}\n\n")
        val failure = events[0] as SoloEvent.Failure
        assertEquals(1005L, failure.code)
        assertEquals("plan", failure.message)
        assertEquals(TraeErrorKind.PLAN_LIMIT, SoloStreamError(failure.code, failure.message).kind)
    }

    @Test
    fun `没有 event 行的空行不产出事件`() {
        assertEquals(0, parseAll("\n\ndata:{\"response\":\"x\"}\n\n").size)
    }
}

class OpenAiSseTranslatorTest {

    @Test
    fun `output 转成 delta chunk`() {
        val translator = OpenAiSseTranslator("id1", "glm-5.2", created = 1)
        val chunks = translator.translate(SoloEvent.Output("你好", "思考", null))
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].startsWith("data: "))
        val chunk = JsonParser.parseString(chunks[0].removePrefix("data: ").trim()).asJsonObject
        assertEquals("chat.completion.chunk", chunk.get("object").asString)
        assertEquals("glm-5.2", chunk.get("model").asString)
        val delta = chunk.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
        assertEquals("你好", delta.get("content").asString)
        assertEquals("思考", delta.get("reasoning_content").asString)
    }

    @Test
    fun `空 output 不产出 chunk`() {
        val translator = OpenAiSseTranslator("id1", "m")
        assertTrue(translator.translate(SoloEvent.Output("", "", null)).isEmpty())
    }

    @Test
    fun `done 收尾并补 DONE，且 usage 附在最后一个 chunk 上`() {
        val translator = OpenAiSseTranslator("id1", "m")
        translator.translate(SoloEvent.Usage(JsonParser.parseString("""{"total_tokens":7}""").asJsonObject))
        val chunks = translator.translate(SoloEvent.Done("stop"))
        assertEquals(2, chunks.size)
        val last = JsonParser.parseString(chunks[0].removePrefix("data: ").trim()).asJsonObject
        assertEquals("stop", last.getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString)
        assertEquals(7, last.getAsJsonObject("usage").get("total_tokens").asInt)
        assertEquals("data: [DONE]\n\n", chunks[1])
        assertTrue(translator.close().isEmpty(), "已经写过 DONE 就不应再补")
    }

    @Test
    fun `上游中断时 close 补 DONE`() {
        val translator = OpenAiSseTranslator("id1", "m")
        assertEquals(listOf("data: [DONE]\n\n"), translator.close())
    }

    @Test
    fun `tool_call 的 function_call 字段被改名为 function`() {
        val translator = OpenAiSseTranslator("id1", "m")
        val raw = JsonParser.parseString(
            """[{"index":0,"id":"c1","type":"function","function_call":{"name":"f","namespace":"n","partial_arguments":"p","arguments":"{}"}}]""",
        )
        val chunks = translator.translate(SoloEvent.Output("", "", raw))
        val delta = JsonParser.parseString(chunks[0].removePrefix("data: ").trim()).asJsonObject
            .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
        val call = delta.getAsJsonArray("tool_calls")[0].asJsonObject
        assertNull(call.get("function_call"))
        val fn = call.getAsJsonObject("function")
        assertEquals("f", fn.get("name").asString)
        assertNull(fn.get("namespace"))
        assertNull(fn.get("partial_arguments"))
    }
}

class OpenAiAggregatorTest {

    @Test
    fun `聚合正文、思维链与用量`() {
        val aggregator = OpenAiAggregator("id1", "glm-5.2")
        aggregator.accept(SoloEvent.Output("Hel", "想", null))
        aggregator.accept(SoloEvent.Output("lo", "", null))
        aggregator.accept(SoloEvent.Usage(JsonParser.parseString("""{"prompt_tokens":1,"completion_tokens":2}""").asJsonObject))
        aggregator.accept(SoloEvent.Done("stop"))

        val out = aggregator.build()
        assertEquals("chat.completion", out.get("object").asString)
        val message = out.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
        assertEquals("Hello", message.get("content").asString)
        assertEquals("想", message.get("reasoning_content").asString)
        assertEquals(2, out.getAsJsonObject("usage").get("completion_tokens").asInt)
        assertEquals("stop", out.getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString)
        assertNull(aggregator.failure)
    }

    @Test
    fun `按 index 合并分片到达的 tool_call 并拼接 arguments`() {
        val aggregator = OpenAiAggregator("id1", "m")
        fun delta(index: Int, name: String?, arguments: String?) = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", index)
                addProperty("id", "c$index")
                addProperty("type", "function")
                add("function_call", JsonObject().apply {
                    if (name != null) addProperty("name", name)
                    if (arguments != null) addProperty("arguments", arguments)
                })
            })
        }
        aggregator.accept(SoloEvent.Output("", "", delta(0, "f", "{\"a\":")))
        aggregator.accept(SoloEvent.Output("", "", delta(0, null, "1}")))
        aggregator.accept(SoloEvent.Done("tool_calls"))

        val calls = aggregator.build().getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message").getAsJsonArray("tool_calls")
        assertEquals(1, calls.size())
        assertEquals("f", calls[0].asJsonObject.getAsJsonObject("function").get("name").asString)
        assertEquals("""{"a":1}""", calls[0].asJsonObject.getAsJsonObject("function").get("arguments").asString)
    }

    @Test
    fun `流内错误被记录`() {
        val aggregator = OpenAiAggregator("id1", "m")
        aggregator.accept(SoloEvent.Failure(1005, "plan"))
        assertNotNull(aggregator.failure)
        assertEquals(TraeErrorKind.PLAN_LIMIT, aggregator.failure!!.kind)
    }
}

class TraeErrorsTest {

    @Test
    fun `按状态码与业务 code 分类`() {
        assertEquals(TraeErrorKind.SESSION_DEAD, TraeErrors.fromStatus(401, ""))
        assertEquals(TraeErrorKind.NOT_FOUND, TraeErrors.fromStatus(404, ""))
        assertEquals(TraeErrorKind.SOFT_RATE, TraeErrors.fromStatus(429, ""))
        assertEquals(TraeErrorKind.SERVER, TraeErrors.fromStatus(503, ""))
        assertEquals(TraeErrorKind.PLAN_LIMIT, TraeErrors.fromStatus(400, """{"code":1005}"""))
        assertEquals(TraeErrorKind.PLAN_LIMIT, TraeErrors.fromStatus(400, """{"code":4008}"""))
        assertEquals(TraeErrorKind.SOFT_RATE, TraeErrors.fromStatus(400, """{"code":4011}"""))
        assertEquals(TraeErrorKind.SESSION_DEAD, TraeErrors.fromStatus(400, """{"code":1001}"""))
        assertEquals(TraeErrorKind.CLIENT, TraeErrors.fromStatus(400, """{"code":4001}"""))
    }

    @Test
    fun `能取出嵌套在 data 里的 code 与消息`() {
        assertEquals(1234L, TraeErrors.extractCode("""{"data":{"code":1234}}"""))
        assertEquals("模型不存在", TraeErrors.extractMessage("""{"data":{"message":"模型不存在"}}"""))
        assertEquals("boom", TraeErrors.extractMessage("""{"msg":"boom"}"""))
    }
}

class TraeAccountTest {

    @Test
    fun `解析嵌套形凭证`() {
        val raw = """
            {"auth":{"accessToken":"at","refreshToken":"rt","expiresAt":100,"domain":"trae.cn",
             "apiHost":"https://api.trae.com.cn","machineId":"m","deviceId":"d"},
             "account":{"uid":"u1","enterpriseId":"e1","nickname":"白"}}
        """.trimIndent()
        val account = TraeAccount.parse(raw)
        assertEquals("at", account.accessToken)
        assertEquals("rt", account.refreshToken)
        assertEquals("u1", account.uid)
        assertEquals("白", account.nickname)
        assertEquals(100L, account.expiresAt)
    }

    @Test
    fun `解析扁平形凭证`() {
        val account = TraeAccount.parse("""{"accessToken":"at","refreshToken":"rt","uid":"u2"}""")
        assertEquals("at", account.accessToken)
        assertEquals("u2", account.uid)
    }

    @Test
    fun `缺 accessToken 时抛错`() {
        assertFails {
            TraeAccount.parse("""{"uid":"u1"}""")
        }
    }

    @Test
    fun `落盘后能原样读回`() {
        val account = TraeAccount("u1", "at", "rt", 500, "白", "e1", "m", "d")
        val restored = TraeAccount.parse(account.toJson().toString())
        assertEquals(account, restored)
    }

    @Test
    fun `过期判断`() {
        val account = TraeAccount("u", "at", "rt", expiresAt = 1000, nickname = "", enterpriseId = "", machineId = "", deviceId = "")
        assertTrue(account.needsRefresh(withinSeconds = 100, nowSeconds = 950))
        assertTrue(!account.needsRefresh(withinSeconds = 10, nowSeconds = 900))
        assertTrue(account.copy(expiresAt = 0).needsRefresh(0, 0), "未知过期时间视为需要刷新")
    }
}

class TraeLoginTest {

    @Test
    fun `登录链接包含全部必需参数`() {
        val url = TraeLogin.buildLoginUrl("aa".repeat(16), "bb".repeat(16), "http://127.0.0.1:8788/authorize")
        assertTrue(url.startsWith("https://www.trae.cn/authorization?"))
        for (key in listOf(
            "client_id=", "auth_from=solo", "auth_type=local", "login_channel=native_ide",
            "login_version=1", "redirect=0", "login_trace_id=", "auth_callback_url=",
            "machine_id=", "device_id=", "x_machine_id=", "x_device_id=", "x_app_type=stable",
        )) {
            assertTrue(url.contains(key), "缺少参数 $key")
        }
    }

    @Test
    fun `trace id 取 machine 与 device 拼接的末 16 位`() {
        // device 长 4 位时，trace id 会横跨 machine 尾部与 device
        val machine = "x".repeat(20) + "111111111111"
        assertEquals("1111111111112222", TraeLogin.machineTraceId(machine, "2222"))
        // 不足 16 位时左侧补 0
        assertEquals("00000000000000ab", TraeLogin.machineTraceId("a", "b"))
    }

    @Test
    fun `回调优先使用 refreshToken 并解析用户信息`() {
        val userInfo = java.net.URLEncoder.encode(
            """{"UserID":"u1","ScreenName":"白","TenantID":"t1"}""",
            "UTF-8",
        )
        val account = TraeLogin.parseCallback("http://127.0.0.1:8788/authorize?refreshToken=rt1&userInfo=$userInfo")
        assertEquals("rt1", account.refreshToken)
        assertEquals("u1", account.uid)
        assertEquals("白", account.nickname)
        assertEquals("t1", account.enterpriseId)
    }

    @Test
    fun `缺少 refreshToken 时回退 userJwt`() {
        val userJwt = java.net.URLEncoder.encode(
            """{"Token":"at1","RefreshToken":"rt2","TokenExpireAt":1786847930141}""",
            "UTF-8",
        )
        val account = TraeLogin.parseCallback("http://127.0.0.1:8788/authorize?userJwt=$userJwt")
        assertEquals("rt2", account.refreshToken)
    }

    @Test
    fun `没有 refreshToken 时用 userJwt 的 Token 兜底`() {
        val userJwt = java.net.URLEncoder.encode(
            """{"Token":"at1","TokenExpireAt":1786847930141}""",
            "UTF-8",
        )
        val account = TraeLogin.parseCallback("http://127.0.0.1:8788/authorize?userJwt=$userJwt")
        assertEquals("", account.refreshToken)
        assertEquals("at1", account.accessToken)
        assertEquals(1786847930L, account.expiresAt, "毫秒应被归一化为秒")
    }

    @Test
    fun `昵称乱码可回转，回转失败时回退默认名`() {
        val mojibake = String("白".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals("白", TraeLogin.cleanNickname(mojibake, "u1"))
        assertEquals("白", TraeLogin.cleanNickname(java.net.URLEncoder.encode(mojibake, "UTF-8"), "u1"))
        assertEquals("用户5678", TraeLogin.cleanNickname("", "12345678"))
    }

    @Test
    fun `回调缺少凭证时抛错`() {
        assertFails {
            TraeLogin.parseCallback("http://127.0.0.1:8788/authorize?foo=1")
        }
    }
}

class TraeModelCatalogTest {

    @Test
    fun `解析模型目录响应`() {
        val raw = """
            {"config_info_list":[
              {"config_name":"glm-5.2","display_config":{"display_name":"GLM 5.2"}},
              {"config_name":"kimi-k3","display_config":{"display_name":"Kimi K3"}},
              {"config_name":""}
            ]}
        """.trimIndent()
        val models = TraePayloadModels.parse(raw)
        assertEquals(2, models.size)
        assertEquals("glm-5.2", models[0].id)
        assertEquals("GLM 5.2", models[0].name)
    }

    @Test
    fun `没有账号时回退内置清单`() {
        val catalog = TraeModelCatalog(TraeChatClient(TraeVersion()))
        val models = catalog.models(null)
        assertTrue(models.isNotEmpty())
        assertTrue(catalog.fromFallback)
        assertTrue(models.none { TraeModelCatalog.isInternal(it.id) }, "内置清单不应含内部条目")
    }

    @Test
    fun `请求体包含 function 与 poly_prompt`() {
        val body = JsonParser.parseString(TraePayloadModels.requestBody()).asJsonObject
        assertEquals("solo_work_lite", body.get("function").asString)
        assertEquals(true, body.get("poly_prompt").asBoolean)
        assertTrue(body.has("config_names"))
    }
}

class TraeAuthClientTest {

    @Test
    fun `过期时间优先取 TokenExpireAt 并归一化为秒`() {
        assertEquals(1786847930L, TraeAuthClient.expiresAtOf(1786847930141L, 0, 0))
        assertEquals(200L, TraeAuthClient.expiresAtOf(0, 100, 100))
        assertEquals(0L, TraeAuthClient.expiresAtOf(0, 0, 0))
    }
}
