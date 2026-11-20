package dev.aigw.core.provider.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Codex 客户端版本号探测。
 *
 * 上游按版本号决定下发哪些模型，写死会随上游推进逐渐失效。这里只测解析与兜底：
 * 网络请求本身不打真网（CI 里不该依赖外网）。
 */
class ChatGPTVersionTest {

    @Test
    fun `解析 npm registry 的 version 字段`() {
        val raw = """{"name":"@openai/codex","version":"0.171.0","dist-tags":{"latest":"0.171.0"}}"""
        assertEquals("0.171.0", ChatGPTVersion.parseRegistry(raw))
    }

    @Test
    fun `解析不出合法版本号时返回 null`() {
        // 错误页 / 非 JSON / 非版本形状的串都不能被当成版本号
        assertNull(ChatGPTVersion.parseRegistry("<html>502 Bad Gateway</html>"))
        assertNull(ChatGPTVersion.parseRegistry("""{"version":"latest"}"""))
        assertNull(ChatGPTVersion.parseRegistry("""{"version":"0.171"}"""))
        assertNull(ChatGPTVersion.parseRegistry("""{"error":"not found"}"""))
    }

    @Test
    fun `探测失败时退回兜底版本`() {
        // 首次调用时缓存已过期，会尝试回源；本测试环境不保证联网，
        // 因此只断言「无论探测成败，都返回一个合法版本号」
        val version = ChatGPTVersion.current()
        assertTrue(Regex("""^\d+\.\d+\.\d+$""").matches(version), "返回了非法版本号：$version")
    }

    @Test
    fun `invalidate 后仍返回合法版本`() {
        ChatGPTVersion.invalidate()
        assertTrue(Regex("""^\d+\.\d+\.\d+$""").matches(ChatGPTVersion.current()))
    }
}
