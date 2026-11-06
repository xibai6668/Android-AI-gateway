package dev.aigw.core.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 价格表与费用计算的口径锁定。
 *
 * 核心不变量：**算不出价格必须返回 null，不能返回 0**（0 是「免费模型」的合法价格，
 * 两者混淆会让未知价格的调用被当成免费，费用统计与限额全部失真）。
 */
class ModelPricingTest {

    private val price = ModelPrice(
        promptPerMillion = 3.0,
        completionPerMillion = 15.0,
        cacheReadPerMillion = 1.0,
        cacheWritePerMillion = 5.0,
    )

    // ---- PricingTable 查价 ----

    @Test
    fun `withPrice withoutPrice priceOf 精确匹配`() {
        val table = PricingTable().withPrice("trae/glm-5.3", price)
        assertEquals(price, table.priceOf("trae/glm-5.3"))
        assertEquals(price, table.priceOf("  trae/glm-5.3  "), "查询前应 trim")
        assertNull(table.priceOf("trae/glm-5.4"))
        assertNull(table.priceOf("codebuddy/glm-5.3"))

        val removed = table.withoutPrice("trae/glm-5.3")
        assertNull(removed.priceOf("trae/glm-5.3"))
        assertEquals(price, table.priceOf("trae/glm-5.3"), "原表不可变")
        assertTrue(removed.prices.isEmpty())
    }

    @Test
    fun `裸模型名回落`() {
        val bare = ModelPrice(1.0, 2.0, 0.5, 0.25)
        val table = PricingTable().withPrice("gemini-3.8-flash", bare)

        assertEquals(bare, table.priceOf("trae/gemini-3.8-flash"))
        assertEquals(bare, table.priceOf("antigravity/gemini-3.8-flash"))
        assertEquals(bare, table.priceOf("gemini-3.8-flash"))
        assertNull(table.priceOf("trae/gemini-3.8-pro"))
    }

    @Test
    fun `精确匹配优先于裸名回落`() {
        val bare = ModelPrice(1.0, 1.0, 1.0, 1.0)
        val exact = ModelPrice(9.0, 9.0, 9.0, 9.0)
        val table = PricingTable().withPrice("gemini-3.8-flash", bare)
            .withPrice("trae/gemini-3.8-flash", exact)

        assertEquals(exact, table.priceOf("trae/gemini-3.8-flash"))
        assertEquals(bare, table.priceOf("antigravity/gemini-3.8-flash"))
    }

    @Test
    fun `空串与都查不到返回 null`() {
        val table = PricingTable().withPrice("trae/glm-5.3", price)
        assertNull(table.priceOf(""))
        assertNull(table.priceOf("   "))
        assertNull(table.priceOf("/"))
        assertNull(PricingTable().priceOf("trae/glm-5.3"))
    }

    // ---- 序列化 ----

    @Test
    fun `toJson fromJson 往返一致`() {
        val original = PricingTable()
            .withPrice("trae/glm-5.3", price)
            .withPrice("gemini-3.8-flash", ModelPrice(0.0, 0.0, 0.0, 0.0))

        val parsed = PricingTable.fromJson(original.toJson().toString())
        assertEquals(original, parsed)
        assertEquals(price, parsed.priceOf("trae/glm-5.3"))
        assertEquals(ModelPrice(0.0, 0.0, 0.0, 0.0), parsed.priceOf("gemini-3.8-flash"))
    }

    @Test
    fun `损坏或缺字段的条目整条跳过`() {
        val json = """
            {
              "prices": {
                "ok/model": {"prompt": 1.0, "completion": 2.0, "cacheRead": 0.5, "cacheWrite": 0.25},
                "missing-prompt": {"completion": 2.0, "cacheRead": 0.5, "cacheWrite": 0.25},
                "missing-cacheWrite": {"prompt": 1.0, "completion": 2.0, "cacheRead": 0.5},
                "non-numeric-prompt": {"prompt": "abc", "completion": 2.0, "cacheRead": 0.5, "cacheWrite": 0.25},
                "non-numeric-completion": {"prompt": 1.0, "completion": null, "cacheRead": 0.5, "cacheWrite": 0.25},
                "negative-cacheRead": {"prompt": 1.0, "completion": 2.0, "cacheRead": -0.5, "cacheWrite": 0.25},
                "negative-cacheWrite": {"prompt": 1.0, "completion": 2.0, "cacheRead": 0.5, "cacheWrite": -1.0},
                "not-object": 123
              }
            }
        """.trimIndent()

        val table = PricingTable.fromJson(json)
        assertEquals(1, table.prices.size)
        assertEquals(ModelPrice(1.0, 2.0, 0.5, 0.25), table.priceOf("ok/model"))
        assertNull(table.priceOf("missing-prompt"))
        assertNull(table.priceOf("missing-cacheWrite"))
        assertNull(table.priceOf("non-numeric-prompt"))
        assertNull(table.priceOf("non-numeric-completion"))
        assertNull(table.priceOf("negative-cacheRead"))
        assertNull(table.priceOf("negative-cacheWrite"))
        assertNull(table.priceOf("not-object"))
    }

