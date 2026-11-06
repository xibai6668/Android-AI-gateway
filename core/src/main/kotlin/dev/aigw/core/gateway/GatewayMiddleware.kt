package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.concurrent.ConcurrentHashMap

/** 一次上游尝试的上下文。 */
data class MiddlewareContext(
    /** 客户端原始请求的模型名。 */
    val requestedModel: String,
    /** 本次要发往的供应商 id。 */
    val providerId: String,
    /** 已解析出的上游模型名。 */
    val upstreamModel: String,
    /** 客户端密钥 id（本机免鉴权调用的空串）。 */
    val clientKeyId: String = "",
)

/** 请求改写钩子。 */
interface GatewayMiddleware {
    val id: String

    /** 返回改写后的请求体（可直接返回入参表示不改）。 */
    fun onRequest(context: MiddlewareContext, body: JsonObject): JsonObject
}

/** 模型别名：把客户端请求的模型名换成目标模型（`fast` → `glm-5.3-flash`）。 */
class ModelAliasMiddleware(private val aliases: Map<String, String>) : GatewayMiddleware {
    override val id: String get() = ID

    /** 只改 body 的 model 字段；别名表为空或未命中时原样返回。 */
    override fun onRequest(context: MiddlewareContext, body: JsonObject): JsonObject {
        val requested = body.stringOrNull("model").orEmpty()
        val target = aliases[requested]?.takeIf { it.isNotBlank() } ?: return body
        return JsonObject().apply {
            body.entrySet().forEach { (key, value) -> add(key, value) }
            addProperty("model", target)
        }
    }

    companion object {
        const val ID = "model-alias"
    }
}

/** system 提示词追加：在第一条 system 消息末尾追加固定文本；没有 system 消息时插入一条。 */
class SystemPromptAppendMiddleware(private val text: String) : GatewayMiddleware {
    override val id: String get() = ID

    override fun onRequest(context: MiddlewareContext, body: JsonObject): JsonObject {
        if (text.isBlank()) return body
        val messages = body.arrayOrNull("messages") ?: return body
        val copy = JsonArray()
        var appended = false
        for (element in messages) {
            val message = element.takeIf { it.isJsonObject }?.asJsonObject
            if (message == null) {
                copy.add(element)
                continue
            }
            val role = message.stringOrNull("role").orEmpty()
            // 上游可能把 content 写成字符串或多模态数组，只处理字符串形态；
            // 数组形态直接跳过（硬改会破坏 part 结构，风险大于收益）。
            val content = message.stringOrNull("content")
            if (!appended && content != null && (role == "system" || role == "developer")) {
                appended = true
                copy.add(JsonObject().apply {
                    message.entrySet().forEach { (key, value) -> add(key, value) }
                    addProperty("content", content + text)
                })
                continue
            }
            copy.add(message)
        }
        if (!appended) {
            // Gson 的 JsonArray 没有 add(index, element) 重载，只能重建一个数组插到最前
            val head = JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", text.trim())
            }
            val withHead = JsonArray()
            withHead.add(head)
            for (element in copy) withHead.add(element)
            return JsonObject().apply {
                body.entrySet().forEach { (key, value) -> add(key, value) }
                add("messages", withHead)
            }
        }
        return JsonObject().apply {
            body.entrySet().forEach { (key, value) -> add(key, value) }
            add("messages", copy)
        }
    }

    companion object {
        const val ID = "system-prompt"
    }
}

/** 思考标签清理：从 assistant 消息里剥掉 `<think>…</think>`（含未闭合到结尾的情况）。 */
class ThinkTagsStripMiddleware : GatewayMiddleware {
    override val id: String get() = ID

    override fun onRequest(context: MiddlewareContext, body: JsonObject): JsonObject {
        val messages = body.arrayOrNull("messages") ?: return body
        var changed = false
        val copy = JsonArray()
        for (element in messages) {
            val message = element.takeIf { it.isJsonObject }?.asJsonObject
            val role = message?.stringOrNull("role").orEmpty()
            val content = message?.stringOrNull("content")
            // 只清理 assistant 的思考残留：user/system 是用户或调用方给的原文，
            // 改写它们等于污染输入（用户写的 <think> 也可能是有意为之的文本）。
            if (message == null || role != "assistant" || content == null || !content.contains(THINK_TAG_OPEN)) {
                copy.add(element)
                continue
            }
            changed = true
            copy.add(JsonObject().apply {
                message.entrySet().forEach { (key, value) -> add(key, value) }
                addProperty("content", strip(content))
            })
        }
        if (!changed) return body
        return JsonObject().apply {
            body.entrySet().forEach { (key, value) -> add(key, value) }
            add("messages", copy)
        }
    }

