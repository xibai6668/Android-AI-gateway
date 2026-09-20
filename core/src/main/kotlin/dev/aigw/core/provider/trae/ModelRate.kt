package dev.aigw.core.provider.trae

import java.util.Locale

/**
 * 模型的积分消耗倍率（相对基准的计费倍数，数字越大扣积分越快）。
 *
 * 上游 `get_detail_param` **不返回**倍率字段（已核对两份独立实现：trae2api-web 的
 * `FetchModels` 与 cli2api 的 `catalog.go` 都只解析 config_name / display_name /
 * context_window 等，没有任何价格或倍率字段），所以这里是一张**本地维护的参考表**。
 *
 * 取值优先级：官方文档 > 官方社区公告（模型上新帖）> 社区实测。每条都记来源，
 * 因为倍率会随官方调价、活动折扣、会员档位、忙闲时段变化——官方文档也明确写着
 * 「同一个模型在不同用户身份和时段看到的消耗速度不同」，所以界面上只能当参考值展示。
 */
data class ModelRate(
    /** 折扣前的基准倍率。0 表示该模型不计费。 */
    val base: Double,
    val source: RateSource,
    /** 官方明确公告的限时折扣后倍率；未公告则为 null。会随会员档位与时段变化。 */
    val discounted: Double? = null,
    /** 折扣的生效条件短标签，如「会员5折」「空闲5折」；无折扣为 null。 */
    val discountTag: String? = null,
    /** 补充说明（完整口径、数据日期等）。无默认值：每条都必须写明来源。 */
    val note: String,
) {
    /** 自定义模型不消耗积分。 */
    val isFree: Boolean get() = base == 0.0

    /** 界面用的主标签，如 `0.78×`、`不消耗积分`。 */
    fun baseLabel(): String = if (isFree) "不消耗积分" else "${formatMultiplier(base)}×"

    /** 限时折扣标签，如 `0.39×`；没有折扣则为 null。 */
    fun discountLabel(): String? = discounted?.let { "${formatMultiplier(it)}×" }

    /**
     * 一行式倍率摘要，界面只展示这一处，避免同一模型出现两个倍率数字。
     *
     * 带折扣时写明「原价 → 现价」的关系，而不是把基准价与折后价分开两处展示。
     */
    fun summaryLabel(): String = when {
        isFree -> "不消耗积分"
        discounted != null -> "限时 ${discountLabel()}（${discountTag ?: "折扣"}，基准 ${baseLabel()}）"
        else -> "基准 ${baseLabel()}"
    }
}

/** 倍率数据来源，决定界面上的可信度标注。 */
enum class RateSource(val tag: String) {
    /** docs.trae.cn 官方文档。 */
    OFFICIAL("官方"),

    /** 官方社区（forum.trae.cn）的模型上新/调价公告。 */
    ANNOUNCEMENT("公告"),

    /** 社区实测汇总，官方未公布。 */
    COMMUNITY("实测"),
}

/**
 * 模型 ID → 积分倍率。ID 大小写不敏感，并收敛同一模型的不同写法
 * （`Doubao-Seed-2.1-Turbo` / `Seed-2.1-Turbo`、`DeepSeek-V4-Flash-Official` / `DeepSeek-V4-Flash` 等）。
 */
object ModelRates {

    private const val SOURCE_DOC = "来源：docs.trae.cn《内置模型限时折扣》"

    /** 查倍率；没有公开数据时返回 null（界面不展示，不要瞎猜）。 */
    fun rateOf(modelId: String): ModelRate? = TABLE[modelId.trim().lowercase(Locale.US)]

    /** 所有已知倍率的模型 ID 数（便于测试与自检）。 */
    val knownCount: Int get() = TABLE.size

    /** 全表快照，供自检（如“每条都必须带来源说明”）遍历。 */
    fun all(): Map<String, ModelRate> = TABLE.toMap()

    private fun official(
        base: Double,
        note: String,
        discounted: Double? = null,
        discountTag: String? = null,
    ): ModelRate = ModelRate(
        base = base,
        source = RateSource.OFFICIAL,
        discounted = discounted,
        discountTag = discountTag,
        note = note,
    )

    private fun announcement(base: Double, note: String): ModelRate = ModelRate(
        base = base,
        source = RateSource.ANNOUNCEMENT,
        note = note,
    )

