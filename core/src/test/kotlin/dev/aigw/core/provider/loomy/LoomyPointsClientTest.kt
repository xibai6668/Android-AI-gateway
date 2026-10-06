package dev.aigw.core.provider.loomy

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ProviderAccount
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 额度链路的假上游测试：个人/团队账本的聚合规则、session 失效的可读报错、
 * 以及「上游没回余额字段」时诊断信息必须透出（不能让界面无声地空）。
 *
 * 口径说明（0.1.83 实测结论）：对话扣的是当日赠送账本（records 的 dailyRemainingPoints），
 * balance 字段是永久账本不动；Web 版 /api/auth/points-summary 在桌面端 base 下 404，不取数。
 */
class LoomyPointsClientTest {

    /** 假积分上游：按路径返回固定 JSON，全部 HTTP 200（上游对业务错误也是 200）。 */
    private fun withUpstream(responses: Map<String, String>, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = responses[exchange.requestURI.path] ?: """{"code":"000000","data":{}}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun provider(apiBase: String): LoomyProvider =
        LoomyProvider(defaultModel = { "x1" }, pointsClient = LoomyPointsClient(apiBase))

    private fun account(): ProviderAccount = ProviderAccount(
        providerId = LoomyProvider.ID,
        uid = "u1",
        nickname = "测试",
        secret = LoomyAccount(uid = "u1", phone = "13800000000", session = "st")
            .toJson().toString(),
    )

    @Test
    fun `balance 汇总个人与团队两个账本`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to
                """{"code":"000000","data":{"balance":120,"dailyBalance":25,"dailyRemainingPoints":30,"dailyLimitPoints":100,"dailyConsumedPoints":70}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to
                """{"code":"000000","data":{"currentBalance":7}}""",
        ),
    ) { base ->
        val s = LoomyPointsClient(base).balance("st")
        assertEquals(120L, s.personal)
        assertEquals(7L, s.team)
        assertEquals(30L, s.dailyRemaining)
        assertEquals(25L, s.dailyBalance)
        assertEquals(120L, s.balance)
        assertEquals("个人", s.source)
        assertTrue(s.known)
    }

    @Test
    fun `records 缺 balance 字段时个人记为未知`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to """{"code":"000000","data":{"list":[]}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to """{"code":"000000","data":{"currentBalance":0}}""",
        ),
    ) { base ->
        val s = LoomyPointsClient(base).balance("st")
        assertEquals(-1L, s.personal)
        assertEquals(0L, s.team)
        assertEquals(0L, s.balance)
        // 原文必须保留，供「余额为 0/未知」时排查
        assertTrue(s.personalRaw.contains("list"))
    }

    @Test
    fun `session 失效抛出分类正确的异常`() = withUpstream(
        mapOf(LoomyConstants.PATH_POINTS_RECORDS to """{"code":"100002","desc":"token invalid"}"""),
    ) { base ->
        val e = assertFailsWith<LoomyApiException> { LoomyPointsClient(base).balance("st") }
        assertEquals(LoomyErrorKind.SESSION_DEAD, e.kind)
        assertEquals("100002", e.upstreamCode)
    }

    @Test
    fun `creditInfo 余额是个人加当日剩余`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to
                """{"code":"000000","data":{"balance":10000,"dailyRemainingPoints":2992}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to """{"code":"000000","data":{}}""",
        ),
    ) { base ->
        // 对话扣当日赠送；只有加上 dailyRemaining 余额才会跟着消耗下降
        val info = provider(base).creditInfo(account())!!
        assertTrue(info.known)
        assertEquals(12992L, info.balance)
        assertTrue(info.detail.contains("个人=10000"), "detail：${info.detail}")
        assertTrue(info.detail.contains("当日剩余=2992"), "detail：${info.detail}")
    }

    @Test
    fun `creditInfo 缺当日剩余字段时余额就是主账本`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to """{"code":"000000","data":{"balance":120}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to """{"code":"000000","data":{"currentBalance":7}}""",
        ),
    ) { base ->
        val info = provider(base).creditInfo(account())!!
        assertTrue(info.known)
        assertEquals(120L, info.balance)
    }

    @Test
    fun `creditInfo 在两个账本都未知时带响应片段`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to """{"code":"000000","data":{"list":[]}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to """{"code":"000000","data":{}}""",
        ),
    ) { base ->
        val info = provider(base).creditInfo(account())!!
        assertTrue(!info.known)
        assertTrue(info.detail.startsWith("未取到余额"))
        assertTrue(info.detail.contains("list"), "detail 应带响应原文片段：${info.detail}")
    }

    @Test
    fun `creditInfo 在 session 失效时报可读错误`() = withUpstream(
        mapOf(LoomyConstants.PATH_POINTS_RECORDS to """{"code":"100002"}"""),
    ) { base ->
        val info = provider(base).creditInfo(account())!!
        assertTrue(!info.known)
        assertEquals("登录状态已失效，需要重新登录", info.detail)
    }

    @Test
    fun `creditPacks 汇出三个额度包`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to
                """{"code":"000000","data":{"balance":120,"dailyRemainingPoints":30,"dailyLimitPoints":100,"dailyConsumedPoints":70}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to
                """{"code":"000000","data":{"currentBalance":7}}""",
        ),
    ) { base ->
        val packs = provider(base).creditPacks(account())
        assertEquals(3, packs.size)
        assertEquals("个人积分" to 120L, packs[0].name to packs[0].remain)
        assertEquals("团队积分" to 7L, packs[1].name to packs[1].remain)
        assertEquals("当日赠送" to 30L, packs[2].name to packs[2].remain)
        assertEquals(100L, packs[2].limit)
    }

    @Test
    fun `creditPacks 缺 dailyRemainingPoints 时用 dailyBalance 兜底`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to
                """{"code":"000000","data":{"balance":120,"dailyBalance":25}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to """{"code":"000000","data":{}}""",
        ),
    ) { base ->
        val packs = provider(base).creditPacks(account())
        assertEquals(2, packs.size)
        val daily = packs.last { it.name == "当日赠送" }
        assertEquals(25L, daily.remain)
        assertEquals(0L, daily.limit)
    }

    @Test
    fun `creditPacks records 无每日字段时不出当日赠送行`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to """{"code":"000000","data":{"balance":120}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to """{"code":"000000","data":{"currentBalance":7}}""",
        ),
    ) { base ->
        val packs = provider(base).creditPacks(account())
        assertEquals(2, packs.size)
        assertEquals("个人积分" to 120L, packs[0].name to packs[0].remain)
        assertEquals("团队积分" to 7L, packs[1].name to packs[1].remain)
    }

    @Test
    fun `creditPacks 两账本都未知时为空`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to """{"code":"000000","data":{"list":[]}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to """{"code":"000000","data":{}}""",
        ),
    ) { base ->
        assertTrue(provider(base).creditPacks(account()).isEmpty())
    }
}
