package dev.aigw.core.security

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.regex.Pattern

/**
 * 出网取证记录：记录脱敏拦截与穿透处理的快照。
 */
data class OutboundEvidence(
    val atMillis: Long,
    val model: String,
    val matchedTerms: List<String>,
    val originalSnippet: String,
    val sanitizedSnippet: String,
)

/**
 * 反审核脱敏器：
 * 采用“零宽字符（Zero-width Space \u200B）注入”技术穿透敏感词过滤。
 * 作用范围严格限定在 `system` / `developer` 消息，完全不触碰用户 `user` 输入。
 */
class RequestSanitizer(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** 零宽空格，肉眼不可见、LLM Tokenizer 还原理解，但能彻底打断关键词匹配。 */
    val ZERO_WIDTH_SPACE = "\u200B"

    /** 内置高频误拦敏感词列表（涵盖网络安全、渗透测试、Agent合规声明常见词）。 */
    val DEFAULT_SENSITIVE_WORDS = listOf(
        "DoS",
        "DDoS",
        "exploit",
        "exploits",
        "exploiting",
        "vulnerability",
        "vulnerabilities",
        "payload",
        "payloads",
        "shellcode",
        "backdoor",
        "rootkit",
        "trojan",
        "jailbreak",
        "bypass",
        "injection",
        "SQLi",
        "XSS",
        "CSRF",
        "privilege escalation",
        "brute force",
        "reverse shell",
        "malware",
        "ransomware",
    )

    private val evidenceHistory = ConcurrentLinkedDeque<OutboundEvidence>()
    private val maxEvidenceCount = 50

    @Volatile
    private var activeWordList: List<String> = DEFAULT_SENSITIVE_WORDS

    @Volatile
    private var compiledRegexes: List<Pair<String, Pattern>> = compilePatterns(DEFAULT_SENSITIVE_WORDS)

    /** 重新加载处理链规则与词表。 */
    fun reloadPipeline(extraWords: List<String> = emptyList()) {
        val combined = (DEFAULT_SENSITIVE_WORDS + extraWords)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        activeWordList = combined
        compiledRegexes = compilePatterns(combined)
    }

    fun currentRulesCount(): Int = activeWordList.size

    fun evidenceList(): List<OutboundEvidence> = evidenceHistory.toList()

    fun clearEvidence() {
        evidenceHistory.clear()
    }

    /**
     * 针对 OpenAI 格式请求体进行脱敏处理：
     * 仅修改 `messages` 数组中 `role == "system"` 或 `role == "developer"` 的消息。
     * 若未开启脱敏，或无匹配，返回原 body。
     */
    fun sanitizeOpenAiBody(body: String, model: String, enabled: Boolean): String {
        if (!enabled) return body
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return body
        val messages = obj.arrayOrNull("messages") ?: return body

        var modified = false
        val allMatchedTerms = mutableListOf<String>()
        var originalSample = ""
        var sanitizedSample = ""

        val newMessages = JsonArray()
        for (element in messages) {
            val msg = element.asObjectOrNull()
            if (msg == null) {
                newMessages.add(element)
                continue
            }
            val role = msg.stringOrNull("role").orEmpty()
            if (role == "system" || role == "developer") {
                val contentElem = msg.get("content")
                if (contentElem != null && contentElem.isJsonPrimitive) {
                    val originalText = contentElem.asString
                    val (resultText, matches) = sanitizeText(originalText)
                    if (matches.isNotEmpty()) {
                        modified = true
                        allMatchedTerms.addAll(matches)
                        if (originalSample.isEmpty()) {
                            originalSample = originalText.take(300)
                            sanitizedSample = resultText.take(300)
                        }
                        val copyMsg = JsonObject()
                        for ((k, v) in msg.entrySet()) copyMsg.add(k, v)
                        copyMsg.addProperty("content", resultText)
                        newMessages.add(copyMsg)
                        continue
                    }
                }
            }
            newMessages.add(element)
        }

        if (!modified) return body

        obj.add("messages", newMessages)
        val finalBody = obj.toString()

        recordEvidence(model, allMatchedTerms.distinct(), originalSample, sanitizedSample)
        return finalBody
    }

    /**
     * 将敏感词内部每两个相邻字符之间注入零宽空格。
     * 例：DoS -> D\u200Bo\u200BS
     */
    internal fun obfuscateWord(word: String): String {
        if (word.length <= 1) return word
        val sb = StringBuilder()
        for (i in word.indices) {
            sb.append(word[i])
            if (i < word.length - 1) {
                sb.append(ZERO_WIDTH_SPACE)
            }
        }
        return sb.toString()
    }

    /**
     * 对一段字符串执行敏感词检测与零宽字符注入替换。
     */
    internal fun sanitizeText(input: String): Pair<String, List<String>> {
        var current = input
        val matched = mutableListOf<String>()

        for ((word, pattern) in compiledRegexes) {
            val matcher = pattern.matcher(current)
            if (matcher.find()) {
                matched.add(word)
                // 替换时保留原词的大小写，在字符间注入零宽字符
                current = matcher.replaceAll { matchResult ->
                    obfuscateWord(matchResult.group())
                }
            }
        }
        return current to matched
    }

    private fun recordEvidence(
        model: String,
        terms: List<String>,
        originalSnippet: String,
        sanitizedSnippet: String,
    ) {
        val evidence = OutboundEvidence(
            atMillis = nowMillis(),
            model = model,
            matchedTerms = terms,
            originalSnippet = originalSnippet,
            sanitizedSnippet = sanitizedSnippet,
        )
        evidenceHistory.addFirst(evidence)
        while (evidenceHistory.size > maxEvidenceCount) {
            evidenceHistory.removeLast()
        }
    }

    private fun compilePatterns(words: List<String>): List<Pair<String, Pattern>> {
        // 词长降序排列，优先匹配较长词组（如 brute force 优先于 force）
        return words.sortedByDescending { it.length }.map { word ->
            val regex = if (word.all { it.isLetterOrDigit() }) {
                "\\b" + Pattern.quote(word) + "\\b"
            } else {
                Pattern.quote(word)
            }
            word to Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
        }
    }
}
