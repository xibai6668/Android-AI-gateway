package dev.aigw.core.security

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 本地隐私脱敏：请求出网前把 API Key、邮箱等敏感信息换成占位符，等响应回来后原样还原。
 *
 * 目的很单纯——**别把你自己的密钥和邮箱交给上游**。模型看到的、上游日志里留下的是占位符，
 * 本机客户端看到的仍是原文，对调用方透明。
 *
 * 三个设计取舍：
 * 1. 只认**高置信度**的模式（各家 key 的固定前缀、邮箱字形）。宁可漏，不能误伤普通正文——
 *    把一段正常代码替换成占位符，比不脱敏更糟。
 * 2. 一个原文固定映射到同一个占位符：同一段对话里多处出现同一把 key，模型看到的仍是同一个记号，
 *    不会因为「每次出现都不一样」而困惑。
 * 3. 还原用纯文本替换（占位符只含 ASCII 字母数字下划线，不受 JSON 转义影响），
 *    对非流式响应与流式 delta 都适用；流式下用 [StreamingRestorer] 处理占位符被切在两个 chunk 的情况。
 */
class PrivacyRedactor {

    /** 一次请求内的脱敏映射：原文 ⇄ 占位符。请求结束后即可丢弃（不落盘、不进日志）。 */
    class Session internal constructor(
        private val restoreMap: Map<String, String>,
    ) {
        val active: Boolean get() = restoreMap.isNotEmpty()

        /** 占位符长度上限，供流式还原确定回看窗口。 */
        internal val maxPlaceholderLength: Int = restoreMap.keys.maxOfOrNull { it.length } ?: 0

        internal val placeholderPrefixes: List<String> = restoreMap.keys.toList()

        /** 把文本里出现的占位符换回原文。 */
        fun restore(text: String): String {
            if (restoreMap.isEmpty() || text.isEmpty()) return text
            var result = text
            for ((placeholder, original) in restoreMap) result = result.replace(placeholder, original)
            return result
        }

        companion object {
            val EMPTY = Session(emptyMap())
        }
    }

    /**
     * 改写请求体：把 messages 里出现的敏感信息替换成占位符。
     *
     * 递归遍历所有字符串值（工具结果、多模态 parts、system 都在内），但跳过 `name` / `id` /
     * `model` / `tool_call_id` 这类协议标识——它们要参与上游与客户端的键匹配，改不得。
     *
     * 返回改写后的 body 与本次会话；未开启或无命中时返回原 body 与 [Session.EMPTY]。
     */
    fun redactOpenAiBody(body: String, enabled: Boolean): Pair<String, Session> {
        if (!enabled || body.isEmpty()) return body to Session.EMPTY
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return body to Session.EMPTY

        val mapping = LinkedHashMap<String, String>()
        var changed = false

        fun placeholderFor(original: String): String {
            mapping.entries.firstOrNull { it.value == original }?.let { return it.key }
            val placeholder = PLACEHOLDER_PREFIX + (mapping.size + 1) + "__"
            mapping[placeholder] = original
            return placeholder
        }

        fun walk(element: JsonElement) {
            when {
                element.isJsonObject -> {
                    val node = element.asJsonObject
                    for (key in node.keySet().toList()) {
                        val value = node.get(key) ?: continue
                        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                            if (key in PROTOCOL_IDENTIFIER_KEYS) continue
                            val original = value.asString
                            val rewritten = redactText(original) { placeholderFor(it) }
                            if (rewritten != original) {
                                changed = true
                                node.addProperty(key, rewritten)
                            }
                        } else {
                            walk(value)
                        }
                    }
                }
                element.isJsonArray -> for (item in element.asJsonArray) walk(item)
            }
        }

        walk(obj)
        if (!changed) return body to Session.EMPTY
        return obj.toString() to Session(mapping)
    }

    /** 按模式逐个替换；[placeholderFor] 负责保证同一原文对应同一占位符。 */
    private fun redactText(text: String, placeholderFor: (String) -> String): String {
        var result = text
        for (pattern in PATTERNS) {
            val matcher = pattern.matcher(result)
            if (!matcher.find()) continue
            val sb = StringBuilder()
            var last = 0
            matcher.reset()
            while (matcher.find()) {
                sb.append(result, last, matcher.start())
                sb.append(placeholderFor(matcher.group()))
                last = matcher.end()
            }
            sb.append(result, last, result.length)
            result = sb.toString()
        }
        return result
    }

    /**
     * 流式还原器：处理占位符被上游切在两个 chunk 之间的情况。
     *
     * 每次 [feed] 只交出「确定不可能是占位符前缀」的部分，尾巴先攒着；[flush] 在流结束时交出剩余。
     * 回看窗口上限就是最长占位符长度，因此最多延迟一个占位符长度的内容，不会无限缓冲。
     */
    class StreamingRestorer(private val session: Session) {

        private val pending = StringBuilder()

        val active: Boolean get() = session.active

        fun feed(text: String): String {
            if (!session.active || text.isEmpty()) return text
            pending.append(text)
            val keep = holdbackLength()
            if (keep <= 0) {
                val out = session.restore(pending.toString())
                pending.setLength(0)
                return out
            }
            val emitEnd = pending.length - keep
            val out = session.restore(pending.substring(0, emitEnd))
            pending.delete(0, emitEnd)
            return out
        }

        /** 流结束：交出攒下的尾巴（可能是一个不完整的占位符，原样还原）。 */
        fun flush(): String {
            if (!session.active || pending.isEmpty()) return ""
            val out = session.restore(pending.toString())
            pending.setLength(0)
            return out
        }

        /** 尾部有多长可能是某个占位符的前缀。 */
        private fun holdbackLength(): Int {
            val max = minOf(pending.length, session.maxPlaceholderLength - 1)
            for (n in max downTo 1) {
                val suffix = pending.substring(pending.length - n)
                if (session.placeholderPrefixes.any { it.startsWith(suffix) }) return n
            }
            return 0
        }
    }

    fun restorerFor(session: Session): StreamingRestorer = StreamingRestorer(session)

    companion object {
        /** 占位符前缀：刻意用 ASCII 且几乎不会自然出现，便于流式回看与整体替换。 */
        const val PLACEHOLDER_PREFIX = "__AIGW_"

        /** 占位符不参与替换的字段：它们要参与上游与客户端的键匹配。 */
        private val PROTOCOL_IDENTIFIER_KEYS = setOf("name", "id", "tool_call_id", "model")

        /**
         * 高置信度敏感模式。只认带固定前缀的密钥与邮箱字形：
         * 宁可漏掉一些没把握的串，也不能把正常代码或正文替换成占位符。
         */
        private val PATTERNS: List<java.util.regex.Pattern> = listOf(
            // Anthropic 与 OpenAI 的 sk- 系（Anthropic 更长，放前面先匹配整段）
            java.util.regex.Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{16,}"),
            java.util.regex.Pattern.compile("sk-[A-Za-z0-9_\\-]{16,}"),
            // GitHub token（ghp_ / gho_ / ghu_ / ghs_ / ghr_）
            java.util.regex.Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
            // Google API key
            java.util.regex.Pattern.compile("AIza[A-Za-z0-9_\\-]{30,}"),
            // Slack token
            java.util.regex.Pattern.compile("xox[abprs]-[A-Za-z0-9\\-]{10,}"),
            // AWS access key id
            java.util.regex.Pattern.compile("AKIA[0-9A-Z]{16}"),
            // HuggingFace token
            java.util.regex.Pattern.compile("hf_[A-Za-z0-9]{20,}"),
            // 邮箱
            java.util.regex.Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
        )
    }
}