    private fun community(base: Double, note: String): ModelRate = ModelRate(
        base = base,
        source = RateSource.COMMUNITY,
        note = note,
    )

    /**
     * 官方文档口径（`docs.trae.cn/ide_limited-time-discount-for-builtin-models`，2026-09 抓取）：
     * 表里给的是「折扣前积分消耗速度」。
     */
    private val GLM_5X = official(0.78, "$SOURCE_DOC；会员享 5 折", 0.39, "会员5折")
    private val SEED_PRO = official(0.8, "$SOURCE_DOC；9/24 前限时 1 折", 0.08, "限时1折")
    private val SEED_TURBO = official(0.4, "$SOURCE_DOC；会员享 2.5 折", 0.1, "会员2.5折")
    private val SEED_CODE = official(0.12, "$SOURCE_DOC；会员享 2.5 折", 0.03, "会员2.5折")
    private val DS_PRO = official(0.72, "$SOURCE_DOC；22:00-次日 8:00 享 5 折", 0.36, "空闲5折")
    private val DS_FLASH = official(0.16, "$SOURCE_DOC；免费用户空闲时段 5 折，会员全时段 5 折", 0.08, "5折")

    private val CUSTOM_MODEL = official(0.0, "官方：仅内置模型消耗积分，自定义模型不消耗积分")

    private val TABLE: Map<String, ModelRate> = buildMap {
        // ---- 豆包 / Seed 系 ----
        put("seed-evolving", SEED_PRO)
        put("doubao-seed-evolving", SEED_PRO)

        put("seed-2.1-pro", SEED_PRO)
        put("doubao-seed-2.1-pro", SEED_PRO)

        put("seed-2.1-turbo", SEED_TURBO)
        put("doubao-seed-2.1-turbo", SEED_TURBO)

        // 官方折扣文档写作 Seed-2.1-Code，模型清单里叫 Seed-Code / Doubao-Seed-Code，视作同一模型
        put("seed-code", SEED_CODE)
        put("seed-2.1-code", SEED_CODE)
        put("doubao-seed-code", SEED_CODE)

        // ---- GLM 系 ----
        put("glm-5.3", GLM_5X)
        put("glm-5.2", GLM_5X)
        put("glm-5", community(0.70, "社区实测汇总（forum.trae.cn 173840，2026-08）"))
        put(
            "glm-5.3-flash",
            community(0.08, "官方社区：定价约为 GLM-5.3 的 1/10、限时折扣内 1/20（forum.trae.cn 178015）"),
        )

        // ---- DeepSeek 系 ----
        // 2026-09-10 官方公告移除预览版、正式版继承模型名，故两个 ID 都按正式版记
        put("deepseek-v4-pro", DS_PRO)
        put("deepseek-v4-pro-official", DS_PRO)
        put("deepseek-v4-flash", DS_FLASH)
        put("deepseek-v4-flash-official", DS_FLASH)

        // ---- 千问系 ----
        put("qwen3.8-max", announcement(1.50, "官方社区模型上新公告（forum.trae.cn 175814）"))
        put("qwen3.8-flash", announcement(0.08, "官方社区模型上新公告（forum.trae.cn 178262）"))
        put("qwen-3.7-plus", community(0.25, "社区实测汇总（forum.trae.cn 173840，2026-08）"))

        // ---- Kimi / MiniMax ----
        put("kimi-k3", community(1.65, "社区实测汇总（forum.trae.cn 173840，2026-08）"))
        put("kimi-k2.7-code", community(0.62, "社区实测汇总（forum.trae.cn 173840，2026-08）"))
        put("kimi-k2.6", community(0.69, "社区实测汇总（forum.trae.cn 173840，2026-08）"))
        put("minimax-m3", community(0.26, "社区实测汇总（forum.trae.cn 173840，2026-08）"))

        // ---- 自定义模型：不计费 ----
        put("custom_model_gemini", CUSTOM_MODEL)
        put("custom_model_kimi", CUSTOM_MODEL)
        put("custom_model_claude", CUSTOM_MODEL)
        put("custom_model_gpt-5", CUSTOM_MODEL)
        put("custom_model_deepseek_v4", CUSTOM_MODEL)
    }
}

/** `0.78` → `"0.78"`、`1.5` → `"1.5"`、`0.1` → `"0.1"`；固定 Locale.US，避免地区小数点差异。 */
private fun formatMultiplier(value: Double): String =
    "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')
