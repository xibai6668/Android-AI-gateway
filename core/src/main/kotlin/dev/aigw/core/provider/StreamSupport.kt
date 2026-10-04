package dev.aigw.core.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.intOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 逐行转换的 SSE 流：把 [source] 每读一行交给 [transform]，
 * 产出的文本按字节写给下游（transform 负责拼好 `data: ...\n\n` 这类帧）。
 *
 * 用于把上游私有 SSE 实时翻译成 OpenAI SSE，供网关原样透传。
 */
class LineTransformStream(
    private val source: InputStream,
    private val transform: (String) -> List<String>,
    private val onFinish: () -> List<String> = { emptyList() },
) : InputStream() {
    private val reader = source.bufferedReader(StandardCharsets.UTF_8)
    private var pending: ByteArray = EMPTY
    private var offset = 0
    private var finished = false

    private fun fill(): Boolean {
        while (offset >= pending.size) {
            if (finished) return false
            val line = reader.readLine()
            if (line == null) {
                finished = true
                pending = onFinish().joinToString("").toByteArray(StandardCharsets.UTF_8)
                offset = 0
                return pending.isNotEmpty()
            }
            // 单行转换失败不能把整条流掐断：跳过这一行，客户端还能收到 [DONE] 正常收尾
            pending = runCatching { transform(line).joinToString("") }
                .getOrDefault("")
                .toByteArray(StandardCharsets.UTF_8)
            offset = 0
        }
        return true
    }

    override fun read(): Int {
        if (!fill()) return -1
        return pending[offset++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val count = minOf(len, pending.size - offset)
        System.arraycopy(pending, offset, b, off, count)
        offset += count
        return count
    }

    override fun close() {
        runCatching { reader.close() }
        runCatching { source.close() }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * 把 OpenAI SSE 流聚合成一个非流式 `chat.completion`。
 *
 * 供「上游本身就是 OpenAI 协议」的 provider（Loomy / CodeBuddy / 自定义）复用；
 * 它们非流式时上游可能不支持，或干脆不区分，统一由这里聚合。
 */
object OpenAiSseAggregator {

    fun aggregate(stream: InputStream, fallbackModel: String): String {
        var id = ""
        var model = fallbackModel
        var created = 0L
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = LinkedHashMap<Int, JsonObject>()
        var finishReason = "stop"
        var usage: JsonObject? = null

        stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
                obj.stringOrNull("id")?.takeIf { it.isNotEmpty() }?.let { id = it }
                obj.stringOrNull("model")?.takeIf { it.isNotEmpty() }?.let { model = it }
                obj.longOrNull("created")?.takeIf { it > 0 }?.let { created = it }
                obj.objOrNull("usage")?.let { usage = it }
                val choices = obj.arrayOrNull("choices") ?: continue
                for (element in choices) {
                    val choice = element.asObjectOrNull() ?: continue
                    choice.stringOrNull("finish_reason")?.takeIf { it.isNotEmpty() }?.let { finishReason = it }
                    val delta = choice.objOrNull("delta") ?: continue
                    delta.stringOrNull("content")?.let { content.append(it) }
                    delta.stringOrNull("reasoning_content")?.let { reasoning.append(it) }
                    delta.arrayOrNull("tool_calls")?.let { mergeToolCalls(toolCalls, it) }
                }
            }
        }

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
            addProperty("id", id.ifEmpty { "chatcmpl-aggregated" })
            addProperty("object", "chat.completion")
            addProperty("created", if (created > 0) created else System.currentTimeMillis() / 1000)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
            usage?.let { add("usage", it) }
        }.toString()
    }

    private fun mergeToolCalls(merged: MutableMap<Int, JsonObject>, deltas: JsonArray) {
        for (element in deltas) {
            val delta = element.asObjectOrNull() ?: continue
            val index = delta.intOrNull("index") ?: 0
            val target = merged.getOrPut(index) {
                JsonObject().apply {
                    addProperty("index", index)
                    addProperty("type", "function")
                }
            }
            delta.stringOrNull("id")?.takeIf { it.isNotEmpty() }?.let { target.addProperty("id", it) }
            val fn = delta.objOrNull("function") ?: continue
            val targetFn = target.objOrNull("function")
                ?: JsonObject().also { target.add("function", it) }
            fn.stringOrNull("name")?.takeIf { it.isNotEmpty() }?.let { targetFn.addProperty("name", it) }
            fn.stringOrNull("arguments")?.takeIf { it.isNotEmpty() }?.let { chunk ->
                val previous = targetFn.stringOrNull("arguments").orEmpty()
                targetFn.addProperty("arguments", previous + chunk)
            }
        }
    }

    /**
     * 聚合结果是否为空回复（既无正文、无思维链、也无工具调用）。
     *
     * 上游 200 但一条内容都没有时必须保持「可检测的失败」：
     * 当成成功的空回复，客户端只会看到「输出完成却什么都没有」。
     */
    fun isEmptyCompletion(completion: String): Boolean {
        val obj = runCatching { JsonParser.parseString(completion).asJsonObject }.getOrNull() ?: return true
        val message = obj.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()?.objOrNull("message") ?: return true
        val content = message.stringOrNull("content").orEmpty()
        val reasoning = message.stringOrNull("reasoning_content").orEmpty()
        val toolCalls = message.arrayOrNull("tool_calls")?.size() ?: 0
        return content.isEmpty() && reasoning.isEmpty() && toolCalls == 0
    }

    /**
     * 把上游整段返回的 `chat.completion` JSON 包成 SSE 流（单 chunk + [DONE]）。
     *
     * 客户端请求 stream=true 而 upstream 不支持流式、直接回了 JSON 时用：
     * OpenAI 协议要求 stream=true 必须以 SSE 响应；把 JSON 原样发回，
     * 严格实现的客户端会把整包当成无效事件丢弃——表现就是「调用成功却零输出」。
     */
    fun completionAsSse(completion: String): String {
        val obj = runCatching { JsonParser.parseString(completion).asJsonObject }.getOrNull()
        val chunk = if (obj == null) {
            // 上游连 JSON 都不是：把原文塞进 content，至少让客户端看得见东西
            JsonObject().apply {
                addProperty("id", "chatcmpl-wrapped")
                addProperty("object", "chat.completion.chunk")
                addProperty("created", System.currentTimeMillis() / 1000)
                add("choices", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("index", 0)
                        add("delta", JsonObject().apply { addProperty("content", completion) })
                        addProperty("finish_reason", "stop")
                    })
                })
            }
        } else {
            val choice = obj.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()
            JsonObject().apply {
                addProperty("id", obj.stringOrNull("id").orEmpty().ifEmpty { "chatcmpl-wrapped" })
                addProperty("object", "chat.completion.chunk")
                addProperty("created", obj.longOrNull("created") ?: System.currentTimeMillis() / 1000)
                addProperty("model", obj.stringOrNull("model").orEmpty())
                add("choices", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("index", 0)
                        add("delta", choice?.objOrNull("message") ?: JsonObject())
                        addProperty("finish_reason", choice?.stringOrNull("finish_reason").orEmpty().ifEmpty { "stop" })
                    })
                })
                obj.objOrNull("usage")?.let { add("usage", it) }
            }
        }
        return "data: $chunk\n\ndata: [DONE]\n\n"
    }
}
