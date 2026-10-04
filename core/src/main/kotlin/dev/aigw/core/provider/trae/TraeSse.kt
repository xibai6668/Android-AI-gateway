package dev.aigw.core.provider.trae

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.aigw.core.util.long
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

/** SOLO SSE 的归一化事件。 */
sealed interface SoloEvent {
    /** `event:output`，正文/思维链/tool_call 增量。 */
    data class Output(
        val response: String,
        val reasoning: String,
        val toolCalls: JsonElement?,
    ) : SoloEvent

    /** `event:token_usage`，token 统计。 */
    data class Usage(val usage: JsonObject) : SoloEvent

    /**
     * `event:notify_usage`，计费回报。
     * SOLO 通道真正剩余多少积分只看这里的 `cn_credits_remain_info.ide_credits`，
     * 权益包接口返回的聚合值并不等于它。
     */
    data class NotifyUsage(
        val ideCredits: Long?,
        val workCredits: Long?,
    ) : SoloEvent

    /** `event:done`。 */
    data class Done(val finishReason: String) : SoloEvent

    /** `event:error`，流内业务错误。 */
    data class Failure(val code: Long, val message: String) : SoloEvent

    /** metadata / timing_cost / extra_info 等无需转发的事件。 */
    data class Named(val name: String) : SoloEvent
}

/**
 * SOLO 自定义 SSE 的逐行解析器。
 *
 * 事件以「event: / data: 若干行 + 空行」为单位，data 行可能分多行累积。
 * 与上游 Go 实现一致：没有 event 行的空行只做复位，不产出事件。
 */
class SoloSseParser {
    private var event = ""
    private val data = StringBuilder()

    /** 喂入一行（可为带换行的原始行）；返回 null 或一个事件。 */
    fun feed(rawLine: String): SoloEvent? {
        val line = rawLine.trimEnd('\r', '\n')
        if (line.isEmpty()) {
            if (event.isEmpty()) {
                reset()
                return null
            }
            val name = event
            val payload = data.toString()
            reset()
            return parse(name, payload)
        }
        return when {
            line.startsWith("event:") -> {
                event = line.removePrefix("event:").trim()
                null
            }
            line.startsWith("data:") -> {
                data.append(line.removePrefix("data:"))
                null
            }
            // id: 行与注释行忽略
            else -> null
        }
    }

    private fun reset() {
        event = ""
        data.setLength(0)
    }

    private fun parse(name: String, payload: String): SoloEvent? {
        if (payload.isEmpty()) return SoloEvent.Named(name)
        val raw = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return null
        return when (name) {
            "output" -> SoloEvent.Output(
                response = raw.str("response"),
                reasoning = raw.str("reasoning_content"),
                toolCalls = raw.get("tool_calls"),
            )
            "token_usage" -> SoloEvent.Usage(raw)
            "notify_usage" -> {
                val remain = raw.objOrNull("cn_credits_remain_info")
                SoloEvent.NotifyUsage(
                    ideCredits = remain?.takeIf { it.has("ide_credits") }?.long("ide_credits"),
                    workCredits = remain?.takeIf { it.has("work_credits") }?.long("work_credits"),
                )
            }
            "done" -> SoloEvent.Done(raw.str("finish_reason"))
            "error" -> SoloEvent.Failure(raw.long("code"), raw.str("message"))
            else -> SoloEvent.Named(name)
        }
    }
}

/**
 * SOLO 事件 → OpenAI `chat.completion.chunk`。逐事件转换，返回值即待写出的 SSE 文本，
 * 便于单测断言。上游中断（没有 done）时用 [close] 补一个 `[DONE]`。
 */