    @Test
    fun `空值与非法 JSON 解析为空表`() {
        assertEquals(PricingTable(), PricingTable.fromJson(null))
        assertEquals(PricingTable(), PricingTable.fromJson(""))
        assertEquals(PricingTable(), PricingTable.fromJson("not json"))
        assertEquals(PricingTable(), PricingTable.fromJson("{}"))
        assertEquals(PricingTable(), PricingTable.fromJson("""{"prices": []}"""))
    }

    // ---- costOf ----

    @Test
    fun `未知价格返回 null 而零价返回 0`() {
        assertNull(costOf(null, 1000, 500, 0, 0), "未知价格必须是 null，不能当 0")
        assertEquals(0.0, costOf(ModelPrice(0.0, 0.0, 0.0, 0.0), 1000, 500, 100, 100))
        assertEquals(0.0, costOf(price, 0, 0, 0, 0))
    }

    @Test
    fun `正常计算精确结果`() {
        // 1000 prompt * 3 + 500 completion * 15 = 3000 + 7500 = 10500 (微美元量级按百万单价)
        assertEquals(0.0105, costOf(price, 1000, 500, 0, 0))
        // fresh = 1000 - 100(cacheRead) - 300(cacheWrite) = 600：600*3 + 100*1 + 300*5 + 2000*15
        // = 1800 + 100 + 1500 + 30000 = 33400
        assertEquals(0.0334, costOf(price, 1000, 2000, 100, 300))
    }

    @Test
    fun `cache 命中不重复计 prompt 单价`() {
        // prompt 1000 全部命中缓存：只按 cacheRead 单价算一次
        assertEquals(0.001, costOf(price, 1000, 0, 1000, 0))
        // 若误按 prompt 全额再算一次，结果会是 0.004
        assertTrue(costOf(price, 1000, 0, 1000, 0)!! < 0.004)
    }

    @Test
    fun `cacheRead 与 cacheWrite 超出 prompt 时截断`() {
        // cacheRead 800 + cacheWrite 500 > prompt 1000：cacheWrite 截到 200，fresh = 0
        assertEquals(0.0018, costOf(price, 1000, 0, 800, 500))
        // prompt 为 0 时缓存也计 0，不会出现负数
        assertEquals(0.0, costOf(price, 0, 0, 100, 100))
        // 缓存之和远超 prompt，仍只按 prompt 内额度计费
        assertEquals(0.001, costOf(price, 1000, 0, 5000, 5000))
    }

    @Test
    fun `负 token 按 0 处理`() {
        assertEquals(0.0075, costOf(price, -100, 500, -50, -50))
        assertEquals(0.0, costOf(price, -1, -1, -1, -1))
    }

    // ---- 微美元 ----

    @Test
    fun `toMicroUsd 非法输入归零`() {
        assertEquals(0L, toMicroUsd(null))
        assertEquals(0L, toMicroUsd(-1.0))
        assertEquals(0L, toMicroUsd(0.0))
        assertEquals(0L, toMicroUsd(Double.NaN))
        assertEquals(0L, toMicroUsd(Double.POSITIVE_INFINITY))
        assertEquals(0L, toMicroUsd(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `toMicroUsd 按 1e-6 截断`() {
        assertEquals(3_500_000L, toMicroUsd(3.5))
        assertEquals(500_000L, toMicroUsd(0.5))
        assertEquals(62_500L, toMicroUsd(0.0625))
        assertEquals(123_456L, toMicroUsd(0.123456789), "多余小数截断而非四舍五入")
    }

    @Test
    fun `formatMicroUsd 去掉尾随零`() {
        assertEquals("0", formatMicroUsd(0L))
        assertEquals("0", formatMicroUsd(-1L))
        assertEquals("3", formatMicroUsd(3_000_000L))
        assertEquals("3.5", formatMicroUsd(3_500_000L))
        assertEquals("3.0001", formatMicroUsd(3_000_100L))
        assertEquals("0.000001", formatMicroUsd(1L))
        assertEquals("0.01", formatMicroUsd(10_000L))
    }
}
