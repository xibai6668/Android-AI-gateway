package dev.aigw.core.provider.minimax

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * MiniMax 账号凭证。
 *
 * 支持两种认证体系：
 * 1. 官方 OAuth 2.0 Device Flow 凭证（推荐，浏览器授权自动拾取）：
 *    - [accessToken]: Bearer Token
 *    - [refreshToken]: 换取新 access_token
 *    - [expiresAt]: 过期毫秒时间戳
 *    - [region]: "cn" 或 "global"
 * 2. 网页端/API Key 凭证（备用或手动粘贴）：
 *    - [token]: JWT 或 API Key
 *    - [userId]: 账号唯一标识（realUserID）
 *    - [deviceId]: 设备唯一标识
 */
data class MiniMaxAccount(
    val token: String,
    val userId: String,
    val deviceId: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
    val region: String = MiniMaxConstants.REGION_CN,
    val accountUid: String = "",
) {
    /** 有效的实际调用 Token（优先 accessToken/token）。 */
    val activeToken: String get() = token.ifEmpty { refreshToken }

    val isOAuth: Boolean get() = refreshToken.isNotEmpty() || expiresAt > 0L

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("token", token)
        addProperty("userId", userId)
        if (deviceId.isNotEmpty()) addProperty("deviceId", deviceId)
        if (refreshToken.isNotEmpty()) addProperty("refreshToken", refreshToken)
        if (expiresAt > 0L) addProperty("expiresAt", expiresAt)
        if (region.isNotEmpty()) addProperty("region", region)
        if (accountUid.isNotEmpty()) addProperty("accountUid", accountUid)
    }

    fun withDeviceId(deviceId: String): MiniMaxAccount = copy(deviceId = deviceId)

    companion object {
        fun parse(secret: String): MiniMaxAccount? {
            val obj = runCatching { JsonParser.parseString(secret).asJsonObject }.getOrNull() ?: return null
            val token = obj.get("token")?.asString?.trim().orEmpty()
            val userId = obj.get("userId")?.asString?.trim().orEmpty()
            val refreshToken = obj.get("refreshToken")?.asString?.trim().orEmpty()
            val expiresAt = obj.get("expiresAt")?.asLong ?: 0L
            val region = obj.get("region")?.asString?.trim()?.ifEmpty { MiniMaxConstants.REGION_CN } ?: MiniMaxConstants.REGION_CN
            val accountUid = obj.get("accountUid")?.asString?.trim().orEmpty()

            if (token.isEmpty() && refreshToken.isEmpty()) return null
            val finalUserId = userId.ifEmpty { accountUid }
            if (finalUserId.isEmpty() && refreshToken.isEmpty()) return null
            val deviceId = obj.get("deviceId")?.asString?.trim().orEmpty()

            return MiniMaxAccount(
                token = token,
                userId = finalUserId,
                deviceId = deviceId,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                region = region,
                accountUid = accountUid.ifEmpty { finalUserId },
            )
        }

        /**
         * 粘贴导入支持：
         * 1. 完整 JSON：包含 token/userId 或 OAuth 导出；
         * 2. 组合串：`userId+_token`；
         * 3. 裸 API Key / Access Token。
         */
        fun import(raw: String, defaultRegion: String = MiniMaxConstants.REGION_CN): MiniMaxAccount? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{")) {
                return parse(trimmed)
            }
            if (trimmed.contains("+")) {
                val plus = trimmed.indexOf('+')
                if (plus <= 0 || plus >= trimmed.length - 1) return null
                val userId = trimmed.substring(0, plus).trim()
                val token = trimmed.substring(plus + 1).trim()
                if (userId.isEmpty() || token.isEmpty()) return null
                return MiniMaxAccount(
                    token = token,
                    userId = userId,
                    region = defaultRegion,
                )
            }
            // 裸 Token / API Key
            val uid = "mm-" + trimmed.hashCode().toUInt().toString(16).padStart(8, '0')
            return MiniMaxAccount(
                token = trimmed,
                userId = uid,
                accountUid = uid,
                region = defaultRegion,
            )
        }
    }
}
