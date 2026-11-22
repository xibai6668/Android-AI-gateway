package dev.aigw.core.security

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore

/**
 * 安全防护与风控配置。
 */
data class SecuritySettings(
    /** 反审核脱敏开关：在敏感词内部插入零宽空格，只改 system/developer 消息。 */
    val sanitizeEnabled: Boolean = false,
    /**
     * 账号级限速：两次上游调用的最短间隔（毫秒）。
     *
     * 默认 0（不限速）：它是在发往上游之前**阻塞等待**的，连续/并发请求会被串成
     * 1.5~1.8s 的节拍，直接体现为「首字很慢」；需要规避自动化特征的用户可在
     * 「安全防护」页自行开启（建议 1000~3000ms）。
     */
    val minIntervalMillis: Long = 0L,
    /** 节拍抖动幅度（毫秒），避免固定节奏被风控打标。实际等待 = 间隔 + 随机(0..抖动)。 */
    val jitterMillis: Long = 0L,
    /** 自定义补充敏感词表（内置词表之外额外指定的词汇）。 */
    val extraWords: List<String> = emptyList(),
    /**
     * 本地隐私脱敏：请求出网前把 API Key、邮箱等换成占位符，响应回来后还原。
     *
     * 默认关闭：它会让上游看到占位符而非原文，会让「把当前配置贴给模型改」这类用法多一层
     * 理解成本，由用户自己权衡是否开启。
     */
    val privacyRedactionEnabled: Boolean = false,
    /**
     * 风控提示词替换：改写系统提示词里会被上游当成「反代特征」的句子。
     *
     * 上游并不总是直接报错，常见做法是伪装成 429 RESOURCE_EXHAUSTED 或直接降智，
     * 所以默认开启：它只改系统轨里的固定句式，模型语义不变，对正常对话无副作用。
     */
    val promptRewriteEnabled: Boolean = true,
    /** 启用的内置规则预设（按 [PromptRiskRewriter.builtinRules] 的 setName 分组）。 */
    val promptRewritePresets: List<String> = emptyList(),
    /**
     * 被单独关掉的内置规则（按 [PromptRule.name] 记录）。
     *
     * 内置规则写死在代码里，无法真正删除；用户「删除」一条内置规则时记在这里，
     * 装配规则链时跳过它。这样上游改了检测点、我们后续更新内置规则表时，
     * 用户的禁用选择依然保留，不会因为规则列表变动而失效。
     */
    val promptRewriteDisabledRules: List<String> = emptyList(),
    /** 用户自定义替换规则。 */
    val promptRewriteRules: List<PromptRule> = emptyList(),
) {
    /** 实际生效的预设：未显式配置时全部内置预设默认开启。 */
    fun effectivePromptPresets(): Set<String> =
        promptRewritePresets.ifEmpty { PromptRiskRewriter.builtinRuleSets().keys }.toSet()

    /** 某条内置规则是否被用户关掉。 */
    fun isBuiltinRuleDisabled(ruleName: String): Boolean = ruleName in promptRewriteDisabledRules

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("sanitizeEnabled", sanitizeEnabled)
        addProperty("minIntervalMillis", minIntervalMillis)
        addProperty("jitterMillis", jitterMillis)
        addProperty("privacyRedactionEnabled", privacyRedactionEnabled)
        addProperty("promptRewriteEnabled", promptRewriteEnabled)
        val presetArr = JsonArray()
        for (p in promptRewritePresets) presetArr.add(p)
        add("promptRewritePresets", presetArr)
        val disabledArr = JsonArray()
        for (n in promptRewriteDisabledRules) disabledArr.add(n)
        add("promptRewriteDisabledRules", disabledArr)
        val ruleArr = JsonArray()
        for (r in promptRewriteRules) {
            ruleArr.add(
                JsonObject().apply {
                    addProperty("name", r.name)
                    addProperty("pattern", r.pattern)
                    addProperty("replacement", r.replacement)
                },
            )
        }
        add("promptRewriteRules", ruleArr)
        val arr = JsonArray()
        for (w in extraWords) arr.add(w)
        add("extraWords", arr)
    }

    companion object {
        const val STORE_KEY = "settings/security.json"

        fun fromJson(raw: String?): SecuritySettings {
            if (raw.isNullOrBlank()) return SecuritySettings()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return SecuritySettings()
            val extra = mutableListOf<String>()
            obj.getAsJsonArray("extraWords")?.forEach {
                if (it.isJsonPrimitive) extra.add(it.asString)
            }
            val presets = mutableListOf<String>()
            obj.getAsJsonArray("promptRewritePresets")?.forEach {
                if (it.isJsonPrimitive) presets.add(it.asString)
            }
            val disabledRules = mutableListOf<String>()
            obj.getAsJsonArray("promptRewriteDisabledRules")?.forEach {
                if (it.isJsonPrimitive) disabledRules.add(it.asString)
            }
            val rules = mutableListOf<PromptRule>()
            obj.getAsJsonArray("promptRewriteRules")?.forEach { element ->
                val ruleObj = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
                val pattern = ruleObj.get("pattern")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (pattern.isEmpty()) return@forEach
                rules.add(
                    PromptRule(
                        name = ruleObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                            .ifEmpty { PromptRiskRewriter.CUSTOM_SET },
                        pattern = pattern,
                        replacement = ruleObj.get("replacement")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    ),
                )
            }
            return SecuritySettings(
                sanitizeEnabled = obj.get("sanitizeEnabled")?.asBoolean ?: false,
                minIntervalMillis = obj.get("minIntervalMillis")?.asLong?.coerceIn(0L, 60_000L) ?: 0L,
                jitterMillis = obj.get("jitterMillis")?.asLong?.coerceIn(0L, 10_000L) ?: 0L,
                extraWords = extra,
                privacyRedactionEnabled = obj.get("privacyRedactionEnabled")?.asBoolean ?: false,
                promptRewriteEnabled = obj.get("promptRewriteEnabled")?.asBoolean ?: true,
                promptRewritePresets = presets,
                promptRewriteDisabledRules = disabledRules,
                promptRewriteRules = rules,
            )
        }
    }
}
