package dev.aigw.core.provider.chatgpt

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import java.util.Base64

class ChatGPTCredentialTest {

    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.sig"
    }

    private val accessToken = jwt(
        """{"exp":2000000000,"email":"me@example.com",""" +
            """"https://api.openai.com/auth":{"chatgpt_account_id":"acc-123","chatgpt_plan_type":"plus"}}""",
    )

    @Test
    fun `jwt claims provide account id plan and expiry`() {
        assertEquals("acc-123", ChatGPTJwt.accountId(accessToken))
        assertEquals("plus", ChatGPTJwt.planType(accessToken))
        assertEquals(2_000_000_000_000L, ChatGPTJwt.expiresAtMillis(accessToken))
    }

    @Test
    fun `email prefers id_token profile claim`() {
        val idToken = jwt("""{"https://api.openai.com/profile":{"email":"from-id-token@example.com"}}""")
        assertEquals("from-id-token@example.com", ChatGPTJwt.email(idToken, accessToken))
        // 没有 id_token 时退回 access token 顶层 email
        assertEquals("me@example.com", ChatGPTJwt.email(null, accessToken))
    }

    @Test
    fun `broken token does not throw`() {
        assertEquals("", ChatGPTJwt.accountId("not-a-jwt"))
        assertEquals(0L, ChatGPTJwt.expiresAtMillis("garbage"))
    }

    @Test
    fun `credential round trips through json`() {
        val credential = ChatGPTCredential(
            accessToken = "at",
            refreshToken = "rt",
            expiresAtMillis = 1234L,
            accountId = "acc-1",
            email = "a@b.c",
            plan = "pro",
        )
        val restored = ChatGPTCredential.fromJson(credential.toJson())
        assertEquals(credential, restored)
        assertTrue(restored.hasRefreshToken)
    }

    @Test
    fun `missing refresh token is preserved as empty`() {
        val restored = ChatGPTCredential.fromJson("""{"access_token":"at"}""")
        assertEquals("", restored.refreshToken)
        assertFalse(restored.hasRefreshToken)
    }

    @Test
    fun `plan labels follow codex plan table`() {
        assertEquals("Plus", ChatGPTCredential.planDisplayName("plus"))
        // 上游命名反直觉：prolite 才是 Pro，pro 是更高的 Pro (More)
        assertEquals("Pro", ChatGPTCredential.planDisplayName("prolite"))
        assertEquals("Pro (More)", ChatGPTCredential.planDisplayName("pro"))
        assertEquals("Pro (Max)", ChatGPTCredential.planDisplayName("promax"))
        assertEquals("Team", ChatGPTCredential.planDisplayName("team"))
        assertEquals("", ChatGPTCredential.planDisplayName(""))
    }

    @Test
    fun `expiring check treats unknown expiry as expiring`() {
        val unknown = ChatGPTCredential("at", "rt", 0L)
        assertTrue(unknown.isExpiringWithin(60_000, 1_000))
        val valid = ChatGPTCredential("at", "rt", 1_000_000L)
        assertFalse(valid.isExpiringWithin(60_000, 1_000))
        assertTrue(valid.isExpiringWithin(60_000, 950_000))
    }

    @Test
    fun `null payload decodes to null claims`() {
        assertNull(ChatGPTJwt.claims("onlyheader"))
    }
}
