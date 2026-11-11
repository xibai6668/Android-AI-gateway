package dev.aigw.core.provider.trae

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Trae 链路的 `finish_reason` 必须与 message 内容自洽。
 *
 * 回归背景（issue #15）：Trae 上游没有 OpenAI 的 finish_reason 概念，`done` 事件的
 * finish_reason 往往为空或恒为 stop，而 tool_calls 是照常转发的。严格校验的客户端
 * （Eta / Roo Code）看到「stop 却带 tool_calls」会判定协议违规，直接丢弃整批工具调用。
 * 流式与非流式两条路径都必须按「本轮是否出现工具调用」改写终止原因。
 */
class TraeFinishReasonTest {

    private fun toolCall(index: Int, id: String, name: String, args: String): JsonObject = JsonObject().apply {
        addProperty("index", index)
        addProperty("id", id)
        addProperty("type", "function")
        add(
            "function_call",
            JsonObject().apply {
                addProperty("name", name)
                addProperty("arguments", args)
            },
        )
    }

    private fun finishReasonOf(completion: JsonObject): String =
        completion.getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString

    // ---------------------------------------------------------------- 非流式

    @Test
    fun `非流式带工具调用时 finish_reason 是 tool_calls`() {
        val aggregator = OpenAiAggregator("id", "model")
        aggregator.accept(
            SoloEvent.Output(
                response = "",
                reasoning = "",
                toolCalls = JsonArray().apply { add(toolCall(0, "call_1", "get_time", "{}")) },
            ),
        )
        aggregator.accept(SoloEvent.Done("stop"))

        val completion = aggregator.build()
        assertEquals("tool_calls", finishReasonOf(completion))
        val message = completion.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
        assertEquals(1, message.getAsJsonArray("tool_calls").size())
    }

    @Test
    fun `非流式纯文本时保持上游终止原因`() {
        val aggregator = OpenAiAggregator("id", "model")
        aggregator.accept(SoloEvent.Output(response = "你好", reasoning = "", toolCalls = null))
        aggregator.accept(SoloEvent.Done("length"))

        assertEquals("length", finishReasonOf(aggregator.build()))
    }

    @Test
    fun `上游不给终止原因且无工具调用时回落为 stop`() {
        val aggregator = OpenAiAggregator("id", "model")
        aggregator.accept(SoloEvent.Output(response = "你好", reasoning = "", toolCalls = null))
        aggregator.accept(SoloEvent.Done(""))

        assertEquals("stop", finishReasonOf(aggregator.build()))
    }

    // ---------------------------------------------------------------- 流式

    private fun dataPayloads(frames: List<String>): List<JsonObject> = frames
        .filter { it.startsWith("data: ") && !it.contains("[DONE]") }
        .map { JsonParser.parseString(it.removePrefix("data: ").trim()).asJsonObject }

    @Test
    fun `流式出现过工具调用时末帧 finish_reason 是 tool_calls`() {
        val stream = OpenAiSseTranslator("id", "model", created = 1)
        val frames = mutableListOf<String>()
        frames += stream.translate(
            SoloEvent.Output(
                response = "",
                reasoning = "",
                toolCalls = JsonArray().apply { add(toolCall(0, "call_1", "get_time", "{}")) },
            ),
        )
        frames += stream.translate(SoloEvent.Done("stop"))

        val payloads = dataPayloads(frames)
        assertTrue(payloads.isNotEmpty(), "应产出至少一帧")
        val last = payloads.last()
        val reason = last.getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString
        assertEquals("tool_calls", reason)
    }

    @Test
    fun `流式没有工具调用时不改写终止原因`() {
        val stream = OpenAiSseTranslator("id", "model", created = 1)
        val frames = mutableListOf<String>()
        frames += stream.translate(SoloEvent.Output(response = "你好", reasoning = "", toolCalls = null))
        frames += stream.translate(SoloEvent.Done("length"))

        val reason = dataPayloads(frames).last()
            .getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString
        assertEquals("length", reason)
    }

    @Test
    fun `流式工具调用以数组为空的形状出现时不算工具调用`() {
        val stream = OpenAiSseTranslator("id", "model", created = 1)
        val frames = mutableListOf<String>()
        frames += stream.translate(
            SoloEvent.Output(response = "好的", reasoning = "", toolCalls = JsonArray()),
        )
        frames += stream.translate(SoloEvent.Done("stop"))

        val reason = dataPayloads(frames).last()
            .getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString
        assertEquals("stop", reason)
    }
}
