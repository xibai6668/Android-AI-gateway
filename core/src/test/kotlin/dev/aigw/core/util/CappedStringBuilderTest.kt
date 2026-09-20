package dev.aigw.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 限长累积器。
 *
 * 回归：流式响应用 `StringBuilder` 全量累积回复内容，但记录只保留前 8KB，
 * 一次长回复（几十万字符）会白占堆内存且直到请求结束才释放。
 */
class CappedStringBuilderTest {

    @Test
    fun `未超限时内容与总长都正确`() {
        val b = CappedStringBuilder(limit = 100)
        b.append("abc")
        b.append("def")
        assertEquals("abcdef", b.content())
        assertEquals(6L, b.totalLength)
        assertFalse(b.truncated)
    }

    @Test
    fun `超限后只保留前 limit 个字符`() {
        val b = CappedStringBuilder(limit = 10)
        b.append("x".repeat(25))
        assertEquals("x".repeat(10), b.content())
        assertEquals(25L, b.totalLength, "仍要记录真实总长")
        assertTrue(b.truncated)
    }

    @Test
    fun `跨多次 append 恰好截在边界`() {
        val b = CappedStringBuilder(limit = 10)
        b.append("1234567")
        b.append("890")
        b.append("XYZ")
        assertEquals("1234567890", b.content())
        assertEquals(13L, b.totalLength)
        assertTrue(b.truncated)
    }

    @Test
    fun `已满后再 append 不再增长`() {
        val b = CappedStringBuilder(limit = 5)
        b.append("abcde")
        val before = b.content()
        repeat(100) { b.append("f".repeat(1000)) }
        assertEquals(before, b.content(), "内容保持恒定，不随流式数据无限增长")
        assertEquals(5L + 100_000L, b.totalLength)
    }

    @Test
    fun `空串与空内容不改变状态`() {
        val b = CappedStringBuilder(limit = 5)
        b.append("")
        assertEquals("", b.content())
        assertEquals(0L, b.totalLength)
        assertFalse(b.truncated)
    }

    @Test
    fun `limit 为 1 与较大值都安全`() {
        val tiny = CappedStringBuilder(limit = 1)
        tiny.append("abcdef")
        assertEquals("a", tiny.content())

        val big = CappedStringBuilder(limit = 100_000)
        big.append("z".repeat(100_000))
        assertEquals(100_000, big.content().length)
        assertFalse(big.truncated)
    }

    @Test
    fun `toString 与 content 一致`() {
        val b = CappedStringBuilder(limit = 4)
        b.append("abcdefg")
        assertEquals(b.content(), b.toString())
        assertEquals("abcd", b.toString())
    }
}
