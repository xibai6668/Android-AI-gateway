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
    fun `creditInfo 优先用 summary 的永久加每日口径`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_SUMMARY to
                """{"code":"000000","data":{"permanent":8000,"daily":500}}""",
            LoomyConstants.PATH_POINTS_RECORDS to
                """{"code":"000000","data":{"balance":120,"dailyRemainingPoints":30}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to
                """{"code":"000000","data":{"currentBalance":7}}""",
        ),
    ) { base ->
        val info = provider(base).creditInfo(account())!!
        assertTrue(info.known)
        // 对话扣每日账本，余额必须是永久+每日之和才会跟着动
        assertEquals(8500L, info.balance)
        assertTrue(info.detail.contains("永久=8000"), "detail：${info.detail}")
        assertTrue(info.detail.contains("每日=500"), "detail：${info.detail}")
    }

    @Test
    fun `creditInfo summary 失败时回退 records 且 detail 带原因`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_SUMMARY to """{"code":"000000","data":{}}""",
            LoomyConstants.PATH_POINTS_RECORDS to
                """{"code":"000000","data":{"balance":120,"dailyRemainingPoints":30}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to
                """{"code":"000000","data":{"currentBalance":7}}""",
        ),
    ) { base ->
        val info = provider(base).creditInfo(account())!!
        assertTrue(info.known)
        assertEquals(120L, info.balance)
        assertTrue(info.detail.contains("总览接口失败"), "detail：${info.detail}")
        assertTrue(info.detail.contains("records"), "detail：${info.detail}")
    }

    @Test
    fun `creditPacks summary 失败时回退 records 口径并附诊断行`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to
                """{"code":"000000","data":{"balance":120,"dailyRemainingPoints":30,"dailyLimitPoints":100,"dailyConsumedPoints":70}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to
                """{"code":"000000","data":{"currentBalance":7}}""",
        ),
    ) { base ->
        // summary 未配置 → 假上游默认回空 data → 失败：个人积分 + 每日积分诊断行 + 团队 + 当日赠送
        val packs = provider(base).creditPacks(account())
        assertEquals(4, packs.size)
        assertEquals("个人积分" to 120L, packs[0].name to packs[0].remain)
        assertEquals("每日积分", packs[1].name)
        assertEquals(-1L, packs[1].remain)
        assertTrue(packs[1].note.isNotEmpty(), "note：${packs[1].note}")
        assertEquals("团队积分" to 7L, packs[2].name to packs[2].remain)
        assertEquals("当日赠送" to 30L, packs[3].name to packs[3].remain)
        assertEquals(100L, packs[3].limit)
    }

    @Test
    fun `summary 解析永久与每日两个数`() = withUpstream(
        mapOf(LoomyConstants.PATH_POINTS_SUMMARY to """{"code":"000000","data":{"permanent":8000,"daily":500}}"""),
    ) { base ->
        val s = LoomyPointsClient(base).summary("st")
        assertTrue(s.ok)
        assertEquals(8000L, s.permanent)
        assertEquals(500L, s.daily)
    }

    @Test
    fun `summary 缺 permanent 字段时带回失败原因`() = withUpstream(
        mapOf(LoomyConstants.PATH_POINTS_SUMMARY to """{"code":"000000","data":{}}"""),
    ) { base ->
        val s = LoomyPointsClient(base).summary("st")
        assertTrue(!s.ok)
        assertTrue(s.error.contains("permanent"), "error：${s.error}")
        assertEquals(-1L, s.permanent)
    }

    @Test
    fun `summary 请求异常时不拋错只报原因`() = withUpstream(emptyMap()) { base ->
        // 假上游对未知路径回 200+空 data，不会拋；这里验证 data 缺失路径同样安全
        val s = LoomyPointsClient(base).summary("st")
        assertTrue(!s.ok)
        assertTrue(s.error.isNotEmpty())
    }

    @Test
    fun `creditPacks 优先用 points-summary 的永久与每日口径`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_SUMMARY to
                """{"code":"000000","data":{"permanent":8000,"daily":500}}""",
            LoomyConstants.PATH_POINTS_RECORDS to
                """{"code":"000000","data":{"balance":120,"dailyRemainingPoints":30,"dailyLimitPoints":100,"dailyConsumedPoints":70}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to
                """{"code":"000000","data":{"currentBalance":7}}""",
        ),
    ) { base ->
        val packs = provider(base).creditPacks(account())
        assertEquals(4, packs.size)
        assertEquals("永久积分" to 8000L, packs[0].name to packs[0].remain)
        assertEquals("每日积分" to 500L, packs[1].name to packs[1].remain)
        assertEquals("团队积分" to 7L, packs[2].name to packs[2].remain)
        assertEquals("当日赠送" to 30L, packs[3].name to packs[3].remain)
    }

    @Test
    fun `creditPacks summary 失败且 records 无每日字段时不出当日赠送行`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_SUMMARY to """{"code":"000000","data":{}}""",
            LoomyConstants.PATH_POINTS_RECORDS to
                """{"code":"000000","data":{"balance":120}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to
                """{"code":"000000","data":{"currentBalance":7}}""",
        ),
    ) { base ->
        val packs = provider(base).creditPacks(account())
        assertEquals(3, packs.size)
        assertEquals("个人积分" to 120L, packs[0].name to packs[0].remain)
        // 诊断行：每日积分未知（remain=-1），失败原因随 note 透出，不再静默
        assertEquals("每日积分", packs[1].name)
        assertEquals(-1L, packs[1].remain)
        assertTrue(packs[1].note.contains("总览接口失败"), "note：${packs[1].note}")
        assertEquals("团队积分" to 7L, packs[2].name to packs[2].remain)
    }

    @Test
    fun `creditPacks 缺 dailyRemainingPoints 时用 dailyBalance 兔底`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to
                """{"code":"000000","data":{"balance":120,"dailyBalance":25}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to """{"code":"000000","data":{}}""",
        ),
    ) { base ->
        // summary 未配置 → 假上游默认回空 data → 失败；个人积分 + 诊断行 + 当日赠送（取 dailyBalance）
        val packs = provider(base).creditPacks(account())
        val daily = packs.last { it.name == "当日赠送" }
        assertEquals(25L, daily.remain)
        assertEquals(0L, daily.limit)
    }

    @Test
    fun `creditPacks 两账本都未知时只剩诊断行`() = withUpstream(
        mapOf(
            LoomyConstants.PATH_POINTS_RECORDS to """{"code":"000000","data":{"list":[]}}""",
            LoomyConstants.PATH_TEAM_POINTS_BALANCE to """{"code":"000000","data":{}}""",
        ),
    ) { base ->
        // 两账本都未知 + summary 也失败：列表只剩「每日积分」诊断行，不空也不出数字
        val packs = provider(base).creditPacks(account())
        assertEquals(1, packs.size)
        assertEquals("每日积分", packs[0].name)
        assertEquals(-1L, packs[0].remain)
        assertTrue(packs[0].note.isNotEmpty())
    }
}
