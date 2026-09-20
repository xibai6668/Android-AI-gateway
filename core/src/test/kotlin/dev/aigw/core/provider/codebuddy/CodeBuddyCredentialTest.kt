package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WorkBuddy 凭证字段解析。
 *
 * 背景：账号页显示的是 fallback uid（`codebuddy-xxxx`），签到直接 HTTP 400——
 * 说明登录响应里的 uid 没被解析到，billing 接口因此缺 `X-User-Id` 头。
 * 上游不同版本/站点用过 camelCase 与 snake_case 两套字段名，这里把两种都锁住。
 */
class CodeBuddyCredentialTest {

    private fun jwt(payloadJson: String): String {
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "eyJhbGciOiJIUzI1NiJ9.$encoded.signature"
    }

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `camelCase 与 snake_case 字段名都能取到`() {
        val camel = obj("""{"accessToken":"a1","refreshToken":"r1","uid":"u1","enterpriseId":"e1"}""")
        assertEquals("a1", camel.firstString("accessToken", "access_token"))
        assertEquals("r1", camel.firstString("refreshToken", "refresh_token"))
        assertEquals("u1", camel.firstString("uid", "userId", "user_id"))
        assertEquals("e1", camel.firstString("enterpriseId", "enterprise_id"))

        val snake = obj("""{"access_token":"a2","refresh_token":"r2","user_id":"u2","enterprise_id":"e2"}""")
        assertEquals("a2", snake.firstString("accessToken", "access_token"))
        assertEquals("r2", snake.firstString("refreshToken", "refresh_token"))
        assertEquals("u2", snake.firstString("uid", "userId", "user_id"))
        assertEquals("e2", snake.firstString("enterpriseId", "enterprise_id"))
    }

    @Test
    fun `缺失字段返回空串而不是抛异常`() {
        val empty = obj("""{"domain":"www.codebuddy.cn"}""")
        assertEquals("", empty.firstString("accessToken", "access_token"))
        assertEquals("", empty.firstString("uid", "userId"))
        assertEquals(0L, empty.firstLong("expiresAt", "expires_at"))
    }

    @Test
    fun `从 JWT 里解出 user_id 与 tenant_id`() {
        val token = jwt("""{"user_id":"u-123","tenant_id":"t-9","sub":"s-1"}""")
        assertEquals("u-123", jwtClaim(token, "user_id", "userId", "uid", "sub"))
        assertEquals("t-9", jwtClaim(token, "tenant_id", "tenantId"))
        assertEquals("s-1", jwtClaim(token, "sub"))
    }

    @Test
    fun `非 JWT 或缺少声明时返回空串`() {
        assertEquals("", jwtClaim("not-a-jwt", "user_id"))
        assertEquals("", jwtClaim("", "user_id"))
        assertEquals("", jwtClaim(jwt("""{"sub":"s"}"""), "user_id"))
        assertEquals("", jwtClaim("header.!!!not-base64!!!.sig", "user_id"))
    }
}