    private fun strip(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val start = text.indexOf(THINK_TAG_OPEN, index)
            if (start < 0) {
                out.append(text, index, text.length)
                break
            }
            out.append(text, index, start)
            val end = text.indexOf(THINK_TAG_CLOSE, start + THINK_TAG_OPEN.length)
            if (end < 0) break // 未闭合：其后的内容全部视为思考，直接丢掉
            index = end + THINK_TAG_CLOSE.length
        }
        return out.toString().trim()
    }

    companion object {
        const val ID = "think-tags"
        private const val THINK_TAG_OPEN = "<think>"
        private const val THINK_TAG_CLOSE = "</think>"
    }
}

/**
 * 中间件链：按注册顺序依次执行。
 *
 * 铁律：**单个中间件抛异常就跳过它继续下一个**。钩子是可选增强，
 * 任何一处写错都不能让用户的请求失败——宁可少改一次，不可少答一句。
 */
class MiddlewareChain(private val middlewares: List<GatewayMiddleware> = emptyList()) {

    private val lastFailures = ConcurrentHashMap<String, String>()

    val isEmpty: Boolean get() = middlewares.isEmpty()

    /** 依次应用；线程安全（除失败信息表外无状态）。 */
    fun apply(context: MiddlewareContext, body: JsonObject): JsonObject {
        if (middlewares.isEmpty()) return body
        var current = body
        for (middleware in middlewares) {
            current = try {
                middleware.onRequest(context, current) ?: current
            } catch (e: Exception) {
                lastFailures[middleware.id] = e.message ?: e::class.java.simpleName
                current
            }
        }
        return current
    }

    /** 链上各中间件最近一次失败信息，供界面展示（键为中间件 id）。 */
    fun failures(): Map<String, String> = lastFailures.toMap()

    fun with(vararg extra: GatewayMiddleware): MiddlewareChain =
        MiddlewareChain(middlewares + extra)

    /**
     * 按用户配置重建一条链。
     *
     * 目前只有三种内置改写，均来自「请求改写」设置页；顺序固定为
     * 别名 → system 追加 → 思考标签清理：别名先定模型，另外两个只看消息内容，彼此不冲突。
     */
    companion object {
        const val SETTINGS_KEY = "settings/middleware.json"

        fun build(settings: MiddlewareSettings): MiddlewareChain {
            val list = ArrayList<GatewayMiddleware>(3)
            if (settings.aliases.isNotEmpty()) list.add(ModelAliasMiddleware(settings.aliases))
            if (settings.systemPromptAppend.isNotBlank()) list.add(SystemPromptAppendMiddleware(settings.systemPromptAppend))
            if (settings.stripThinkTags) list.add(ThinkTagsStripMiddleware())
            return MiddlewareChain(list)
        }
    }
}

/** 「请求改写」设置。 */
data class MiddlewareSettings(
    /** 模型别名表：客户端写的模型名 → 实际请求的模型名。 */
    val aliases: Map<String, String> = emptyMap(),
    /** 追加到 system 提示词末尾的文本；空串表示不改。 */
    val systemPromptAppend: String = "",
    /** 是否从 assistant 消息里剥掉 `<think>…</think>`。 */
    val stripThinkTags: Boolean = false,
) {
    val isActive: Boolean get() = aliases.isNotEmpty() || systemPromptAppend.isNotBlank() || stripThinkTags

    fun toJson(): JsonObject = JsonObject().apply {
        add("aliases", JsonObject().apply { aliases.forEach { (from, to) -> addProperty(from, to) } })
        addProperty("systemPromptAppend", systemPromptAppend)
        addProperty("stripThinkTags", stripThinkTags)
    }

    companion object {
        const val STORE_KEY = MiddlewareChain.SETTINGS_KEY

        fun fromJson(raw: String?): MiddlewareSettings {
            if (raw.isNullOrBlank()) return MiddlewareSettings()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return MiddlewareSettings()
            val aliases = LinkedHashMap<String, String>()
            obj.objOrNull("aliases")?.entrySet()?.forEach { (from, to) ->
                if (to.isJsonPrimitive && from.isNotBlank()) {
                    val target = runCatching { to.asString }.getOrDefault("")
                    if (target.isNotBlank()) aliases[from] = target
                }
            }
            return MiddlewareSettings(
                aliases = aliases,
                systemPromptAppend = obj.stringOrNull("systemPromptAppend").orEmpty(),
                stripThinkTags = obj.get("stripThinkTags")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }
    }
}
