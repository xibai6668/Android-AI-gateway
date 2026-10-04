package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmartRoutingTest {

    private inner class DummyProvider(override val id: String) : Provider {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = throw UnsupportedOperationException()
    }

    @Test
    fun `带别名前缀能正确路由且不区分大小写`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("antigravity"))

        assertEquals("antigravity", engine.resolveRoute("google/gemini-2.5-flash")?.providerId)
        assertEquals("gemini-2.5-flash", engine.resolveRoute("google/gemini-2.5-flash")?.model)

        assertEquals("antigravity", engine.resolveRoute("Gemini/gemini-3.8-flash")?.providerId)
        assertEquals("antigravity", engine.resolveRoute("Antigravity/claude-sonnet-4-6")?.providerId)
        assertEquals("antigravity", engine.resolveRoute("agy/gemini-3.8-flash-high")?.providerId)
    }

    @Test
    fun `WorkBuddy 区域前缀能正确识别区域约束并在模型列表拆为两组`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // 自动注册的内置供应商包括 CodeBuddyProvider（已实现 RegionAwareSupport）
        // 路由解析支持 codebuddy-cn 与 codebuddy-global，且带出正确的 region 约束
        val routeCn = engine.resolveRoute("codebuddy-cn/deepseek-v3")
        assertEquals("codebuddy", routeCn?.providerId)
        assertEquals("deepseek-v3", routeCn?.model)
        assertEquals("cn", routeCn?.region)

        val routeGlobal = engine.resolveRoute("codebuddy-global/claude-3-5-sonnet")
        assertEquals("codebuddy", routeGlobal?.providerId)
        assertEquals("claude-3-5-sonnet", routeGlobal?.model)
        assertEquals("global", routeGlobal?.region)

        // 别名 workbuddy-cn 与 workbuddy-global 同样生效
        assertEquals("cn", engine.resolveRoute("workbuddy-cn/deepseek-v3")?.region)
        assertEquals("global", engine.resolveRoute("workbuddy-global/deepseek-v3")?.region)

        // 模型列表里针对 CodeBuddy 拆出两个独立前缀组
        val models = engine.models().filter { it.providerId == "codebuddy" }
        val prefixes = models.map { it.routePrefix }.distinct()
        assertTrue(prefixes.contains("codebuddy-cn"), "应包含国内组前缀：$prefixes")
        assertTrue(prefixes.contains("codebuddy-global"), "应包含国外组前缀：$prefixes")

        // 组标题明确区分国内和国外
        val titles = models.map { it.providerName }.distinct()
        assertTrue(titles.any { it.contains("国内") }, "应包含国内标题：$titles")
        assertTrue(titles.any { it.contains("国外") }, "应包含国外标题：$titles")
    }

    @Test
    fun `无前缀时根据模型特征智能路由到 antigravity`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("trae"))
        engine.registry.register(DummyProvider("antigravity"))
        // 反重力有账号，trae 没有
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "谷歌账号", "{}"))

        val route1 = engine.resolveRoute("gemini-3.8-flash-high")
        assertNotNull(route1)
        assertEquals("antigravity", route1.providerId)
        assertEquals("gemini-3.8-flash-high", route1.model)

        val route2 = engine.resolveRoute("claude-opus-4-6-thinking")
        assertNotNull(route2)
        assertEquals("antigravity", route2.providerId)
    }

    @Test
    fun `用户仅配置反重力一个供应商时所有无前缀请求由反重力接管`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("trae"))
        engine.registry.register(DummyProvider("antigravity"))
        engine.registry.register(DummyProvider("codebuddy"))
        // 只有 antigravity 有账号
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "唯一账号", "{}"))

        // 即使用户请求 auto 或随意模型名，也直接由反重力接管，而不是退回到 trae 报 503
        val route = engine.resolveRoute("auto")
        assertNotNull(route)
        assertEquals("antigravity", route.providerId)
    }
}