class OpenAiSseTranslator(
    private val id: String,
    private val model: String,
    private val created: Long = System.currentTimeMillis() / 1000,
) {
    private var done = false
    private var pendingUsage: JsonObject? = null
    private val gson = Gson()

    fun translate(event: SoloEvent): List<String> = when (event) {
        is SoloEvent.Output -> {
            val delta = JsonObject()
            if (event.response.isNotEmpty()) delta.addProperty("content", event.response)
            if (event.reasoning.isNotEmpty()) delta.addProperty("reasoning_content", event.reasoning)
            toOpenAiToolCalls(event.toolCalls)?.let { delta.add("tool_calls", it) }
            if (delta.size() == 0) emptyList() else listOf(chunk(delta, null))
        }

        is SoloEvent.Usage -> {
            pendingUsage = event.usage
            emptyList()
        }

        is SoloEvent.NotifyUsage -> emptyList()

        is SoloEvent.Done -> {
            done = true
            listOf(chunk(JsonObject(), event.finishReason.ifEmpty { "stop" }), SSE_DONE)
        }
        is SoloEvent.Failure -> {
            done = true
            // 用 OpenAI 标准错误帧（顶层 error 对象）：网关的 parseChunk 能识别它并把调用记为失败，
            // 客户端也能直接读到错误。旧私有格式（event: error + 字符串 body）会被网关静默吞掉，
            // 表现为「额度耗尽却像正常结束」。
            val kind = SoloStreamError(event.code, event.message).kind.toErrorKind().name
            listOf(
                "data: " + gson.toJson(
                    JsonObject().apply {
                        add("error", JsonObject().apply {
                            addProperty("code", event.code)
                            addProperty("message", event.message.ifEmpty { "上游流内错误" })
                            addProperty("type", kind)
                        })
                    },
                ) + "\n\n",
                SSE_DONE,
            )
        }

        is SoloEvent.Named -> emptyList()
    }

    fun close(): List<String> = if (done) emptyList() else listOf(SSE_DONE)
    private fun chunk(delta: JsonObject, finishReason: String?): String {
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("delta", delta)
            if (finishReason != null) addProperty("finish_reason", finishReason)
        }
        val obj = JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion.chunk")
            addProperty("created", created)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
        }
        pendingUsage?.let {
            obj.add("usage", it)
            pendingUsage = null
        }
        return "data: " + obj.toString() + "\n\n"
    }

    /** 上游 tool_call 用 `function_call` 字段，OpenAI 用 `function`，并清掉 SOLO 私有字段。 */
    private fun toOpenAiToolCalls(element: JsonElement?): JsonArray? {
        if (element == null || element.isJsonNull) return null
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> JsonArray().apply { add(element) }
            else -> return null
        }
        val out = JsonArray()
        for (item in array) {
            val call = item as? JsonObject ?: continue
            (call.get("function_call") as? JsonObject)?.let { fc ->
                call.add("function", fc)
                call.remove("function_call")
            }
            (call.get("function") as? JsonObject)?.let { fn ->
                fn.remove("namespace")
                fn.remove("partial_arguments")
            }
            out.add(call)
        }
        return if (out.size() == 0) null else out
    }

    companion object {
        const val SSE_DONE = "data: [DONE]\n\n"
    }
}

/**
 * SOLO 事件流 → 单个 OpenAI `chat.completion`（非流式）。
 * 按 index 合并分片到达的 tool_call，`arguments` 做字符串拼接。
 */
class OpenAiAggregator(
    private val id: String,
    private val model: String,
) {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val toolCalls = LinkedHashMap<Int, JsonObject>()
    private var finishReason = "stop"
    private var usage: JsonObject? = null

    /** 流内业务错误，非 null 时调用方应冷却账号并换号重试。 */
    var failure: SoloStreamError? = null
        private set

    /** 计费回报里的 SOLO 真实剩余（`notify_usage.ide_credits`）。 */
    var soloCredits: Long? = null
        private set

    fun accept(event: SoloEvent) {
        when (event) {
            is SoloEvent.Output -> {
                content.append(event.response)
                reasoning.append(event.reasoning)
                mergeToolCalls(event.toolCalls)
            }
            is SoloEvent.Usage -> usage = event.usage
            is SoloEvent.Done -> if (event.finishReason.isNotEmpty()) finishReason = event.finishReason
            is SoloEvent.Failure -> failure = SoloStreamError(event.code, event.message)
            is SoloEvent.NotifyUsage -> event.ideCredits?.let { soloCredits = it }
            is SoloEvent.Named -> Unit
        }
    }

    fun build(): JsonObject {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", content.toString())
        }
        if (reasoning.isNotEmpty()) message.addProperty("reasoning_content", reasoning.toString())
        if (toolCalls.isNotEmpty()) {
            message.add("tool_calls", JsonArray().apply {
                toolCalls.entries.sortedBy { it.key }.forEach { add(it.value) }
            })
        }

        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("message", message)
            addProperty("finish_reason", finishReason)
        }

        return JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion")
            addProperty("created", System.currentTimeMillis() / 1000)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
            usage?.let { add("usage", it) }
        }
    }

    private fun mergeToolCalls(element: JsonElement?) {
        if (element == null || element.isJsonNull) return
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> JsonArray().apply { add(element) }
            else -> return
        }
        for (item in array) {
            val delta = item as? JsonObject ?: continue
            val index = delta.long("index").toInt()
            val merged = toolCalls.getOrPut(index) {
                JsonObject().apply { addProperty("index", index) }
            }
            mergeToolCallDelta(merged, delta)
        }
    }

    private fun mergeToolCallDelta(merged: JsonObject, delta: JsonObject) {
        stringField(delta, "id")?.let { merged.addProperty("id", it) }
        stringField(delta, "type")?.let { merged.addProperty("type", it) }

        val fn = (delta.get("function") as? JsonObject)
            ?: (delta.get("function_call") as? JsonObject)
            ?: return
        fn.remove("namespace")
        fn.remove("partial_arguments")

        val target = (merged.get("function") as? JsonObject)
            ?: JsonObject().also { merged.add("function", it) }

        stringField(fn, "name")?.let { target.addProperty("name", it) }
        stringField(fn, "arguments")?.let { chunk ->
            val previous = target.str("arguments")
            target.addProperty("arguments", if (previous.isEmpty()) chunk else previous + chunk)
        }
    }

    private fun stringField(obj: JsonObject, key: String): String? {
        val value = obj.get(key) as? JsonPrimitive ?: return null
        if (!value.isString) return null
        return value.asString.ifEmpty { null }
    }
}
