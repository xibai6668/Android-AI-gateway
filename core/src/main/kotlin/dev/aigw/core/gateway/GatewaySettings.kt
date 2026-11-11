package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.objOrNull

/** 网关的全局运行参数（与具体供应商无关）。 */
data class GatewaySettings(
    val port: Int = DEFAULT_PORT,
    /** 客户端调用网关时要带的 API Key；为空表示不校验。 */
    val apiKey: String = "",
    /** 「无 Key 调用」开关：允许客户端不填 Key 直连。 */
    val allowNoKey: Boolean = true,
    /**
     * 是否同时监听局域网（关闭则只绑 127.0.0.1）。
     *
     * 默认 false（仅本机）：局域网暴露会把网关端口开给同网段所有设备，
     * 而默认 allowNoKey=true 时任何设备都能直接调用，风险过大。
     * [fromJson] 用 [boolOr] 读旧配置：已存过值的用户（无论 true/false）保持原值，
     * 仅当字段缺失（老版本升级/首次运行）时才落到这个新默认。
     */
    val exposeLan: Boolean = false,
    /** 「只看可用模型」开关：隐藏上游的非对话内部条目。 */
    val onlyUsableModels: Boolean = false,
    /** token 预刷新窗口（秒）：临期多久内才刷新。 */
    val refreshSkewSeconds: Long = 300L,
    /** 单次请求最多换号次数。 */
    val maxRotate: Int = 3,
    /** 详细日志：记录每次调用的请求/转发/发送/返回全链路原文，用于排查 503 类问题；会增加存储占用。 */
    val verboseLogging: Boolean = false,
    /** 调用记录保留天数（1~3650）；服务运行时每日自动清理超过该天数的记录。 */
    val logRetentionDays: Int = DEFAULT_RETENTION_DAYS,
    /** 调用记录开关：关闭后不再写入每条调用的记录（含请求/响应原文），已存记录不受影响。 */
    val callLoggingEnabled: Boolean = true,
    /** 请求日志环形缓冲容量（条）。 */
    val requestLogCapacity: Int = DEFAULT_REQUEST_LOG_CAPACITY,
    /** 模型名不带 provider 前缀时落到哪个供应商。 */
    val defaultProvider: String = "trae",
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("port", port)
        addProperty("apiKey", apiKey)
        addProperty("allowNoKey", allowNoKey)
        addProperty("exposeLan", exposeLan)
        addProperty("onlyUsableModels", onlyUsableModels)
        addProperty("refreshSkewSeconds", refreshSkewSeconds)
        addProperty("maxRotate", maxRotate)
        addProperty("verboseLogging", verboseLogging)
        addProperty("logRetentionDays", logRetentionDays)
        addProperty("callLoggingEnabled", callLoggingEnabled)
        addProperty("requestLogCapacity", requestLogCapacity)
        addProperty("defaultProvider", defaultProvider)
    }

    companion object {
        const val DEFAULT_PORT = 8790
        const val STORE_KEY = "settings/gateway.json"

        const val DEFAULT_RETENTION_DAYS = 30
        const val MIN_RETENTION_DAYS = 1
        const val MAX_RETENTION_DAYS = 3650

        const val DEFAULT_REQUEST_LOG_CAPACITY = 300
        const val MIN_REQUEST_LOG_CAPACITY = 50
        const val MAX_REQUEST_LOG_CAPACITY = 5000

        fun clampRequestLogCapacity(value: Int): Int =
            value.coerceIn(MIN_REQUEST_LOG_CAPACITY, MAX_REQUEST_LOG_CAPACITY)

        /**
         * 预刷新窗口上限（秒）。
         *
         * 窗口被当成「临期多久内刷新」真实使用后，过大的值会让 CodeBuddy / Raccoon 等
         * 每次请求都刷新，故存量配置里的 24h 也在读取时收敛到这个上限。
         */
        const val MAX_REFRESH_SKEW_SECONDS = 3600L

        fun fromJson(raw: String): GatewaySettings {
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return GatewaySettings()
            val defaults = GatewaySettings()
            return GatewaySettings(
                port = obj.intOr("port", defaults.port),
                apiKey = obj.strOr("apiKey", defaults.apiKey),
                allowNoKey = obj.boolOr("allowNoKey", defaults.allowNoKey),
                exposeLan = obj.boolOr("exposeLan", defaults.exposeLan),
                onlyUsableModels = obj.boolOr("onlyUsableModels", defaults.onlyUsableModels),
                refreshSkewSeconds = clampRefreshSkew(obj.longOr("refreshSkewSeconds", defaults.refreshSkewSeconds)),
                maxRotate = obj.intOr("maxRotate", defaults.maxRotate),
                verboseLogging = obj.boolOr("verboseLogging", defaults.verboseLogging),
                callLoggingEnabled = obj.boolOr("callLoggingEnabled", defaults.callLoggingEnabled),
                requestLogCapacity = clampRequestLogCapacity(obj.intOr("requestLogCapacity", defaults.requestLogCapacity)),
                defaultProvider = obj.strOr("defaultProvider", defaults.defaultProvider),
                logRetentionDays = clampRetentionDays(obj.intOr("logRetentionDays", defaults.logRetentionDays)),
            )
        }

        /** 保留天数超出范围时夹回合法区间，避免 0 天把记录全删光或天文数字永不清理。 */
        fun clampRetentionDays(days: Int): Int = days.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

        /** 预刷新窗口夹回 [0, MAX_REFRESH_SKEW_SECONDS]，避免负值或超大值造成刷新风暴。 */
        fun clampRefreshSkew(seconds: Long): Long = seconds.coerceIn(0L, MAX_REFRESH_SKEW_SECONDS)
    }
}

