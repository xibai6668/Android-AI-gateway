package dev.aigw.core.protocol

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Anthropic Messages ⇄ OpenAI 协议互转。
 *
 * 重点是「往返不丢信息」与「结构对得上」：Claude Code 这类客户端会严格按字段读响应，
 * 少一个 content block 或多一层嵌套都会让它当成解析失败。
 */
class AnthropicMessagesTest {

    @Test
    fun `请求：system 与消息形状会被翻成 OpenAI`() {
        val raw = """
            {"model":"m","max_tokens":128,"system":"你是助手","messages":[
              {"role":"user","content":"你好"}
            ]}
        """.trimIndent()
        val openAi = JsonParser.parseString(AnthropicMessages.toOpenAiRequest(raw)).asJsonObject
        val messages = openAi.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        assertEquals("system", messages[0].asJsonObject.get("role").asString)
        assertEquals("你是助手", messages[0].asJsonObject.get("content").asString)
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
        assertEquals(128, openAi.get("max_tokens").asInt)
    }

    @Test
    fun `请求：tool_use 与 tool_result 各自落到 OpenAI 形状`() {
        val raw = """
            {"model":"m","messages":[
              {"role":"user","content":"查天气"},
              {"role":"assistant","content":[
                {"type":"text","text":"我来查"},
                {"type":"tool_use","id":"tu_1","name":"get_weather","input":{"city":"上海"}}
              ]},
              {"role":"user","content":[
                {"type":"tool_result","tool_use_id":"tu_1","content":"晴"}
              ]}
            ]}
        """.trimIndent()
        val messages = JsonParser.parseString(AnthropicMessages.toOpenAiRequest(raw))
            .asJsonObject.getAsJsonArray("messages")

        val assistant = messages[1].asJsonObject
        assertEquals("我来查", assistant.get("content").asString)
        val call = assistant.getAsJsonArray("tool_calls")[0].asJsonObject
        assertEquals("tu_1", call.get("id").asString)
        assertEquals("get_weather", call.getAsJsonObject("function").get("name").asString)
        assertEquals(
            "上海",
            JsonParser.parseString(call.getAsJsonObject("function").get("arguments").asString)
                .asJsonObject.get("city").asString,
        )

        val toolResult = messages[2].asJsonObject
        assertEquals("tool", toolResult.get("role").asString)
        assertEquals("tu_1", toolResult.get("tool_call_id").asString)
        assertEquals("晴", toolResult.get("content").asString)
    }

    @Test
    fun `请求：tools 与 tool_choice 的字段名转换`() {
        val raw = """
            {"model":"m","messages":[],"tools":[
              {"name":"f","description":"d","input_schema":{"type":"object","properties":{}}}
            ],"tool_choice":{"type":"any"}}
        """.trimIndent()
        val openAi = JsonParser.parseString(AnthropicMessages.toOpenAiRequest(raw)).asJsonObject
        val tool = openAi.getAsJsonArray("tools")[0].asJsonObject
        assertEquals("function", tool.get("type").asString)
        assertEquals("f", tool.getAsJsonObject("function").get("name").asString)
        assertTrue(tool.getAsJsonObject("function").has("parameters"))
        // Anthropic 的 any 等价于 OpenAI 的 required
        assertEquals("required", openAi.get("tool_choice").asString)
    }

    @Test
    fun `请求：图片从 base64 源转成 data URL`() {
        val raw = """
            {"model":"m","messages":[{"role":"user","content":[
              {"type":"text","text":"看图"},
              {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}
            ]}]}
        """.trimIndent()
        val messages = JsonParser.parseString(AnthropicMessages.toOpenAiRequest(raw))
            .asJsonObject.getAsJsonArray("messages")
        val parts = messages[0].asJsonObject.getAsJsonArray("content")
        assertEquals("image_url", parts[1].asJsonObject.get("type").asString)
        assertEquals(
            "data:image/png;base64,AAAA",
            parts[1].asJsonObject.getAsJsonObject("image_url").get("url").asString,
        )
    }

    @Test
    fun `请求：不是 JSON 对象时返回 null`() {
        assertNull(AnthropicMessages.toOpenAiRequest("not-json"))
        assertNull(AnthropicMessages.toOpenAiRequest("[1,2]"))
    }

    @Test
    fun `响应：正文与工具调用都成为 content block`() {
        val openAi = """
            {"id":"c1","model":"m","choices":[{"message":{
              "role":"assistant","content":"好的",
              "tool_calls":[{"id":"t1","type":"function","function":{"name":"f","arguments":"{\"a\":1}"}}]
            },"finish_reason":"tool_calls"}],
            "usage":{"prompt_tokens":10,"completion_tokens":5}}
        """.trimIndent()

        val result = JsonParser.parseString(AnthropicMessages.toAnthropicResponse(openAi, "m")).asJsonObject
        assertEquals("message", result.get("type").asString)
        assertEquals("assistant", result.get("role").asString)
        assertEquals("tool_use", result.get("stop_reason").asString)

        val content = result.getAsJsonArray("content")
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertEquals("好的", content[0].asJsonObject.get("text").asString)
        assertEquals("tool_use", content[1].asJsonObject.get("type").asString)
        assertEquals("f", content[1].asJsonObject.get("name").asString)
        assertEquals(1, content[1].asJsonObject.getAsJsonObject("input").get("a").asInt)

        val usage = result.getAsJsonObject("usage")
        assertEquals(10, usage.get("input_tokens").asInt)
        assertEquals(5, usage.get("output_tokens").asInt)
    }

