package dev.aigw.core.provider.chatgpt

import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ChatGPTProviderTest {

    private fun provider(now: Long = 1_000_000L) = ChatGPTProvider(nowMillis = { now })

    @Test
    fun `auth url carries pkce state and registered redirect`() {
        val url = provider().buildAuthUrl()
        assertTrue(url.startsWith(ChatGPTConstants.AUTH_ENDPOINT + "?"))
        assertTrue(url.contains("client_id=${ChatGPTConstants.CLIENT_ID}"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge="))
        assertTrue(url.contains("state="))
        // redirect_uri 必须与注册值逐字符一致（URL 编码后比较）
        val encoded = java.net.URLEncoder.encode(ChatGPTConstants.REDIRECT_URI, "UTF-8")
        assertTrue(url.contains("redirect_uri=$encoded"))
        assertTrue(url.contains("codex_cli_simplified_flow=true"))
    }

    @Test
    fun `state mismatch is rejected`() {
        val provider = provider()
        provider.buildAuthUrl()
        val error = runCatching {
            provider.completeCallback("http://localhost:1455/auth/callback?code=abc&state=other")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("state"))
    }

    @Test
    fun `callback without pending login is rejected`() {
        val error = runCatching {
            provider().completeCallback("http://localhost:1455/auth/callback?code=abc&state=x")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `login ttl is enforced`() {
        var now = 0L
        val provider = ChatGPTProvider(nowMillis = { now })
        provider.buildAuthUrl()
        now = ChatGPTConstants.LOGIN_TTL_MS + 1
        val error = runCatching {
            provider.completeCallback("http://localhost:1455/auth/callback?code=abc")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("超时"))
    }

    @Test
    fun `import credentials accepts nested tokens`() {
        val raw = """{"tokens":{"access_token":"at","refresh_token":"rt","account_id":"acc-9",
            "email":"u@example.com","plan":"plus"}}"""
        val account = provider().importCredentials(raw)
        assertNotNull(account)
        assertEquals("acc-9", account!!.uid)
        assertTrue(account.nickname.contains("u@example.com"))
        assertTrue(account.nickname.contains("Plus"))
        assertEquals("acc-9", ChatGPTCredential.fromJson(account.secret).accountId)
    }

    @Test
    fun `import credentials rejects payload without access token`() {
        assertNull(provider().importCredentials("""{"access_token":""}"""))
        assertNull(provider().importCredentials("not json"))
    }

    @Test
    fun `resolve model falls back to default when catalog is empty`() {
        assertEquals(ChatGPTConstants.DEFAULT_MODEL, provider().resolveModel("auto"))
        assertEquals("gpt-5.1", provider().resolveModel("gpt-5.1"))
        // 带前缀也能剥掉（客户端偶尔会把 provider 前缀写进 model）
        assertEquals("gpt-5.1", provider().resolveModel("models/gpt-5.1"))
    }

    @Test
    fun `classify maps auth failures to session dead`() {
        assertEquals(ErrorKind.SESSION_DEAD, provider().classify(401, "{}").kind)
        assertEquals(ErrorKind.SESSION_DEAD, provider().classify(403, "{}").kind)
    }

    @Test
    fun `classify separates quota from soft rate limit`() {
        assertEquals(ErrorKind.QUOTA, provider().classify(429, """{"error":{"message":"limit_reached"}}""").kind)
        assertEquals(ErrorKind.QUOTA, provider().classify(429, "you have reached your usage limit").kind)
        assertEquals(ErrorKind.SOFT_RATE, provider().classify(429, """{"error":{"message":"too many requests"}}""").kind)
    }

    @Test
    fun `classify server client and not found`() {
        assertEquals(ErrorKind.SERVER, provider().classify(503, "").kind)
        assertEquals(ErrorKind.CLIENT, provider().classify(400, """{"error":{"message":"bad model"}}""").kind)
        assertEquals(ErrorKind.NOT_FOUND, provider().classify(404, "").kind)
    }

    @Test
    fun `classify surfaces upstream message`() {
        val error = provider().classify(400, """{"error":{"message":"model does not exist"}}""")
        assertTrue(error.message.contains("model does not exist"))
    }

    @Test
    fun `refresh skips when token is still fresh`() {
        val credential = ChatGPTCredential(
            accessToken = "at",
            refreshToken = "rt",
            expiresAtMillis = 10_000_000L,
            accountId = "acc",
        )
        val account = ProviderAccount("chatgpt", "acc", "n", credential.toJson())
        // 距过期还很久 → 不该刷新（也避免在这里打网络）
        assertNull(provider(now = 1_000_000L).refreshAccount(account, 0))
    }

    @Test
    fun `refresh skips account without refresh token`() {
        val credential = ChatGPTCredential("at", "", 1L, "acc")
        val account = ProviderAccount("chatgpt", "acc", "n", credential.toJson())
        assertNull(provider(now = 1_000_000L).refreshAccount(account, 0))
    }

    @Test
    fun `credit info shows plan without fake balance`() {
        val credential = ChatGPTCredential("at", "rt", 9_999_999L, "acc", "me@example.com", "pro")
        val account = ProviderAccount("chatgpt", "acc", "n", credential.toJson())
        val info = provider().creditInfo(account)
        assertNotNull(info)
        assertEquals(false, info!!.known)
        assertEquals(0L, info.balance)
        assertTrue(info.detail.contains("Pro (More)"))
        assertTrue(info.detail.contains("me@example.com"))
    }
}
