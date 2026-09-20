package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.long
import dev.aigw.core.util.str

/**
 * 一个 Loomy（讯飞账号）账号。
 *
 * 没有 refresh token：官方客户端也是靠 14 天有效期的 session 直接调接口，
 * 失效后需要重新用短信验证码登录。
 */
data class LoomyAccount(
    /** 讯飞账号 userid。 */
    val uid: String,
    /** 登录手机号（仅用于展示与重登）。 */
    val phone: String,
    /** 登录 session，等价于官方客户端的 `token`。 */
    val session: String,
    val nickname: String = "",
    /** 本次 session 的签发时间（毫秒）。 */
    val createdAtMillis: Long = 0L,
    /** 预估过期时间（毫秒）；上游未下发时按 14 天算。 */
    val expiresAtMillis: Long = 0L,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("uid", uid)
        addProperty("phone", phone)
        addProperty("session", session)
        addProperty("nickname", nickname)
        addProperty("createdAt", createdAtMillis)
        addProperty("expiresAt", expiresAtMillis)
    }

    companion object {
        fun parse(raw: String): LoomyAccount {
            val obj = JsonParser.parseString(raw).asJsonObject
            return fromJson(obj)
        }

        fun fromJson(obj: JsonObject): LoomyAccount = LoomyAccount(
            uid = obj.str("uid"),
            phone = obj.str("phone"),
            session = obj.str("session"),
            nickname = obj.str("nickname"),
            createdAtMillis = obj.long("createdAt"),
            expiresAtMillis = obj.long("expiresAt"),
        )
    }
}
