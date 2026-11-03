package dev.aigw.core.provider.chatgpt

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class OpenAiResponsesTest {

    // ---------- 请求转换 ----------

    @Test
    fun `system becomes developer input item`() {
        val body = """{"model":"chatgpt/gpt-5.1-codex","messages":[
            {"role":"system","content":"You are a coder."},
            {"role":"user","content":"hi"}
        ]}"""
        val out = OpenAiResponses.toResponsesRequest(body, "gpt-5.1-codex")
        assertTrue(out.contains("\"role\":\"developer\""))
        assertTrue(out.contains("You are a coder."))
        // Codex 不接受 instructions/system 顶层字段
        assertTrue(!out.contains("\"instructions\""))
        assertTrue(out.contains("\"store\":false"))
        assertTrue(out.contains("reasoning.encrypted_content"))
        assertTrue(out.contains("\"stream\":true"))
    }

    @Test
    fun `sampling params are stripped`() {
        val body = """{"model":"m","temperature":0.7,"top_p":0.9,"max_tokens":100,
            "messages":[{"role":"user","content":"x"}]}"""
        val out = OpenAiResponses.toResponsesRequest(body, "m")
        assertTrue(!out.contains("temperature"))
        assertTrue(!out.contains("top_p"))
        assertTrue(!out.contains("max_output_tokens"))
    }

    @Test
    fun `tools are flattened and forced non-strict`() {
        val body = """{"model":"m","messages":[{"role":"user","content":"x"}],"tools":[
            {"type":"function","function":{"name":"get_weather","description":"d",
             "parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}
        ]}"""
        val out = OpenAiResponses.toResponsesRequest(body, "m")
        assertTrue(out.contains("\"name\":\"get_weather\""))
        // 必须 explicit strict:false，否则上游把可选参数当必填
        assertTrue(out.contains("\"strict\":false"))
    }

    @Test
    fun `assistant tool_calls become function_call items`() {
        val body = """{"model":"m","messages":[
            {"role":"user","content":"weather?"},
            {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"SG\"}"}}
            ]},
            {"role":"tool","tool_call_id":"call_1","content":"25C"}
        ]}"""
        val out = OpenAiResponses.toResponsesRequest(body, "m")
        assertTrue(out.contains("\"type\":\"function_call\""))
        assertTrue(out.contains("\"call_id\":\"call_1\""))
        assertTrue(out.contains("\"type\":\"function_call_output\""))
        assertTrue(out.contains("25C"))
    }

    @Test
    fun `image parts are preserved as input_image`() {
        val body = """{"model":"m","messages":[
            {"role":"user","content":[
                {"type":"text","text":"look"},
                {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}
            ]}
        ]}"""
        val out = OpenAiResponses.toResponsesRequest(body, "m")
        assertTrue(out.contains("\"type\":\"input_text\""))
        assertTrue(out.contains("\"type\":\"input_image\""))
        assertTrue(out.contains("data:image/png;base64,AAAA"))
    }

    @Test
    fun `reasoning effort is forwarded when present`() {
        val body = """{"model":"m","reasoning_effort":"high","messages":[{"role":"user","content":"x"}]}"""
        val out = OpenAiResponses.toResponsesRequest(body, "m")
        assertTrue(out.contains("\"effort\":\"high\""))
    }

    // ---------- 流翻译 ----------

    private fun data(payload: String) = "data: $payload"

    @Test
    fun `codex event stream translates to chat chunks`() {
        val translator = OpenAiResponses.SseTranslator("chatcmpl-1", "gpt-5.1-codex")
        val lines = listOf(
            "event: response.created",
            data("""{"type":"response.created","response":{}}"""),
            data("""{"type":"response.output_text.delta","delta":"Hello"}"""),
            data("""{"type":"response.reasoning_summary_text.delta","delta":"thinking"}"""),
            "event: response.completed",
            data("""{"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}"""),
        )
        val out = lines.flatMap { translator.translate(it) } + translator.onFinish()
        val joined = out.joinToString("")
        assertTrue(out.size >= 5)
        assertTrue(joined.contains("\"content\":\"Hello\""))
        assertTrue(joined.contains("\"reasoning_content\":\"thinking\""))
        assertTrue(joined.contains("\"finish_reason\":\"stop\""))
        assertTrue(joined.contains("\"prompt_tokens\":10"))
        assertTrue(joined.contains("\"completion_tokens\":5"))
        assertTrue(joined.contains("[DONE]"))
    }

    @Test
    fun `tool call items translate to tool_calls deltas`() {
        val translator = OpenAiResponses.SseTranslator("chatcmpl-2", "m")
        val out = listOf(
            data("""{"type":"response.output_item.added","output_index":0,
                "item":{"type":"function_call","call_id":"call_9","name":"get_weather","arguments":""}}"""),
            data("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"city\""}"""),
            data("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":":\"SG\"}"}"""),
            data("""{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}"""),
        ).flatMap { translator.translate(it) } + translator.onFinish()
        val joined = out.joinToString("")
        assertTrue(joined.contains("\"id\":\"call_9\""))
        assertTrue(joined.contains("\"name\":\"get_weather\""))
        // 参数分片经 Gson 重新序列化后是转义形态（\"city\"），直接断言语义内容
        assertTrue(joined.contains("city"))
        assertTrue(joined.contains("SG"))
        assertTrue(joined.contains("\"arguments\""))
        assertTrue(joined.contains("\"finish_reason\":\"tool_calls\""))
    }

    @Test
    fun `event line fallback works when type missing`() {
        val translator = OpenAiResponses.SseTranslator("id", "m")
        val out = translator.translate("event: response.output_text.delta") +
            translator.translate(data("""{"delta":"fallback"}"""))
        assertEquals(1, out.size)
        assertTrue(out[0].contains("fallback"))
    }

    @Test
    fun `incomplete maps max_output_tokens to length finish`() {
        val translator = OpenAiResponses.SseTranslator("id", "m")
        val out = translator.translate(
            data("""{"type":"response.incomplete","response":{"status":"incomplete",
                "incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}}"""),
        ) + translator.onFinish()
        assertTrue(out.joinToString("").contains("\"finish_reason\":\"length\""))
    }

    @Test
    fun `failed event degrades to final content chunk`() {
        val translator = OpenAiResponses.SseTranslator("id", "m")
        val out = translator.translate(
            data("""{"type":"response.failed","response":{"error":{"message":"boom"}}}"""),
        ) + translator.onFinish()
        val joined = out.joinToString("")
        assertTrue(joined.contains("[ChatGPT 上游错误] boom"))
        assertTrue(joined.contains("[DONE]"))
    }

    @Test
    fun `non-data and done lines are ignored`() {
        val translator = OpenAiResponses.SseTranslator("id", "m")
        assertEquals(emptyList<String>(), translator.translate(": keep-alive"))
        assertEquals(emptyList<String>(), translator.translate(data("[DONE]")))
        assertEquals(emptyList<String>(), translator.translate("data: not-json"))
    }

    // ---------- 模型目录 / 用量 ----------

    @Test
    fun `model catalog filters by visibility`() {
        val raw = """{"models":[
            {"slug":"gpt-5.1-codex","display_name":"GPT-5.1 Codex","visibility":"list","context_window":272000},
            {"slug":"hidden","display_name":"Hidden","visibility":"hidden"},
            {"slug":"gpt-5.1","display_name":"GPT-5.1","visibility":"list"}
        ]}"""
        val models = OpenAiResponses.parseModels(raw)
        assertEquals(listOf("gpt-5.1-codex", "gpt-5.1"), models.map { it.id })
        assertEquals("GPT-5.1 Codex", models[0].name)
        assertEquals(272000L, models[0].contextWindow)
    }

    @Test
    fun `usage parses both windows`() {
        val raw = """{"rate_limit":{"primary_window":{"used_percent":42.5,"limit_window_seconds":18000,"reset_at":1700000000},
            "secondary_window":{"used_percent":5.0,"limit_window_seconds":604800}},"limit_reached":false}"""
        val packs = OpenAiResponses.parseUsage(raw)
        assertEquals(2, packs.size)
        assertEquals("5 小时窗口", packs[0].name)
        assertEquals(100L, packs[0].limit)
        assertEquals(42L, packs[0].used)
        assertEquals(58L, packs[0].remain)
        assertEquals(1700000000L, packs[0].expireAt)
        assertEquals(5L, packs[1].used)
    }
}
