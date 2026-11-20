package dev.aigw.core.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * Anthropic Messages 协议（`POST /v1/messages`）与网关内部 OpenAI 协议的互转。
 *
 * 存在的理由：网关对上游一律用 OpenAI 协议，而 Claude Code 这类客户端只会讲 Anthropic 协议。
 * 在这里把「Anthropic 进」翻成「OpenAI 进」，把「OpenAI 出」翻回「Anthropic 出」，
 * 网关其余部分（路由、选号、Failover、日志）一行都不用改。
 *
 * 边界：这里只做协议形状的搬运，不做语义推断。映射不出来的字段直接丢弃，
 * 拿不准的一律不用默认值假装——例如上游没给 usage 就不编 token 数。
 */
object AnthropicMessages {

    /** 把 Anthropic 请求体翻成 OpenAI 请求体；不是合法 JSON 对象时返回 null。 */
    fun toOpenAiRequest(raw: String): String? {
        val src = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val result = JsonObject()

        src.get("model")?.takeIf { it.isJsonPrimitive }?.let { result.add("model", it) }
        if (src.has("stream")) result.addProperty("stream", src.get("stream").asBoolean)
        src.get("max_tokens")?.takeIf { it.isJsonPrimitive }?.let { result.add("max_tokens", it) }
        src.get("temperature")?.takeIf { it.isJsonPrimitive }?.let { result.add("temperature", it) }
        src.get("top_p")?.takeIf { it.isJsonPrimitive }?.let { result.add("top_p", it) }
        // Anthropic 的 stop_sequences 对应 OpenAI 的 stop（单串或数组都合法）
        src.arrayOrNull("stop_sequences")?.let { stops ->
            if (stops.size() > 0) result.add("stop", stops)
        }

        val messages = JsonArray()
        systemText(src.get("system"))?.takeIf { it.isNotEmpty() }?.let { text ->
            messages.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", text)
            })
        }
        src.arrayOrNull("messages")?.forEach { element ->
            val message = element.asObjectOrNull() ?: return@forEach
            when (message.get("role")?.asString) {
                "assistant" -> appendAssistant(message, messages)
                else -> appendUser(message, messages)
            }
        }
        result.add("messages", messages)

        src.arrayOrNull("tools")?.let { tools ->
            val converted = JsonArray()
            for (tool in tools) {
                val t = tool.asObjectOrNull() ?: continue
                val fn = JsonObject()
                t.get("name")?.let { fn.add("name", it) }
                t.get("description")?.let { fn.add("description", it) }
                fn.add("parameters", t.objOrNull("input_schema") ?: JsonObject().apply { addProperty("type", "object") })
                converted.add(JsonObject().apply {
                    addProperty("type", "function")
                    add("function", fn)
                })
            }
            if (converted.size() > 0) result.add("tools", converted)
        }

        src.objOrNull("tool_choice")?.let { choice ->
            when (choice.get("type")?.asString) {
                "auto" -> result.addProperty("tool_choice", "auto")
                "any" -> result.addProperty("tool_choice", "required")
                "tool" -> choice.get("name")?.asString?.let { name ->
                    result.add("tool_choice", JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply { addProperty("name", name) })
                    })
                }
                "none" -> result.addProperty("tool_choice", "none")
            }
        }
        return result.toString()
    }

    /** `system` 可能是字符串，也可能是 `[{type:text,text:...}]`；拼成一段文本。 */
    private fun systemText(element: com.google.gson.JsonElement?): String? {
        element ?: return null
        return when {
            element.isJsonPrimitive -> element.asString
            element.isJsonArray -> element.asJsonArray.joinToString("\n") { part ->
                part.asObjectOrNull()?.get("text")?.asString.orEmpty()
            }
            else -> null
        }
    }

    /**
     * assistant 消息：正文与 `tool_use` 分别落到 OpenAI 的 content 与 tool_calls。
     * 只有工具调用没有正文时 content 留空串——OpenAI 允许这种形状。
     */
    private fun appendAssistant(message: JsonObject, out: JsonArray) {
        val text = StringBuilder()
        val toolCalls = JsonArray()
        val content = message.get("content")
        when {
            content == null -> Unit
            content.isJsonPrimitive -> text.append(content.asString)
            content.isJsonArray -> for (part in content.asJsonArray) {
                val block = part.asObjectOrNull() ?: continue
                when (block.get("type")?.asString) {
                    "text" -> text.append(block.get("text")?.asString.orEmpty())
                    "tool_use" -> {
                        val args = block.get("input")?.toString() ?: "{}"
                        toolCalls.add(JsonObject().apply {
                            addProperty("id", block.get("id")?.asString.orEmpty())
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", block.get("name")?.asString.orEmpty())
                                addProperty("arguments", args)
                            })
                        })
                    }
                }
            }
        }
        val msg = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", text.toString())
        }
        if (toolCalls.size() > 0) msg.add("tool_calls", toolCalls)
        out.add(msg)
    }

    /**
     * user 消息：`tool_result` 要拆成独立的 `role:tool` 消息（OpenAI 的形状），
     * 其余 text / image 合并成一条 user 消息。
     */
    private fun appendUser(message: JsonObject, out: JsonArray) {
        val content = message.get("content")
        if (content == null) return
        if (content.isJsonPrimitive) {
            out.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", content.asString)
            })
            return
        }
        if (!content.isJsonArray) return

        val parts = JsonArray()
        for (part in content.asJsonArray) {
            val block = part.asObjectOrNull() ?: continue
            when (block.get("type")?.asString) {
                "text" -> parts.add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", block.get("text")?.asString.orEmpty())
                })
                "image" -> imageUrlOf(block)?.let { url ->
                    parts.add(JsonObject().apply {
                        addProperty("type", "image_url")
                        add("image_url", JsonObject().apply { addProperty("url", url) })
                    })
                }
                "tool_result" -> out.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", block.get("tool_use_id")?.asString.orEmpty())
                    addProperty("content", toolResultText(block.get("content")))
                })
            }
        }
        if (parts.size() == 0) return
        // 只有一段文本时退回字符串形式：更贴近各客户端的原始写法，也少一层嵌套
        val singleText = parts.size() == 1 &&
            parts[0].asObjectOrNull()?.get("type")?.asString == "text"
        out.add(JsonObject().apply {
            addProperty("role", "user")
            if (singleText) {
                addProperty("content", parts[0].asObjectOrNull()?.get("text")?.asString.orEmpty())
            } else {
                add("content", parts)
            }
        })
    }

    private fun imageUrlOf(block: JsonObject): String? {
        val source = block.objOrNull("source") ?: return null
        return when (source.get("type")?.asString) {
            "base64" -> {
                val mediaType = source.get("media_type")?.asString ?: "image/png"
                val data = source.get("data")?.asString ?: return null
                "data:$mediaType;base64,$data"
            }
            "url" -> source.get("url")?.asString
            else -> null
        }
    }

    /** `tool_result.content` 可能是字符串，也可能是 `[{type:text,text:...}]`。 */
    private fun toolResultText(element: com.google.gson.JsonElement?): String {
        element ?: return ""
        return when {
            element.isJsonPrimitive -> element.asString
            element.isJsonArray -> element.asJsonArray.joinToString("\n") { part ->
                part.asObjectOrNull()?.get("text")?.asString.orEmpty()
            }
            else -> ""
        }
    }

    /**
     * 把 OpenAI 的非流式响应翻成 Anthropic 的 message 对象。
     *
     * `stop_reason` 映射：stop→end_turn、length→max_tokens、tool_calls→tool_use。
     * 上游没给 usage 时同样不输出——不编 token 数。
     */
    fun toAnthropicResponse(openAiBody: String, fallbackModel: String): String {
        val src = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull()
            ?: return openAiBody
        val choice = src.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()
        val message = choice?.objOrNull("message")
        val content = JsonArray()

        val text = message?.get("content")
        if (text != null && text.isJsonPrimitive && text.asString.isNotEmpty()) {
            content.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text.asString)
            })
        }
        message?.arrayOrNull("tool_calls")?.forEach { element ->
            val call = element.asObjectOrNull() ?: return@forEach
            val fn = call.objOrNull("function")
            content.add(JsonObject().apply {
                addProperty("type", "tool_use")
                addProperty("id", call.get("id")?.asString.orEmpty())
                addProperty("name", fn?.get("name")?.asString.orEmpty())
                add("input", parseArguments(fn?.get("arguments")?.asString))
            })
        }

        val result = JsonObject().apply {
            addProperty("id", src.get("id")?.asString?.takeIf { it.isNotEmpty() } ?: ("msg_" + System.nanoTime()))
            addProperty("type", "message")
            addProperty("role", "assistant")
            addProperty("model", src.get("model")?.asString?.takeIf { it.isNotEmpty() } ?: fallbackModel)
            add("content", content)
            addProperty("stop_reason", stopReasonOf(choice?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString))
            add("stop_sequence", com.google.gson.JsonNull.INSTANCE)
            usageOf(src.objOrNull("usage"))?.let { add("usage", it) }
        }
        return result.toString()
    }

    private fun parseArguments(raw: String?): JsonObject {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return JsonObject()
        return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: JsonObject()
    }

    private fun stopReasonOf(finishReason: String?): String = when (finishReason) {
        "length" -> "max_tokens"
        "tool_calls" -> "tool_use"
        else -> "end_turn"
    }

    private fun usageOf(usage: JsonObject?): JsonObject? {
        usage ?: return null
        val input = usage.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asLong
        val output = usage.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asLong
        if (input == null && output == null) return null
        return JsonObject().apply {
            addProperty("input_tokens", input ?: 0L)
            addProperty("output_tokens", output ?: 0L)
        }
    }

    /**
     * OpenAI SSE → Anthropic SSE 的有状态翻译器。
     *
     * Anthropic 的流是一条有结构的事件序列：`message_start` 先给消息壳，之后每个内容块
     * 用 `content_block_start` / `content_block_delta` / `content_block_stop` 包裹，
     * 最后 `message_delta` + `message_stop` 收尾。所以这里必须自己维护「当前有哪些块、哪个开着」。
     */
    class SseTranslator(private val model: String) {

        private var started = false
        private var finished = false
        private val openBlocks = LinkedHashSet<Int>()
        /** OpenAI 的 tool_calls[].index → Anthropic 的 content block index。 */
        private val toolBlockIndex = LinkedHashMap<Int, Int>()
        private var nextBlockIndex = 0
        private var inputTokens = 0L
        private var outputTokens = 0L
        private var stopReason = "end_turn"

        /** 处理一行 OpenAI SSE。返回要写给客户端的 Anthropic SSE 文本（可能为空）。 */
        fun translate(line: String): String {
            if (!line.startsWith("data:")) return ""
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) return ""
            if (payload == "[DONE]") return ""
            val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return ""
            val sb = StringBuilder()
            ensureStarted(sb)

            // 上游每个流式 chunk 都可能带 "usage": null（标准 OpenAI 兼容服务就这样），
            // getAsJsonObject 是裸强转，JsonNull 会直接抛 ClassCastException 并掐断整条流。
            obj.objOrNull("usage")?.let { usage ->
                usage.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.let { inputTokens = it.asLong }
                usage.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.let { outputTokens = it.asLong }
            }

            val choice = obj.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()
            val delta = choice?.objOrNull("delta")
            delta?.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }?.let { text ->
                blockDelta(sb, text)
            }
            delta?.arrayOrNull("tool_calls")?.forEach { element ->
                val call = element.asObjectOrNull() ?: return@forEach
                val sourceIndex = call.get("index")?.asInt ?: 0
                val blockIndex = toolBlockIndex.getOrPut(sourceIndex) {
                    val index = nextBlockIndex++
                    openBlocks.add(index)
                    sb.append(event("content_block_start", JsonObject().apply {
                        addProperty("type", "content_block_start")
                        addProperty("index", index)
                        add("content_block", JsonObject().apply {
                            addProperty("type", "tool_use")
                            addProperty("id", call.get("id")?.asString.orEmpty())
                            addProperty("name", call.objOrNull("function")?.get("name")?.asString.orEmpty())
                            add("input", JsonObject())
                        })
                    }))
                    index
                }
                val args = call.objOrNull("function")?.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString
                if (!args.isNullOrEmpty()) {
                    sb.append(event("content_block_delta", JsonObject().apply {
                        addProperty("type", "content_block_delta")
                        addProperty("index", blockIndex)
                        add("delta", JsonObject().apply {
                            addProperty("type", "input_json_delta")
                            addProperty("partial_json", args)
                        })
                    }))
                }
            }
            choice?.get("finish_reason")?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isJsonNull }?.asString?.let {
                stopReason = stopReasonOf(it)
            }
            return if (sb.isEmpty()) "" else frame(sb)
        }

        /** 流结束：关掉还开着的块，补 message_delta 与 message_stop。 */
        fun close(): String {
            if (finished) return ""
            finished = true
            val sb = StringBuilder()
            for (index in openBlocks) {
                sb.append(event("content_block_stop", JsonObject().apply {
                    addProperty("type", "content_block_stop")
                    addProperty("index", index)
                }))
            }
            openBlocks.clear()
            sb.append(event("message_delta", JsonObject().apply {
                addProperty("type", "message_delta")
                add("delta", JsonObject().apply {
                    addProperty("stop_reason", stopReason)
                    add("stop_sequence", com.google.gson.JsonNull.INSTANCE)
                })
                add("usage", JsonObject().apply { addProperty("output_tokens", outputTokens) })
            }))
            sb.append(event("message_stop", JsonObject().apply { addProperty("type", "message_stop") }))
            return frame(sb)
        }

        private fun ensureStarted(sb: StringBuilder) {
            if (started) return
            started = true
            sb.append(event("message_start", JsonObject().apply {
                addProperty("type", "message_start")
                add("message", JsonObject().apply {
                    addProperty("id", "msg_" + System.nanoTime())
                    addProperty("type", "message")
                    addProperty("role", "assistant")
                    addProperty("model", model)
                    add("content", JsonArray())
                    add("stop_reason", com.google.gson.JsonNull.INSTANCE)
                    add("stop_sequence", com.google.gson.JsonNull.INSTANCE)
                    add("usage", JsonObject().apply {
                        addProperty("input_tokens", inputTokens)
                        addProperty("output_tokens", 0)
                    })
                })
            }))
        }

        /** 文本增量：必要时先开一个 text 块。 */
        private fun blockDelta(sb: StringBuilder, text: String) {
            if (openBlocks.isEmpty()) {
                val index = nextBlockIndex++
                openBlocks.add(index)
                sb.append(event("content_block_start", JsonObject().apply {
                    addProperty("type", "content_block_start")
                    addProperty("index", index)
                    add("content_block", JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "")
                    })
                }))
            }
            val index = openBlocks.first()
            sb.append(event("content_block_delta", JsonObject().apply {
                addProperty("type", "content_block_delta")
                addProperty("index", index)
                add("delta", JsonObject().apply {
                    addProperty("type", "text_delta")
                    addProperty("text", text)
                })
            }))
        }

        private fun event(name: String, payload: JsonObject): String =
            "event: $name\ndata: $payload\n\n"

        private fun frame(sb: StringBuilder): String = sb.toString()
    }
}
