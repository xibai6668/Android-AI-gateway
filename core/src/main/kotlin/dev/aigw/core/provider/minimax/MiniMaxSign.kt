package dev.aigw.core.provider.minimax

import java.security.MessageDigest

/**
 * 请求签名。上游校验三个头：
 * - `x-timestamp`：秒级时间戳；
 * - `x-signature`：`md5(ts + SECRET_KEY + bodyJson)`（逆向自网页 JS 的静态密钥版）；
 * - `yy`：`md5(encodeURIComponent(path?query) + "_" + bodyJson + md5(ts) + "ooui")`。
 *
 * body 的序列化必须与签名一致：Gson `JsonObject.toString()` 与浏览器 `JSON.stringify`
 * 同为紧凑无空格格式，字段顺序即插入顺序，全链路只用同一个 body 字符串。
 */
internal object MiniMaxSign {

    fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun xSignature(tsSeconds: String, bodyJson: String): String =
        md5Hex(tsSeconds + MiniMaxConstants.SECRET_KEY + bodyJson)

    fun yy(pathWithQuery: String, bodyJson: String, tsSeconds: String): String =
        md5Hex(encodeURIComponent(pathWithQuery) + "_" + bodyJson + md5Hex(tsSeconds) + MiniMaxConstants.SIGN_SALT)

    /**
     * 与 JS `encodeURIComponent` 等价：不转义 `A-Za-z0-9 - _ . ! ~ * ' ( )`，
     * 其余按 UTF-8 逐字节转大写 %XX。签名对编码结果敏感，不要换 URLEncoder。
     */
    fun encodeURIComponent(value: String): String {
        val keep = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.!~*'()"
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt().toChar()
            if (keep.indexOf(ch) >= 0) out.append(ch) else out.append('%').append("%02X".format(byte))
        }
        return out.toString()
    }
}
