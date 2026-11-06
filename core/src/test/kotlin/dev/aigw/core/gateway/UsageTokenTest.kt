package dev.aigw.core.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.CreditUnit
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /api/usage/token` 回归（new-api 形状）。
 *
 * 重点锁两件事：响应字段形状要能被 new-api 客户端读出余额；余额汇总**不能跨单位相加**
 * ——Antigravity 没有积分时只有配额剩余百分比，把它加进积分类会得出既不是积分也不是百分比
 * 的数，对客报余额就是造假。
 */
class UsageTokenTest {

    private class CreditProvider(
        override val id: String,
        private val info: CreditInfo,
    ) : Provider {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            FailedChatCall(500, "不该走对话通道")
        override fun creditInfo(account: ProviderAccount): CreditInfo = info
    }

    private fun withServer(vararg providers: Provider, block: (port: Int) -> Unit) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        for (provider in providers) {
            engine.registry.register(provider)
            engine.pool.upsert(ProviderAccount(provider.id, "u-${provider.id}", provider.id, "{}"))
            // 先把额度写进账号池：端点读的是池里的快照，不是现场拉上游
            val info = runCatching { provider.creditInfo(ProviderAccount(provider.id, "u", "", "{}")) }.getOrNull()
            if (info != null) {
                engine.pool.updateCredits(provider.id, "u-${provider.id}", info.balance, info.known, info.detail, info.unit)
            }
        }
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort)
        } finally {
            server.stop()
        }
    }

    private fun get(port: Int, path: String, authorize: Boolean = true): String {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        if (authorize) conn.setRequestProperty("Authorization", "Bearer test")
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private fun dataOf(body: String): JsonObject {
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals(true, root.get("code")?.asBoolean, "new-api 形状要求 code=true：$body")
        assertEquals("ok", root.stringOrNull("message"))
        return root.objOrNull("data") ?: error("缺 data：$body")
    }

    @Test
    fun `返回 new-api 的 token_usage 结构与真实余额`() {
        withServer(CreditProvider("fake", CreditInfo(balance = 12345, known = true, detail = "测试"))) { port ->
            val data = dataOf(get(port, "/api/usage/token"))
            assertEquals("token_usage", data.stringOrNull("object"))
            assertEquals(12345, data.get("total_available")?.asLong)
            assertEquals(0, data.get("total_used")?.asLong, "上游没给「已用」口径就不编造")
            assertEquals(12345, data.get("total_granted")?.asLong)
            assertEquals(false, data.get("unlimited_quota")?.asBoolean)
            assertEquals(false, data.get("model_limits_enabled")?.asBoolean)
            assertEquals(0, data.get("expires_at")?.asLong)
        }
    }

    @Test
    fun `百分比余额不参与积分类合计`() {
        withServer(
            CreditProvider("credits-a", CreditInfo(balance = 900, known = true)),
            CreditProvider("percent-b", CreditInfo(balance = 87, known = true, unit = CreditUnit.PERCENT)),
        ) { port ->
            val data = dataOf(get(port, "/api/usage/token"))
            // 900 积分 + 87% ≠ 987：只许汇总同一单位
            assertEquals(900, data.get("total_available")?.asLong, "百分比不能加进积分类余额")
            val units = data.objOrNull("units")!!
            assertEquals(900, units.get("credits")?.asLong)
            assertEquals(1, units.get("percent")?.asInt, "百分比账号要单独计数，不能被吞掉")
            assertEquals(1, data.get("percent_known")?.asInt)
        }
    }

    @Test
    fun `凭证明细里带单位，调用方能看到余额怎么算出来的`() {
        withServer(
            CreditProvider("percent-b", CreditInfo(balance = 64, known = true, unit = CreditUnit.PERCENT)),
        ) { port ->
            val data = dataOf(get(port, "/api/usage/token"))
            val provider = data.get("providers").asJsonArray
                .map { it.asJsonObject }
                .first { it.stringOrNull("id") == "percent-b" }
            val account = provider.get("accounts").asJsonArray.first().asJsonObject
            assertEquals("percent", account.stringOrNull("unit"))
            assertEquals(64, account.get("credits")?.asLong)
            // 百分比账号的供应商合计不该被当成积分加进去
            assertEquals(0, provider.get("totalCredits")?.asLong)
            assertTrue(provider.get("percentKnown")?.asBoolean == true)
        }
    }

    @Test
    fun `没有额度数据时如实报未知而不是假的 0`() {
        withServer(CreditProvider("unknown", CreditInfo(balance = 0, known = false, detail = "上游没返回"))) { port ->
            val data = dataOf(get(port, "/api/usage/token"))
            assertEquals(0, data.get("total_available")?.asLong)
            assertEquals(false, data.get("quota_known")?.asBoolean, "未知余额必须标出来")
        }
    }

    @Test
    fun `关闭无 Key 后缺 Key 被拒`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.updateSettings(engine.settings().copy(apiKey = "secret", allowNoKey = false))
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            val body = get(server.listeningPort, "/api/usage/token", authorize = false)
            assertTrue(body.contains("invalid_api_key"), body)
            // 带上正确 Key 就能读
            val conn = URL("http://127.0.0.1:${server.listeningPort}/api/usage/token").openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer secret")
            val ok = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            assertEquals("token_usage", dataOf(ok).stringOrNull("object"))
        } finally {
            server.stop()
        }
    }
}
