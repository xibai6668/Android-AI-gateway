package dev.aigw.core.util

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 统计格的数字格式化。
 *
 * 回归用例：v0.1.6 之前 `"%.1fK".format(23.2).removeSuffix(".0K") + "K"` 会产出 "23.2KK"，
 * 撑破格子导致首页「今日 Tokens」换行。整数千位（如 23000）恰好走通，所以一直没暴露。
 */
class CountFormatTest {

    private val originalLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `千位以下原样输出`() {
        assertEquals("0", formatCount(0))
        assertEquals("1", formatCount(1))
        assertEquals("999", formatCount(999))
    }

    @Test
    fun `千位带一位小数`() {
        assertEquals("1K", formatCount(1_000))
        assertEquals("23.2K", formatCount(23_200))
        assertEquals("999.9K", formatCount(999_949))
    }

    @Test
    fun `百万位带一位小数`() {
        assertEquals("1M", formatCount(1_000_000))
        assertEquals("3.5M", formatCount(3_500_000))
    }

    @Test
    fun `任何量级都不会重复后缀`() {
        val samples = listOf(0L, 999L, 1_000L, 23_200L, 999_999L, 1_000_000L, 12_345_678L, Long.MAX_VALUE)
        for (value in samples) {
            val text = formatCount(value)
            assertTrue(
                text.count { it == 'K' || it == 'M' } <= 1,
                "$value 格式化出了重复后缀：$text",
            )
            assertTrue(text.isNotEmpty())
        }
    }

    @Test
    fun `小数点不随系统区域变化`() {
        // 德语区域小数点是逗号，必须不影响到显示
        Locale.setDefault(Locale.GERMANY)
        assertEquals("23.2K", formatCount(23_200))
        assertEquals("3.5M", formatCount(3_500_000))
    }
}
