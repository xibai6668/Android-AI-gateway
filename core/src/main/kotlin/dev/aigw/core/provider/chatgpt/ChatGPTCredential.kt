package dev.aigw.core.provider.chatgpt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.stringOrNull
import java.util.Base64

/**
 * ChatGPT 账号凭证。
 *
 * 字段是 OAuth TokenResponse 的原样投影，不做二次加工：
 * - [expiresAtMillis] 是绝对毫秒（换 token 时按 `expires_in` 算出，缺则读 JWT 的 `exp`）
 * - 刷新时若响应不带 `refresh_token`，**必须沿用旧值**（服务端只在轮换时下发新值，置空等于把账号锁死）
 */
data class ChatGPTCredential(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val accountId: String = "",
    val email: String = "",
    val plan: String = "",
) {
    val hasRefreshToken: Boolean get() = refreshToken.isNotBlank()

    fun isExpiringWithin(thresholdMillis: Long, nowMillis: Long): Boolean =
        expiresAtMillis <= 0L || expiresAtMillis - nowMillis <= thresholdMillis

    /** 订阅档位的人话名称；空串表示上游没给。 */
    fun planLabel(): String = planDisplayName(plan)

    fun toJson(): String = JsonObject().apply {
        addProperty("access_token", accessToken)
        addProperty("refresh_token", refreshToken)
        addProperty("expires_at", expiresAtMillis)
        addProperty("account_id", accountId)
        addProperty("email", email)
        addProperty("plan", plan)
    }.toString()

    companion object {
        fun fromJson(raw: String): ChatGPTCredential {
            val obj = JsonParser.parseString(raw).asJsonObject
            fun str(vararg keys: String): String {
                for (key in keys) {
                    obj.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let { return it.asString }
                }
                return ""
            }
            val expires = obj.longOrNull("expires_at") ?: obj.longOrNull("expiresAt") ?: 0L
            return ChatGPTCredential(
                accessToken = str("access_token", "accessToken"),
                refreshToken = str("refresh_token", "refreshToken"),
                expiresAtMillis = expires,
                accountId = str("account_id", "accountId"),
                email = str("email"),
                plan = str("plan", "chatgpt_plan_type"),
            )
        }

        /**
         * 订阅档位的展示名。
         *
         * 映射是反直觉的：`prolite` 才是 ChatGPT Pro，`pro` 是更高的 Pro（More），`promax` 再高一档。
         * 照抄 codex-rs 的 PLANS 表，不要按字面猜。
         */
        fun planDisplayName(plan: String): String = when (plan.lowercase()) {
            "free" -> "Free"
            "plus" -> "Plus"
            "pro" -> "Pro (More)"
            "prolite" -> "Pro"
            "promax" -> "Pro (Max)"
            "team" -> "Team"
            "business" -> "Business"
            "enterprise" -> "Enterprise"
            else -> plan.trim()
        }
    }
}

/**
 * 只解析、不验签的 JWT claim 读取器。
 *
 * 用途是取 `chatgpt_account_id` / `chatgpt_plan_type` / `email` 这类展示与路由信息，
 * 不承担任何安全判断——凭证可信性由 HTTPS + 上游校验保证。
 */
object ChatGPTJwt {
    private const val API_AUTH_CLAIM = "https://api.openai.com/auth"
    private const val PROFILE_CLAIM = "https://api.openai.com/profile"

    fun claims(token: String): JsonObject? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val payload = Base64.getUrlDecoder().decode(parts[1])
            JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonObject
        }.getOrNull()
    }

    fun accountId(accessToken: String): String =
        runCatching { claims(accessToken)?.getAsJsonObject(API_AUTH_CLAIM)?.stringOrNull("chatgpt_account_id") }
            .getOrNull().orEmpty()

    fun planType(accessToken: String): String =
        runCatching { claims(accessToken)?.getAsJsonObject(API_AUTH_CLAIM)?.stringOrNull("chatgpt_plan_type") }
            .getOrNull().orEmpty()

    fun expiresAtMillis(accessToken: String): Long {
        val exp = runCatching { claims(accessToken)?.get("exp")?.asLong }.getOrNull() ?: 0L
        return if (exp > 0) exp * 1000 else 0L
    }

    fun email(idToken: String?, accessToken: String): String {
        idToken?.let { token ->
            runCatching { claims(token)?.getAsJsonObject(PROFILE_CLAIM)?.stringOrNull("email") }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return runCatching { claims(accessToken)?.stringOrNull("email") }.getOrNull().orEmpty()
    }
}
