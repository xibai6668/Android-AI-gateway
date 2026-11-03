package dev.aigw.core.provider.chatgpt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * ChatGPT OAuth 的协议实现：PKCE 生成、授权 URL、code 交换、refresh。
 *
 * 两处容易踩的格式差异（都在 codex CLI 里是隐式的，写错不报错只报 invalid_request）：
 * 1. code 交换是 **form**（`application/x-www-form-urlencoded`）；
 * 2. refresh 是 **JSON**（`application/json`）——反过来会直接 400。
 */
class ChatGPTAuthClient(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class Pkce(val verifier: String, val challenge: String)

    fun newPkce(): Pkce {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = base64Url(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, base64Url(digest))
    }

    fun newState(): String = base64Url(ByteArray(16).also { SecureRandom().nextBytes(it) })

    fun buildAuthUrl(state: String, pkce: Pkce): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to ChatGPTConstants.CLIENT_ID,
            "redirect_uri" to ChatGPTConstants.REDIRECT_URI,
            "scope" to ChatGPTConstants.SCOPE,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            // 让 id_token 带上 organization 信息；Codex 登录页按这个参数走简化流程。
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to ChatGPTConstants.ORIGINATOR,
        )
        return ChatGPTConstants.AUTH_ENDPOINT + "?" + params.joinToString("&") { (key, value) ->
            key + "=" + URLEncoder.encode(value, "UTF-8")
        }
    }

    /** 用授权码换凭证。失败时抛出带上游描述的可读异常。 */
    fun exchangeCode(code: String, verifier: String): ChatGPTCredential {
        val body = listOf(
            "grant_type" to "authorization_code",
            "client_id" to ChatGPTConstants.CLIENT_ID,
            "code" to code,
            "redirect_uri" to ChatGPTConstants.REDIRECT_URI,
            "code_verifier" to verifier,
        ).joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }
        val json = post(
            ChatGPTConstants.TOKEN_ENDPOINT,
            contentType = "application/x-www-form-urlencoded",
            body = body,
        )
        return toCredential(json, previous = null)
    }

    /** 刷新凭证。响应缺 refresh_token 时沿用 [ChatGPTCredential.refreshToken]。 */
    fun refresh(refreshToken: String): ChatGPTCredential {
        if (refreshToken.isBlank()) throw IllegalStateException("refresh_token 为空，无法刷新")
        val payload = JsonObject().apply {
            addProperty("client_id", ChatGPTConstants.CLIENT_ID)
            addProperty("grant_type", "refresh_token")
            addProperty("refresh_token", refreshToken)
        }
        val json = post(
            ChatGPTConstants.TOKEN_ENDPOINT,
            contentType = "application/json",
            body = payload.toString(),
        )
        return toCredential(json, previous = ChatGPTCredential("", refreshToken, 0))
    }

    private fun post(url: String, contentType: String, body: String): JsonObject {
        val conn = HttpSupport.open(
            url = url,
            method = "POST",
            headers = mapOf(
                "Content-Type" to contentType,
                "Accept" to "application/json",
                "User-Agent" to ChatGPTConstants.USER_AGENT,
            ),
            body = body,
            connectTimeoutMs = ChatGPTConstants.CONNECT_TIMEOUT_MS,
            readTimeoutMs = ChatGPTConstants.CONNECT_TIMEOUT_MS,
        )
        return try {
            val status = conn.responseCode
            val raw = HttpSupport.readBody(conn)
            if (status !in 200..299) {
                throw IllegalStateException("OpenAI 授权失败（HTTP $status）：${errorMessage(raw)}")
            }
            JsonParser.parseString(raw).asJsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun toCredential(json: JsonObject, previous: ChatGPTCredential?): ChatGPTCredential {
        val access = json.stringOrNull("access_token")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenAI 授权响应缺少 access_token")
        val refresh = json.stringOrNull("refresh_token")?.takeIf { it.isNotBlank() }
            ?: previous?.refreshToken.orEmpty()
        val idToken = json.stringOrNull("id_token")
        val expiresIn = json.longOrNull("expires_in") ?: 0L
        val expiresAt = if (expiresIn > 0) nowMillis() + expiresIn * 1000 else ChatGPTJwt.expiresAtMillis(access)
        return ChatGPTCredential(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = expiresAt,
            accountId = ChatGPTJwt.accountId(access).ifEmpty { previous?.accountId.orEmpty() },
            email = ChatGPTJwt.email(idToken, access).ifEmpty { previous?.email.orEmpty() },
            plan = ChatGPTJwt.planType(access).ifEmpty { previous?.plan.orEmpty() },
        )
    }

    /** 上游错误体可能是 `{"error":"invalid_grant","error_description":"…"}` 或嵌套对象。 */
    private fun errorMessage(raw: String): String {
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        obj?.let { body ->
            body.stringOrNull("error_description")?.takeIf { it.isNotBlank() }?.let { return it }
            body.stringOrNull("error")?.takeIf { it.isNotBlank() }?.let { return it }
            body.objOrNull("error")?.let { nested ->
                listOf("code", "message", "type").firstNotNullOfOrNull { key ->
                    nested.stringOrNull(key)?.takeIf { it.isNotBlank() }
                }?.let { return it }
            }
        }
        return raw.take(200)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
