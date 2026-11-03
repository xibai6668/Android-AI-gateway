package dev.aigw.core.provider.chatgpt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * OpenAI Chat Completions ⇄ OpenAI Responses 的双向转换（Codex 后端只认 Responses）。
 *
 * 这层之所以必须存在：网关的对外契约是 OpenAI Chat，而 Codex 上游是 Responses ——
 * 两者的消息形状、工具形状、流事件完全不同。转换里每一条「不能带什么」都有上游硬约束：
 *
 * - `store` 必须 false，`include` 必须带 `reasoning.encrypted_content`；
 * - **不能**带 `max_output_tokens` / `temperature` / `top_p`（Codex 会 400）；
 * - system 提示要用 `developer` 角色放进 `input`，不能用 `instructions`；
 * - 工具必须显式 `strict: false`，否则 schema 里的可选参数会被当成必填。
 */
object OpenAiResponses {

    /** 客户端请求体 → Codex `/responses` 请求体。 */
    fun toResponsesRequest(openAiBody: String, model: String): String {
        val body = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull()
            ?: throw IllegalArgumentException("请求体不是合法 JSON")

        val input = JsonArray()
        val messages = body.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray
        if (messages != null) {
            for (element in messages) {
                if (!element.isJsonObject) continue
                appendMessage(element.asJsonObject, input)
            }
        }

        val out = JsonObject()
        out.addProperty("model", model)
        // Codex 只支持流式：客户端要非流式也先按流式请求，由 provider 聚合。
        out.addProperty("stream", true)
        out.addProperty("store", false)
        out.add("input", input)
        out.add("include", JsonArray().apply { add(JsonPrimitive("reasoning.encrypted_content")) })

        val reasoning = JsonObject()
        reasoning.addProperty("summary", "auto")
        val effort = body.stringOrNull("reasoning_effort")?.takeIf { it.isNotBlank() }
        if (effort != null) reasoning.addProperty("effort", effort)
        out.add("reasoning", reasoning)

        body.get("tools")?.takeIf { it.isJsonArray }?.asJsonArray?.let { tools ->
            val converted = JsonArray()
            for (tool in tools) {
                convertTool(tool, converted)
            }
            if (converted.size() > 0) out.add("tools", converted)
        }

        body.get("tool_choice")?.let { out.add("tool_choice", convertToolChoice(it)) }
        body.get("parallel_tool_calls")?.takeIf { it.isJsonPrimitive }?.let { out.add("parallel_tool_calls", it) }
        return out.toString()
    }

