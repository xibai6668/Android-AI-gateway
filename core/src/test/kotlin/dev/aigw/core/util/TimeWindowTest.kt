package dev.aigw.core.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「今天」的边界计算。
 *
 * 回归点：原先 GatewayEngine 与 AppViewModel 各有一份逐字相同的 Calendar 实现，
 * 且 Engine 那份用的是系统时钟而不是注入的 `nowMillis`，在固定时钟的测试里会取到真实当天。
 */
class TimeWindowTest {

    private val originalZone: TimeZone = TimeZone.getDefault()

    @AfterTest
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `取当天零点`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        // 2026-09-17 15:22:10 +08:00
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 17, 15, 22, 10)
            set(Calendar.MILLISECOND, 0)
        }
        val start = startOfDay(calendar.timeInMillis)

        val expected = Calendar.getInstance().apply {
            timeInMillis = calendar.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expected, start)
        assertTrue(start < calendar.timeInMillis)
    }

    @Test
    fun `零点本身与当天末尾归入同一天`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val noon = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 17, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val start = startOfDay(noon)

        assertEquals(start, startOfDay(start), "零点往回算仍是同一天")
        assertEquals(start, startOfDay(start + 23 * 3600_000L + 59 * 60_000L + 59_000L), "当天最后一秒")
        assertEquals(start + 24 * 3600_000L, startOfDay(start + 24 * 3600_000L), "次日零点应进位")
    }

    @Test
    fun `结果与传入时刻的可变状态无关`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val a = startOfDay(1_800_000_000_000L)
        val b = startOfDay(1_800_000_000_000L)
        assertEquals(a, b)
        assertEquals(0, a % (24 * 3600_000L), "UTC 下零点应对齐到整日的整数倍")
    }
}
