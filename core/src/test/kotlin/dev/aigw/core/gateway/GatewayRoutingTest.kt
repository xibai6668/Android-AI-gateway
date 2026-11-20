package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 引擎的路由与选号：这两条是每个请求的必经之路，出错的表现是「模型明明配了却 503」，
 * 且很难从日志反推。这里把它们锁住。
 *
 * 不测网络：所有 provider 都是桩实现，只回目录与固定错误。
 */
class GatewayRoutingTest {

    private open class StubProvider(
        override val id: String,
        override val displayName: String = id,
    ) : Provider {
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(listOf(ProviderModel("$id-model", "$id-model")), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            FailedChatCall(500, "{}")
    }

    private fun engineWith(vararg providers: Provider, accounts: Boolean = true): GatewayEngine {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        for (provider in providers) {
            engine.registry.register(provider)
            if (accounts) engine.pool.upsert(ProviderAccount(provider.id, "u1", "账号1", "{}"))
        }
        return engine
    }

    @Test
    fun `带前缀的模型名路由到对应供应商`() {
        val engine = engineWith(StubProvider("alpha"), StubProvider("beta"))
        val route = engine.resolveRoute("beta/some-model")
        assertEquals(Route("beta", "some-model"), route)
    }

    @Test
    fun `models 前缀会被剥掉`() {
        // Google/Gemini 客户端与 SDK 常写 models/gemini-xxx
        val engine = engineWith(StubProvider("alpha"))
        val route = engine.resolveRoute("models/alpha-model")
        assertEquals("alpha", route?.providerId)
        assertEquals("alpha-model", route?.model)
    }

    @Test
    fun `前缀大小写不敏感`() {
        val engine = engineWith(StubProvider("alpha"))
        assertEquals("alpha", engine.resolveRoute("ALPHA/m1")?.providerId)
    }

    @Test
    fun `空前缀或未知前缀不会崩`() {
        val engine = engineWith(StubProvider("alpha"))
        // 未识别的前缀：退回按模型名特征推导，允许为 null，但不能抛异常
        engine.resolveRoute("/weird")
        engine.resolveRoute("unknown-provider/m1")
        engine.resolveRoute("")
        assertNull(engine.resolveRoute("   "))
    }

    @Test
    fun `未启用供应商的模型不会被路由`() {
        val engine = engineWith(StubProvider("alpha"), StubProvider("beta"))
        engine.updateProviderSettings("beta", ProviderSettings(enabled = false))
        // 显式前缀仍然指向它（由上游决定成败），但自动推导不该选中已停用的家
        val inferred = engine.resolveRoute("beta-model")
        assertTrue(inferred == null || inferred.providerId != "beta", "停用的供应商不该被自动推导选中：$inferred")
    }

    @Test
    fun `没有账号的供应商不会被自动推导选中`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(StubProvider("alpha"))
        // 注册了但一个账号都没有
        val route = engine.resolveRoute("alpha-model")
        assertTrue(route == null || route.providerId != "alpha", "无账号的供应商不该被选中：$route")
    }

    @Test
    fun `候选路由包含首选供应商`() {
        val engine = engineWith(StubProvider("alpha"))
        val candidates = engine.resolveCandidateRoutes("alpha/m1")
        assertTrue(candidates.isNotEmpty(), "候选路由不该为空")
        assertEquals("alpha", candidates.first().providerId)
    }

    @Test
    fun `选号会跳过排除列表里的账号`() {
        val engine = engineWith(StubProvider("alpha"))
        engine.pool.upsert(ProviderAccount("alpha", "u2", "账号2", "{}"))

        val first = engine.pickAccount("alpha")
        assertTrue(first != null)
        val second = engine.pickAccount("alpha", exclude = setOf(first.uid))
        assertTrue(second != null)
        assertTrue(second!!.uid != first.uid, "排除后不该再选中同一个账号")
    }

    @Test
    fun `全部账号都被排除时选号返回 null`() {
        val engine = engineWith(StubProvider("alpha"))
        assertNull(engine.pickAccount("alpha", exclude = setOf("u1")))
    }

    @Test
    fun `未被禁用的账号优先`() {
        val engine = engineWith(StubProvider("alpha"))
        engine.pool.upsert(ProviderAccount("alpha", "u2", "账号2", "{}"))
        engine.pool.disable("alpha", "u1", "凭证失效")

        val picked = engine.pickAccount("alpha")
        assertEquals("u2", picked?.uid, "被禁用的账号不该被选中")
    }

    @Test
    fun `区域型供应商只在指定区域选号`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(RegionProvider())
        engine.pool.upsert(ProviderAccount("reg", "u-cn", "国内号", "{}"))
        engine.pool.upsert(ProviderAccount("reg", "u-global", "国际号", "{}"))

        assertEquals("u-cn", engine.pickAccount("reg", region = "cn")?.uid)
        assertEquals("u-global", engine.pickAccount("reg", region = "global")?.uid)
    }

    /** 仅用于验证按区域选号：regionOf 决定账号属于哪个区域。 */
    private class RegionProvider : StubProvider("reg"), dev.aigw.core.provider.RegionAwareSupport {
        override fun regionOf(account: ProviderAccount) = if (account.uid == "u-global") "global" else "cn"
        override fun regions() = listOf("cn", "global")
    }
}
