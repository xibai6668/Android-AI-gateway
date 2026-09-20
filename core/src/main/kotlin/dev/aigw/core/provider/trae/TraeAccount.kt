package dev.aigw.core.provider.trae

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.objOrNull

/**
 * 一个 Trae 账号的凭证。
 *
 * accessToken 即 `Cloud-IDE-JWT` 头；refreshToken 每次 ExchangeToken 都会轮换，
 * 因此刷新成功后必须原子写回磁盘。
 */
data class TraeAccount(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    /** accessToken 过期时刻（Unix 秒），0 表示未知。 */
    val expiresAt: Long,
    val nickname: String,
    val enterpriseId: String,
    val machineId: String,
    val deviceId: String,
    val domain: String = TraeConstants.REGION_CN,
    val apiHost: String = TraeConstants.OAUTH_HOST,
) {
    fun needsRefresh(withinSeconds: Long, nowSeconds: Long): Boolean =
        expiresAt <= 0L || nowSeconds + withinSeconds >= expiresAt

    fun withTokens(accessToken: String, refreshToken: String, expiresAt: Long): TraeAccount =
        copy(accessToken = accessToken, refreshToken = refreshToken, expiresAt = expiresAt)

    fun withIdentity(uid: String, nickname: String, enterpriseId: String): TraeAccount =
        copy(
            uid = uid.ifEmpty { this.uid },
            nickname = nickname.ifEmpty { this.nickname },
            enterpriseId = enterpriseId.ifEmpty { this.enterpriseId },
        )

    /** 落盘格式（嵌套形，与登录脚本产出保持一致）。 */
    fun toJson(): JsonObject {
        val auth = JsonObject().apply {
            addProperty("accessToken", accessToken)
            addProperty("refreshToken", refreshToken)
            addProperty("expiresAt", expiresAt)
            addProperty("domain", domain)
            addProperty("apiHost", apiHost)
            addProperty("machineId", machineId)
            addProperty("deviceId", deviceId)
        }
        val account = JsonObject().apply {
            addProperty("uid", uid)
            addProperty("enterpriseId", enterpriseId)
            addProperty("nickname", nickname)
        }
        return JsonObject().apply {
            add("auth", auth)
            add("account", account)
        }
    }

    companion object {
        /** 兼容嵌套形 `{auth:{...},account:{...}}` 与扁平形 `{accessToken:...,uid:...}`。 */
        fun parse(raw: String): TraeAccount {
            val root = JsonParser.parseString(raw).asJsonObject
            val nested = root.has("auth")
            val auth = if (nested) root.objOrNull("auth") ?: JsonObject() else root
            val account = if (nested) root.objOrNull("account") ?: JsonObject() else root

            val accessToken = auth.str("accessToken")
            if (accessToken.isEmpty()) throw IllegalArgumentException("凭证缺少 accessToken")

            return TraeAccount(
                uid = account.str("uid"),
                accessToken = accessToken,
                refreshToken = auth.str("refreshToken"),
                expiresAt = auth.long("expiresAt"),
                nickname = account.str("nickname"),
                enterpriseId = account.str("enterpriseId"),
                machineId = auth.str("machineId"),
                deviceId = auth.str("deviceId"),
                domain = auth.str("domain").ifEmpty { TraeConstants.REGION_CN },
                apiHost = auth.str("apiHost").ifEmpty { TraeConstants.OAUTH_HOST },
            )
        }
    }
}

internal fun JsonObject.str(key: String): String {
    val v = get(key) ?: return ""
    return if (v.isJsonNull) "" else v.asString
}

internal fun JsonObject.long(key: String): Long {
    val v = get(key) ?: return 0L
    if (v.isJsonNull) return 0L
    return runCatching { v.asLong }.getOrDefault(0L)
}

internal fun JsonObject.obj(key: String): JsonObject? {
    val v = get(key) ?: return null
    return if (v.isJsonObject) v.asJsonObject else null
}
