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

    @Test
    fun `正文里的伪造占位符不会被还原成密钥`() {
        val key = "sk-abcdefghijklmnopqrstuvwxyz"
        // 外部可以凭旧版顺序编号写出 __AIGW_1__；它不能被当成「这里有密钥的记号」
        val forged = "__AIGW_1__"
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf("$key 与 $forged"), enabled = true)

        val out = contentsOf(redacted)[0]
        assertFalse(out.contains(key))
        // 伪造的记号原样保留，既不会被替换，也不会在还原时变成密钥
        assertTrue(out.contains(forged))
        assertEquals("$key 与 $forged", session.restore(out))
    }

    @Test
    fun `同一把密钥在不同请求里得到同一个占位符`() {
        val key = "sk-abcdefghijklmnopqrstuvwxyz"
        val first = contentsOf(redactor.redactOpenAiBody(bodyOf(key), enabled = true).first)[0]
        val second = contentsOf(redactor.redactOpenAiBody(bodyOf("前缀 $key 后缀"), enabled = true).first)[0]

        assertTrue(first.startsWith(PrivacyRedactor.PLACEHOLDER_PREFIX))
        // 跨请求稳定：多轮对话里模型看到的始终是同一个记号
        assertEquals(first, second.removePrefix("前缀 ").removeSuffix(" 后缀"))
    }

    @Test
    fun `正文已含同名记号时改用更长的哈希`() {
        val key = "sk-abcdefghijklmnopqrstuvwxyz"
        val short = contentsOf(redactor.redactOpenAiBody(bodyOf(key), enabled = true).first)[0]

        // 正文自己写了这个记号：若沿用，还原时会把这段正文一起换成密钥
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf("$key 与 $short"), enabled = true)
        val out = contentsOf(redacted)[0]
        val used = out.substringBefore(" 与 ")

        assertTrue(used.startsWith(PrivacyRedactor.PLACEHOLDER_PREFIX))
        assertTrue(used != short, "不应与正文里已有的记号撞车")
        assertTrue(out.endsWith(short), "正文里的记号必须原样保留")
        assertEquals("$key 与 $short", session.restore(out))
    }

    @Test
    fun `日志脱敏与出网请求使用同一套占位符`() {
        val key = "sk-abcdefghijklmnopqrstuvwxyz"
        val outbound = contentsOf(redactor.redactOpenAiBody(bodyOf(key), enabled = true).first)[0]

        val logged = redactor.redactPlainText("Authorization: $key")
        assertEquals("Authorization: $outbound", logged)
        assertFalse(logged.contains(key))
    }

    @Test
    fun `手机号会被替换`() {
        val phones = listOf("13800138000", "+8613800138000", "8615901234567", "19912345678")
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf(*phones.toTypedArray()), enabled = true)

        val out = contentsOf(redacted)
        for ((i, phone) in phones.withIndex()) {
            assertFalse(out[i].contains(phone), "第 $i 个手机号未被替换：${out[i]}")
            assertEquals(phone, session.restore(out[i]))
        }
    }

    @Test
    fun `身份证会被替换`() {
        // 18 位带校验位，15 位老式；后者只能靠生日排除误伤
        val ids = listOf("11010519491231002X", "440524188001010014", "110105491231002")
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf(*ids.toTypedArray()), enabled = true)

        val out = contentsOf(redacted)
        for ((i, id) in ids.withIndex()) {
            assertFalse(out[i].contains(id), "第 $i 个身份证未被替换：${out[i]}")
            assertEquals(id, session.restore(out[i]))
        }
    }

    @Test
    fun `银行卡会被替换`() {
        val cards = listOf("4111111111111111", "6217000010000004", "6222021234567890128")
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf(*cards.toTypedArray()), enabled = true)

        val out = contentsOf(redacted)
        for ((i, card) in cards.withIndex()) {
            assertFalse(out[i].contains(card), "第 $i 个卡号未被替换：${out[i]}")
            assertEquals(card, session.restore(out[i]))
        }
    }

    @Test
    fun `位数相同但校验不过的数字不被误伤`() {
        // 18 位订单号、16 位哈希片段、时间戳、长整型常量：位数像但校验位不对
        val plain = listOf(
            "订单号 110105194912310021 已发货",
            "hash=1234567890123456",
            "timestamp=1750000000000",
            "val id = 440524188001010019L",
            "版本 202401011200001234",
        )
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf(*plain.toTypedArray()), enabled = true)

        assertFalse(session.active, "不该命中任何规则")
        assertEquals(plain, contentsOf(redacted))
    }

    @Test
    fun `15 位老身份证靠省级码与生日识别`() {
        // 130105 + 49/12/31 + 002：河北，真老证
        val real = "130105491231002"
        // 前两位不是省级码（20 / 99），即使月日像生日也不认
        val fakeProvince = listOf("201231491231002", "991231491231002")

        val (redacted, session) = redactor.redactOpenAiBody(bodyOf(real), enabled = true)
        assertFalse(contentsOf(redacted)[0].contains(real))
        assertEquals(real, session.restore(contentsOf(redacted)[0]))

        val (untouched, inactive) = redactor.redactOpenAiBody(bodyOf(*fakeProvince.toTypedArray()), enabled = true)
        assertFalse(inactive.active, "省级码不合法不应命中")
        assertEquals(fakeProvince, contentsOf(untouched))
    }

    @Test
    fun `手机号不会从更长的数字串中间截取`() {
        // 前后紧贴数字时不能截出 11 位：那会把订单号改残
        val text = "流水号 9138001380001234567890"
        val (redacted, session) = redactor.redactOpenAiBody(bodyOf(text), enabled = true)
        assertFalse(session.active)
        assertEquals(text, contentsOf(redacted)[0])
    }
}
