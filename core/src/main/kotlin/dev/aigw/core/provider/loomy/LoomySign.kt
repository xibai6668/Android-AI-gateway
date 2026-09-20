package dev.aigw.core.provider.loomy

import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞账号服务的请求签名。
 *
 * 算法逐行对齐官方客户端的 `electron/xfyun/sign.js`：
 * ```
 * stringToSign = METHOD \n ESCAPED_PATH \n ESCAPED_QUERY \n CONTENT_MD5 \n
 *                CONTENT_TYPE \n DATE \n NONCE \n SIGNED_HEADERS \n CANONICALIZED_HEADERS
 * signature = base64(HMAC-SHA1(accessKeySecret, stringToSign))
 * Authorization = "account {accessKeyId}:{signature}"
 * ```
 * 因为不额外携带 `x-` 前缀头，SIGNED_HEADERS 与 CANONICALIZED_HEADERS 都是空串
 * （对应 stringToSign 末尾的两个空段）。
 */
object LoomySign {

    const val AUTH_SCHEME = "account"

    /** 已实测：签名正确时服务端会返回业务码（如 session 无效），而不是签名错误。 */
    fun authorization(accessKeyId: String, accessKeySecret: String, method: String, path: String, body: String): SignedRequest {
        val date = httpDate()
        val nonce = UUID.randomUUID().toString()
        val contentType = "application/json"
        val contentMD5 = contentMd5(body)

        val signature = signature(
            accessKeySecret = accessKeySecret,
            method = method,
            path = path,
            query = "",
            body = body,
            contentType = contentType,
            date = date,
            nonce = nonce,
        )

        return SignedRequest(
            authorization = "$AUTH_SCHEME $accessKeyId:$signature",
            date = date,
            nonce = nonce,
            contentType = contentType,
            contentMD5 = contentMD5,
        )
    }

    /**
     * 纯函数形式的签名：日期与 nonce 由调用方给定，便于用官方实现的输出做对照测试。
     */
    fun signature(
        accessKeySecret: String,
        method: String,
        path: String,
        query: String,
        body: String,
        contentType: String,
        date: String,
        nonce: String,
    ): String {
        val parts = listOf(
            method.uppercase(Locale.US),
            escapedPath(path),
            query,
            contentMd5(body),
            contentType,
            date,
            nonce,
            "",
            "",
        )
        val stringToSign = parts.joinToString("\n")

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(accessKeySecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)))
    }

    /** 与 `crypto.createHash('md5').update(body,'utf8').digest('base64')` 等价。 */
    fun contentMd5(body: String): String {
        if (body.isEmpty()) return ""
        val digest = MessageDigest.getInstance("MD5").digest(body.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** RFC3986 §3.3：逐段转义，并额外把 `!'()*` 换成百分号编码。 */
    fun escapedPath(path: String): String {
        val clean = if (path.startsWith("/")) path else "/$path"
        val trimmed = if (clean.length > 1 && clean.endsWith("/")) clean.dropLast(1) else clean
        return trimmed.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) "" else escapeRfc3986(segment)
        }
    }

    private fun escapeRfc3986(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("!", "%21")
            .replace("'", "%27")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace("*", "%2A")

    /** `Date` 头格式，与 `new Date().toUTCString()` 一致。 */
    fun httpDate(nowMillis: Long = System.currentTimeMillis()): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date(nowMillis))
    }
}

/** 一次签名的产物，直接铺成请求头。 */
data class SignedRequest(
    val authorization: String,
    val date: String,
    val nonce: String,
    val contentType: String,
    val contentMD5: String,
) {
    fun toHeaders(): Map<String, String> = buildMap {
        put("Authorization", authorization)
        put("Date", date)
        put("Nonce", nonce)
        put("Content-Type", contentType)
        if (contentMD5.isNotEmpty()) put("Content-MD5", contentMD5)
    }
}
