package dev.aigw.core.protocol

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OpenAI ⇄ Antigravity(Gemini) 协议转换。
 *
 * 关键约束（来自 Antigravity API spec 的实测结论）：
 * role 用 user/model；systemInstruction 必须是带 parts 的对象；JSON Schema 不支持
 * `const`/`$ref`/`$schema`/`default`（发了会 400）。
 */
class OpenAiGeminiTest {

    @Test
    fun `system 消息归入 systemInstruction 且是对象带 parts`() {
        val body = """
            {"model":"x","messages":[
              {"role":"system","content":"你是助手"},
              {"role":"user","content":"你好"},
              {"role":"assistant","content":"在"},
              {"role":"user","content":"继续"}
            ]}
        """.trimIndent()
        val request = OpenAiGemini.toGeminiRequest(body)

        val system = request.getAsJsonObject("systemInstruction")
        assertEquals("你是助手", system.getAsJsonArray("parts")[0].asJsonObject.get("text").asString)

        val contents = request.getAsJsonArray("contents")
        assertEquals(3, contents.size(), "system 不该出现在 contents 里")
        assertEquals("user", contents[0].asJsonObject.get("role").asString)
        assertEquals("model", contents[1].asJsonObject.get("role").asString, "assistant 必须转成 model")
        assertEquals("user", contents[2].asJsonObject.get("role").asString)
    }

    @Test
    fun `数组形式的 content 也能取到文本`() {
        val body = """
            {"messages":[{"role":"user","content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}]}]}
        """.trimIndent()
        val request = OpenAiGemini.toGeminiRequest(body)
        val parts = request.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertEquals("第一段第二段", parts[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `采样参数映射到 generationConfig`() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"max_tokens":100,"temperature":0.5,"top_p":0.9,"stop":["END"]}"""
        val config = OpenAiGemini.toGeminiRequest(body).getAsJsonObject("generationConfig")
        assertEquals(100, config.get("maxOutputTokens").asInt)
        assertEquals(0.5, config.get("temperature").asDouble)
        assertEquals(0.9, config.get("topP").asDouble)
        assertEquals("END", config.getAsJsonArray("stopSequences")[0].asString)
    }

    @Test
    fun `tools 转成 functionDeclarations 并剥离不支持的 schema 字段`() {
        val body = """
            {"messages":[{"role":"user","content":"hi"}],
             "tools":[{"type":"function","function":{"name":"get_weather","description":"查天气",
               "parameters":{"type":"object","properties":{
                 "loc":{"type":"string","default":"x","${'$'}ref":"#/a","description":"城市"}
               },"required":["loc"]}}}]}
        """.trimIndent()
        val request = OpenAiGemini.toGeminiRequest(body)
        val declaration = request.getAsJsonArray("tools")[0].asJsonObject
            .getAsJsonArray("functionDeclarations")[0].asJsonObject

        assertEquals("get_weather", declaration.get("name").asString)
        val loc = declaration.getAsJsonObject("parameters")
            .getAsJsonObject("properties").getAsJsonObject("loc")
        assertFalse(loc.has("default"), "default 会 400，必须剥离")
        assertFalse(loc.has("${'$'}ref"), "\$ref 会 400，必须剥离")
        assertEquals("城市", loc.get("description").asString, "合法字段要保留")
    }

    @Test
    fun `envelope 包住 project model 与 requestId`() {
        val request = OpenAiGemini.toGeminiRequest("""{"messages":[{"role":"user","content":"hi"}]}""")
        val envelope = JsonParser.parseString(
            OpenAiGemini.envelope("proj-1", "gemini-3-pro-high", request, "agent-abc"),
        ).asJsonObject

        assertEquals("proj-1", envelope.get("project").asString)
        assertEquals("gemini-3-pro-high", envelope.get("model").asString)
        assertEquals("antigravity", envelope.get("userAgent").asString)
        assertEquals("agent-abc", envelope.get("requestId").asString)
        assertTrue(envelope.has("request"))
    }

    @Test
    fun `Gemini SSE 转成 OpenAI chunk 并带 usage`() {
        val translator = OpenAiGemini.SseTranslator("id1", "gemini-3-pro-high", created = 1)
        val line = """data: {"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"你好"}]}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}},"traceId":"t"}"""

        val out = translator.translate(line)
        assertEquals(1, out.size)
        assertTrue(out[0].startsWith("data: "))

        val chunk = JsonParser.parseString(out[0].removePrefix("data: ").trim()).asJsonObject
        assertEquals("chat.completion.chunk", chunk.get("object").asString)
        assertEquals("id1", chunk.get("id").asString)
        assertEquals("你好", chunk.getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("delta").get("content").asString)
        assertEquals(5L, chunk.getAsJsonObject("usage").get("prompt_tokens").asLong)
        assertEquals(2L, chunk.getAsJsonObject("usage").get("completion_tokens").asLong)
    }

    @Test
    fun `finishReason 映射并补 DONE`() {
        val translator = OpenAiGemini.SseTranslator("id", "m", created = 1)
        val line = """data: {"response":{"candidates":[{"content":{"parts":[{"text":"end"}]},"finishReason":"MAX_TOKENS"}]}}"""

        val out = translator.translate(line)
        assertEquals(2, out.size)
        assertTrue(out[1].contains("[DONE]"))

        val chunk = JsonParser.parseString(out[0].removePrefix("data: ").trim()).asJsonObject
        assertEquals("length", chunk.getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString)
        assertTrue(translator.close().isEmpty(), "已结束就不该再补 DONE")
    }

    @Test
    fun `thought 部分归到 reasoning_content`() {
        val translator = OpenAiGemini.SseTranslator("id", "m", created = 1)
        val line = """data: {"response":{"candidates":[{"content":{"parts":[{"thought":true,"text":"推理中"},{"text":"答案"}]}}]}}"""

        val chunk = JsonParser.parseString(
            translator.translate(line)[0].removePrefix("data: ").trim(),
        ).asJsonObject
        val delta = chunk.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
        assertEquals("推理中", delta.get("reasoning_content").asString)
        assertEquals("答案", delta.get("content").asString)
    }

    @Test
    fun `没有内容也没有结束原因时不产生输出`() {
        val translator = OpenAiGemini.SseTranslator("id", "m", created = 1)
        assertTrue(translator.translate("").isEmpty())
        assertTrue(translator.translate(": keep-alive").isEmpty())
        assertTrue(translator.translate("data: {\"response\":{\"candidates\":[]}}").isEmpty())
        assertEquals(1, translator.close().size, "没结束时 close 应补一个 DONE")
    }
}
