package dev.aigw.core.provider.trae

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

/**
 * OpenAI 请求体 → SOLO `llm_utils_chat` 请求体。
 *
 * 上游 Go 结构体与 OpenAI 规范有几处硬性差异，缺一即反序列化失败：
 *  - `messages[].content` 必须是 `[{type:text,text:...}]` 数组
 *  - `tools[].function.parameters` 必须是 JSON **字符串**而不是对象
 *  - `tool_choice` 只接受字符串
 *  - assistant 的 `tool_calls[].function` 要改名为上游认识的 `function_call`
 *  - `stream` 恒为 true（非流式由网关侧聚合）
 */
object TraePayload {

    /** 单遍改写；无法解析时原样返回，交由上游报错。 */
    fun prepare(src: String, fallbackConfigName: String = TraeConstants.DEFAULT_CONFIG_NAME): String {
        if (src.isEmpty()) return src
        val obj = runCatching { JsonParser.parseString(src).asJsonObject }.getOrNull() ?: return src

        obj.addProperty("stream", true)
        obj.addProperty("function", TraeConstants.FUNCTION)

        rewriteMessages(obj)

        val model = obj.str("model").trim().ifEmpty { fallbackConfigName }
        obj.addProperty("config_name", model)
        obj.addProperty("model", model)

        normalizeToolChoice(obj)
        normalizeTools(obj)

        return obj.toString()
    }

    private fun rewriteMessages(obj: JsonObject) {
        val messages = obj.get("messages") as? JsonArray ?: return
        for (element in messages) {
            val message = element as? JsonObject ?: continue

            if (message.str("role") == "assistant") {
                rewriteAssistantToolCalls(message)
            }

            val content = message.get("content")
            if (content == null || content is JsonNull) continue
            if (content.isJsonPrimitive && content.asJsonPrimitive.isString) {
                val wrapped = JsonArray()
                wrapped.add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", content.asString)
                })
                message.add("content", wrapped)
            }
            // 已是数组（多模态）→ 原样透传
        }
    }

    private fun rewriteAssistantToolCalls(message: JsonObject) {
        val toolCalls = message.get("tool_calls") as? JsonArray ?: return
        val kept = JsonArray()
        for (element in toolCalls) {
            val call = element as? JsonObject ?: continue
            (call.get("function") as? JsonObject)?.let { fn ->
                call.add("function_call", fn)
                call.remove("function")
            }
            // 上游要求 function_call.name 必填，无名条目直接剔除
            val name = (call.get("function_call") as? JsonObject)?.str("name").orEmpty().trim()
            if (name.isEmpty()) continue
            kept.add(call)
        }
        if (kept.size() == 0) message.remove("tool_calls") else message.add("tool_calls", kept)
    }

    private fun normalizeToolChoice(obj: JsonObject) {
        val choice: JsonElement = obj.get("tool_choice") ?: return
        when {
            choice.isJsonPrimitive && choice.asJsonPrimitive.isString -> {
                if (choice.asString.trim().equals("none", ignoreCase = true)) {
                    obj.remove("tool_choice")
                    suppressTools(obj)
                }
            }
            choice.isJsonObject -> {
                val v = choice.asJsonObject
                when (v.str("type").trim().lowercase()) {
                    "none" -> {
                        obj.remove("tool_choice")
                        suppressTools(obj)
                    }
                    "auto", "required" -> obj.addProperty("tool_choice", v.str("type").trim().lowercase())
                    "function" -> {
                        val name = (v.objOrNull("function")?.str("name") ?: v.str("name")).trim()
                        obj.addProperty("tool_choice", name.ifEmpty { "auto" })
                    }
                    else -> obj.remove("tool_choice")
                }
            }
            else -> obj.remove("tool_choice")
        }
    }

    private fun suppressTools(obj: JsonObject) {
        obj.remove("tools")
        obj.remove("functions")
    }

    private fun normalizeTools(obj: JsonObject) {
        val raw = obj.get("tools") as? JsonArray ?: return
        if (raw.size() == 0) return
        val out = JsonArray()
        for (element in raw) {
            val tool = element as? JsonObject ?: continue
            val fn = tool.objOrNull("function") ?: continue
            val params = fn.get("parameters")
            if (params != null && params.isJsonObject) {
                fn.addProperty("parameters", params.toString())
            }
            out.add(tool)
        }
        if (out.size() == 0) obj.remove("tools") else obj.add("tools", out)
    }
}