    @Test
    fun `响应：finish_reason 映射到 Anthropic 的 stop_reason`() {
        fun stopReason(finish: String): String {
            val body = """{"choices":[{"message":{"content":"x"},"finish_reason":"$finish"}]}"""
            return JsonParser.parseString(AnthropicMessages.toAnthropicResponse(body, "m"))
                .asJsonObject.get("stop_reason").asString
        }
        assertEquals("end_turn", stopReason("stop"))
        assertEquals("max_tokens", stopReason("length"))
        assertEquals("tool_use", stopReason("tool_calls"))
    }

    @Test
    fun `响应：上游没给 usage 时不编 token 数`() {
        val body = """{"choices":[{"message":{"content":"x"},"finish_reason":"stop"}]}"""
        val result = JsonParser.parseString(AnthropicMessages.toAnthropicResponse(body, "m")).asJsonObject
        assertFalse(result.has("usage"))
    }

    @Test
    fun `流式：先 message_start，再内容块，最后 message_stop`() {
        val translator = AnthropicMessages.SseTranslator("m")
        val first = translator.translate("""data: {"choices":[{"delta":{"role":"assistant"}}]}""")
        assertTrue(first.contains("event: message_start"))

        val text = translator.translate("""data: {"choices":[{"delta":{"content":"你"}}]}""")
        assertTrue(text.contains("event: content_block_start"))
        assertTrue(text.contains("event: content_block_delta"))
        assertTrue(text.contains("\"text\":\"你\""))

        val tail = translator.close()
        assertTrue(tail.contains("event: content_block_stop"))
        assertTrue(tail.contains("event: message_delta"))
        assertTrue(tail.contains("event: message_stop"))
        // 重复 close 不应重复发事件
        assertEquals("", translator.close())
    }

    @Test
    fun `流式：工具调用的参数增量翻成 input_json_delta`() {
        val translator = AnthropicMessages.SseTranslator("m")
        val start = translator.translate("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","function":{"name":"f","arguments":""}}]}}]}""")
        assertTrue(start.contains("content_block_start"))
        assertTrue(start.contains("tool_use"))

        val args = translator.translate("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"a\":1}"}}]}}]}""")
        assertTrue(args.contains("input_json_delta"))
        assertTrue(args.contains("partial_json"))

        val tail = translator.close()
        assertTrue(tail.contains("event: message_stop"))
    }

    @Test
    fun `流式：DONE 与空行不产生事件`() {
        val translator = AnthropicMessages.SseTranslator("m")
        assertEquals("", translator.translate("data: [DONE]"))
        assertEquals("", translator.translate(""))
        assertEquals("", translator.translate(": keep-alive"))
    }

    @Test
    fun `流式 chunk 带 usage null 不会抛异常`() {
        // 回归：线上 release 包报 "JsonNull cannot be cast to JsonObject"，
        // 因为 getAsJsonObject 是裸强转。标准 OpenAI 兼容服务的每个 chunk 都带 "usage": null。
        val translator = AnthropicMessages.SseTranslator("m")
        val out = translator.translate("""data: {"choices":[{"delta":{"content":"hi"}}],"usage":null}""")
        assertTrue(out.contains("content_block_delta"), out)
        assertTrue(out.contains("hi"), out)
    }

    @Test
    fun `delta 为 null 或缺失时正常跳过`() {
        val translator = AnthropicMessages.SseTranslator("m")
        // delta 为 null
        translator.translate("""data: {"choices":[{"delta":null}]}""")
        // choices 为 null
        translator.translate("""data: {"choices":null}""")
        // choices 为空数组
        translator.translate("""data: {"choices":[]}""")
        // 以上都不该抛异常；流仍能正常收尾
        assertTrue(translator.close().contains("message_stop"))
    }

    @Test
    fun `非流式响应里 usage 为 null 不抛异常`() {
        val body = """{"choices":[{"message":{"content":"x"},"finish_reason":"stop"}],"usage":null}"""
        val result = JsonParser.parseString(AnthropicMessages.toAnthropicResponse(body, "m")).asJsonObject
        assertFalse(result.has("usage"))
    }

    @Test
    fun `请求里 tools 项为 null 时被跳过`() {
        val raw = """{"model":"m","messages":[{"role":"user","content":"hi"}],"tools":[null,{"name":"f","input_schema":{"type":"object"}}]}"""
        val openAi = JsonParser.parseString(AnthropicMessages.toOpenAiRequest(raw)).asJsonObject
        val tools = openAi.getAsJsonArray("tools")
        assertEquals(1, tools.size(), "null 项应被跳过，只保留合法工具")
        assertEquals("f", tools[0].asJsonObject.getAsJsonObject("function").get("name").asString)
    }
}