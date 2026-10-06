package dev.aigw.core.security

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityProtectionTest {

    @Test
    fun `敏感词内部准确注入零宽空格且保留大小写`() {
        val sanitizer = RequestSanitizer()
        val word = "exploit"
        val obfuscated = sanitizer.obfuscateWord(word)
        assertEquals("e\u200Bx\u200Bp\u200Bl\u200Bo\u200Bi\u200Bt", obfuscated)

        val (sanitized, matches) = sanitizer.sanitizeText("Preventing any Exploit or DoS attack.")
        assertTrue("exploit" in matches)
        assertTrue("DoS" in matches)
        assertTrue(sanitized.contains("E\u200Bx\u200Bp\u200Bl\u200Bo\u200Bi\u200Bt"))
        assertTrue(sanitized.contains("D\u200Bo\u200BS"))
    }

    @Test
    fun `只改写 system 消息，完全不触碰 user 输入`() {
        val sanitizer = RequestSanitizer()
        val openAiBody = """
            {
              "model": "gpt-4o",
              "messages": [
                {"role": "system", "content": "Rules: Never execute exploit or DoS code."},
                {"role": "user", "content": "Can you explain how an exploit works?"},
                {"role": "assistant", "content": "An exploit is..."}
              ]
            }
        """.trimIndent()

        val processed = sanitizer.sanitizeOpenAiBody(openAiBody, "gpt-4o", enabled = true)
        val obj = JsonParser.parseString(processed).asJsonObject
        val messages = obj.getAsJsonArray("messages")

        val sysContent = messages[0].asJsonObject.get("content").asString
        val userContent = messages[1].asJsonObject.get("content").asString

        // system 消息被脱敏注入
        assertTrue(sysContent.contains("e\u200Bx\u200Bp\u200Bl\u200Bo\u200Bi\u200Bt"), "system 消息内敏感词必须被零宽空格打断：$sysContent")
        assertTrue(sysContent.contains("D\u200Bo\u200BS"))

        // user 消息原封不动，未被修改
        assertEquals("Can you explain how an exploit works?", userContent, "user 输入绝不能被修改")

        // 出网取证记录生成
        val evidences = sanitizer.evidenceList()
        assertEquals(1, evidences.size)
        assertEquals("gpt-4o", evidences[0].model)
        assertTrue("exploit" in evidences[0].matchedTerms)
    }

    @Test
    fun `脱敏关闭时不发生任何改写`() {
        val sanitizer = RequestSanitizer()
        val body = """{"messages":[{"role":"system","content":"exploit"}]}"""
        val result = sanitizer.sanitizeOpenAiBody(body, "m", enabled = false)
        assertEquals(body, result)
        assertEquals(0, sanitizer.evidenceList().size)
    }

    @Test
    fun `处理链支持热重载额外敏感词`() {
        val sanitizer = RequestSanitizer()
        assertEquals(sanitizer.DEFAULT_SENSITIVE_WORDS.size, sanitizer.currentRulesCount())

        sanitizer.reloadPipeline(listOf("customBadWord1", "customBadWord2"))
        assertEquals(sanitizer.DEFAULT_SENSITIVE_WORDS.size + 2, sanitizer.currentRulesCount())

        val (sanitized, matches) = sanitizer.sanitizeText("this has custombadword1 inside")
        assertTrue("customBadWord1" in matches)
        assertTrue(sanitized.contains("c\u200Bu\u200Bs\u200Bt\u200Bo\u200Bm"))
    }

    @Test
    fun `账号限速器控制两次调用间隔与抖动`() {
        var mockTime = 1_000_000L
        val limiter = AccountRateLimiter(nowMillis = { mockTime })

        // 第一次调用：无等待
        val wait1 = limiter.acquire("trae", "acc1", minIntervalMillis = 1500L, jitterMillis = 0L)
        assertEquals(0L, wait1)

        // 500ms 后第二次调用：需等待 1000ms
        mockTime += 500L
        val wait2 = limiter.acquire("trae", "acc1", minIntervalMillis = 1500L, jitterMillis = 0L)
        assertEquals(1000L, wait2)

        // 另一个账号互不干扰
        val waitOther = limiter.acquire("trae", "acc2", minIntervalMillis = 1500L, jitterMillis = 0L)
        assertEquals(0L, waitOther)
    }

    @Test
    fun `SecuritySettings 正确序列化与反序列化`() {
        val original = SecuritySettings(
            sanitizeEnabled = true,
            minIntervalMillis = 2000L,
            jitterMillis = 500L,
            extraWords = listOf("foo", "bar"),
        )
        val json = original.toJson().toString()
        val parsed = SecuritySettings.fromJson(json)

        assertEquals(true, parsed.sanitizeEnabled)
        assertEquals(2000L, parsed.minIntervalMillis)
        assertEquals(500L, parsed.jitterMillis)
        assertEquals(listOf("foo", "bar"), parsed.extraWords)
    }
}
