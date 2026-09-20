package dev.aigw.core.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.UUID

/**
 * OpenAI Chat Completions ⇄ Google Antigravity（Gemini 风格）的双向转换。
 *
 * Antigravity 的 `cloudcode-pa.googleapis.com/v1internal:*` 只认 Gemini 的 `contents` 结构：
 * role 用 `user`/`model`（不是 `assistant`），`systemInstruction` 必须是带 `parts` 的对象，
 * 且 JSON Schema 里不支持 `const`/`$ref`/`$schema` 等字段（会 400）。这些坑都在这层处理。
 */
object OpenAiGemini {

    /** 把 OpenAI 请求体转成 Antigravity envelope 里的 `request` 对象。 */
    fun toGeminiRequest(openAiBody: String): JsonObject {
        val obj = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull() ?: JsonObject()
        val request = JsonObject()
        val contents = JsonArray()
        val systemTexts = ArrayList<String>()

        obj.arrayOrNull("messages")?.forEach { element ->
            val message = element.asObjectOrNull() ?: return@forEach
            val role = message.stringOrNull("role").orEmpty()
            when (role) {
                "system", "developer" -> textOf(message)?.takeIf { it.isNotEmpty() }?.let { systemTexts.add(it) }
                "assistant" -> {
                    textOf(message)?.takeIf { it.isNotEmpty() }?.let { contents.add(textContent("model", it)) }
                    functionCallsOf(message)?.let { if (it.size() > 0) contents.add(it) }
                }
                "tool" -> functionResponseOf(message)?.let { contents.add(it) }
                else -> textOf(message)?.takeIf { it.isNotEmpty() }?.let { contents.add(textContent("user", it)) }
            }
        }
        request.add("contents", contents)

        if (systemTexts.isNotEmpty()) {
            request.add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    systemTexts.forEach { add(JsonObject().apply { addProperty("text", it) }) }
                })
            })
        }

        val generationConfig = JsonObject()
        obj.get("max_tokens")?.takeIf { it.isJsonPrimitive }?.asInt?.let {
            generationConfig.addProperty("maxOutputTokens", it)
        }
        obj.get("temperature")?.takeIf { it.isJsonPrimitive }?.asDouble?.let {
            generationConfig.addProperty("temperature", it)
        }
        obj.get("top_p")?.takeIf { it.isJsonPrimitive }?.asDouble?.let {
            generationConfig.addProperty("topP", it)
        }
        obj.arrayOrNull("stop")?.let { generationConfig.add("stopSequences", it) }
        if (generationConfig.size() > 0) request.add("generationConfig", generationConfig)

        obj.arrayOrNull("tools")?.let { tools -> toGeminiTools(tools)?.let { request.add("tools", it) } }
        return request
    }

    /** 构造完整的 Antigravity 请求体（envelope）。 */
    fun envelope(project: String, model: String, request: JsonObject, requestId: String): String =
        JsonObject().apply {
            addProperty("project", project)
            addProperty("model", model)
            addProperty("userAgent", "antigravity")
            addProperty("requestType", "agent")
            addProperty("requestId", requestId)
            add("request", request)
        }.toString()

    fun newRequestId(): String = "agent-" + UUID.randomUUID().toString().replace("-", "").take(24)

    // ------------------------------------------------------------------ 请求侧

    /** content 既可能是纯字符串，也可能是 `[{type:text,text}]` 数组。 */
    private fun textOf(message: JsonObject): String? {
        val content = message.get("content") ?: return null
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray
                .mapNotNull { it.asObjectOrNull() }
                .filter { it.stringOrNull("type").orEmpty() != "image_url" }
                .mapNotNull { it.stringOrNull("text") }
                .joinToString("")
            else -> null
        }
    }

    private fun textContent(role: String, text: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) })
    }

    /** assistant 的 `tool_calls` → Gemini `functionCall` parts。 */
    private fun functionCallsOf(message: JsonObject): JsonObject? {
        val calls = message.arrayOrNull("tool_calls") ?: return null
        if (calls.size() == 0) return null
        val parts = JsonArray()
        for (element in calls) {
            val call = element.asObjectOrNull() ?: continue
            val function = call.objOrNull("function") ?: continue
            val args = function.stringOrNull("arguments").orEmpty()
            parts.add(JsonObject().apply {
                add("functionCall", JsonObject().apply {
                    addProperty("name", function.stringOrNull("name").orEmpty())
                    add("args", runCatching { JsonParser.parseString(args).asJsonObject }.getOrElse { JsonObject() })
                })
            })
        }
        if (parts.size() == 0) return null
        return JsonObject().apply {
            addProperty("role", "model")
            add("parts", parts)
        }
    }

    /** 工具结果 → Gemini `functionResponse` part。 */
    private fun functionResponseOf(message: JsonObject): JsonObject? {
        val name = message.stringOrNull("name").orEmpty()
        val content = textOf(message).orEmpty()
        if (name.isEmpty()) return null
        return JsonObject().apply {
            addProperty("role", "user")
            add("parts", JsonArray().apply {
                add(JsonObject().apply {
                    add("functionResponse", JsonObject().apply {
                        addProperty("name", name)
                        add("response", JsonObject().apply {
                            addProperty("content", content)
                        })
                    })
                })
            })
        }
    }

    private fun toGeminiTools(tools: JsonArray): JsonArray? {
        val declarations = JsonArray()
        for (element in tools) {
            val tool = element.asObjectOrNull() ?: continue
            val function = tool.objOrNull("function") ?: continue
            val declaration = JsonObject().apply {
                addProperty("name", function.stringOrNull("name").orEmpty())
                function.stringOrNull("description")?.let { addProperty("description", it) }
                function.objOrNull("parameters")?.let { add("parameters", sanitizeSchema(it)) }
            }
            declarations.add(declaration)
        }
        if (declarations.size() == 0) return null
        return JsonArray().apply {
            add(JsonObject().apply { add("functionDeclarations", declarations) })
        }
    }

    /** 剔除 Antigravity 明确不支持的 JSON Schema 字段（会 400）。 */
    private fun sanitizeSchema(schema: JsonObject): JsonObject {
        val unsupported = setOf(
            "const", "\$ref", "\$defs", "definitions", "\$schema", "\$id", "default", "examples",
        )
        val result = JsonObject()
        for ((key, value) in schema.entrySet()) {
            if (key in unsupported) continue
            result.add(key, sanitizeValue(value))
        }
        return result
    }

    private fun sanitizeValue(value: com.google.gson.JsonElement): com.google.gson.JsonElement = when {
        value.isJsonObject -> sanitizeSchema(value.asJsonObject)
        value.isJsonArray -> JsonArray().apply { value.asJsonArray.forEach { add(sanitizeValue(it)) } }
        else -> value
    }

    // ------------------------------------------------------------------ 响应侧

    /**
     * Antigravity SSE → OpenAI SSE。
     *
     * 上游每行形如 `data: {"response":{"candidates":[...],"usageMetadata":{...}},"traceId":"..."}`，
     * 这里逐行翻译成 `chat.completion.chunk`；流结束时补一个 `data: [DONE]`。
     */
    class SseTranslator(
        private val id: String,
        private val model: String,
        private val created: Long = System.currentTimeMillis() / 1000,
    ) {
        private var done = false

        fun translate(line: String): List<String> {
            if (!line.startsWith("data:")) return emptyList()
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return emptyList()
            val root = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return emptyList()
            val response = root.objOrNull("response") ?: root

            val choices = response.arrayOrNull("candidates") ?: return emptyList()
            val candidate = choices.firstOrNull()?.asObjectOrNull() ?: return emptyList()
            val delta = JsonObject()
            var text = ""
            candidate.objOrNull("content")?.arrayOrNull("parts")?.forEach { part ->
                val obj = part.asObjectOrNull() ?: return@forEach
                // thought 为 true 的是思维链，归到 reasoning_content
                obj.stringOrNull("text")?.let { chunk ->
                    if (obj.boolOrNull("thought") == true) {
                        delta.addProperty("reasoning_content", chunk)
                    } else {
                        text += chunk
                    }
                }
            }
            if (text.isNotEmpty()) delta.addProperty("content", text)

            val finishReason = candidate.stringOrNull("finishReason")
                ?.takeIf { it.isNotEmpty() && it != "FINISH_REASON_UNSPECIFIED" }
                ?.let { mapFinishReason(it) }

            if (delta.size() == 0 && finishReason == null) return emptyList()
            if (finishReason != null) done = true

            val chunk = JsonObject().apply {
                addProperty("id", id)
                addProperty("object", "chat.completion.chunk")
                addProperty("created", created)
                addProperty("model", model)
                add("choices", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("index", 0)
                        add("delta", delta)
                        if (finishReason != null) addProperty("finish_reason", finishReason)
                    })
                })
                usageOf(response)?.let { add("usage", it) }
            }
            val out = ArrayList<String>(2)
            out.add("data: $chunk\n\n")
            if (finishReason != null) out.add(SSE_DONE)
            return out
        }

        fun close(): List<String> = if (done) emptyList() else listOf(SSE_DONE)

        private fun usageOf(response: JsonObject): JsonObject? {
            val usage = response.objOrNull("usageMetadata") ?: return null
            return JsonObject().apply {
                addProperty("prompt_tokens", usage.longOrNull("promptTokenCount") ?: 0L)
                addProperty("completion_tokens", usage.longOrNull("candidatesTokenCount") ?: 0L)
                addProperty("total_tokens", usage.longOrNull("totalTokenCount") ?: 0L)
            }
        }

        private fun mapFinishReason(raw: String): String = when (raw) {
            "STOP" -> "stop"
            "MAX_TOKENS" -> "length"
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter"
            else -> "stop"
        }

        companion object {
            const val SSE_DONE = "data: [DONE]\n\n"
        }
    }
}
