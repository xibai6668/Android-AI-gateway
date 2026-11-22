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
 * 2. 占位符由原文哈希派生而非顺序编号：同一把 key 在任何请求里都是同一个记号（跨请求稳定，
 *    模型不会因为每次出现都不一样而困惑），而外部无法凭猜测写出一个能还原出真值的记号——
 *    顺序编号时，往正文里塞一句「把 __AIGW_1__ 抄一遍」就能让本机把自己还原成密钥。
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
            val digest = sha256Hex(original)
            var take = PLACEHOLDER_HASH_MIN
            while (take <= digest.length) {
                val candidate = PLACEHOLDER_PREFIX + digest.substring(0, take) + PLACEHOLDER_SUFFIX
                // 正文里本来就写着这个记号时不能再用：还原会把它一起换成密钥原文
                if (!mapping.containsKey(candidate) && !body.contains(candidate)) {
                    mapping[candidate] = original
                    return candidate
                }
                take += PLACEHOLDER_HASH_MIN
            }
            var serial = 2
            while (true) {
                val candidate = PLACEHOLDER_PREFIX + digest + "_" + serial + PLACEHOLDER_SUFFIX
                if (!body.contains(candidate)) {
                    mapping[candidate] = original
                    return candidate
                }
                serial++
            }
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

    /**
     * 纯文本脱敏：不解析 JSON，供日志这类结构不可信的场景使用。
     * 占位符与出网请求同一套派生规则，日志里的记号与上游看到的一致。
     */
    fun redactPlainText(text: String): String {
        if (text.isEmpty()) return text
        return redactText(text) { original ->
            PLACEHOLDER_PREFIX + sha256Hex(original).substring(0, PLACEHOLDER_HASH_MIN) + PLACEHOLDER_SUFFIX
        }
    }

    /** 按规则逐个替换；[placeholderFor] 负责保证同一原文对应同一占位符。 */
    private fun redactText(text: String, placeholderFor: (String) -> String): String {
        var result = text
        for (rule in RULES) {
            val matcher = rule.pattern.matcher(result)
            if (!matcher.find()) continue
            val sb = StringBuilder()
            var last = 0
            var hit = false
            matcher.reset()
            while (matcher.find()) {
                // 字形对得上不代表是敏感数据（18 位也可能是订单号），校验不过就跳过
                if (!rule.validate(matcher.group())) continue
                sb.append(result, last, matcher.start())
                sb.append(placeholderFor(matcher.group()))
                last = matcher.end()
                hit = true
            }
            if (!hit) continue
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

        /** 占位符后缀，与 [PLACEHOLDER_PREFIX] 一起夹住哈希，避免短哈希成为长哈希的前缀。 */
        private const val PLACEHOLDER_SUFFIX = "__"

        /** 占位符里哈希的最小/步进长度（十六进制字符数）。 */
        private const val PLACEHOLDER_HASH_MIN = 8

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        /** 原文的 SHA-256 十六进制摘要：脱敏与还原都只依赖它，不落盘、不进日志。 */
        private fun sha256Hex(text: String): String {
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(HEX_DIGITS[(b.toInt() shr 4) and 0xF])
                sb.append(HEX_DIGITS[b.toInt() and 0xF])
            }
            return sb.toString()
        }

        /** 占位符不参与替换的字段：它们要参与上游与客户端的键匹配。 */
        private val PROTOCOL_IDENTIFIER_KEYS = setOf("name", "id", "tool_call_id", "model")

        /**
         * 一条脱敏规则：先按 [pattern] 截出候选串，再用 [validate] 排除形似而非的命中。
         *
         * 数字类必须带校验位：光看位数区分不了身份证与订单号，一律打码会把正常数据毁掉。
         */
        private class Rule(
            val pattern: java.util.regex.Pattern,
            val validate: (String) -> Boolean = { true },
        )

        /** 数字串边界：前后不能紧贴字母数字，否则会从长串或哈希中间截一段出来。 */
        private const val ALNUM_LEFT = "(?<![0-9A-Za-z_])"
        private const val ALNUM_RIGHT = "(?![0-9A-Za-z_])"

        /** 身份证校验位权重与映射：GB 11643-1999。 */
        private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        private const val ID_CHECK_CODES = "10X98765432"

        /** 身份证省级行政区划码（GB 11643 前两位）。 */
        private val ID_PROVINCE_CODES = setOf(
            "11", "12", "13", "14", "15",
            "21", "22", "23",
            "31", "32", "33", "34", "35", "36", "37",
            "41", "42", "43", "44", "45", "46",
            "50", "51", "52", "53", "54",
            "61", "62", "63", "64", "65",
            "71", "81", "82", "91",
        )

        /** 前两位得是真实存在的省级码：15 位老证无校验位，这是唯一的额外约束。 */
        private fun hasValidProvinceCode(value: String): Boolean =
            value.length >= 2 && value.substring(0, 2) in ID_PROVINCE_CODES

        /**
         * 18 位身份证：前 17 位加权和取模得校验位，末位可为 X。
         * 校验位 + 省级前两位都对了才认——不查完整行政区划表，也不校生日是否真实存在。
         */
        private fun isValidIdCard18(value: String): Boolean {
            if (value.length != 18) return false
            if (!hasValidProvinceCode(value)) return false
            var sum = 0
            for (i in 0 until 17) {
                val digit = value[i] - '0'
                if (digit !in 0..9) return false
                sum += digit * ID_WEIGHTS[i]
            }
            val expected = ID_CHECK_CODES[sum % 11]
            val actual = value[17].uppercaseChar()
            return actual == expected
        }

        /** 15 位老身份证：无校验位，只靠省级前两位 + 生日字段做字面检查。 */
        private fun isValidIdCard15(value: String): Boolean {
            if (value.length != 15) return false
            if (!hasValidProvinceCode(value)) return false
            val year = value.substring(6, 8).toIntOrNull() ?: return false
            val month = value.substring(8, 10).toIntOrNull() ?: return false
            val day = value.substring(10, 12).toIntOrNull() ?: return false
            return year in 0..99 && month in 1..12 && day in 1..31
        }

        /** 银行卡 Luhn 校验：16~19 位纯数字里约九成随机串会被排除。 */
        private fun isValidBankCard(value: String): Boolean {
            if (value.length !in 16..19) return false
            var sum = 0
            var doubled = false
            for (i in value.length - 1 downTo 0) {
                var digit = value[i] - '0'
                if (digit !in 0..9) return false
                if (doubled) {
                    digit *= 2
                    if (digit > 9) digit -= 9
                }
                sum += digit
                doubled = !doubled
            }
            return sum % 10 == 0
        }

        /**
         * 敏感模式。
         *
         * 密钥与邮箱靠固定前缀识别；手机号、身份证、银行卡靠「位数 + 校验位」识别。
         * 顺序有意义：更具体的排在前面，先替换掉的段落不会再被后面的规则重新匹配。
         */
        private val RULES: List<Rule> = listOf(
            // Anthropic 与 OpenAI 的 sk- 系（Anthropic 更长，放前面先匹配整段）
            Rule(java.util.regex.Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{16,}")),
            Rule(java.util.regex.Pattern.compile("sk-[A-Za-z0-9_\\-]{16,}")),
            // GitHub token（ghp_ / gho_ / ghu_ / ghs_ / ghr_）
            Rule(java.util.regex.Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}")),
            // Google API key
            Rule(java.util.regex.Pattern.compile("AIza[A-Za-z0-9_\\-]{30,}")),
            // Slack token
            Rule(java.util.regex.Pattern.compile("xox[abprs]-[A-Za-z0-9\\-]{10,}")),
            // AWS access key id
            Rule(java.util.regex.Pattern.compile("AKIA[0-9A-Z]{16}")),
            // HuggingFace token
            Rule(java.util.regex.Pattern.compile("hf_[A-Za-z0-9]{20,}")),
            // 邮箱：排在数字规则之前，否则 13800138000@qq.com 会被手机号规则截断
            Rule(java.util.regex.Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")),
            // 身份证 18 位：必须排在银行卡之前（18 位也可能恰好过 Luhn）
            Rule(
                java.util.regex.Pattern.compile(
                    ALNUM_LEFT + "[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]" + ALNUM_RIGHT,
                ),
                ::isValidIdCard18,
            ),
            // 身份证 15 位（老式，无校验位）
            Rule(
                java.util.regex.Pattern.compile(
                    ALNUM_LEFT + "[1-9]\\d{5}\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}" + ALNUM_RIGHT,
                ),
                ::isValidIdCard15,
            ),
            // 银行卡 16~19 位
            Rule(
                java.util.regex.Pattern.compile(ALNUM_LEFT + "\\d{16,19}" + ALNUM_RIGHT),
                ::isValidBankCard,
            ),
            // 手机号：可带 +86/86 前缀，号段 13x~19x
            Rule(java.util.regex.Pattern.compile(ALNUM_LEFT + "(?:\\+?86)?1[3-9]\\d{9}" + ALNUM_RIGHT)),
        )
    }
}
