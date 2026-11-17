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
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("sanitizeEnabled", sanitizeEnabled)
        addProperty("minIntervalMillis", minIntervalMillis)
        addProperty("jitterMillis", jitterMillis)
        addProperty("privacyRedactionEnabled", privacyRedactionEnabled)
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
            return SecuritySettings(
                sanitizeEnabled = obj.get("sanitizeEnabled")?.asBoolean ?: false,
                minIntervalMillis = obj.get("minIntervalMillis")?.asLong?.coerceIn(0L, 60_000L) ?: 0L,
                jitterMillis = obj.get("jitterMillis")?.asLong?.coerceIn(0L, 10_000L) ?: 0L,
                extraWords = extra,
                privacyRedactionEnabled = obj.get("privacyRedactionEnabled")?.asBoolean ?: false,
            )
        }
    }
}