/** 单个供应商的设置。 */
data class ProviderSettings(
    val enabled: Boolean = true,
    /** 该供应商的默认模型（模型名不带 `provider/` 前缀时用）。 */
    val defaultModel: String = "",
    /** 供应商私有设置（如 Trae 的客户端版本号），key/value 由各自 UI 组件维护。 */
    val options: Map<String, String> = emptyMap(),
) {
    fun option(key: String, fallback: String): String =
        options[key]?.takeIf { it.isNotEmpty() } ?: fallback

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("enabled", enabled)
        addProperty("defaultModel", defaultModel)
        val opts = JsonObject()
        for ((key, value) in options) opts.addProperty(key, value)
        add("options", opts)
    }

    companion object {
        fun fromJson(raw: String?): ProviderSettings {
            if (raw.isNullOrEmpty()) return ProviderSettings()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ProviderSettings()
            val options = LinkedHashMap<String, String>()
            obj.objOrNull("options")?.entrySet()?.forEach { (key, value) ->
                if (value.isJsonPrimitive) options[key] = value.asString
            }
            return ProviderSettings(
                enabled = obj.get("enabled")?.asBoolean ?: true,
                defaultModel = obj.get("defaultModel")?.asString.orEmpty(),
                options = options,
            )
        }
    }
}

/** 用户手动导入的 OpenAI 兼容供应商。 */
data class CustomProviderConfig(
    /** 稳定短名，作为 provider id 的后缀（`custom:<key>`）。 */
    val key: String,
    val name: String,
    val baseUrl: String,
    val apiKeys: List<String>,
    val protocol: String = PROTOCOL_OPENAI,
    /** 手填或从 `/v1/models` 拉取的模型名列表。 */
    val models: List<String> = emptyList(),
) {
    val providerId: String get() = "custom:$key"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("key", key)
        addProperty("name", name)
        addProperty("baseUrl", baseUrl)
        add("apiKeys", JsonArray().apply { apiKeys.forEach { add(it) } })
        addProperty("protocol", protocol)
        add("models", JsonArray().apply { models.forEach { add(it) } })
    }

    companion object {
        const val PROTOCOL_OPENAI = "openai"

        fun fromJson(obj: JsonObject): CustomProviderConfig? {
            val key = obj.get("key")?.asString.orEmpty()
            val baseUrl = obj.get("baseUrl")?.asString.orEmpty()
            if (key.isEmpty() || baseUrl.isEmpty()) return null
            val keys = obj.arrayOrNull("apiKeys")?.mapNotNull { it.asString } ?: emptyList()
            val models = obj.arrayOrNull("models")?.mapNotNull { it.asString } ?: emptyList()
            return CustomProviderConfig(
                key = key,
                name = obj.get("name")?.asString.orEmpty().ifEmpty { key },
                baseUrl = baseUrl,
                apiKeys = keys,
                protocol = obj.get("protocol")?.asString.orEmpty().ifEmpty { PROTOCOL_OPENAI },
                models = models,
            )
        }

        /**
         * 把用户填的短名整理成模型前缀可用的形式。
         *
         * 模型 id 是 `custom:<短名>/<模型名>`，短名里带空格或斜杠会让路由解析混乱；
         * 留空则供应商存得住但活不过重启（`fromJson` 会把它当无效项丢掉）。
         */
        fun normalizeKey(raw: String): String = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-', '_')

        /** 由显示名称派生一个可用的短名，用于表单预填。 */
        fun deriveKey(name: String): String = normalizeKey(name)
    }
}

/** 设置的读写：全局、各供应商、自定义供应商三类分开存。 */
class SettingsRepository(private val store: KeyValueStore) {

    fun load(): GatewaySettings {
        val raw = store.read(GatewaySettings.STORE_KEY) ?: return GatewaySettings()
        return GatewaySettings.fromJson(raw)
    }

    fun save(settings: GatewaySettings) {
        store.write(GatewaySettings.STORE_KEY, settings.toJson().toString())
    }

    fun loadProvider(id: String): ProviderSettings =
        ProviderSettings.fromJson(store.read(providerKey(id)))

    fun saveProvider(id: String, settings: ProviderSettings) {
        store.write(providerKey(id), settings.toJson().toString())
    }

    fun loadCustomProviders(): List<CustomProviderConfig> {
        val raw = store.read(CUSTOM_KEY) ?: return emptyList()
        val array = runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            CustomProviderConfig.fromJson(obj)
        }
    }

    fun saveCustomProviders(list: List<CustomProviderConfig>) {
        store.write(CUSTOM_KEY, JsonArray().apply { list.forEach { add(it.toJson()) } }.toString())
    }

    private fun providerKey(id: String) = "settings/provider/$id.json"

    private companion object {
        const val CUSTOM_KEY = "settings/custom.json"
    }
}

private fun JsonObject.strOr(key: String, fallback: String): String {
    val v = get(key) ?: return fallback
    return if (v.isJsonNull) fallback else runCatching { v.asString }.getOrDefault(fallback)
}

private fun JsonObject.intOr(key: String, fallback: Int): Int {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asInt }.getOrDefault(fallback)
}

private fun JsonObject.longOr(key: String, fallback: Long): Long {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asLong }.getOrDefault(fallback)
}

private fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asBoolean }.getOrDefault(fallback)
}
