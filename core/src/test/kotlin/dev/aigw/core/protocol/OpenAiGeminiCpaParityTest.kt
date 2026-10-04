package dev.aigw.core.protocol

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 从 CLIProxyAPI 移植的请求侧行为回归。
 */
class OpenAiGeminiCpaParityTest {

    @Test
    fun `functionCall 带 id 且 functionResponse 用 result 字符串配对`() {
        val body = """
            {"messages":[
              {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"loc\":\"北京\"}"}}]},
              {"role":"tool","tool_call_id":"call_1","name":"get_weather","content":"晴天"}
            ]}
        """.trimIndent()
        val request = OpenAiGemini.toGeminiRequest(body)
        val contents = request.getAsJsonArray("contents")
        val callPart = contents[0].asJsonObject.getAsJsonArray("parts")[0].asJsonObject
            .getAsJsonObject("functionCall")
        assertEquals("call_1", callPart.get("id").asString)
        assertEquals("get_weather", callPart.get("name").asString)
        assertEquals("北京", callPart.getAsJsonObject("args").get("loc").asString)

        val responsePart = contents[1].asJsonObject.getAsJsonArray("parts")[0].asJsonObject
            .getAsJsonObject("functionResponse")
        assertEquals("call_1", responsePart.get("id").asString)
        assertEquals("get_weather", responsePart.get("name").asString)
        // result 是字符串不是对象：解析成 JSON 对象会触发上游 400
        val result = responsePart.getAsJsonObject("response").get("result")
        assertTrue(result.isJsonPrimitive, "result 必须是字符串，实际：$result")
        assertEquals("晴天", result.asString)
    }

    @Test
    fun `assistant 思维链转成 thought part`() {
        val body = """
            {"messages":[
              {"role":"assistant","content":"答案","reasoning_content":"先想想"}
            ]}
        """.trimIndent()
        val request = OpenAiGemini.toGeminiRequest(body)
        val contents = request.getAsJsonArray("contents")
        assertEquals(1, contents.size(), "思维链与正文必须合并在同一个 model content 内，严防连续相同 role")
        val parts = contents[0].asJsonObject.getAsJsonArray("parts")
        assertEquals(2, parts.size())
        val thought = parts[0].asJsonObject
        assertEquals("先想想", thought.get("text").asString)
        assertEquals(true, thought.get("thought").asBoolean)
        assertEquals(OpenAiGemini.THOUGHT_SIGNATURE, thought.get("thoughtSignature").asString)
        val answer = parts[1].asJsonObject
        assertEquals("答案", answer.get("text").asString)
    }

    @Test
    fun `多工具调用响应聚合在同一个 user content 保持严格交替`() {
        val body = """
            {"messages":[
              {"role":"user","content":"查北京上海天气"},
              {"role":"assistant","content":"我来查","tool_calls":[
                {"id":"c1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}},
                {"id":"c2","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"上海\"}"}}
              ]},
              {"role":"tool","tool_call_id":"c1","name":"get_weather","content":"北京晴"},
              {"role":"tool","tool_call_id":"c2","name":"get_weather","content":"上海雨"}
            ]}
        """.trimIndent()
        val request = OpenAiGemini.toGeminiRequest(body)
        val contents = request.getAsJsonArray("contents")
        // user -> model -> user (含两个 functionResponse)
        assertEquals(3, contents.size())
        assertEquals("user", contents[0].asJsonObject.get("role").asString)
        assertEquals("model", contents[1].asJsonObject.get("role").asString)
        assertEquals("user", contents[2].asJsonObject.get("role").asString)

        val modelParts = contents[1].asJsonObject.getAsJsonArray("parts")
        assertEquals(3, modelParts.size(), "文本 + 2 个 functionCall")
        assertEquals("我来查", modelParts[0].asJsonObject.get("text").asString)
        assertEquals("c1", modelParts[1].asJsonObject.getAsJsonObject("functionCall").get("id").asString)
        assertEquals("c2", modelParts[2].asJsonObject.getAsJsonObject("functionCall").get("id").asString)

        val toolResponseParts = contents[2].asJsonObject.getAsJsonArray("parts")
        assertEquals(2, toolResponseParts.size(), "两个 tool 响应必须合并在同一个 user content 内")
        assertEquals("c1", toolResponseParts[0].asJsonObject.getAsJsonObject("functionResponse").get("id").asString)
        assertEquals("北京晴", toolResponseParts[0].asJsonObject.getAsJsonObject("functionResponse").getAsJsonObject("response").get("result").asString)
        assertEquals("c2", toolResponseParts[1].asJsonObject.getAsJsonObject("functionResponse").get("id").asString)
        assertEquals("上海雨", toolResponseParts[1].asJsonObject.getAsJsonObject("functionResponse").getAsJsonObject("response").get("result").asString)
    }

