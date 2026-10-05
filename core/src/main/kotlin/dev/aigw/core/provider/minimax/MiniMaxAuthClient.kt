package dev.aigw.core.provider.minimax

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.stringOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * MiniMax 官方 OAuth 2.0 Device Authorization Grant（RFC 8628 + PKCE）客户端。
 *
 * 流程：
 * 1. [startDeviceAuth] 请求 `/oauth2/device/code`，返回带有用户校验码与验证 URI 的凭据；
 * 2. 用户在浏览器打开 `verification_uri` 完成授权；
 * 3. [pollDeviceAuth] 轮询 `/oauth2/token`，授权完成后自动拿到 access_token 与 refresh_token 并构建 [ProviderAccount]；
 * 4. [refreshToken] 在 access_token 临期前用 refresh_token 换新。
 */
class MiniMaxAuthClient(
    private val oauthBaseProvider: (String) -> String = { MiniMaxConstants.oauthBase(it) },
) {
    private val random = SecureRandom()

    /** 临时缓存正在轮询中的授权上下文：state -> DeviceSession */
    private val pendingSessions = mutableMapOf<String, DeviceSession>()

    data class DeviceSession(
        val state: String,
        val userCode: String,
        val codeVerifier: String,
        val region: String,
        val expiredIn: Long,
    )

    /**
     * 第一步：发起设备授权请求，返回登录页面 URL 与 state。
     */
    fun startDeviceAuth(region: String = MiniMaxConstants.REGION_CN): DeviceAuthTicket {
        val finalRegion = if (region.equals(MiniMaxConstants.REGION_GLOBAL, ignoreCase = true)) {
            MiniMaxConstants.REGION_GLOBAL
        } else {
            MiniMaxConstants.REGION_CN
        }
        val oauthBase = oauthBaseProvider(finalRegion)

        // 生成 PKCE 验证对
        val verifierBytes = ByteArray(32).also { random.nextBytes(it) }
        val codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val challengeBytes = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes)

        val stateBytes = ByteArray(16).also { random.nextBytes(it) }
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)

        val params = mapOf(
            "client_id" to MiniMaxConstants.CLIENT_ID,
            "scope" to MiniMaxConstants.SCOPES.joinToString(" "),
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )

        val (status, responseBody) = postForm("$oauthBase${MiniMaxConstants.PATH_DEVICE_CODE}", params)
        if (status !in 200..299) {
            throw IllegalStateException("获取 MiniMax 设备授权码失败 (HTTP $status): $responseBody")
        }

        val json = runCatching { JsonParser.parseString(responseBody).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("解析 MiniMax 授权码响应失败: $responseBody")

        val userCode = json.stringOrNull("user_code")
            ?: throw IllegalStateException("MiniMax 响应中缺少 user_code: $responseBody")
        val verificationUri = json.stringOrNull("verification_uri")
            ?: throw IllegalStateException("MiniMax 响应中缺少 verification_uri: $responseBody")
        val expiredIn = json.longOrNull("expired_in") ?: (System.currentTimeMillis() + 300_000L)

        // 缓存本次会话，供后续按 state 轮询
        synchronized(pendingSessions) {
            pendingSessions[state] = DeviceSession(
                state = state,
                userCode = userCode,
                codeVerifier = codeVerifier,
                region = finalRegion,
                expiredIn = expiredIn,
            )
        }

        return DeviceAuthTicket(
            loginUrl = verificationUri,
            state = state,
        )
    }

    /**
     * 第二步：轮询授权结果。用户在浏览器点击授权后立即返回 [DeviceAuthPoll.Success]。
     */
    fun pollDeviceAuth(state: String, region: String = ""): DeviceAuthPoll {
        val session = synchronized(pendingSessions) { pendingSessions[state] }
            ?: return DeviceAuthPoll.Failed("未找到匹配的授权会话，请重新发起登录")

        val oauthBase = oauthBaseProvider(session.region)
        val params = mapOf(
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "client_id" to MiniMaxConstants.CLIENT_ID,
            "user_code" to session.userCode,
            "code_verifier" to session.codeVerifier,
        )

        val (status, responseBody) = postForm("$oauthBase${MiniMaxConstants.PATH_OAUTH_TOKEN}", params)
        if (status !in 200..299) {
            return DeviceAuthPoll.Failed("轮询授权接口错误 (HTTP $status): $responseBody")
        }

        val json = runCatching { JsonParser.parseString(responseBody).asJsonObject }.getOrNull()
            ?: return DeviceAuthPoll.Failed("解析 Token 响应失败: $responseBody")

        val authStatus = json.stringOrNull("status")?.lowercase()
        if (authStatus == "pending") {
            return DeviceAuthPoll.Pending
        }

        if (authStatus == "success" || json.has("access_token")) {
            val accessToken = json.stringOrNull("access_token")
                ?: return DeviceAuthPoll.Failed("响应声明成功但缺少 access_token: $responseBody")
            val refreshToken = json.stringOrNull("refresh_token").orEmpty()
            val expiredIn = json.longOrNull("expired_in") ?: 0L

            // 成功后清理会话缓存
            synchronized(pendingSessions) { pendingSessions.remove(state) }

            // 派生唯一 UID
            val uid = deriveUid(accessToken)
            val account = MiniMaxAccount(
                token = accessToken,
                userId = uid,
                accountUid = uid,
                refreshToken = refreshToken,
                expiresAt = expiredIn,
                region = session.region,
            )

            return DeviceAuthPoll.Success(
                ProviderAccount(
                    providerId = MiniMaxProvider.ID,
                    uid = uid,
                    nickname = "MiniMax (${if (session.region == MiniMaxConstants.REGION_GLOBAL) "国际版" else "国内版"})",
                    secret = account.toJson().toString(),
                ),
            )
        }

        return DeviceAuthPoll.Failed(
            json.stringOrNull("status_msg")
                ?: json.stringOrNull("error_description")
                ?: json.stringOrNull("error")
                ?: "授权未通过 ($authStatus)",
        )
    }

    /**
     * 刷新 Token。
     */
    fun refreshToken(account: MiniMaxAccount): MiniMaxAccount? {
        if (account.refreshToken.isEmpty()) return null
        val oauthBase = oauthBaseProvider(account.region)
        val params = mapOf(
            "grant_type" to "refresh_token",
            "client_id" to MiniMaxConstants.CLIENT_ID,
            "refresh_token" to account.refreshToken,
        )
        val (status, responseBody) = postForm("$oauthBase${MiniMaxConstants.PATH_OAUTH_TOKEN}", params)
        if (status !in 200..299) return null

        val json = runCatching { JsonParser.parseString(responseBody).asJsonObject }.getOrNull() ?: return null
        val newAccessToken = json.stringOrNull("access_token") ?: return null
        val newRefreshToken = json.stringOrNull("refresh_token") ?: account.refreshToken
        val expiredIn = json.longOrNull("expired_in") ?: account.expiresAt

        return account.copy(
            token = newAccessToken,
            refreshToken = newRefreshToken,
            expiresAt = expiredIn,
        )
    }

    private fun deriveUid(accessToken: String): String =
        "mmx-" + MessageDigest.getInstance("MD5")
            .digest(accessToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(10)

    private fun postForm(url: String, params: Map<String, String>): Pair<Int, String> {
        val formBody = params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val bytes = formBody.toByteArray(Charsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(bytes.size)
        }
        conn.outputStream.use { it.write(bytes) }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val body = stream?.use { readAll(it) }.orEmpty()
        conn.disconnect()
        return status to body
    }

    private fun readAll(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toString("UTF-8")
    }
}
