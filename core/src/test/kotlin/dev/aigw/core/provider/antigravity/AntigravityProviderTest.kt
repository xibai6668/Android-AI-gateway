package dev.aigw.core.provider.antigravity

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ProviderAccount
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
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
    private class Received(
        val paths: MutableList<String> = mutableListOf(),
        val bodies: MutableList<String> = mutableListOf(),
        val userAgents: MutableList<String> = mutableListOf(),
    )

    /** 两个端点返回同一响应的便捷路由。 */
    private fun both(status: Int, body: String): Map<String, Pair<Int, String>> =
        mapOf(CREDITS_PATH to (status to body), QUOTA_PATH to (status to body))

    /** 假上游：routes 按路径精确分发（path → 状态码+响应体），未命中的路径返回 200 `{}`。 */
    private fun withUpstream(
        routes: Map<String, Pair<Int, String>>,
        block: (base: String, received: Received) -> Unit,
    ) = withUpstream({ path, _, _ -> routes[path] ?: (200 to "{}") }, block)

    /** 假上游（可同时按 UA / 请求体应答）：验证 UA 形态与「带 project 被拒 → 空体重试」用。 */
    private fun withUpstream(
        respond: (path: String, userAgent: String, body: String) -> Pair<Int, String>,
        block: (base: String, received: Received) -> Unit,
    ) {
        val received = Received()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val ua = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            received.paths += path
            received.bodies += requestBody
            received.userAgents += ua
            val (status, body) = respond(path, ua, requestBody)
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

    /** 多个 bucket 的组（含 disabled 与自定义 displayName）。 */
    private fun quotaGroupWithBuckets(name: String, vararg buckets: String): String =
        """{"displayName":"$name","buckets":[${buckets.joinToString(",")}]}"""

    private fun bucket(
        id: String,
        fraction: String,
        displayName: String = "",
        disabled: Boolean = false,
        resetTime: String = "",
    ): String {
        val display = if (displayName.isEmpty()) "" else "\"displayName\":\"$displayName\","
        val disabledField = if (disabled) "\"disabled\":true," else ""
        return """{"bucketId":"$id",$disabledField$display"remainingFraction":$fraction,"resetTime":"$resetTime"}"""
    }

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
        mapOf("/token" to (500 to "boom")),
    ) { base, _ ->
        val provider = AntigravityProvider(nowMillis = { 2_000_000L }, tokenEndpoint = "$base/token")
        val stale = account(expiresAt = 1_000L)
        val error = runCatching { provider.refreshAccount(stale, 3600) }
        assertTrue(error.isFailure, "过期 + 刷新失败必须抛异常")
        assertTrue(error.exceptionOrNull()!!.message!!.contains("刷新失败"), error.exceptionOrNull()!!.message)
    }

    @Test
    fun `invalid_grant 时明确提示重新登录并给账号降温`() = withUpstream(
        mapOf("/token" to (400 to """{"error":"invalid_grant"}""")),
    ) { base, _ ->
        val provider = AntigravityProvider(nowMillis = { 2_000_000L }, tokenEndpoint = "$base/token")
        val stale = account(expiresAt = 1_000L)

        // refresh_token 被吊销后重试无用，必须报「重新登录」而不是含糊的刷新失败
        val error = runCatching { provider.refreshAccount(stale, 3600) }
        assertTrue(error.isFailure, "invalid_grant 必须抛异常")
        assertTrue(
            error.exceptionOrNull()!!.message!!.contains("重新登录"),
            error.exceptionOrNull()!!.message,
        )

        // 已标记吊销：后续调用不再打 token 端点（避免用死 token 反复撞风控）
        // 用一个未过期的凭证验证短路发生在网络请求之前
        val fresh = account(expiresAt = 2_000_000_000L)
        val again = runCatching { provider.refreshAccount(fresh, 3600) }
        assertTrue(again.isFailure, "标记吊销后仍须报错")
        assertTrue(
            again.exceptionOrNull()!!.message!!.contains("重新登录"),
            again.exceptionOrNull()!!.message,
        )
    }

    @Test
    fun `未过期但刷新失败时返回 null 不抛异常`() = withUpstream(
        mapOf("/token" to (500 to "boom")),
    ) { base, _ ->
        val provider = AntigravityProvider(nowMillis = { 1_000_000L }, tokenEndpoint = "$base/token")
        val almostStale = account(expiresAt = 1_000_000L / 1000 + 60)
        assertEquals(null, provider.refreshAccount(almostStale, 3600))
    }

    @Test
    fun `并发刷新同一临期账号只打一次 token 端点`() = withUpstream(
        mapOf("/token" to (200 to """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""")),
    ) { base, received ->
        val provider = AntigravityProvider(nowMillis = { 1_000_000L }, tokenEndpoint = "$base/token")
        val almostStale = account(expiresAt = 1_000_000L / 1000 + 240)
        val results = ConcurrentLinkedQueue<ProviderAccount?>()
        val start = CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                start.await()
                results += provider.refreshAccount(almostStale, 3600)
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, received.paths.count { it == "/token" }, "并发刷新应只打一次 token 端点：${received.paths}")
        assertEquals(8, results.size)
        for (result in results) {
            assertTrue(result != null, "每个线程都应拿到刷新后的账号而非 null")
            val secret = com.google.gson.JsonParser.parseString(result!!.secret).asJsonObject
            assertEquals("new-access", secret.get("accessToken").asString)
        }
    }

    @Test
    fun `刷新失败后短时间冷却不再重打 token 端点`() = withUpstream(
        mapOf("/token" to (500 to "boom")),
    ) { base, received ->
        val provider = AntigravityProvider(nowMillis = { 1_000_000L }, tokenEndpoint = "$base/token")
        val almostStale = account(expiresAt = 1_000_000L / 1000 + 60)
        assertEquals(null, provider.refreshAccount(almostStale, 3600))
        assertEquals(null, provider.refreshAccount(almostStale, 3600))
        assertEquals(1, received.paths.count { it == "/token" }, "冷却期内不应重打 token 端点：${received.paths}")
    }

    @Test
    fun `过大的 skew 被夹到安全窗口内不再刷新`() = withUpstream(emptyMap()) { base, received ->
        val provider = AntigravityProvider(nowMillis = { 1_000_000L }, tokenEndpoint = "$base/token")
        val oneHourLeft = account(expiresAt = 1_000_000L / 1000 + 3600)
        assertEquals(null, provider.refreshAccount(oneHourLeft, skewSeconds = 86400))
        assertTrue(received.paths.none { it == "/token" }, "窗口应被夹到 5 分钟：${received.paths}")
    }

    // ------------------------------------------------------- 域名分工

    @Test
    fun `对话与模型列表走 daily 域名而不是配额域名`() = withUpstream(
        mapOf(
            "/${AntigravityProvider.API_VERSION}:streamGenerateContent" to (429 to "exhausted"),
            "/${AntigravityProvider.API_VERSION}:fetchAvailableModels" to
                (200 to """{"models":{"gemini-3.8-flash-high":{"displayName":"Gemini 3.8 Flash"}}}"""),
        ),
    ) { base, received ->
        // 配额域名指向不可达端口：对话一旦走它就会连接失败，而不是走到假上游
        val provider = AntigravityProvider(
            apiBase = "http://127.0.0.1:1",
            quotaBase = "http://127.0.0.1:1",
            chatBase = base,
        )
        val call = provider.openChat(
            account(),
            """{"model":"gemini-3.8-flash-high","stream":false,"messages":[{"role":"user","content":"ping"}]}""",
        )
        assertEquals(429, call.status, "对话必须打到 chatBase 上的假上游")
        assertEquals(
            listOf("gemini-3.8-flash-high"),
            provider.listModels(account()).models.map { it.id },
            "模型列表也必须走 chatBase",
        )
        assertTrue(received.paths.any { it.endsWith(":streamGenerateContent") }, "${received.paths}")
    }

    @Test
    fun `对话域名是 daily 而配额域名是 production`() {
        // 两者不能共用：prod 对消费级账号的对话请求必回 429 RESOURCE_EXHAUSTED，
        // 而配额查询反过来只有 prod 给完整数据。
        assertTrue(AntigravityProvider.CHAT_BASE.contains("daily-"), AntigravityProvider.CHAT_BASE)
        assertTrue(!AntigravityProvider.QUOTA_BASE.contains("daily-"), AntigravityProvider.QUOTA_BASE)
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

    // ------------------------------------------------------- 额度查询对齐 CLIProxyAPI

    @Test
    fun `额度查询用裸 hub UA，不带任何指纹头`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"currentTier":{"id":"free-tier"}}"""),
            QUOTA_PATH to (200 to quotaBody(quotaGroup("Gemini Models", "weekly", "0.8"))),
        ),
    ) { base, received ->
        AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())
        val index = received.paths.indexOfFirst { it == QUOTA_PATH }
        assertTrue(index >= 0, "应请求配额端点：${received.paths}")
        // CLIProxyAPI 只用 `antigravity/hub/<ver> <os>/<arch>`，不带 (aidev_client; ...) 段
        val ua = received.userAgents[index]
        assertTrue(ua.startsWith("antigravity/hub/"), "应使用 hub UA：$ua")
        assertTrue(!ua.contains("aidev_client"), "不应附加 aidev_client 指纹段：$ua")
    }

    @Test
    fun `loadCodeAssist 的 metadata 只带 ideType`() = withUpstream(
        { path, _, _ ->
            if (path == CREDITS_PATH) {
                200 to """{"paidTier":{"id":"g1-pro-tier","name":"Google AI Pro"}}"""
            } else {
                200 to "{}"
            }
        },
    ) { base, received ->
        AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())
        val payload = received.bodies[received.paths.indexOfFirst { it == CREDITS_PATH }]
        // CLIProxyAPI 只发 metadata.ideType；多余字段会被上游当作不同客户端
        assertTrue(payload.contains("ANTIGRAVITY"), payload)
        assertTrue(!payload.contains("pluginType"), "不应带 pluginType：$payload")
        assertTrue(!payload.contains("platform"), "不应带 platform：$payload")
    }

    @Test
    fun `配额端点 403 时不能报登录失效`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"currentTier":{"id":"free-tier"}}"""),
            QUOTA_PATH to (403 to """{"error":{"message":"The caller does not have permission"}}"""),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertTrue(!info.known)
        assertTrue(!info.detail.contains("登录状态已失效"), "403 不应当成登录失效：${info.detail}")
        assertTrue(info.detail.contains("403"), "detail 应带真实状态码：${info.detail}")
    }

    @Test
    fun `配额端点 401 才报登录失效`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"currentTier":{"id":"free-tier"}}"""),
            QUOTA_PATH to (401 to """{"error":{"message":"unauthorized"}}"""),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertTrue(!info.known)
        assertEquals("登录状态已失效，需要重新登录", info.detail)
    }

    @Test
    fun `loadCodeAssist 非 401 失败时仍展示配额而不是报授权失效`() = withUpstream(
        mapOf(
            CREDITS_PATH to (403 to """{"error":{"message":"SUBSCRIPTION_REQUIRED"}}"""),
            QUOTA_PATH to (200 to quotaBody(quotaGroup("Gemini Models", "weekly", "0.7"))),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertEquals(70L, info.balance, "额度应以配额为准：${info.detail}")
        assertTrue(info.known)
        assertTrue(!info.detail.contains("登录状态已失效"), "403 不是登录失效：${info.detail}")
    }

    @Test
    fun `loadCodeAssist 401 仍然报登录失效`() = withUpstream(
        mapOf(
            CREDITS_PATH to (401 to """{"error":{"message":"unauthorized"}}"""),
            QUOTA_PATH to (200 to quotaBody(quotaGroup("Gemini Models", "weekly", "0.7"))),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertTrue(!info.known)
        assertEquals("登录状态已失效，需要重新登录", info.detail)
    }

    @Test
    fun `带 project 被 403 时去掉 project 重试并成功`() = withUpstream(
        { path, _, requestBody ->
            when {
                path == CREDITS_PATH -> 200 to """{"paidTier":{"id":"g1-pro-tier","name":"Google AI Pro"}}"""
                // 只有空请求体被放行：模拟 #3501 的 project 授权校验失败
                requestBody.contains("project") ->
                    403 to """{"error":{"code":403,"message":"You do not have a valid license of this product"}}"""
                else -> 200 to quotaBody(quotaGroup("Gemini Models", "weekly", "0.75"))
            }
        },
    ) { base, received ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        assertEquals(75L, info.balance, "剥掉 project 后应拿到配额：${info.detail}")
        assertTrue(info.known)
        // 确实走了「带 project 失败 → 空体重试」
        val quotaBodies = received.paths.indices.filter { received.paths[it] == QUOTA_PATH }.map { received.bodies[it] }
        assertTrue(quotaBodies.any { it.contains("proj-1") }, "应先带 project：$quotaBodies")
        assertTrue(quotaBodies.any { it == "{}" }, "应再用空体重试：$quotaBodies")
    }

    @Test
    fun `creditPacks 在非 2xx 时为空`() = withUpstream(both(401, """{"error":{"message":"unauthorized"}}""")) { base, _ ->
        assertTrue(AntigravityProvider(apiBase = base, quotaBase = base).creditPacks(account()).isEmpty())
    }

    // ------------------------------------------------------- Gemini 4 Argon 独立配额

    @Test
    fun `周额度耗尽时 5 小时窗口仍然展示，因为它确实可用`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"paidTier":{"id":"g1-pro-tier","name":"Google AI Pro"}}"""),
            QUOTA_PATH to (200 to quotaBody(
                quotaGroupWithBuckets(
                    "Gemini Models",
                    // 实测事实：周额度耗尽后上游给 5h 桶打 disabled 但 remainingFraction 仍为 1，
                    // 而官方客户端里 5 小时额度**可以正常使用**。所以不能丢掉这个桶，
                    // 否则用户只能看到周额度（issue：geminiplus 只能看周额度）。
                    bucket("gemini-weekly", "0.0", displayName = "Weekly Limit"),
                    bucket("gemini-5h", "1", displayName = "Five Hour Limit", disabled = true),
                ),
            )),
        ),
    ) { base, _ ->
        val packs = AntigravityProvider(apiBase = base, quotaBase = base).creditPacks(account())
        assertEquals(2, packs.size, "周窗口与可用的 5 小时窗口都要列出来：$packs")
        val weekly = packs.single { it.group.endsWith("Weekly Limit") }
        assertEquals(0L, weekly.remain)
        val fiveHour = packs.single { it.group.endsWith("Five Hour Limit") }
        assertEquals(100L, fiveHour.remain, "5 小时池是满的，不能因为 disabled 就藏起来")
    }

    @Test
    fun `残缺的 200 不会被当成最终结果，会继续问下一个端点`() {
        var quotaCalls = 0
        withUpstream(
            // 用 respond 按「第几次配额请求」分别应答：首次残缺（只剩 Claude/GPT 周窗口），
        // 第二次才给完整的 Gemini 5h + weekly。模拟 daily 残缺、prod 完整的上游现状。
        respond = { path, _, _ ->
            if (!path.contains("retrieveUserQuotaSummary")) {
                200 to """{"paidTier":{"id":"g1-pro-tier","name":"Google AI Pro"}}"""
            } else {
                quotaCalls++
                // 首次残缺、之后完整：模拟上游「第一个 200 不给全」，验证我们不会停在残缺结果上
                if (quotaCalls == 1) {
                    200 to quotaBody(
                        quotaGroupWithBuckets(
                            "Claude and GPT models",
                            bucket("3p-weekly", "1", displayName = "Weekly Limit Remaining"),
                        ),
                    )
                } else {
                    200 to quotaBody(
                        quotaGroupWithBuckets(
                            "Gemini Models",
                            bucket("gemini-weekly", "0", displayName = "Weekly Limit Remaining"),
                            bucket("gemini-5h", "1", displayName = "Five Hour Limit Remaining"),
                        ),
                        quotaGroupWithBuckets(
                            "Claude and GPT models",
                            bucket("3p-weekly", "1", displayName = "Weekly Limit Remaining"),
                        ),
                    )
                }
            }
        },
        ) { base, _ ->
            val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
            assertTrue(info.detail.contains("Gemini Models"), "残缺响应里的 Gemini 组不能被丢掉：${info.detail}")
            assertTrue(
                info.detail.contains("Five Hour Limit"),
                "5 小时窗口必须能拿到（这正是 issue 的核心）：${info.detail}",
            )
            assertTrue(quotaCalls >= 2, "残缺 200 应继续问下一个端点，实际请求次数：$quotaCalls")
        }
    }

    @Test
    fun `耗尽的桶即使缺少 remainingFraction 也不能消失`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"paidTier":{"id":"g1-pro-tier","name":"Google AI Pro"}}"""),
            // proto3 JSON 省略零值：耗尽的桶可能不带 remainingFraction
            QUOTA_PATH to (200 to quotaBody(
                quotaGroupWithBuckets(
                    "Gemini Models",
                    """{"bucketId":"gemini-weekly","displayName":"Weekly Limit Remaining","resetTime":"2026-11-01T00:00:00Z"}""",
                    bucket("gemini-5h", "1", displayName = "Five Hour Limit Remaining"),
                ),
            )),
        ),
    ) { base, _ ->
        val packs = AntigravityProvider(apiBase = base, quotaBase = base).creditPacks(account())
        assertEquals(2, packs.size, "缺字段的耗尽桶要按 0% 展示，而不是整个消失：$packs")
        val weekly = packs.single { it.group.endsWith("Weekly Limit Remaining") }
        assertEquals(0L, weekly.remain)
    }

    @Test
    fun `Gemini 4 Argon 独立配额单独成包并标注仅限 argon`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"paidTier":{"id":"g1-pro-tier","name":"Google AI Pro"}}"""),
            QUOTA_PATH to (200 to quotaBody(
                quotaGroupWithBuckets(
                    "Gemini Models",
                    bucket("gemini-weekly", "0.9", displayName = "Weekly Limit"),
                    bucket("gemini-5h", "0.6", displayName = "Five Hour Limit"),
                ),
                quotaGroupWithBuckets(
                    "Gemini 4 Argon",
                    bucket("gemini-argon-wtu-weekly", "0.3", displayName = "Weekly Limit"),
                ),
            )),
        ),
    ) { base, _ ->
        val packs = AntigravityProvider(apiBase = base, quotaBase = base).creditPacks(account())
        assertEquals(3, packs.size, "共享池 2 个窗口 + Argon 1 个周窗口：$packs")
        val argon = packs.single { it.name == "Gemini 4 Argon" }
        assertEquals(30L, argon.remain)
        assertEquals(70L, argon.used)
        assertTrue(argon.scope.contains("gemini-4-argon"), "Argon 包应标明适用的模型：${argon.scope}")
        assertTrue(packs.filter { it.scope.isEmpty() }.size == 2, "共享池两个窗口不应带模型限定：$packs")
    }

    @Test
    fun `Argon 配额耗尽不影响账号级额度判定`() = withUpstream(
        mapOf(
            CREDITS_PATH to (200 to """{"paidTier":{"id":"g1-pro-tier","name":"Google AI Pro"}}"""),
            QUOTA_PATH to (200 to quotaBody(
                quotaGroupWithBuckets(
                    "Gemini Models",
                    bucket("gemini-weekly", "0.9", displayName = "Weekly Limit"),
                ),
                quotaGroupWithBuckets(
                    "Gemini 4 Argon",
                    bucket("gemini-argon-wtu-weekly", "0.0", displayName = "Weekly Limit"),
                ),
            )),
        ),
    ) { base, _ ->
        val info = AntigravityProvider(apiBase = base, quotaBase = base).creditInfo(account())!!
        // Argon 是模型级配额：耗尽只影响 gemini-4-argon，不能把整个账号判成 0 额度
        assertEquals(90L, info.balance, "账号级余额应只看共享池：${info.detail}")
        assertTrue(info.known)
        assertTrue(info.detail.contains("Argon"), "detail 仍应告知 Argon 状态：${info.detail}")
        assertTrue(info.detail.contains("仅 gemini-4-argon"), info.detail)
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
