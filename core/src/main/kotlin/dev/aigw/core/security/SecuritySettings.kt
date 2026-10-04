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
    /** 账号级限速：两次上游调用的最短间隔（毫秒）。 */
    val minIntervalMillis: Long = 1500L,
    /** 节拍抖动幅度（毫秒），避免固定节奏被风控打标。实际等待 = 间隔 + 随机(0..抖动)。 */
    val jitterMillis: Long = 300L,
    /** 自定义补充敏感词表（内置词表之外额外指定的词汇）。 */
    val extraWords: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("sanitizeEnabled", sanitizeEnabled)
        addProperty("minIntervalMillis", minIntervalMillis)
        addProperty("jitterMillis", jitterMillis)
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
                minIntervalMillis = obj.get("minIntervalMillis")?.asLong?.coerceIn(0L, 60_000L) ?: 1500L,
                jitterMillis = obj.get("jitterMillis")?.asLong?.coerceIn(0L, 10_000L) ?: 300L,
                extraWords = extra,
            )
        }
    }
}
