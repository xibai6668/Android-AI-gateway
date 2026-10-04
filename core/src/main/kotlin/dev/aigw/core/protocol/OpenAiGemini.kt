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

    const val THOUGHT_SIGNATURE = "skip_thought_signature_validator"

    /** 把 OpenAI 请求体转成 Antigravity envelope 里的 `request` 对象。 */
    fun toGeminiRequest(openAiBody: String): JsonObject {
        val obj = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull() ?: JsonObject()
        val request = JsonObject()
        val contents = JsonArray()
        val systemTexts = ArrayList<String>()

        val messages = obj.arrayOrNull("messages") ?: JsonArray()
        var hasEncounteredConversation = false

        var i = 0
        while (i < messages.size()) {
            val message = messages[i].asObjectOrNull()
            if (message == null) {
                i++
                continue
            }
            val role = message.stringOrNull("role").orEmpty()
            if ((role == "system" || role == "developer") && !hasEncounteredConversation) {
                textOf(message)?.takeIf { it.isNotEmpty() }?.let { systemTexts.add(it) }
                i++
            } else if (role == "user" || role == "system" || role == "developer") {
                hasEncounteredConversation = true
                val parts = multimodalPartsOf(message) ?: JsonArray().apply {
                    textOf(message)?.takeIf { it.isNotEmpty() }?.let {
                        add(JsonObject().apply { addProperty("text", it) })
                    }
                }
                if (parts.size() > 0) {
                    contents.add(JsonObject().apply {
                        addProperty("role", "user")
                        add("parts", parts)
                    })
                }
                i++
            } else if (role == "assistant") {
                hasEncounteredConversation = true
                val partItems = JsonArray()

                // 思维链（带 thoughtSignature，与 CLIProxyAPI 一致）
                message.stringOrNull("reasoning_content")?.takeIf { it.isNotEmpty() }?.let {
                    partItems.add(JsonObject().apply {
                        addProperty("text", it)
                        addProperty("thought", true)
                        addProperty("thoughtSignature", THOUGHT_SIGNATURE)
                    })
                }

                // 文本内容与思维链合并在同一个 model content 内，严防连续相同 role
                textOf(message)?.takeIf { it.isNotEmpty() }?.let {
                    partItems.add(JsonObject().apply { addProperty("text", it) })
                }

                // 工具调用同样聚合在同一个 model content 内
                val calls = message.arrayOrNull("tool_calls")
                val toolCallsList = mutableListOf<Pair<String, String>>()
                if (calls != null && calls.size() > 0) {
                    for (element in calls) {
                        val call = element.asObjectOrNull() ?: continue
                        val function = call.objOrNull("function") ?: continue
                        val callId = call.stringOrNull("id").orEmpty()
                        val fnName = function.stringOrNull("name").orEmpty()
                        val argsStr = function.stringOrNull("arguments").orEmpty()
                        partItems.add(JsonObject().apply {
                            add("functionCall", JsonObject().apply {
                                addProperty("id", callId)
                                addProperty("name", fnName)
                                add("args", runCatching { JsonParser.parseString(argsStr).asJsonObject }.getOrElse { JsonObject() })
                            })
                            addProperty("thoughtSignature", THOUGHT_SIGNATURE)
                        })
                        toolCallsList.add(callId to fnName)
                    }
                }

                if (partItems.size() > 0) {
                    contents.add(JsonObject().apply {
                        addProperty("role", "model")
                        add("parts", partItems)
                    })
                }

                // 按回合收集紧随其后的 tool 响应，聚合为同一个 user content
                if (toolCallsList.isNotEmpty()) {
                    val turnToolResponses = mutableMapOf<String, String>()
                    var j = i + 1
                    while (j < messages.size()) {
                        val nextMsg = messages[j].asObjectOrNull() ?: break
                        val nextRole = nextMsg.stringOrNull("role").orEmpty()
                        if (nextRole == "assistant") break
                        if (nextRole == "tool") {
                            val tId = nextMsg.stringOrNull("tool_call_id").orEmpty()
                            if (tId.isNotEmpty()) {
                                turnToolResponses[tId] = textOf(nextMsg).orEmpty()
                            }
                        }
                        j++
                    }

                    val responseParts = JsonArray()
                    for ((callId, fnName) in toolCallsList) {
                        val resp = turnToolResponses[callId].orEmpty().ifEmpty { "{}" }
                        responseParts.add(JsonObject().apply {
                            add("functionResponse", JsonObject().apply {
                                addProperty("id", callId)
                                addProperty("name", fnName)
                                add("response", JsonObject().apply {
                                    // result 保持字符串不解析：解析成 JSON 对象会触发上游 400（CLIProxyAPI 同样处理）
                                    addProperty("result", resp)
                                })
                            })
                        })
                    }
                    if (responseParts.size() > 0) {
                        contents.add(JsonObject().apply {
                            addProperty("role", "user")
                            add("parts", responseParts)
                        })
                    }
                }
                i++
            } else {
                // role == "tool" 等已在所属 assistant 回合内聚合成 user content，跳过避免重复
                i++
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
        // 默认关闭安全过滤，否则部分正常内容会被截断（与 CLIProxyAPI 默认一致）
        request.add("safetySettings", defaultSafetySettings())
        applyToolChoice(obj, request)
        applyThinkingConfig(obj, request)
        return request
    }

    /**
     * OpenAI tool_choice → Gemini toolConfig.functionCallingConfig。
     * 与 CLIProxyAPI 一致：none=NONE（并删 tools）、auto=AUTO、required/any=ANY、
     * 指定函数名=ANY+allowedFunctionNames。
     */
    private fun applyToolChoice(source: JsonObject, request: JsonObject) {
        val choice = source.get("tool_choice") ?: return
        var mode = ""
        var allowedName = ""
        if (choice.isJsonPrimitive) {
            when (choice.asString.trim().lowercase()) {
                "none" -> mode = "NONE"
                "auto" -> mode = "AUTO"
                "required", "any" -> mode = "ANY"
            }
        } else if (choice.isJsonObject) {
            when (choice.asJsonObject.get("type")?.asString?.trim()?.lowercase().orEmpty()) {
                "none" -> mode = "NONE"
                "function" -> {
                    mode = "ANY"
                    allowedName = choice.asJsonObject.objOrNull("function")?.stringOrNull("name").orEmpty()
                }
            }
        }
        if (mode.isEmpty()) return
        val config = JsonObject().apply {
            addProperty("mode", mode)
            if (allowedName.isNotEmpty()) {
                add("allowedFunctionNames", JsonArray().apply { add(allowedName) })
            }
        }
        val toolConfig = JsonObject().apply { add("functionCallingConfig", config) }
        request.add("toolConfig", toolConfig)
        if (mode == "NONE") request.remove("tools")
    }

    /** OpenAI reasoning_effort → Gemini thinkingConfig（与 CLIProxyAPI 一致）。 */
    private fun applyThinkingConfig(source: JsonObject, request: JsonObject) {
        val effort = source.stringOrNull("reasoning_effort")?.trim()?.lowercase() ?: return
        if (effort.isEmpty()) return
        val generationConfig = request.objOrNull("generationConfig") ?: JsonObject().also { request.add("generationConfig", it) }
        val thinkingConfig = generationConfig.objOrNull("thinkingConfig") ?: JsonObject().also { generationConfig.add("thinkingConfig", it) }
        if (effort == "auto") {
            thinkingConfig.addProperty("thinkingBudget", -1)
        } else {
            thinkingConfig.addProperty("thinkingLevel", effort)
        }
    }

    internal fun defaultSafetySettings(): JsonArray = JsonArray().apply {
        for (category in listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")) {
            add(JsonObject().apply {
                addProperty("category", category)
                addProperty("threshold", "OFF")
            })
        }
        add(JsonObject().apply {
            addProperty("category", "HARM_CATEGORY_CIVIC_INTEGRITY")
            addProperty("threshold", "BLOCK_NONE")
        })
    }

    /**
     * 构造完整的 Antigravity 请求体（envelope）。与 CLIProxyAPI 的 geminiToAntigravity 对齐：
     * requestType 默认 agent（image 模型用 image_gen）；sessionId 从首条 user 文本稳定派生，
     * 同一会话多次请求复用同一 id，上游会话统计才不会乱。
     */
    fun envelope(project: String, model: String, request: JsonObject, requestId: String): String {
        val isImageModel = model.contains("image")
        val requestType = if (isImageModel) "image_gen" else "agent"
        if (isImageModel) {
            // image_gen 的 requestId 带时间戳前缀（上游格式），不走 agent- 前缀
            request.remove("sessionId")
        } else {
            request.addProperty("sessionId", stableSessionIdOf(request))
        }
        return JsonObject().apply {
            addProperty("project", project)
            addProperty("model", model)
            addProperty("userAgent", "antigravity")
            addProperty("requestType", requestType)
            addProperty("requestId", requestId)
            add("request", request)
        }.toString()
    }

    /** 首条 user 文本 SHA256 前 8 字节派生稳定 sessionId；没有则随机（与 CLIProxyAPI 一致）。 */
    private fun stableSessionIdOf(request: JsonObject): String {
        val contents = request.arrayOrNull("contents") ?: return randomSessionId()
        for (element in contents) {
            val content = element.asObjectOrNull() ?: continue
            if (content.stringOrNull("role") != "user") continue
            val text = content.arrayOrNull("parts")?.firstOrNull()?.asObjectOrNull()?.stringOrNull("text").orEmpty()
            if (text.isEmpty()) continue
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (hash[i].toLong() and 0xFF)
            value = value and Long.MAX_VALUE
            return "-$value"
        }
        return randomSessionId()
    }

    private fun randomSessionId(): String = "-${(Math.random() * 9_000_000_000_000_000_000L).toLong() + 1_000_000_000_000_000_000L}"

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

    /**
     * user 消息的多模态 parts：text → `{text}`，image_url → `{inlineData}`（data URL base64）
     * 或 `{fileData}`（https 图片链接）。
     * 只有纯文本（或无法解析的 content）时返回 null，走原文本路径。
     */
    private fun multimodalPartsOf(message: JsonObject): JsonArray? {
        val content = message.get("content") ?: return null
        if (!content.isJsonArray) return null
        val parts = JsonArray()
        val textBuffer = StringBuilder()
        fun flushText() {
            if (textBuffer.isNotEmpty()) {
                parts.add(JsonObject().apply { addProperty("text", textBuffer.toString()) })
                textBuffer.setLength(0)
            }
        }
        for (element in content.asJsonArray) {
            val part = element.asObjectOrNull() ?: continue
            when (part.stringOrNull("type").orEmpty()) {
                "text" -> part.stringOrNull("text")?.takeIf { it.isNotEmpty() }?.let { textBuffer.append(it) }
                "image_url" -> {
                    val url = part.objOrNull("image_url")?.stringOrNull("url").orEmpty()
                    if (url.isEmpty()) continue
                    flushText()
                    val dataMatch = Regex("^data:([^;,]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL)
                        .matchEntire(url)
                    if (dataMatch != null) {
                        parts.add(JsonObject().apply {
                            add("inlineData", JsonObject().apply {
                                addProperty("mimeType", dataMatch.groupValues[1])
                                addProperty("data", dataMatch.groupValues[2])
                            })
                        })
                    } else {
                        parts.add(JsonObject().apply {
                            add("fileData", JsonObject().apply {
                                addProperty("fileUri", url)
                            })
                        })
                    }
                }
            }
        }
        flushText()
        return if (parts.size() > 0) parts else null
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
                    // id 必带：工具响应靠它配对（与 CLIProxyAPI 一致）
                    addProperty("id", call.stringOrNull("id").orEmpty())
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
                        // tool_call_id 原样带回，与 functionCall.id 配对
                        addProperty("id", message.stringOrNull("tool_call_id").orEmpty())
                        addProperty("name", name)
                        add("response", JsonObject().apply {
                            // result 保持字符串不解析：解析成 JSON 对象会触发上游 400（CLIProxyAPI 同样处理）
                            addProperty("result", content.ifEmpty { "{}" })
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
                // 上游要求字段名为 parametersJsonSchema（与 CLIProxyAPI 一致），不认 parameters
                function.objOrNull("parameters")?.let { add("parametersJsonSchema", sanitizeSchema(it)) }
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
            "strict", "additionalProperties",
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
        private var sawToolCall = false
        private var upstreamFinishReason = ""
        private var modelVersion = ""

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
                // 只含 thoughtSignature 的 part 是加密签名，跳过但保留同 part 里的真实内容
                val hasSignature = !obj.stringOrNull("thoughtSignature").isNullOrEmpty()
                val call = obj.objOrNull("functionCall")
                val hasContent = obj.has("text") || call != null || obj.has("inlineData")
                if (hasSignature && !hasContent) return@forEach
                obj.stringOrNull("text")?.let { chunk ->
                    if (obj.boolOrNull("thought") == true) {
                        delta.addProperty("reasoning_content", chunk)
                    } else {
                        text += chunk
                    }
                }
                call?.let { functionCall ->
                    sawToolCall = true
                    val calls = delta.arrayOrNull("tool_calls")
                        ?: JsonArray().also { fresh -> delta.add("tool_calls", fresh) }
                    val args = functionCall.get("args")?.let { if (it.isJsonObject) it.toString() else "{}" } ?: "{}"
                    calls.add(JsonObject().apply {
                        addProperty("id", "call-${functionCall.stringOrNull("name").orEmpty()}-${System.nanoTime()}")
                        addProperty("index", calls.size())
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", functionCall.stringOrNull("name").orEmpty())
                            addProperty("arguments", args)
                        })
                    })
                }
                // 上游回图（inlineData）→ OpenAI 的 images 字段（与 CLIProxyAPI 一致）
                obj.objOrNull("inlineData")?.let { inline ->
                    val data = inline.stringOrNull("data").orEmpty()
                    if (data.isEmpty()) return@let
                    val mime = inline.stringOrNull("mimeType").orEmpty().ifEmpty { "image/png" }
                    val images = delta.arrayOrNull("images")
                        ?: JsonArray().also { fresh -> delta.add("images", fresh) }
                    images.add(JsonObject().apply {
                        addProperty("index", images.size())
                        addProperty("type", "image_url")
                        add("image_url", JsonObject().apply {
                            addProperty("url", "data:$mime;base64,$data")
                        })
                    })
                }
            }
            if (text.isNotEmpty()) delta.addProperty("content", text)

            response.stringOrNull("modelVersion")?.takeIf { it.isNotEmpty() }?.let { modelVersion = it }

            // 缓存上游 finishReason，但只在最终 chunk（同时带 usage）时输出：
            // 上游可能把两者拆在不同 chunk，早终结会让严格客户端丢掉后续内容
            candidate.stringOrNull("finishReason")?.takeIf { it.isNotEmpty() }?.let { upstreamFinishReason = it.uppercase() }
            val usage = usageOf(response)
            val isFinalChunk = upstreamFinishReason.isNotEmpty() && usage != null
            val finishReason = when {
                isFinalChunk && sawToolCall -> "tool_calls"
                isFinalChunk && upstreamFinishReason == "MAX_TOKENS" -> "max_tokens"
                isFinalChunk -> "stop"
                else -> null
            }

            if (delta.size() == 0 && finishReason == null) return emptyList()
            if (finishReason != null) done = true

            val chunk = JsonObject().apply {
                addProperty("id", id)
                addProperty("object", "chat.completion.chunk")
                addProperty("created", created)
                addProperty("model", modelVersion.ifEmpty { model })
                add("choices", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("index", 0)
                        add("delta", delta)
                        if (finishReason != null) addProperty("finish_reason", finishReason)
                    })
                })
                usage?.let { add("usage", it) }
            }
            val out = ArrayList<String>(2)
            out.add("data: $chunk\n\n")
            if (finishReason != null) out.add(SSE_DONE)
            return out
        }

        /** 流结束：上游从未发 finishReason 时补一个终止 chunk，不能只发 [DONE] 了事。 */
        fun close(): List<String> {
            if (done) return emptyList()
            done = true
            val finishReason = when {
                sawToolCall -> "tool_calls"
                upstreamFinishReason == "MAX_TOKENS" -> "max_tokens"
                else -> "stop"
            }
            val chunk = JsonObject().apply {
                addProperty("id", id)
                addProperty("object", "chat.completion.chunk")
                addProperty("created", created)
                addProperty("model", modelVersion.ifEmpty { model })
                add("choices", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("index", 0)
                        add("delta", JsonObject())
                        addProperty("finish_reason", finishReason)
                    })
                })
            }
            return listOf("data: $chunk\n\n", SSE_DONE)
        }

        private fun usageOf(response: JsonObject): JsonObject? {
            val usage = response.objOrNull("usageMetadata") ?: return null
            val thoughts = usage.longOrNull("thoughtsTokenCount") ?: 0L
            val cached = usage.longOrNull("cachedContentTokenCount") ?: 0L
            return JsonObject().apply {
                addProperty("prompt_tokens", usage.longOrNull("promptTokenCount") ?: 0L)
                addProperty("completion_tokens", usage.longOrNull("candidatesTokenCount") ?: 0L)
                addProperty("total_tokens", usage.longOrNull("totalTokenCount") ?: 0L)
                if (thoughts > 0) {
                    add("completion_tokens_details", JsonObject().apply {
                        addProperty("reasoning_tokens", thoughts)
                    })
                }
                if (cached > 0) {
                    add("prompt_tokens_details", JsonObject().apply {
                        addProperty("cached_tokens", cached)
                    })
                }
            }
        }

        companion object {
            const val SSE_DONE = "data: [DONE]\n\n"
        }
    }
}
