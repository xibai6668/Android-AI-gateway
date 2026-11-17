package dev.aigw.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 本地隐私脱敏：出网前把密钥/邮箱换成占位符，响应回来后还原。
 *
 * 两条硬约束：① 对客户端透明——还原后的内容必须与原文一致；
 * ② 宁可漏、不可误伤——普通代码与正文不能被动到。
 */
class PrivacyRedactorTest {

    private val redactor = PrivacyRedactor()

    private fun bodyOf(vararg contents: String): String {
        val messages = contents.joinToString(",") { """{"role":"user","content":"$it"}""" }
        return """{"model":"m","messages":[$messages]}"""
    }

    private fun contentsOf(body: String): List<String> =
        com.google.gson.JsonParser.parseString(body).asJsonObject
            .getAsJsonArray("messages")
            .map { it.asJsonObject.get("content").asString }

    @Test
    fun `常见密钥与邮箱都会被替换`() {
        val secrets = listOf(
            "sk-ant-api03-abcdefghijklmnopqrstuvwxyz",
            "sk-proj-abcdefghijklmnopqrstuvwx",
            "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "AIzaSyA1234567890abcdefghijklmnopqrstu",
            "AKIAIOSFODNN7EXAMPLE",
            "someone@example.com",
        )
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf(*secrets.toTypedArray()), enabled = true)

        assertTrue(session.active)
        val out = contentsOf(redacted)
        for ((i, secret) in secrets.withIndex()) {
            assertFalse(out[i].contains(secret), "第 $i 条未被替换：${out[i]}")
            assertTrue(out[i].contains(PrivacyRedactor.PLACEHOLDER_PREFIX))
        }
        // 还原后必须一字不差
        for ((i, secret) in secrets.withIndex()) {
            assertEquals(secret, session.restore(out[i]))
        }
    }

    @Test
    fun `同一原文映射到同一占位符`() {
        val key = "sk-abcdefghijklmnopqrstuvwxyz"
        val (redacted, _) = redactor.redactOpenAiBody(
            bodyOf("$key 和 $key", "又是 $key"),
            enabled = true,
        )
        val out = contentsOf(redacted)
        // 两个占位符完全相同：模型看到的是同一个记号，不会因为每次出现都变而困惑
        val first = out[0].substringBefore(" ").trim()
        assertEquals(first, out[0].substringAfter("和 ").trim())
        assertTrue(out[1].endsWith(first))
    }

    @Test
    fun `关闭时原样返回`() {
        val body = bodyOf("sk-abcdefghijklmnopqrstuvwxyz")
        val (redacted, session) = redactor.redactOpenAiBody(body, enabled = false)
        assertEquals(body, redacted)
        assertFalse(session.active)
    }

    @Test
    fun `普通正文与代码不被误伤`() {
        val plain = listOf(
            "请帮我把 sk 前缀的校验逻辑改成允许短一些的 key",
            "user_id=42，email_field 是必填项",
            "SELECT * FROM users WHERE id = 1",
        )
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf(*plain.toTypedArray()), enabled = true)
        assertFalse(session.active, "不该命中任何模式")
        assertEquals(plain, contentsOf(redacted))
    }

    @Test
    fun `协议标识字段不被替换`() {
        val body = """{"model":"sk-abcdefghijklmnopqrstuvwxyz","messages":[],"tools":[{"type":"function","function":{"name":"someone@example.com","description":"d"}}]}"""
        val (redacted, session) = redactor.redactOpenAiBody(body, enabled = true)
        val obj = com.google.gson.JsonParser.parseString(redacted).asJsonObject
        assertEquals("sk-abcdefghijklmnopqrstuvwxyz", obj.get("model").asString)
        assertEquals(
            "someone@example.com",
            obj.getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function").get("name").asString,
        )
        assertFalse(session.active)
    }

    @Test
    fun `流式还原能处理占位符被切在两个 chunk 之间`() {
        val key = "sk-abcdefghijklmnopqrstuvwxyz"
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf("key=$key"), enabled = true)
        val placeholder = contentsOf(redacted)[0].removePrefix("key=")

        val restorer = redactor.restorerFor(session)
        // 从中间切开：前半段不能提前吐出残缺的占位符
        val cut = placeholder.length / 2
        val first = restorer.feed("key=" + placeholder.substring(0, cut))
        val second = restorer.feed(placeholder.substring(cut) + " 完")
        val tail = restorer.flush()

        assertEquals("key=$key 完", first + second + tail)
    }

    @Test
    fun `流式还原在无占位符时不改动内容`() {
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf("sk-abcdefghijklmnopqrstuvwxyz"), enabled = true)
        assertTrue(session.active)
        val restorer = redactor.restorerFor(session)
        assertEquals("普通文本", restorer.feed("普通文本"))
        assertEquals("", restorer.flush())
        assertTrue(redacted.isNotEmpty())
    }
}
