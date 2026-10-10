package dev.aigw.core.provider.codebuddy

import dev.aigw.core.provider.ProviderModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 内置模型目录的回归测试。
 *
 * 重点防两类问题：
 *  1. **国内/国际混用** —— 两份官方清单对同名模型的倍率不同（如 `deepseek-v4.1-flash`
 *     国内 `x0.03`、国际 `x0.00`），拿错了会让用户看到错误的计费；
 *  2. **倍率为空** —— 数据源加载失败时退回极简清单，界面看着正常但倍率全没了。
 */
class CodeBuddyCatalogTest {

    private fun rateOf(models: List<ProviderModel>, id: String): String? =
        models.firstOrNull { it.id == id }?.extra?.get("rate")

    @Test
    fun `国内与国际两份清单都能加载且带倍率`() {
        val cn = CodeBuddyProvider.fallbackCnForTest()
        val intl = CodeBuddyProvider.fallbackIntlForTest()
        assertTrue(cn.isNotEmpty(), "国内版清单应能读到")
        assertTrue(intl.isNotEmpty(), "国际版清单应能读到")

        val cnWithRate = cn.count { !it.extra["rate"].isNullOrEmpty() }
        val intlWithRate = intl.count { !it.extra["rate"].isNullOrEmpty() }
        println("国内版 ${cn.size} 个模型，带倍率 $cnWithRate")
        println("国际版 ${intl.size} 个模型，带倍率 $intlWithRate")
        println("国内样例：" + cn.take(3).map { "${it.id}=${it.extra["rate"]}" })
        println("国际样例：" + intl.take(3).map { "${it.id}=${it.extra["rate"]}" })

        assertTrue(cnWithRate > 0, "国内版应有模型带倍率")
        assertTrue(intlWithRate > 0, "国际版应有模型带倍率")
    }

    @Test
    fun `同名模型在两区域的倍率不同`() {
        val cn = CodeBuddyProvider.fallbackCnForTest()
        val intl = CodeBuddyProvider.fallbackIntlForTest()

        // 这是用户实际反馈的那个例子：国内有倍率、国际限时免费
        val cnFlash = rateOf(cn, "deepseek-v4.1-flash")
        val intlFlash = rateOf(intl, "deepseek-v4.1-flash")
        println("deepseek-v4.1-flash 国内=$cnFlash 国际=$intlFlash")

        // 两份清单都至少要有其中一个给出倍率（说明数据源没退化）
        assertTrue(cnFlash != null || intlFlash != null, "该模型至少要有一边给出倍率")
        if (cnFlash != null && intlFlash != null) {
            assertTrue(cnFlash != intlFlash, "两区域倍率应当不同（国内收费、国际限免）")
        }
    }

    @Test
    fun `默认模型是官方 Auto`() {
        val provider = CodeBuddyProvider()
        assertEquals("default-model", provider.resolveModel("auto"))
        assertEquals("default-model", provider.resolveModel(""))
    }

    @Test
    fun `清单里的模型都带上下文长度`() {
        val cn = CodeBuddyProvider.fallbackCnForTest()
        val missing = cn.filter { it.contextWindow <= 0 && !it.id.contains("image") && !it.id.contains("video") }
        println("国内版清单里缺上下文长度的：${missing.map { it.id }}")
        // 图像/视频类模型本来就没有 token 概念，排除后不应有大片缺失
        assertTrue(missing.size <= 3, "不该有这么多模型缺上下文长度：${missing.map { it.id }}")
    }

    @Test
    fun `关键模型都能查到倍率`() {
        val cn = CodeBuddyProvider.fallbackCnForTest()
        // 这些是官方清单里明确标了 credits 的，缺了说明解析出错
        // 这几个在国内版官方清单里都明确标了 credits
        listOf("deepseek-v4.1-flash", "glm-5.2", "glm-5.3", "kimi-k2.6").forEach { id ->
            val rate = rateOf(cn, id)
            println("$id -> $rate")
            assertNotNull(rate, "$id 在官方清单里有 credits，不该解析不出来")
        }
    }
}
