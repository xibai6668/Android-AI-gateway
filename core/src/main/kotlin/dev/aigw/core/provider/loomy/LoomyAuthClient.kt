package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.util.UUID

/**
 * 讯飞账号服务客户端：短信验证码登录、用户信息、登出。
 *
 * 这一层的每个请求都要 HMAC-SHA1 签名（[LoomySign]），AccessKey 随客户端分发。
 */
class LoomyAuthClient(
    private val accountBase: String = LoomyConstants.ACCOUNT_BASE,
    private val accessKeyId: String = LoomyConstants.ACCESS_KEY_ID,
    private val accessKeySecret: String = LoomyConstants.ACCESS_KEY_SECRET,
    private val appId: String = LoomyConstants.APP_ID,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 发送短信验证码，返回上游的 msgid（登录时回传）。 */
    fun sendSmsCode(phone: String): String {
        val body = envelope(
            JsonObject().apply {
                addProperty("ccode", "86")
                addProperty("phone", phone)
                addProperty("expire", LoomyConstants.SMS_CODE_EXPIRE_SECONDS)
            },
        )
        val data = call(LoomyConstants.PATH_SEND_SMS_CODE, body)
        val msgid = data.str("msgid").ifEmpty { data.str("msgId") }
        if (msgid.isEmpty()) {
            throw LoomyApiException(200, LoomyErrorKind.CLIENT, data.toString(), "MISSING_MSGID")
        }
        return msgid
    }

    /** 用验证码换 session。 */
    fun loginBySmsCode(phone: String, code: String, msgid: String): LoomyAccount {
        val body = envelope(
            JsonObject().apply {
                addProperty("ccode", "86")
                addProperty("phone", phone)
                addProperty("mcode", code)
                addProperty("msgid", msgid)
                addProperty("expire", LoomyConstants.SESSION_EXPIRE_SECONDS)
            },
        )
        val data = call(LoomyConstants.PATH_LOGIN_BY_SMS, body)
        val session = data.str("session")
        if (session.isEmpty()) {
            throw LoomyApiException(200, LoomyErrorKind.CLIENT, data.toString(), "MISSING_SESSION")
        }
        val uid = data.str("userid").ifEmpty { data.str("userId") }
        val issuedAt = nowMillis()
        return LoomyAccount(
            uid = uid,
            phone = phone,
            session = session,
            nickname = "",
            createdAtMillis = issuedAt,
            expiresAtMillis = issuedAt + LoomyConstants.SESSION_EXPIRE_SECONDS * 1000L,
        )
    }

    /**
     * 查用户信息。
     *
     * 上游字段名未经真实账号验证，所以按多种可能取值，取不到就留空——
     * 昵称只用于界面展示，不该因为解析不到就让登录失败。
     */
    fun getUserInfo(session: String): JsonObject {
        val body = envelope(JsonObject().apply { addProperty("session", session) })
        return call(LoomyConstants.PATH_USER_INFO, body)
    }

    /** 从用户信息里尽力取昵称。 */
    fun nicknameOf(info: JsonObject): String {
        val candidates = listOf("nickname", "nickName", "name", "username", "uname")
        for (key in candidates) {
            val v = info.str(key)
            if (v.isNotEmpty()) return v
        }
        val nested = info.objOrNull("userinfo") ?: info.objOrNull("userInfo") ?: info.objOrNull("user")
        if (nested != null) {
            for (key in candidates) {
                val v = nested.str(key)
                if (v.isNotEmpty()) return v
            }
        }
        return ""
    }

    /** 组装 `{base, param}` 请求体。 */
    private fun envelope(param: JsonObject): String = JsonObject().apply {
        add("base", JsonObject().apply {
            addProperty("appid", appId)
            addProperty("modelid", "Web")
            addProperty("version", "1.0.0")
            addProperty("devid", "web")
            addProperty("ua", "Loomy|Desktop|Electron|macOS")
            addProperty("traceid", UUID.randomUUID().toString().replace("-", ""))
        })
        add("param", param)
    }.toString()

    /** 签名 → 请求 → 解出 `data`。业务码非成功时抛 [LoomyApiException]。 */
    private fun call(path: String, body: String): JsonObject {
        val signed = LoomySign.authorization(accessKeyId, accessKeySecret, "POST", path, body)
        val (status, responseBody) = httpJson(
            url = accountBase + path,
            method = "POST",
            headers = signed.toHeaders(),
            body = body,
        )
        return decodeEnvelope(status, responseBody).objOrNull("data") ?: JsonObject()
    }
}
