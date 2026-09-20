package dev.aigw.core.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 账号池里的统一账号。
 *
 * 各 provider 的凭证差异全部封在 [secret]（provider 私有的 JSON 串）里，
 * 账号池只关心 [providerId]/[uid] 与展示用的 [nickname]，因此新增供应商不必改账号池。
 */
data class ProviderAccount(
    val providerId: String,
    val uid: String,
    val nickname: String,
    /** provider 私有凭证 JSON（TraeAccount / LoomyAccount / … 序列化后原样保存）。 */
    val secret: String,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("providerId", providerId)
        addProperty("uid", uid)
        addProperty("nickname", nickname)
        addProperty("secret", secret)
    }

    companion object {
        fun parse(raw: String): ProviderAccount? {
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
            val providerId = obj.get("providerId")?.asString.orEmpty()
            val uid = obj.get("uid")?.asString.orEmpty()
            if (providerId.isEmpty() || uid.isEmpty()) return null
            return ProviderAccount(
                providerId = providerId,
                uid = uid,
                nickname = obj.get("nickname")?.asString.orEmpty(),
                secret = obj.get("secret")?.asString.orEmpty(),
            )
        }
    }
}
