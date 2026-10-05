package dev.aigw.core.provider.minimax

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * MiniMax Agent 网页端凭证：`_token`（JWT）+ `user_id`（realUserID）+ 可选 `device_id`。
 *
 * 三者都来自网页 LocalStorage（`_token` / `ANONYMOUS_REAL_USER_ID` / `USER_HARD_WARE_INFO`）；
 * `device_id` 缺省时网关自动调设备注册接口补齐并回存。
 */
data class MiniMaxAccount(
    val token: String,
    val userId: String,
    val deviceId: String = "",
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("token", token)
        addProperty("userId", userId)
        if (deviceId.isNotEmpty()) addProperty("deviceId", deviceId)
    }

    fun withDeviceId(deviceId: String): MiniMaxAccount = copy(deviceId = deviceId)

    companion object {
        fun parse(secret: String): MiniMaxAccount? {
            val obj = runCatching { JsonParser.parseString(secret).asJsonObject }.getOrNull() ?: return null
            val token = obj.get("token")?.asString?.trim().orEmpty()
            val userId = obj.get("userId")?.asString?.trim().orEmpty()
            if (token.isEmpty() || userId.isEmpty()) return null
            val deviceId = obj.get("deviceId")?.asString?.trim().orEmpty()
            return MiniMaxAccount(token, userId, deviceId)
        }

        /**
         * 粘贴导入。支持两种格式：
         * - JSON：`{"token":"…","userId":"…","deviceId":"…?"}`
         * - 组合串：`userId+_token`（参考实现约定的粘贴格式）
         *
         * 裸 JWT（缺 userId）无法工作：user_id 参与每条请求的伪装 query 与签名，不做兜底。
         */
        fun import(raw: String): MiniMaxAccount? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{")) {
                val obj = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull() ?: return null
                val token = obj.get("token")?.asString?.trim().orEmpty()
                val userId = obj.get("userId")?.asString?.trim().orEmpty()
                if (token.isEmpty() || userId.isEmpty()) return null
                val deviceId = obj.get("deviceId")?.asString?.trim().orEmpty()
                return MiniMaxAccount(token, userId, deviceId)
            }
            val plus = trimmed.indexOf('+')
            if (plus <= 0 || plus >= trimmed.length - 1) return null
            val userId = trimmed.substring(0, plus).trim()
            val token = trimmed.substring(plus + 1).trim()
            if (userId.isEmpty() || token.isEmpty()) return null
            return MiniMaxAccount(token, userId)
        }
    }
}
