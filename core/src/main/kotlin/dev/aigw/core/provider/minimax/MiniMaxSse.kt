package dev.aigw.core.provider.minimax

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/** MiniMax 上游 SSE 的归一化事件。 */
sealed interface MiniMaxSseEvent {
    /**
     * `event: message_result`。[content] 是**全量累积**正文（不是增量），
     * `isEnd == 0` 表示本轮回答结束。
     */
    data class MessageResult(
        val chatId: String,
        val msgId: String,
        val isEnd: Long?,
        val content: String,
    ) : MiniMaxSseEvent

    /** `type: 8`，上游终止帧。 */
    data object Terminal : MiniMaxSseEvent

    /** 流内业务错误（base_resp.status_code != 0）。 */
    data class Failure(val code: Long, val message: String) : MiniMaxSseEvent

    /** 其它事件（心跳/元数据等），无需转发。 */
    data object Other : MiniMaxSseEvent
}

/**
 * MiniMax SSE 逐行解析器。事件以「event: / data: 若干行 + 空行」为单位。
 */
class MiniMaxSseParser {
    private var event = ""
    private val data = StringBuilder()

    fun feed(rawLine: String): MiniMaxSseEvent? {
        val line = rawLine.trimEnd('\r', '\n')
        if (line.isEmpty()) {
            if (event.isEmpty()) return null
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
                data.append(line.removePrefix("data:").trim())
                null
            }
            else -> null
        }
    }

    /**
     * 流结束时派发最后一个未完成的事件。
     *
     * 上游断连（或最后一帧缺少终止空行）时，event/data 已经累积但没有空行触发，
     * 参考实现的 SSE 解析器会在 EOF 时 flush：不补的话最后一帧内容会静默丢失。
     */
    fun flush(): MiniMaxSseEvent? {
        if (event.isEmpty() && data.isEmpty()) return null
        val name = event
        val payload = data.toString()
        reset()
        return parse(name, payload)
    }

    private fun reset() {
        event = ""
        data.setLength(0)
    }

    private fun parse(name: String, payload: String): MiniMaxSseEvent? {
        if (payload.isEmpty()) return MiniMaxSseEvent.Other
        val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
            ?: return MiniMaxSseEvent.Other

        // 业务错误：/matrix 接口用 base_resp，/v1 老接口用 statusInfo；type==3 时上游自己也忽略
        val type = obj.longOrNull("type")
        val code = obj.objOrNull("base_resp")?.longOrNull("status_code")
            ?: obj.objOrNull("statusInfo")?.longOrNull("code")
        if (code != null && code != 0L && type != 3L) {
            val message = obj.objOrNull("base_resp")?.stringOrNull("status_msg")
                ?: obj.objOrNull("statusInfo")?.stringOrNull("message")
                ?: "上游流内错误"
            return MiniMaxSseEvent.Failure(code, message)
        }
        if (type == 8L) return MiniMaxSseEvent.Terminal

        if (name == "message_result") {
            val result = obj.objOrNull("data")?.objOrNull("messageResult") ?: return MiniMaxSseEvent.Other
            val chatId = result.stringOrNull("chatID").orEmpty()
                .ifEmpty { result.stringOrNull("chat_id").orEmpty() }
            val msgId = result.stringOrNull("msgID").orEmpty()
                .ifEmpty { result.stringOrNull("msg_id").orEmpty() }
            return MiniMaxSseEvent.MessageResult(
                chatId = chatId,
                msgId = msgId,
                isEnd = result.longOrNull("isEnd") ?: result.longOrNull("is_end"),
                content = result.stringOrNull("content").orEmpty(),
            )
        }
        return MiniMaxSseEvent.Other
    }
}

/**
 * MiniMax 事件 → OpenAI `chat.completion.chunk`。
 *
 * 上游 `content` 是全量累积文本，这里差分成增量输出；替换符（U+FFFD，多字节字符
 * 在分块边界被截断时的产物）之前的内容才是确定的，照参考实现只输出到它为止。
 */
class MiniMaxOpenAiTranslator(private val model: String) {
    private val gson = Gson()
    private var emitted = 0
    private var done = false
    private var started = false
    private var conversationId = ""

    /** 识别出的会话 id；流结束后用于清理会话。 */
    val chatId: String get() = conversationId

    fun translate(event: MiniMaxSseEvent): List<String> = when (event) {
        is MiniMaxSseEvent.MessageResult -> {
            if (conversationId.isEmpty() && event.chatId.isNotEmpty()) conversationId = event.chatId
            val delta = JsonObject()
            val chunk = incremental(event.content)
            // 首个有产出的帧（正文或终止）先声明 role，空帧不输出
            if ((chunk.isNotEmpty() || event.isEnd == 0L) && !started) {
                delta.addProperty("role", "assistant")
                started = true
            }
            if (chunk.isNotEmpty()) delta.addProperty("content", chunk)
            if (event.isEnd == 0L) {
                done = true
                listOf(chunkJson(delta, "stop"), SSE_DONE)
            } else if (delta.size() == 0) {
                emptyList()
            } else {
                listOf(chunkJson(delta, null))
            }
        }

        is MiniMaxSseEvent.Terminal -> {
            done = true
            listOf(chunkJson(JsonObject(), "stop"), SSE_DONE)
        }

        is MiniMaxSseEvent.Failure -> {
            done = true
            listOf(
                "data: " + gson.toJson(
                    JsonObject().apply {
                        add("error", JsonObject().apply {
                            addProperty("code", event.code)
                            addProperty("message", event.message.ifEmpty { "上游流内错误" })
                        })
                    },
                ) + "\n\n",
                SSE_DONE,
            )
        }

        MiniMaxSseEvent.Other -> emptyList()
    }

    /** 上游中断（没有终止帧）时补 `[DONE]`，让客户端正常收尾。 */
    fun close(): List<String> = if (done) emptyList() else listOf(SSE_DONE)

    /** 全量 → 增量；出现 U+FFFD 时只认它之前的内容（末尾多字节字符尚未到齐）。 */
    private fun incremental(full: String): String {
        if (full.length < emitted) emitted = full.length
        val safeEnd = full.indexOf('\uFFFD').let { if (it >= 0) it else full.length }
        if (safeEnd <= emitted) return ""
        val chunk = full.substring(emitted, safeEnd)
        emitted = safeEnd
        return chunk
    }

    private fun chunkJson(delta: JsonObject, finishReason: String?): String {
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("delta", delta)
            if (finishReason != null) addProperty("finish_reason", finishReason)
        }
        val obj = JsonObject().apply {
            addProperty("id", conversationId.ifEmpty { "chatcmpl-minimax" })
            addProperty("object", "chat.completion.chunk")
            addProperty("created", System.currentTimeMillis() / 1000)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
        }
        return "data: " + obj.toString() + "\n\n"
    }

    companion object {
        const val SSE_DONE = "data: [DONE]\n\n"
    }
}
