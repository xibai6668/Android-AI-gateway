package dev.aigw.core.provider.antigravity

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ProviderAccount
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 额度链路的假上游测试：Google One 积分解析（`loadCodeAssist`）与模型组配额解析
 * （`retrieveUserQuotaSummary`），以及「无积分回退到组配额」的行为。
 * 端点语义与 CLIProxyAPI credits / agy 桌面 App 对齐。
 */
class AntigravityProviderTest {

    private val CREDITS_PATH = "/${AntigravityProvider.API_VERSION}:loadCodeAssist"
    private val QUOTA_PATH = "/${AntigravityProvider.API_VERSION}:retrieveUserQuotaSummary"

    /** 记录收到的请求，供断言路径与请求体。 */
    private class Received(val paths: MutableList<String> = mutableListOf(), val bodies: MutableList<String> = mutableListOf())

    /** 两个端点返回同一响应的便捷路由。 */
    private fun both(status: Int, body: String): Map<String, Pair<Int, String>> =
        mapOf(CREDITS_PATH to (status to body), QUOTA_PATH to (status to body))

    /** 假上游：routes 按路径精确分发（path → 状态码+响应体），未命中的路径返回 200 `{}`。 */
    private fun withUpstream(
        routes: Map<String, Pair<Int, String>>,
        block: (base: String, received: Received) -> Unit,
    ) {
        val received = Received()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            received.paths += path
            received.bodies += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val (status, body) = routes[path] ?: 200 to "{}"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", received)
        } finally {
            server.stop(0)
        }
    }

    private fun paidCreditsBody(
        amount: String,
        minimum: String = "1",
        type: String = "GOOGLE_ONE_AI",
        tierId: String? = "g1-pro-tier",
        tierName: String = "Google AI Pro",
    ): String {
        val tier = if (tierId == null) "" else "\"id\":\"$tierId\",\"name\":\"$tierName\","
        return """{"paidTier":{$tier"availableCredits":[{"creditType":"$type","creditAmount":$amount,"minimumCreditAmountForUsage":$minimum}]}}"""
    }

    private fun quotaBody(vararg groups: String): String =
        """{"groups":[${groups.joinToString(",")}]}"""

    private fun quotaGroup(name: String, window: String, fraction: String, resetTime: String = ""): String =
        """{"displayName":"$name","buckets":[{"bucketId":"$window","remainingFraction":$fraction,"resetTime":"$resetTime"}]}"""

    private fun account(expiresAt: Long = 0): ProviderAccount = ProviderAccount(
        providerId = AntigravityProvider.ID,
        uid = "u1",
        nickname = "测试",
        secret = """{"accessToken":"tok","refreshToken":"r","expiresAt":$expiresAt,"email":"a@b.c","projectId":"proj-1"}""",
    )

    // ------------------------------------------------------- token 刷新

    @Test
    fun `token 未临期时不刷新`() = withUpstream(emptyMap()) { base, received ->
        val provider = AntigravityProvider(nowMillis = { 1_000_000L }, tokenEndpoint = "$base/token")
        val fresh = account(expiresAt = 1_000_000L / 1000 + 3600)
        assertEquals(null, provider.refreshAccount(fresh, skewSeconds = 3600))
        assertTrue(received.paths.none { it == "/token" }, "不应请求刷新：${received.paths}")
    }

    @Test
    fun `token 临期 5 分钟内才刷新且回存新 refresh_token`() = withUpstream(
        mapOf(
            "/token" to (200 to """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""),
        ),
    ) { base, received ->
        // now=1_000_000ms，过期在 +240s：落在 5 分钟安全窗口内 → 刷新
        val provider = AntigravityProvider(nowMillis = { 1_000_000L }, tokenEndpoint = "$base/token")
        val almostStale = account(expiresAt = 1_000_000L / 1000 + 240)
        val refreshed = provider.refreshAccount(almostStale, skewSeconds = 3600)!!
        assertEquals(listOf("/token"), received.paths, "应恰好刷新一次")
        assertTrue(received.bodies[0].contains("grant_type=refresh_token"), received.bodies[0])
        val secret = com.google.gson.JsonParser.parseString(refreshed.secret).asJsonObject
        assertEquals("new-access", secret.get("accessToken").asString)
        assertEquals("new-refresh", secret.get("refreshToken").asString, "新 refresh_token 必须回存")
        assertTrue(secret.get("expiresAt").asLong > 1_000_000L / 1000)
        assertEquals("a@b.c", refreshed.nickname)
    }

    @Test
    fun `刷新响应不轮换 refresh_token 时保留原值`() = withUpstream(
        mapOf("/token" to (200 to """{"access_token":"new-access","expires_in":3600}""")),
    ) { base, _ ->
        val provider = AntigravityProvider(nowMillis = { 1_000_000L }, tokenEndpoint = "$base/token")
        val refreshed = provider.refreshAccount(account(expiresAt = 1_000_000L / 1000 + 60), 3600)!!
        val secret = com.google.gson.JsonParser.parseString(refreshed.secret).asJsonObject
        assertEquals("r", secret.get("refreshToken").asString)
    }

    @Test
    fun `已过期且刷新失败时抛异常而不是拿旧 token 硬闯`() = withUpstream(
        mapOf("/token" to (400 to """{"error":"invalid_grant"}""")),
    ) { base, _ ->
        val provider = AntigravityProvider(nowMillis = { 2_000_000L }, tokenEndpoint = "$base/token")
        val stale = account(expiresAt = 1_000L)
        val error = runCatching { provider.refreshAccount(stale, 3600) }
        assertTrue(error.isFailure, "过期 + 刷新失败必须抛异常")
        assertTrue(error.exceptionOrNull()!!.message!!.contains("刷新失败"), error.exceptionOrNull()!!.message)
    }

    @Test
    fun `未过期但刷新失败时返回 null 不抛异常`() = withUpstream(
        mapOf("/token" to (500 to "boom")),
    ) { base, _ ->
        val provider = AntigravityProvider(nowMillis = { 1_000_000L }, tokenEndpoint = "$base/token")
        val almostStale = account(expiresAt = 1_000_000L / 1000 + 60)
        assertEquals(null, provider.refreshAccount(almostStale, 3600))
    }

    // ------------------------------------------------------- Google One 积分

    @Test
    fun `creditInfo 解析数字金额`() = withUpstream(both(200, paidCreditsBody("45.7"))) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertEquals(45L, info.balance)
        assertTrue(info.known)
        assertEquals("Google AI Pro · Google One AI 积分", info.detail)
    }

    @Test
    fun `creditAmount 为字符串也按浮点解析`() = withUpstream(both(200, paidCreditsBody("\"120\""))) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertEquals(120L, info.balance)
        assertTrue(info.known)
    }

    @Test
    fun `积分命中时不再请求配额端点`() = withUpstream(both(200, paidCreditsBody("45.7"))) { base, received ->
        AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())
        assertTrue(received.paths.none { it.contains("retrieveUserQuotaSummary") }, "命中积分后不应再查配额：${received.paths}")
    }

    @Test
    fun `Pro 订阅无积分条目时仍能识别订阅并回退到组配额`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"paidTier":{"id":"g1-pro-tier","name":"Google AI Pro"}}"""),
            QUOTA_PATH to (200 to quotaBody(quotaGroup("Gemini Models", "weekly_limit", "0.9"))),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertEquals(90L, info.balance)
        assertTrue(info.known)
        assertTrue(info.detail.startsWith("Google AI Pro"), "detail 应标订阅层级：${info.detail}")
        assertTrue(info.detail.contains("组配额剩余 90%"), info.detail)
        assertTrue(info.detail.contains("Gemini Models weekly_limit 90%"), "detail 应列全部窗口：${info.detail}")
    }

    @Test
    fun `未知订阅 id 用上游 name 展示`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"paidTier":{"id":"some-new-tier","name":"Google AI Max"}}"""),
            QUOTA_PATH to (200 to quotaBody(quotaGroup("Gemini Models", "weekly_limit", "0.8"))),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertTrue(info.detail.startsWith("Google AI Max"), info.detail)
    }

    @Test
    fun `积分余额低于可用下限时记为不可用`() = withUpstream(both(200, paidCreditsBody("0.5", minimum = "1"))) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertEquals(0L, info.balance)
        assertTrue(info.known)
        assertTrue(info.detail.contains("下限"), "detail 应说明低于下限：${info.detail}")
    }

    @Test
    fun `creditInfo 在 401 时报登录失效`() = withUpstream(both(401, """{"error":{"message":"unauthorized"}}""")) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertTrue(!info.known)
        assertEquals("登录状态已失效，需要重新登录", info.detail)
    }

    @Test
    fun `creditInfo 在其他非 2xx 时带状态码与上游原文`() = withUpstream(
        both(500, """{"error":{"message":"boom"}}"""),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertTrue(!info.known)
        assertTrue(info.detail.contains("500"), info.detail)
        assertTrue(info.detail.contains("boom"), "detail 应带上游原文：${info.detail}")
    }

    // ------------------------------------------------------- 模型组配额（无积分时的回退）

    @Test
    fun `免费账号回退到模型组配额并取最紧窗口`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"currentTier":{"id":"free-tier"}}"""),
            QUOTA_PATH to (200 to quotaBody(
                quotaGroup("Gemini Models", "weekly_limit", "0.9"),
                quotaGroup("Claude and GPT models", "five_hour_limit", "0.5"),
            )),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertEquals(50L, info.balance)
        assertTrue(info.known)
        assertTrue(info.detail.startsWith("免费版"), "免费账号 detail 应标免费版：${info.detail}")
        assertTrue(info.detail.contains("Claude and GPT models"), info.detail)
        assertTrue(info.detail.contains("50%"), info.detail)
        assertTrue(info.detail.contains("Gemini Models weekly_limit 90%"), info.detail)
        assertTrue(info.detail.contains("Claude and GPT models five_hour_limit 50%"), info.detail)
    }

    @Test
    fun `配额查询请求打到 retrieveUserQuotaSummary 并带 project`() = withUpstream(
        mapOf(CREDITS_PATH to (200 to """{"currentTier":{"id":"free-tier"}}""")),
    ) { base, received ->
        AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())
        assertTrue(received.paths.any { it == QUOTA_PATH }, "应请求配额端点：${received.paths}")
        val quotaBody = received.bodies[received.paths.indexOfFirst { it == QUOTA_PATH }]
        assertTrue(quotaBody.contains("proj-1"), "请求体应带 project id：$quotaBody")
    }

    @Test
    fun `remainingFraction 为字符串也解析`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to "{}"),
            QUOTA_PATH to (200 to quotaBody(quotaGroup("Gemini Models", "weekly", "\"0.25\""))),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertEquals(25L, info.balance)
    }

    @Test
    fun `组无 buckets 时跳过该组`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to "{}"),
            QUOTA_PATH to (200 to quotaBody("""{"displayName":"Empty"}""", quotaGroup("Gemini Models", "w", "0.8"))),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertEquals(80L, info.balance)
    }

    @Test
    fun `配额响应没有 groups 时报口径变化并带原文`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to "{}"),
            QUOTA_PATH to (200 to """{"unexpected":true}"""),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertTrue(!info.known)
        assertTrue(info.detail.contains("没有返回模型组配额"), info.detail)
        assertTrue(info.detail.contains("unexpected"), "detail 应带原文片段：${info.detail}")
    }

    @Test
    fun `配额端点 401 时报登录失效`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to "{}"),
            QUOTA_PATH to (401 to """{"error":{"message":"unauthorized"}}"""),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertTrue(!info.known)
        assertEquals("登录状态已失效，需要重新登录", info.detail)
    }

    @Test
    fun `creditInfo 在积分与配额都非 JSON 时带原文片段`() = withUpstream(both(200, "not json")) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertTrue(!info.known)
        assertTrue(info.detail.contains("not json"), info.detail)
    }

    // ------------------------------------------------------- creditPacks

    @Test
    fun `creditPacks 汇出模型组配额窗口包`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"paidTier":{"id":"g1-pro-tier","name":"Google AI Pro"}}"""),
            QUOTA_PATH to (200 to quotaBody(
                quotaGroup("Gemini Models", "weekly_limit", "0.93", resetTime = "2026-09-21T00:00:00Z"),
                quotaGroup("Gemini Models", "five_hour_limit", "0.5", resetTime = "2026-09-20T06:00:00Z"),
            )),
        ),
    ) { base, _ ->
        val packs = AntigravityProvider(apiBase = base, quotaBase = base).creditPacks(account())
        assertEquals(2, packs.size)
        assertEquals("Gemini Models", packs[0].name)
        assertEquals("Google AI Pro · weekly_limit", packs[0].group)
        assertEquals(100L, packs[0].limit)
        assertEquals(93L, packs[0].remain)
        assertEquals(7L, packs[0].used)
        assertEquals(1789948800L, packs[0].expireAt)
        assertEquals(50L, packs[1].remain)
        assertEquals(50L, packs[1].used)
    }

    @Test
    fun `creditPacks 同时汇出积分与组配额`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to paidCreditsBody("25000")),
            QUOTA_PATH to (200 to quotaBody(quotaGroup("Gemini Models", "weekly", "0.9"))),
        ),
    ) { base, _ ->
        val packs = AntigravityProvider(apiBase = base, quotaBase = base).creditPacks(account())
        assertEquals(2, packs.size)
        assertEquals("Gemini Models", packs[0].name)
        assertEquals("Google AI Pro · weekly", packs[0].group)
        assertEquals(25000L, packs[1].remain)
        assertEquals("Google One AI 积分", packs[1].name)
        assertEquals("Google AI Pro", packs[1].group)
    }

    @Test
    fun `creditPacks 在口径变化且无配额时为空`() = withUpstream(both(200, """{"unexpected":true}""")) { base, _ ->
        assertTrue(AntigravityProvider(apiBase = base, quotaBase = base).creditPacks(account()).isEmpty())
    }

    @Test
    fun `creditPacks 在非 2xx 时为空`() = withUpstream(both(401, """{"error":{"message":"unauthorized"}}""")) { base, _ ->
        assertTrue(AntigravityProvider(apiBase = base, quotaBase = base).creditPacks(account()).isEmpty())
    }

    // ------------------------------------------------------- 凭证

    @Test
    fun `凭证不可解析时 creditInfo 为 null`() {
        val broken = ProviderAccount(
            providerId = AntigravityProvider.ID,
            uid = "u1",
            nickname = "测试",
            secret = "not-json",
        )
        assertEquals(null, AntigravityProvider(apiBase = "http://127.0.0.1:1").creditInfo(broken))
    }
}
