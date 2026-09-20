package dev.aigw.core.provider.trae

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模型积分倍率表。
 *
 * 表里的数字必须能对回公开来源：官方文档（docs.trae.cn）> 官方社区公告 > 社区实测，
 * 来源标签会展示给用户，所以映射错了比没有更糟。
 */
class ModelRateTest {

    @Test
    fun `官方文档口径的倍率与折扣`() {
        val glm = ModelRates.rateOf("glm-5.3")!!
        assertEquals(0.78, glm.base)
        assertEquals(0.39, glm.discounted, "会员 5 折")
        assertEquals(RateSource.OFFICIAL, glm.source)

        val turbo = ModelRates.rateOf("Doubao-Seed-2.1-Turbo")!!
        assertEquals(0.4, turbo.base)
        assertEquals(0.1, turbo.discounted, "会员 2.5 折")

        val code = ModelRates.rateOf("Doubao-Seed-Code")!!
        assertEquals(0.12, code.base)
        assertEquals(0.03, code.discounted)

        val evo = ModelRates.rateOf("Doubao-Seed-Evolving")!!
        assertEquals(0.8, evo.base)
        assertEquals(0.08, evo.discounted, "9/24 前限时 1 折")

        val dsPro = ModelRates.rateOf("DeepSeek-V4-Pro")!!
        assertEquals(0.72, dsPro.base)
        assertEquals(0.36, dsPro.discounted, "空闲时段 5 折")

        val dsFlash = ModelRates.rateOf("DeepSeek-V4-Flash")!!
        assertEquals(0.16, dsFlash.base)
        assertEquals(0.08, dsFlash.discounted)
    }

    @Test
    fun `同一模型的不同写法收敛到同一条`() {
        // 官方文档用 seed-2.1-* 命名，上游模型清单用 Doubao-Seed-2.1-*
        assertEquals(ModelRates.rateOf("seed-2.1-turbo"), ModelRates.rateOf("Doubao-Seed-2.1-Turbo"))
        assertEquals(ModelRates.rateOf("Seed-Code"), ModelRates.rateOf("seed-2.1-code"))
        // 正式版继承模型名后两个 ID 都应命中
        assertEquals(ModelRates.rateOf("DeepSeek-V4-Flash"), ModelRates.rateOf("DeepSeek-V4-Flash-Official"))
    }

    @Test
    fun `大小写与空白不敏感`() {
        assertEquals(0.78, ModelRates.rateOf("  GLM-5.3  ")!!.base)
        assertEquals(1.65, ModelRates.rateOf("Kimi-K3")!!.base)
    }

    @Test
    fun `自定义模型不计费`() {
        for (id in listOf("custom_model_gemini", "custom_model_claude", "custom_model_gpt-5")) {
            val rate = ModelRates.rateOf(id)
            assertNotNull(rate, "$id 应有条目")
            assertTrue(rate.isFree, "$id 应标记为不消耗积分")
            assertEquals("不消耗积分", rate.baseLabel())
        }
    }

    @Test
    fun `没有公开数据的模型返回 null，不瞎猜`() {
        assertNull(ModelRates.rateOf("glm-5-turbo"), "官方未公布 GLM-5-Turbo 倍率")
        assertNull(ModelRates.rateOf("seed-code-pro-0430"), "编码不确定的旧代号不下结论")
        assertNull(ModelRates.rateOf("sagitta"))
        assertNull(ModelRates.rateOf(""))
        assertNull(ModelRates.rateOf("totally-unknown-model"))
    }

    @Test
    fun `全表每一条都带非空来源说明`() {
        // UI 直接渲染 note（不再有 fallback 分支），所以 note 为空会渲染出孤零零的「[官方]」。
        // 这里遍历全表守住该不变式，新增条目忘写 note 会当场报错。
        val empty = ModelRates.all().filterValues { it.note.isBlank() }.keys
        assertTrue(empty.isEmpty(), "以下条目缺少来源说明：$empty")
    }

    @Test
    fun `每条数据都带来源与说明`() {
        for (id in listOf("glm-5.3", "kimi-k3", "qwen3.8-max", "glm-5.3-flash")) {
            val rate = ModelRates.rateOf(id)!!
            assertTrue(rate.note.isNotBlank(), "$id 缺少来源说明")
        }
        // 社区实测值必须标成 COMMUNITY，不与官方口径混淆
        assertEquals(RateSource.COMMUNITY, ModelRates.rateOf("kimi-k3")!!.source)
        assertEquals(RateSource.ANNOUNCEMENT, ModelRates.rateOf("qwen3.8-max")!!.source)
    }

    @Test
    fun `官方折扣必须同时带数值与条件标签`() {
        // 回归：构造 ModelRate 时曾用位置参数，插入新字段后 note 被错位写进 discountTag
        for (id in listOf("glm-5.3", "seed-2.1-turbo", "doubao-seed-code", "deepseek-v4-pro")) {
            val rate = ModelRates.rateOf(id)!!
            assertNotNull(rate.discounted, "$id 官方有折扣数据")
            assertNotNull(rate.discountTag, "$id 折扣应带条件标签")
            assertTrue(rate.note.contains("docs.trae.cn"), "$id 的 note 应写明来源，实际：${rate.note}")
        }
    }

    @Test
    fun `一行式摘要把原价与折后价写在一处`() {
        // 回归：徽标曾单独展示基准倍率、下方折扣行又展示折后价，
        // 同一模型出现两个数字容易被误读成重复。
        assertEquals("限时 0.08×（限时1折，基准 0.8×）", ModelRates.rateOf("Doubao-Seed-Evolving")!!.summaryLabel())
        assertEquals("限时 0.39×（会员5折，基准 0.78×）", ModelRates.rateOf("glm-5.3")!!.summaryLabel())
        // 无折扣的模型也必须看到具体数字，不能只剩来源行
        assertEquals("基准 1.65×", ModelRates.rateOf("kimi-k3")!!.summaryLabel())
        assertEquals("基准 0.7×", ModelRates.rateOf("glm-5")!!.summaryLabel())
        assertEquals("不消耗积分", ModelRates.rateOf("custom_model_claude")!!.summaryLabel())
    }

    @Test
    fun `标签格式不会出现多余小数位`() {
        assertEquals("0.78×", ModelRates.rateOf("glm-5.3")!!.baseLabel())
        assertEquals("0.4×", ModelRates.rateOf("seed-2.1-turbo")!!.baseLabel())
        assertEquals("1.5×", ModelRates.rateOf("qwen3.8-max")!!.baseLabel())
        assertEquals("0.08×", ModelRates.rateOf("qwen3.8-flash")!!.baseLabel())
        assertNull(ModelRates.rateOf("glm-5")!!.discountLabel(), "GLM-5 无官方折扣数据")
    }

    @Test
    fun `内置回退清单里的模型尽量都有倍率`() {
        val missing = TraeModelCatalog.FALLBACK
            .map { it.id }
            .filterNot { TraeModelCatalog.isInternal(it) }
            .filterNot { it.startsWith("custom_model_") }
            .filter { ModelRates.rateOf(it) == null }
        // 允许少量确实没有公开数据的模型；数量增长说明该补表了
        assertTrue(
            missing.size <= 5,
            "未收录倍率的模型过多（${missing.size} 个）：$missing",
        )
    }
}