    private fun appendMessage(msg: JsonObject, input: JsonArray) {
        when (msg.stringOrNull("role")?.lowercase()) {
            "system", "developer" -> {
                val text = textOf(msg.get("content")) ?: return
                input.add(JsonObject().apply {
                    addProperty("role", "developer")
                    addProperty("content", text)
                })
            }

            "user" -> input.add(userItem(msg))

            "assistant" -> {
                val text = textOf(msg.get("content"))
                if (!text.isNullOrEmpty()) {
                    input.add(JsonObject().apply {
                        addProperty("type", "message")
                        addProperty("role", "assistant")
                        add("content", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("type", "output_text")
                                addProperty("text", text)
                            })
                        })
                    })
                }
                val calls = msg.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
                for (element in calls) {
                    if (!element.isJsonObject) continue
                    val call = element.asJsonObject
                    val fn = call.objOrNull("function") ?: continue
                    input.add(JsonObject().apply {
                        addProperty("type", "function_call")
                        addProperty("call_id", call.stringOrNull("id").orEmpty())
                        addProperty("name", fn.stringOrNull("name").orEmpty())
                        addProperty("arguments", fn.stringOrNull("arguments") ?: "{}")
                    })
                }
            }

            // 工具结果：Chat 里是 role=tool + tool_call_id，Responses 里是 function_call_output。
            "tool", "function" -> {
                input.add(JsonObject().apply {
                    addProperty("type", "function_call_output")
                    addProperty("call_id", msg.stringOrNull("tool_call_id") ?: msg.stringOrNull("name").orEmpty())
                    addProperty("output", textOf(msg.get("content")) ?: "")
                })
            }
        }
    }

    private fun userItem(msg: JsonObject): JsonObject {
        val content = msg.get("content")
        if (content == null || content.isJsonNull || content.isJsonPrimitive) {
            return JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", textOf(content) ?: "")
            }
        }
        val parts = JsonArray()
        if (content.isJsonArray) {
            for (element in content.asJsonArray) {
                if (!element.isJsonObject) continue
                val part = element.asJsonObject
                when (part.stringOrNull("type")) {
                    "text", "input_text" -> textOf(part.get("text"))?.let { text ->
                        parts.add(JsonObject().apply {
                            addProperty("type", "input_text")
                            addProperty("text", text)
                        })
                    }

                    "image_url" -> imageUrlOf(part.get("image_url"))?.let { url ->
                        parts.add(JsonObject().apply {
                            addProperty("type", "input_image")
                            addProperty("image_url", url)
                        })
                    }
                }
            }
        }
        return JsonObject().apply {
            addProperty("role", "user")
            if (parts.size() > 0) add("content", parts) else addProperty("content", textOf(content) ?: "")
        }
    }

    private fun imageUrlOf(element: JsonElement?): String? = when {
        element == null || element.isJsonNull -> null
        element.isJsonPrimitive -> element.asString
        element.isJsonObject -> element.asJsonObject.stringOrNull("url")
        else -> null
    }

    /** `{"type":"text","text":"…"}` 数组拼成纯文本；纯字符串原样返回。 */
    private fun textOf(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) return element.asString
        if (!element.isJsonArray) return null
        val builder = StringBuilder()
        for (part in element.asJsonArray) {
            if (!part.isJsonObject) continue
            val obj = part.asJsonObject
            val type = obj.stringOrNull("type") ?: continue
            if (type == "text" || type == "input_text" || type == "output_text") {
                obj.stringOrNull("text")?.let { builder.append(it) }
            }
        }
        return builder.toString()
    }

    private fun convertTool(tool: JsonElement, out: JsonArray) {
        if (!tool.isJsonObject) return
        val obj = tool.asJsonObject
        if (!obj.stringOrNull("type").orEmpty().equals("function", ignoreCase = true)) return
        val fn = obj.objOrNull("function") ?: return
        out.add(JsonObject().apply {
            addProperty("type", "function")
            addProperty("name", fn.stringOrNull("name").orEmpty())
            fn.stringOrNull("description")?.let { addProperty("description", it) }
            fn.get("parameters")?.let { add("parameters", it) }
            // 必须显式 false：省略时上游按 strict=true 处理，schema 里的可选字段会被当成必填。
            addProperty("strict", false)
        })
    }

    private fun convertToolChoice(element: JsonElement): JsonElement {
        if (!element.isJsonObject) return element
        val fn = element.asJsonObject.objOrNull("function") ?: return element
        return JsonObject().apply {
            addProperty("type", "function")
            addProperty("name", fn.stringOrNull("name").orEmpty())
        }
    }

    /**
     * Codex SSE → OpenAI chat.completion.chunk 逐行翻译。
     *
     * 事件名优先取 data JSON 里的 `type`；缺失时回退到 `event:` 行（Codex 两种都会发，
     * 但个别代理只透传一种）。非 data 行一律忽略。
     */
    class SseTranslator(
        private val completionId: String,
        private val model: String,
        private val createdSeconds: Long = System.currentTimeMillis() / 1000,
    ) {
        private val toolIndexByOutput = mutableMapOf<Int, Int>()
        private var nextToolIndex = 0
        private var sawToolCall = false
        private var finished = false
        private var lastEvent: String? = null

        fun translate(line: String): List<String> {
            if (line.startsWith("event:")) {
                lastEvent = line.removePrefix("event:").trim()
                return emptyList()
            }
            if (!line.startsWith("data:")) return emptyList()
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return emptyList()
            val event = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return emptyList()
            val type = event.stringOrNull("type") ?: lastEvent.orEmpty()
            return when (type) {
                "response.created" -> listOf(chunk(roleDelta()))
                "response.output_item.added" -> onItemAdded(event)
                "response.output_text.delta" -> {
                    val delta = event.stringOrNull("delta") ?: return emptyList()
                    if (delta.isEmpty()) emptyList() else listOf(chunk(textDelta(delta)))
                }

                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                    val delta = event.stringOrNull("delta") ?: return emptyList()
                    if (delta.isEmpty()) emptyList() else listOf(chunk(reasoningDelta(delta)))
                }

                "response.function_call_arguments.delta" -> onArgumentsDelta(event)
                "response.completed", "response.incomplete" -> onTerminal(event)
                "response.failed", "error" -> onFailed(event)
                else -> emptyList()
            }
        }

        /** 流结束时补帧：上游没发 completed 也要让客户端收到结束块与 [DONE]。 */
        fun onFinish(): List<String> {
            if (finished) return emptyList()
            finished = true
            return listOf(chunk(JsonObject(), "stop"), DONE)
        }

        private fun onItemAdded(event: JsonObject): List<String> {
            val item = event.objOrNull("item") ?: return emptyList()
            if (!item.stringOrNull("type").orEmpty().equals("function_call", ignoreCase = true)) return emptyList()
            val outputIndex = event.get("output_index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val index = nextToolIndex++
            toolIndexByOutput[outputIndex] = index
            sawToolCall = true
            val callId = item.stringOrNull("call_id") ?: item.stringOrNull("id").orEmpty()
            val toolCall = JsonObject().apply {
                addProperty("index", index)
                addProperty("id", callId)
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", item.stringOrNull("name").orEmpty())
                    addProperty("arguments", "")
                })
            }
            return listOf(chunk(JsonObject().apply {
                add("tool_calls", JsonArray().apply { add(toolCall) })
            }))
        }

        private fun onArgumentsDelta(event: JsonObject): List<String> {
            val outputIndex = event.get("output_index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val index = toolIndexByOutput[outputIndex] ?: 0
            val delta = event.stringOrNull("delta") ?: return emptyList()
            if (delta.isEmpty()) return emptyList()
            val toolCall = JsonObject().apply {
                addProperty("index", index)
                add("function", JsonObject().apply { addProperty("arguments", delta) })
            }
            return listOf(chunk(JsonObject().apply {
                add("tool_calls", JsonArray().apply { add(toolCall) })
            }))
        }

        private fun onTerminal(event: JsonObject): List<String> {
            if (finished) return emptyList()
            finished = true
            val response = event.objOrNull("response")
            val usage = usageOf(response?.objOrNull("usage"))
            val reason = when {
                sawToolCall -> "tool_calls"
                response?.stringOrNull("status") == "incomplete" -> {
                    val detail = response.objOrNull("incomplete_details")?.stringOrNull("reason")
                    when (detail) {
                        "max_output_tokens" -> "length"
                        "content_filter" -> "content_filter"
                        else -> "stop"
                    }
                }

                else -> "stop"
            }
            return listOf(chunk(JsonObject(), reason, usage), DONE)
        }

        private fun onFailed(event: JsonObject): List<String> {
            if (finished) return emptyList()
            finished = true
            val response = event.objOrNull("response")
            val message = response?.objOrNull("error")?.stringOrNull("message")
                ?: event.objOrNull("error")?.stringOrNull("message")
                ?: event.stringOrNull("message")
                ?: "上游流式响应失败"
            // 流内错误没法用 HTTP 状态表达：把原因作为最后一段内容交给客户端，并正常收尾。
            return listOf(chunk(textDelta("[ChatGPT 上游错误] $message"), "stop"), DONE)
        }

        private fun usageOf(obj: JsonObject?): JsonObject? {
            if (obj == null) return null
            val prompt = obj.longOrNull("input_tokens") ?: 0L
            val completion = obj.longOrNull("output_tokens") ?: 0L
            val total = obj.longOrNull("total_tokens") ?: (prompt + completion)
            return JsonObject().apply {
                addProperty("prompt_tokens", prompt)
                addProperty("completion_tokens", completion)
                addProperty("total_tokens", total)
            }
        }

        private fun roleDelta(): JsonObject = JsonObject().apply { addProperty("role", "assistant") }

        private fun textDelta(text: String): JsonObject = JsonObject().apply { addProperty("content", text) }

        private fun reasoningDelta(text: String): JsonObject = JsonObject().apply { addProperty("reasoning_content", text) }

        private fun chunk(delta: JsonObject, finishReason: String? = null, usage: JsonObject? = null): String {
            val choice = JsonObject().apply {
                addProperty("index", 0)
                add("delta", delta)
                if (finishReason == null) add("finish_reason", JsonNull.INSTANCE) else addProperty("finish_reason", finishReason)
            }
            val payload = JsonObject().apply {
                addProperty("id", completionId)
                addProperty("object", "chat.completion.chunk")
                addProperty("created", createdSeconds)
                addProperty("model", model)
                add("choices", JsonArray().apply { add(choice) })
                if (usage != null) add("usage", usage)
            }
            return "data: $payload\n\n"
        }

        private companion object {
            const val DONE = "data: [DONE]\n\n"
        }
    }

    /** 模型目录：只保留 `visibility = list` 的条目。 */
    fun parseModels(raw: String): List<ProviderModel> {
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return emptyList()
        val items = obj.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val models = mutableListOf<ProviderModel>()
        for (element in items) {
            if (!element.isJsonObject) continue
            val model = element.asJsonObject
            val visibility = model.stringOrNull("visibility")
            if (visibility != null && !visibility.equals("list", ignoreCase = true)) continue
            val id = model.stringOrNull("slug") ?: model.stringOrNull("id") ?: continue
            if (id.isBlank()) continue
            val name = model.stringOrNull("display_name") ?: model.stringOrNull("displayName") ?: id
            val context = model.longOrNull("context_window") ?: model.longOrNull("context_length") ?: 0L
            models.add(ProviderModel(id = id, name = name, contextWindow = context))
        }
        return models
    }

    /**
     * 订阅用量 → 两个额度窗口。
     *
     * Codex 的用量模型是「滚动窗口百分比」而不是「剩余次数」：primary 是 5 小时窗口，
     * secondary 是周窗口。这里把 `used_percent` 投影成 `limit = 100` 的额度包，
     * 这样现有 QuotaPackBar 能直接画进度条，不用为 ChatGPT 单独写 UI。
     */
    fun parseUsage(raw: String): List<QuotaPack> {
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return emptyList()
        val rateLimit = obj.objOrNull("rate_limit") ?: obj
        val packs = mutableListOf<QuotaPack>()
        packOf(rateLimit.objOrNull("primary_window"), "5 小时窗口", packs)
        packOf(rateLimit.objOrNull("secondary_window"), "周窗口", packs)
        return packs
    }

    private fun packOf(window: JsonObject?, name: String, out: MutableList<QuotaPack>) {
        if (window == null) return
        val percent = window.get("used_percent")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return
        val used = percent.coerceIn(0.0, 100.0).toLong()
        val resetAt = window.longOrNull("reset_at") ?: 0L
        out.add(
            QuotaPack(
                name = name,
                limit = 100,
                used = used,
                remain = (100 - used).coerceAtLeast(0),
                // API 给的是 Unix 秒；字段本身也是秒。
                expireAt = if (resetAt > 0) resetAt else 0L,
            ),
        )
    }
}