    @Test
    fun `默认挂 safetySettings 全 OFF`() {
        val request = OpenAiGemini.toGeminiRequest("""{"messages":[{"role":"user","content":"hi"}]}""")
        val settings = request.getAsJsonArray("safetySettings")
        assertEquals(5, settings.size())
        for (element in settings) {
            val setting = element.asJsonObject
            val threshold = setting.get("threshold").asString
            assertTrue(threshold == "OFF" || threshold == "BLOCK_NONE", "实际：$setting")
        }
    }

    @Test
    fun `envelope 带稳定 sessionId 且同会话复用`() {
        val request1 = OpenAiGemini.toGeminiRequest("""{"messages":[{"role":"user","content":"你好"},{"role":"assistant","content":"在"},{"role":"user","content":"继续"}]}""")
        val request2 = OpenAiGemini.toGeminiRequest("""{"messages":[{"role":"user","content":"你好"},{"role":"assistant","content":"在"},{"role":"user","content":"继续"}]}""")
        val env1 = JsonParser.parseString(OpenAiGemini.envelope("p", "gemini-3-pro", request1, "agent-1")).asJsonObject
        val env2 = JsonParser.parseString(OpenAiGemini.envelope("p", "gemini-3-pro", request2, "agent-2")).asJsonObject
        assertEquals("agent", env1.get("requestType").asString)
        assertEquals("antigravity", env1.get("userAgent").asString)
        val session1 = env1.getAsJsonObject("request").get("sessionId").asString
        val session2 = env2.getAsJsonObject("request").get("sessionId").asString
        assertTrue(session1.startsWith("-"), "sessionId 应为负数字串，实际：$session1")
        assertEquals(session1, session2, "同一会话内容应派生同一 sessionId")
    }

    @Test
    fun `toolChoice 映射到 functionCallingConfig`() {
        val request = OpenAiGemini.toGeminiRequest(
            """{"messages":[{"role":"user","content":"hi"}],"tool_choice":{"type":"function","function":{"name":"get_weather"}},"tools":[{"type":"function","function":{"name":"get_weather"}}]}""",
        )
        val config = request.getAsJsonObject("toolConfig").getAsJsonObject("functionCallingConfig")
        assertEquals("ANY", config.get("mode").asString)
        assertEquals("get_weather", config.getAsJsonArray("allowedFunctionNames")[0].asString)
    }

    @Test
    fun `toolChoice none 删除 tools`() {
        val request = OpenAiGemini.toGeminiRequest(
            """{"messages":[{"role":"user","content":"hi"}],"tool_choice":"none","tools":[{"type":"function","function":{"name":"f"}}]}""",
        )
        assertTrue(!request.has("tools"), "tool_choice=none 时 tools 应被删除")
        assertEquals("NONE", request.getAsJsonObject("toolConfig").getAsJsonObject("functionCallingConfig").get("mode").asString)
    }

    @Test
    fun `reasoning_effort 映射到 thinkingConfig`() {
        val request = OpenAiGemini.toGeminiRequest(
            """{"messages":[{"role":"user","content":"hi"}],"reasoning_effort":"high"}""",
        )
        val thinking = request.getAsJsonObject("generationConfig").getAsJsonObject("thinkingConfig")
        assertEquals("high", thinking.get("thinkingLevel").asString)
    }

    @Test
    fun `流式工具调用带 id 与 arguments 字符串`() {
        val translator = OpenAiGemini.SseTranslator("id", "m", created = 1)
        val line = """data: {"response":{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"loc":"北京"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}}"""
        val out = translator.translate(line)
        val chunk = JsonParser.parseString(out[0].removePrefix("data: ").trim()).asJsonObject
        val call = chunk.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
            .getAsJsonArray("tool_calls")[0].asJsonObject
        assertTrue(call.get("id").asString.startsWith("call-get_weather-"), "实际：${call}")
        assertEquals("get_weather", call.getAsJsonObject("function").get("name").asString)
        val finish = chunk.getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString
        assertEquals("tool_calls", finish, "出现工具调用时 finish_reason 应为 tool_calls")
    }

    @Test
    fun `usage 带 reasoning 与 cached 细分`() {
        val translator = OpenAiGemini.SseTranslator("id", "m", created = 1)
        val line = """data: {"response":{"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":20,"totalTokenCount":30,"thoughtsTokenCount":5,"cachedContentTokenCount":3}}}"""
        val out = translator.translate(line)
        val chunk = JsonParser.parseString(out[0].removePrefix("data: ").trim()).asJsonObject
        val usage = chunk.getAsJsonObject("usage")
        assertEquals(5, usage.getAsJsonObject("completion_tokens_details").get("reasoning_tokens").asLong)
        assertEquals(3, usage.getAsJsonObject("prompt_tokens_details").get("cached_tokens").asLong)
    }
}
