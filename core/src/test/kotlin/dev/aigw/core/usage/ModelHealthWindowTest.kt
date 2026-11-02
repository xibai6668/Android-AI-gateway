package dev.aigw.core.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 供应商 × 模型的近期健康窗口。
 *
 * 它回答「最近这段时间这个模型到底怎么样」——这是累计计数答不出来的问题，
 * 也是排障时最常问的：p50/p95 耗时、首字延迟、失败原因 Top-N。
 */
class ModelHealthWindowTest {

    private fun window(capacity: Int = 50) = ModelHealthWindow(capacity)

    @Test
    fun `没有样本时不编造数据`() {
        val window = window()
        assertNull(window.snapshotOf("trae", "m"), "没有样本应返回 null，而不是成功率 100%")
        assertTrue(window.snapshot().isEmpty())
    }

    @Test
    fun `成功率按样本算`() {
        val window = window()
        repeat(3) { window.record("trae", "m", ok = true, durationMillis = 100) }
        window.record("trae", "m", ok = false, durationMillis = 200, error = "http_500")
        val snap = window.snapshotOf("trae", "m")!!
        assertEquals(4, snap["samples"])
        assertEquals(3, snap["success"])
        assertEquals("75.0%", snap["successRate"])
    }

    @Test
    fun `分位数按实际样本算`() {
        val window = window()
        listOf(10L, 20L, 30L, 40L, 50L).forEach {
            window.record("trae", "m", ok = true, durationMillis = it)
        }
        val snap = window.snapshotOf("trae", "m")!!
        assertEquals(30L, snap["p50Ms"])
        // nearest-rank：5 个样本的 p95 = 第 5 个（最大值），不是插值出来的中间值
        assertEquals(50L, snap["p95Ms"])
    }

    @Test
    fun `首字延迟单独统计且忽略没有首字的样本`() {
        val window = window()
        window.record("trae", "m", ok = true, durationMillis = 300, firstByteMillis = 100)
        window.record("trae", "m", ok = true, durationMillis = 400, firstByteMillis = 200)
        // 失败样本没有首字
        window.record("trae", "m", ok = false, durationMillis = 900, firstByteMillis = -1, error = "timeout")
        val snap = window.snapshotOf("trae", "m")!!
        assertEquals(2, snap["ttfbSamples"], "没有首字的样本不该拉低 TTFB 统计")
        // 2 个样本：p50 = 第 1 个，p95 = 第 2 个（nearest-rank）
        assertEquals(100L, snap["ttfbP50Ms"])
        assertEquals(200L, snap["ttfbP95Ms"])
    }

    @Test
    fun `失败原因按出现次数排序并截断`() {
        val window = window()
        repeat(5) { window.record("trae", "m", ok = false, durationMillis = 1, error = "http_500") }
        repeat(2) { window.record("trae", "m", ok = false, durationMillis = 1, error = "timeout") }
        window.record("trae", "m", ok = false, durationMillis = 1, error = "quota")
        val errors = window.snapshotOf("trae", "m")!!["errors"] as Map<*, *>
        assertEquals(3, errors.size)
        assertEquals(5, errors["http_500"])
        assertEquals(2, errors["timeout"])
    }

    @Test
    fun `容量封顶后只保留最近样本`() {
        val window = window(capacity = 3)
        window.record("trae", "m", ok = true, durationMillis = 1)
        repeat(3) { window.record("trae", "m", ok = false, durationMillis = 9, error = "http_500") }
        val snap = window.snapshotOf("trae", "m")!!
        assertEquals(3, snap["samples"])
        assertEquals(0, snap["success"], "最早的样本应被挤出")
    }

    @Test
    fun `按供应商与模型分开聚合`() {
        val window = window()
        window.record("trae", "a", ok = true, durationMillis = 1)
        window.record("trae", "b", ok = false, durationMillis = 1, error = "x")
        assertEquals(2, window.snapshot().size)
        assertEquals(1, window.snapshotOf("trae", "a")!!["success"])
        assertEquals(0, window.snapshotOf("trae", "b")!!["success"])
    }
}
